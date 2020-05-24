package com.cbruegg.emuserver;

import com.cbruegg.emuserver.command.Command;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

class Session {
    record Public(UUID uuid, int videoPort, int audioPort, int inputPort, int saveGameNotifierPort) {
    }

    private final UUID uuid;
    private final AtomicBoolean stop;
    private final AtomicBoolean stopSaveWatcher;
    private final File dir;
    private final File saveGame;
    private final List<Process> processes;
    private final int videoPort;
    private final int audioPort;
    private final int inputPort;
    private final int saveGameNotifierPort;
    private final AtomicReference<byte[]> lastKnownSaveGameRef;
    private final OutputStream gameInputStream;
    private final Lock gameInputStreamMutex;
    private final InputStream gameInputConfirmationStream;

    public Session(UUID uuid,
                   AtomicBoolean stop,
                   AtomicBoolean stopSaveWatcher,
                   File dir,
                   File saveGame,
                   List<Process> processes,
                   int videoPort,
                   int audioPort,
                   int inputPort,
                   int saveGameNotifierPort,
                   AtomicReference<byte[]> lastKnownSaveGameRef,
                   OutputStream gameInputStream,
                   Lock gameInputStreamMutex,
                   InputStream gameInputConfirmationStream) {
        this.uuid = uuid;
        this.stop = stop;
        this.stopSaveWatcher = stopSaveWatcher;
        this.dir = dir;
        this.saveGame = saveGame;
        this.processes = processes;
        this.videoPort = videoPort;
        this.audioPort = audioPort;
        this.inputPort = inputPort;
        this.saveGameNotifierPort = saveGameNotifierPort;
        this.lastKnownSaveGameRef = lastKnownSaveGameRef;
        this.gameInputStream = gameInputStream;
        this.gameInputStreamMutex = gameInputStreamMutex;
        this.gameInputConfirmationStream = gameInputConfirmationStream;
    }

    public void stop() {
        stop.set(true);
        for (Process process : processes) {
            process.destroy();
            ensureWaitFor(process);
        }

        // TODO Delete session files, but only if save game is synced
        dir.deleteOnExit();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private static void ensureWaitFor(Process process) {
        while (true) {
            try {
                process.waitFor();
                break;
            } catch (InterruptedException ignored) {
            }
        }
    }

    public UUID getUuid() {
        return uuid;
    }

    public File getDir() {
        return dir;
    }

    public File getSaveGame() {
        return saveGame;
    }

    public int getVideoPort() {
        return videoPort;
    }

    public int getAudioPort() {
        return audioPort;
    }

    public int getInputPort() {
        return inputPort;
    }

    public int getSaveGameNotifierPort() {
        return saveGameNotifierPort;
    }

    public AtomicReference<byte[]> getLastKnownSaveGameRef() {
        return lastKnownSaveGameRef;
    }

    public void onSaveGameUploaded() throws IOException {
        new Command.LoadGameSave().writeTo(gameInputStream, gameInputStreamMutex, gameInputConfirmationStream);
    }

    public Path getSaveState() throws IOException {
        var saveStateFile = Files.createTempFile(getDir().toPath(), "savestate", null);
        new Command.SaveState(saveStateFile.toAbsolutePath().toString()).writeTo(gameInputStream, gameInputStreamMutex, gameInputConfirmationStream);
        return saveStateFile;
    }

    public void loadSaveState(Path saveState) throws IOException {
        new Command.LoadState(saveState.toAbsolutePath().toString()).writeTo(gameInputStream, gameInputStreamMutex, gameInputConfirmationStream);
    }

    public Public toPublic() {
        return new Public(uuid, videoPort, audioPort, inputPort, saveGameNotifierPort);
    }
}
