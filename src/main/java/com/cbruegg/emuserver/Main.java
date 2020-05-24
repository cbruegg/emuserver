package com.cbruegg.emuserver;

import com.cbruegg.emuserver.platform.ds.DsServerOutputReader;
import com.cbruegg.emuserver.platform.ds.JpegCompressor;
import com.cbruegg.emuserver.serialization.UUIDAdapter;
import com.cbruegg.emuserver.utils.ConcurrentUtils;
import com.cbruegg.emuserver.utils.Debouncer;
import com.cbruegg.emuserver.utils.IOUtils;
import com.squareup.moshi.Moshi;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

// TODO Remove ffmpeg
// TODO Kill stale sessions

public class Main {
    public static void main(String[] args) {
        var romDir = new File("roms");
        if (!romDir.exists() && !romDir.mkdirs()) {
            System.err.println("Could not create ROM directory " + romDir + ", exiting!");
            return;
        }
        var romDirNds = new File(romDir, "nds");
        if (!romDirNds.exists() && !romDirNds.mkdirs()) {
            System.err.println("Could not create NDS ROM directory " + romDirNds + ", exiting!");
            return;
        }

        var melonDsServerFile = new File("C:\\Users\\mail\\CLionProjects\\melonDS\\build\\dist-server\\melonDS-server.exe");
        var melonDsBiosDir = new File("C:\\Users\\mail\\CLionProjects\\melonDS\\bios");

        var moshi = new Moshi.Builder().add(UUID.class, new UUIDAdapter()).build();
        var publicSessionAdapter = moshi.adapter(Session.Public.class);

        var vertx = Vertx.vertx();
        var httpServer = vertx.createHttpServer();
        var router = Router.router(vertx);
        var bodyHandler = BodyHandler.create(true);
        var sessions = Collections.synchronizedMap(new HashMap<UUID, Session>());

        router.route().handler(bodyHandler);

        router.get("/roms/nds/:rommd5/exists").handler(event -> {
            var md5 = event.pathParam("rommd5");
            var exists = new File(romDirNds, md5 + ".nds").exists();
            event.response().putHeader("content-type", "text/plain").end(String.valueOf(exists));
        });

        router.post("/roms/nds/:rommd5").blockingHandler(event -> {
            if (event.fileUploads().size() != 1) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_BAD_REQUEST).end("Exactly one uploaded file is required!");
                event.fileUploads().stream().map(upload -> new File(upload.uploadedFileName())).forEach(File::delete);
            } else {
                var upload = event.fileUploads().stream().findFirst().get();
                var uploadedFile = new File(upload.uploadedFileName());
                String actualMd5;
                try {
                    actualMd5 = IOUtils.md5(uploadedFile);
                } catch (IOException e) {
                    event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR).end("Failed to compute MD5 of uploaded file!");
                    e.printStackTrace();
                    return;
                }
                if (Objects.equals(event.pathParam("rommd5"), actualMd5)) {
                    var targetFile = new File(romDirNds, actualMd5 + ".nds");
                    if (IOUtils.moveOrCopy(uploadedFile, targetFile)) {
                        event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_CREATED).end("Upload successful.");
                    } else {
                        event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR).end("Failed to save uploaded file!");
                    }
                } else {
                    event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_BAD_REQUEST).end("MD5 does not match, actual MD5 is " + actualMd5);
                }
            }
        });
        router.post("/roms/nds/:rommd5/session").blockingHandler(event -> {
            var md5 = event.pathParam("rommd5");
            var romFile = new File(romDirNds, md5 + ".nds");
            if (!romFile.exists()) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_NOT_FOUND).end("Could not find ROM for MD5 " + md5);
                return;
            }

            var fileUploads = event.fileUploads();
            if (fileUploads.size() > 1) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_BAD_REQUEST).end("Can only supply one savegame!");
                return;
            }

            var initialSaveGame = fileUploads.stream().findFirst().map(upload -> new File(upload.uploadedFileName())).orElse(null);
            // TODO Remove
            var overwriteInitialSaveGame = false;
            if (overwriteInitialSaveGame) {
                initialSaveGame = new File("C:\\Users\\mail\\Desktop\\3fe7e7a45d138d10b2d35cbb7fe3b0ea.nds.dsv");
            }
            try {

                var session = createNewSession(melonDsServerFile, melonDsBiosDir, romFile, initialSaveGame);
                sessions.put(session.getUuid(), session);

                var publicSession = session.toPublic();
                event.response().putHeader("content-type", "application/json").setStatusCode(HttpURLConnection.HTTP_CREATED).end(publicSessionAdapter.toJson(publicSession));
            } catch (IOException | InterruptedException e) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR).end("Could not create session!");
            }
        });
        router.delete("/roms/nds/:rommd5/session/:uuid").handler(event -> {
            var uuid = UUID.fromString(event.pathParam("uuid"));
            var session = sessions.remove(uuid);
            if (session == null) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_NOT_FOUND).end("Session with UUID " + uuid + " does not exist!");
            } else {
                session.stop();
                event.response().putHeader("content-type", "text/plain").end("Session has been stopped.");
            }
        });
        router.post("/roms/nds/:rommd5/session/:uuid/savegame").blockingHandler(event -> {
            var uuid = UUID.fromString(event.pathParam("uuid"));
            var session = sessions.get(uuid);
            if (session == null) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_NOT_FOUND).end("Session with UUID " + uuid + " does not exist!");
                return;
            }

            var fileUploads = event.fileUploads();
            if (fileUploads.size() != 1) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_BAD_REQUEST).end("Must supply one savegame!");
                return;
            }

            var saveGame = session.getSaveGame();
            var uploadedSaveGame = fileUploads.stream().findFirst().map(upload -> new File(upload.uploadedFileName())).orElseThrow();

            try {
                var skip = false;
                if (!skip) {
                    var uploadedSaveGameBytes = Files.readAllBytes(uploadedSaveGame.toPath());
                    session.getLastKnownSaveGameRef().set(uploadedSaveGameBytes);
                    Files.write(saveGame.toPath(), uploadedSaveGameBytes);
                    session.onSaveGameUploaded();
                }
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_OK).end();
            } catch (IOException e) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR).end("Could not create session!");
            }
        });
        router.get("/roms/nds/:rommd5/session/:uuid/savegame").handler(event -> {
            var uuid = UUID.fromString(event.pathParam("uuid"));
            var session = sessions.get(uuid);
            if (session == null) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_NOT_FOUND).end("Session with UUID " + uuid + " does not exist!");
            } else {
                var saveGame = session.getSaveGame();
                event.response().putHeader("content-type", "application/octet-stream").sendFile(saveGame.getAbsolutePath()).end();
            }
        });
        router.get("/roms/nds/:rommd5/session/:uuid/savestate").handler(event -> {
            var uuid = UUID.fromString(event.pathParam("uuid"));
            var session = sessions.get(uuid);
            if (session == null) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_NOT_FOUND).end("Session with UUID " + uuid + " does not exist!");
            } else {
                try {
                    var saveState = session.getSaveState();
                    event.response().putHeader("content-type", "application/octet-stream").sendFile(saveState.toString()).end();
                } catch (IOException e) {
                    event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR).end("Could not create session!");
                }
            }
        });
        router.post("/roms/nds/:rommd5/session/:uuid/savestate").blockingHandler(event -> {
            var uuid = UUID.fromString(event.pathParam("uuid"));
            var session = sessions.get(uuid);
            if (session == null) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_NOT_FOUND).end("Session with UUID " + uuid + " does not exist!");
                return;
            }

            var fileUploads = event.fileUploads();
            if (fileUploads.size() != 1) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_BAD_REQUEST).end("Must supply one savegame!");
                return;
            }

            var uploadedSaveState = fileUploads.stream().findFirst().map(upload -> new File(upload.uploadedFileName())).orElseThrow();

            try {
                session.loadSaveState(uploadedSaveState.toPath());
                uploadedSaveState.deleteOnExit();
                uploadedSaveState.delete();
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_OK).end();
            } catch (IOException e) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR).end("Could not create session!");
            }
        });

        httpServer.requestHandler(router).listen(1114);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sessions.values().forEach(Session::stop);
        }));

        //noinspection InfiniteLoopStatement
        while (true) {
            try {
                synchronized (Main.class) {
                    Main.class.wait();
                }
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static Session createNewSession(File dsServer, File dsServerBiosDir, File rom, @Nullable File initialSaveGame) throws IOException, InterruptedException {
        var sessionId = UUID.randomUUID();
        var sessionDir = Files.createTempDirectory("emuserver-" + sessionId);
        var saveGame = new File(sessionDir.toFile(), rom.getName() + ".dsv");
        var stop = new AtomicBoolean(false);

        var lastKnownSaveGameBytes = new AtomicReference<>(initialSaveGame != null ? Files.readAllBytes(initialSaveGame.toPath()) : new byte[0]);

        if (false) {
            // TODO Make this branch active again
            if (initialSaveGame != null && !IOUtils.moveOrCopy(initialSaveGame, saveGame)) {
                throw new IOException("Could not import initial savegame!");
            }
        } else {
            if (initialSaveGame != null && !IOUtils.copy(initialSaveGame, saveGame)) {
                throw new IOException("Could not import initial savegame!");
            }
        }

        var dsServerProcess = new ProcessBuilder(
                dsServer.getPath(),
                dsServerBiosDir.toString(),
                rom.toString(),
                saveGame.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        var dsServerReader = new DsServerOutputReader(dsServerProcess.getInputStream());
        var portSpec = dsServerReader.readPortSpec();

        var screenInputSocket = new Socket("localhost", portSpec.screenSocketPort());
        var videoStream = screenInputSocket.getInputStream();

        var audioInputSocket = new Socket("localhost", portSpec.audioSocketPort());
        var audioStream = audioInputSocket.getInputStream();

        var gameInputSocket = new Socket("localhost", portSpec.inputSocketPort());
        var gameInputConfirmationStream = gameInputSocket.getInputStream();
        var gameInputStream = gameInputSocket.getOutputStream();
        var gameInputStreamMutex = new ReentrantLock();

        BlockingQueue<Integer> videoPortQueue = new ArrayBlockingQueue<>(1);
        var videoServerThread = new Thread(() -> {
            // TODO Dual-stack socket pls
            try (var videoSocket = new ServerSocket(0, 2, InetAddress.getByName("0.0.0.0"))) {
                if (!videoPortQueue.offer(videoSocket.getLocalPort())) {
                    throw new AssertionError("Queue should always have enough space!");
                }
                while (!stop.get()) {
                    try {
                        var connection = videoSocket.accept();
                        connection.setTcpNoDelay(true);
                        connection.setTrafficClass(0x10 /* IPTOS_LOWDELAY */);
                        connection.setSendBufferSize(1);
                        var outputStream = connection.getOutputStream();
                        JpegCompressor.compress(videoStream, outputStream);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                System.err.println("Video socket error!");
                e.printStackTrace();
            }
        });
        videoServerThread.start();

        BlockingQueue<Integer> audioPortQueue = new ArrayBlockingQueue<>(1);
        var audioServerThread = new Thread(() -> {
            try (var audioSocket = new ServerSocket(0, 2, InetAddress.getByName("0.0.0.0"))) {
                if (!audioPortQueue.offer(audioSocket.getLocalPort())) {
                    throw new AssertionError("Queue should always have enough space!");
                }
                byte[] audioBuffer = new byte[0x2000];
                byte[] nextAudioFrameByteSizeBuffer = new byte[4];
                while (!stop.get()) {
                    try {
                        var connection = audioSocket.accept();
                        connection.setTcpNoDelay(true);
                        connection.setTrafficClass(0x10 /* IPTOS_LOWDELAY */);
                        connection.setSendBufferSize(1);
                        var outputStream = connection.getOutputStream();

                        while (!stop.get()) {
                            audioStream.readNBytes(nextAudioFrameByteSizeBuffer, 0, 4);
                            var nextAudioFrameByteSizeLong = (((long) nextAudioFrameByteSizeBuffer[0] & 0xFF) << 0) + (((long) nextAudioFrameByteSizeBuffer[1] & 0xFF) << 8) + (((long) nextAudioFrameByteSizeBuffer[2] & 0xFF) << 16) + (((long) nextAudioFrameByteSizeBuffer[3] & 0xFF) << 24);
                            var nextAudioFrameByteSize = Math.toIntExact(nextAudioFrameByteSizeLong);
                            outputStream.write(nextAudioFrameByteSizeBuffer);
                            outputStream.flush();

                            audioStream.readNBytes(audioBuffer, 0, nextAudioFrameByteSize);
                            outputStream.write(audioBuffer, 0, nextAudioFrameByteSize);
                            outputStream.flush();

                            //  // TODO Fix readInt
//                            var nextAudioFrameByteSize = IOUtils.readInt(audioStream);
//                            assert nextAudioFrameByteSize <= audioBuffer.length;
//                            audioStream.readNBytes(audioBuffer, 0, nextAudioFrameByteSize);
//                            // TODO We could compress the buffer here
//                            outputStream.write(audioBuffer, 0, nextAudioFrameByteSize);
                        }
                    } catch (IOException e) {
                        System.err.println("Audio write error, reconnecting...");
                    }
                }
            } catch (IOException e) {
                System.err.println("Could not start audio server!");
            }
        });
        audioServerThread.start();

        BlockingQueue<Integer> inputPortQueue = new ArrayBlockingQueue<>(1);
        var inputServerThread = new Thread(() -> {
            try (var inputSocket = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))) {
                if (!inputPortQueue.offer(inputSocket.getLocalPort())) {
                    throw new AssertionError("Queue should always have enough space!");
                }
                while (!stop.get()) {
                    try {
                        var connection = inputSocket.accept();
                        // connection.setTcpNoDelay(true);
                        // connection.setTrafficClass(0x10 /* IPTOS_LOWDELAY */);
                        // connection.setReceiveBufferSize(1);
                        // connection.setSendBufferSize(1);
                        var inputStream = connection.getInputStream();

                        byte[] lineBuffer = new byte[4 + 256];
                        while (!stop.get()) {
                            if (inputStream.readNBytes(lineBuffer, 0, 4) < 4) {
                                throw new IOException("EOF");
                            }

                            int nextSize = (((int) lineBuffer[0]) << 24) + (((int) lineBuffer[1]) << 16) + (((int) lineBuffer[2]) << 8) + ((int) lineBuffer[3]);
                            if (4 + nextSize > lineBuffer.length) {
                                lineBuffer = new byte[4 + nextSize];
                            }
                            if (inputStream.readNBytes(lineBuffer, 4, nextSize) < nextSize) {
                                throw new IOException("EOF");
                            }

                            gameInputStreamMutex.lock();
                            try {
                                gameInputStream.write(lineBuffer, 4, nextSize);
                            } finally {
                                gameInputStreamMutex.unlock();
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                System.err.println("Input socket error!");
                e.printStackTrace();
            }

        });
        inputServerThread.start();

        var stopSaveWatcher = new AtomicBoolean(false);
        BlockingQueue<Integer> saveGameNotifierPortQueue = new ArrayBlockingQueue<>(1);
        BlockingQueue<byte[]> saveGameQueue = new LinkedBlockingQueue<>();
        var saveGameNotifier = new Thread(() -> {
            try (var saveGameSocket = new ServerSocket(0, 2, InetAddress.getByName("0.0.0.0"))) {
                if (!saveGameNotifierPortQueue.offer(saveGameSocket.getLocalPort())) {
                    throw new AssertionError("Queue should always have enough space!");
                }
                // Ensure all save games are copied, even on connection issues
                byte[] lastSaveGame = null;
                while (!stopSaveWatcher.get()) {
                    try {
                        var connection = saveGameSocket.accept();
                        var outputStream = connection.getOutputStream();

                        while (!stopSaveWatcher.get()) {
                            lastSaveGame = lastSaveGame != null ? lastSaveGame : ConcurrentUtils.takeUninterruptibly(saveGameQueue);
                            var fileSize = lastSaveGame.length;
                            byte[] fileSizeBytes = new byte[]{
                                    (byte) (fileSize >> 24), (byte) (fileSize >> 16), (byte) (fileSize >> 8), (byte) fileSize
                            };
                            outputStream.write(fileSizeBytes);
                            outputStream.write(lastSaveGame);
                            lastSaveGame = null;
                        }
                    } catch (IOException e) {
                        System.err.println("Save game write error, reconnecting...");
                    }
                }
            } catch (IOException e) {
                System.err.println("Could not start save game server!");
            }
        });
        saveGameNotifier.start();

        var saveWatcher = new Thread(() -> {
            final Path path = saveGame.getParentFile().toPath();
            try (final WatchService watchService = FileSystems.getDefault().newWatchService(); var debouncer = new Debouncer()) {
                final WatchKey watchKey = path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                while (!stopSaveWatcher.get()) {
                    final WatchKey wk = watchService.take();
                    for (WatchEvent<?> event : wk.pollEvents()) {
                        //we only register "ENTRY_MODIFY" so the context is always a Path.
                        final Path changed = ((Path) event.context()).toAbsolutePath();
                        if (changed.getFileName().equals(saveGame.toPath().getFileName())) {
                            debouncer.debounce(() -> {
                                try {
                                    var newSaveGameBytes = Files.readAllBytes(saveGame.toPath());
                                    var oldSaveGameBytes = lastKnownSaveGameBytes.getAndSet(newSaveGameBytes);
                                    if (!Arrays.equals(newSaveGameBytes, oldSaveGameBytes)) {
                                        saveGameQueue.put(newSaveGameBytes);
                                    }
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                } catch (IOException e) {
                                    // TODO Send proper error to client
                                    stop.set(true);
                                }
                            });
                        }
                    }
                    // reset the key
                    boolean valid = wk.reset();
                    if (!valid) {
                        System.out.println("Key has been unregistered");
                    }
                }
                watchKey.cancel();
            } catch (IOException e) {
                // TODO Send proper error to client
                stop.set(true);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        saveWatcher.start();

        var processes = List.of(dsServerProcess);

        return new Session(sessionId,
                stop,
                stopSaveWatcher,
                sessionDir.toFile(),
                saveGame,
                processes,
                ConcurrentUtils.takeUninterruptibly(videoPortQueue),
                ConcurrentUtils.takeUninterruptibly(audioPortQueue),
                ConcurrentUtils.takeUninterruptibly(inputPortQueue),
                ConcurrentUtils.takeUninterruptibly(saveGameNotifierPortQueue),
                lastKnownSaveGameBytes,
                gameInputStream,
                gameInputStreamMutex,
                gameInputConfirmationStream);
    }

}
