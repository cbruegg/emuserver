package com.cbruegg.emuserver.utils;

import java.util.Timer;
import java.util.TimerTask;

public final class Debouncer implements AutoCloseable {

    private static final long DEFAULT_DURATION_MS = 500;

    private final long durationMs;

    private final Timer timer = new Timer("Debouncer");
    private TimerTask currentTimerTask = null;

    public Debouncer() {
        this(DEFAULT_DURATION_MS);
    }

    public Debouncer(long durationMs) {
        this.durationMs = durationMs;
    }

    public synchronized void debounce(Runnable runnable) {
        if (currentTimerTask != null) {
            currentTimerTask.cancel();
        }

        currentTimerTask = new TimerTask() {
            @Override
            public void run() {
                runnable.run();
            }
        };
        timer.schedule(currentTimerTask, durationMs);
    }

    @Override
    public void close() {
        timer.cancel();
    }
}
