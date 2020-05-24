package com.cbruegg.emuserver;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private final Runnable onSaveGameUploaded;

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
                   AtomicReference<byte[]> lastKnownSaveGameRef, Runnable onSaveGameUploaded) {
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
        this.onSaveGameUploaded = onSaveGameUploaded;
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

    public void onSaveGameUploaded() {
        onSaveGameUploaded.run();
    }

    public Public toPublic() {
        return new Public(uuid, videoPort, audioPort, inputPort, saveGameNotifierPort);
    }
}
