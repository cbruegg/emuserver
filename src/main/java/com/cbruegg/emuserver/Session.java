package com.cbruegg.emuserver;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class Session {
    record Public(UUID uuid, int videoPort, int audioPort, int inputPort) {
    }

    private final UUID uuid;
    private final AtomicBoolean stop;
    private final File dir;
    private final File saveGame;
    private final List<Process> processes;
    private final int videoPort;
    private final int audioPort;
    private final int inputPort;

    public Session(UUID uuid, AtomicBoolean stop, File dir, File saveGame, List<Process> processes, int videoPort, int audioPort, int inputPort) {
        this.uuid = uuid;
        this.stop = stop;
        this.dir = dir;
        this.saveGame = saveGame;
        this.processes = processes;
        this.videoPort = videoPort;
        this.audioPort = audioPort;
        this.inputPort = inputPort;
    }

    public void stop() {
        stop.set(true);
        processes.forEach(Process::destroy);
        dir.deleteOnExit();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
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

    public Public toPublic() {
        return new Public(uuid, videoPort, audioPort, inputPort);
    }
}
