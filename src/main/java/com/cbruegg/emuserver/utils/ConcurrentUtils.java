package com.cbruegg.emuserver.utils;

import java.util.concurrent.BlockingQueue;

public class ConcurrentUtils {
    private ConcurrentUtils() {
    }

    public static <T> T takeUninterruptibly(BlockingQueue<T> queue) {
        while (true) {
            try {
                return queue.take();
            } catch (InterruptedException ignored) {
            }
        }
    }
}
