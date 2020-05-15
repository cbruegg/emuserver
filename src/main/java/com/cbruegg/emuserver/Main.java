package com.cbruegg.emuserver;

import com.cbruegg.emuserver.serialization.UUIDAdapter;
import com.cbruegg.emuserver.utils.ConcurrentUtils;
import com.cbruegg.emuserver.utils.IOUtils;
import com.squareup.moshi.Moshi;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

import javax.annotation.Nullable;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    public static void main(String[] args) {
        var ffmpegPath = args.length > 0 ? args[0] : "ffmpeg";

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

        router.get("/roms/nds/:md5/exists").handler(event -> {
            var md5 = event.pathParam("md5");
            var exists = new File(romDirNds, md5 + ".nds").exists();
            event.response().putHeader("content-type", "text/plain").end(String.valueOf(exists));
        });

        router.post("/roms/nds/:md5").blockingHandler(event -> {
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
                if (Objects.equals(event.pathParam("md5"), actualMd5)) {
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
        router.post("/roms/nds/:md5/session").blockingHandler(event -> {
            var md5 = event.pathParam("md5");
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
            try {

                var session = createNewSession(melonDsServerFile, melonDsBiosDir, romFile, initialSaveGame, ffmpegPath);
                sessions.put(session.getUuid(), session);

                var publicSession = session.toPublic();
                event.response().putHeader("content-type", "application/json").setStatusCode(HttpURLConnection.HTTP_CREATED).end(publicSessionAdapter.toJson(publicSession));
            } catch (IOException e) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR).end("Could not create session!");
            }
        });
        router.delete("/roms/nds/:md5/session/:uuid").handler(event -> {
            var uuid = UUID.fromString(event.pathParam("uuid"));
            var session = sessions.remove(uuid);
            if (session == null) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_NOT_FOUND).end("Session with UUID " + uuid + " does not exist!");
            } else {
                session.stop();
                event.response().putHeader("content-type", "text/plain").end("Session has been stopped.");
            }
        });
        router.get("/roms/nds/:md5/session/:uuid/savegame").handler(event -> {
            var uuid = UUID.fromString(event.pathParam("uuid"));
            var session = sessions.get(uuid);
            if (session == null) {
                event.response().putHeader("content-type", "text/plain").setStatusCode(HttpURLConnection.HTTP_NOT_FOUND).end("Session with UUID " + uuid + " does not exist!");
            } else {
                var saveGame = session.getSaveGame();
                event.response().putHeader("content-type", "application/octet-stream").sendFile(saveGame.getAbsolutePath()).end();
            }
        });

        httpServer.requestHandler(router).listen(1115);

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

    private static Session createNewSession(File dsServer, File dsServerBiosDir, File rom, @Nullable File initialSaveGame, String ffmpegBin) throws IOException {
        var sessionId = UUID.randomUUID();
        var sessionDir = Files.createTempDirectory("emuserver-" + sessionId);
        var saveGame = new File(sessionDir.toFile(), rom.getName() + ".dsv");
        var stop = new AtomicBoolean(false);

        if (initialSaveGame != null && !IOUtils.moveOrCopy(initialSaveGame, saveGame)) {
            throw new IOException("Could not import initial savegame!");
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

        var ffmpegVideoProcess = new ProcessBuilder(
                ffmpegBin,
                "-f", "rawvideo",
                "-pix_fmt", "bgra",
                "-s:v", "256x384",
                "-r", "60",
                "-i", "tcp://localhost:" + portSpec.screenSocketPort(),
                "-tune", "zerolatency",
                "-c:v", "libx264",
                "-f", "mpegts",
                "pipe:1")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        var videoStream = ffmpegVideoProcess.getInputStream();

        var ffmpegAudioProcess = new ProcessBuilder(
                ffmpegBin,
                "-f", "s16le",
                "-ar", "32.768k",
                "-ac", "2",
                "-i", "tcp://localhost:" + portSpec.audioSocketPort(),
                "-b:a", "128k",
                "-c:a", "aac",
                "-f", "adts",
                "pipe:1")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        var audioStream = ffmpegAudioProcess.getInputStream();

        var gameInputSocket = new Socket("localhost", portSpec.inputSocketPort());
        var gameInputStream = gameInputSocket.getOutputStream();

        // TODO Important: Client needs to consume audio stream at desired rate. That might not be ideal. Find way out?

        BlockingQueue<Integer> videoPortQueue = new ArrayBlockingQueue<>(1);
        var videoServerThread = new Thread(() -> {
            // TODO Discard while no client is connected?
            // TODO Dual-stack socket pls
            try (var videoSocket = new ServerSocket(0, 2, InetAddress.getByName("0.0.0.0"))) {
                if (!videoPortQueue.offer(videoSocket.getLocalPort())) {
                    throw new AssertionError("Queue should always have enough space!");
                }
                while (!stop.get()) {
                    try {
                        var connection = videoSocket.accept();
                        var outputStream = connection.getOutputStream();
                        IOUtils.transfer(videoStream, outputStream, 8192);
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
                while (!stop.get()) {
                    try {
                        var connection = audioSocket.accept();
                        var outputStream = connection.getOutputStream();
                        IOUtils.transfer(audioStream, outputStream, 1024);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                System.err.println("Audio socket error!");
                e.printStackTrace();
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
                        IOUtils.transfer(connection.getInputStream(), gameInputStream, 4);
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

        var processes = List.of(ffmpegVideoProcess, ffmpegAudioProcess, dsServerProcess);

        return new Session(sessionId,
                stop,
                sessionDir.toFile(),
                saveGame,
                processes,
                ConcurrentUtils.takeUninterruptibly(videoPortQueue),
                ConcurrentUtils.takeUninterruptibly(audioPortQueue),
                ConcurrentUtils.takeUninterruptibly(inputPortQueue));
    }

}
