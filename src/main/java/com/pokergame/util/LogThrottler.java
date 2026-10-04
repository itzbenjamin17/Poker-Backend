package com.pokergame.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe rate limiter for log statements to prevent log flooding from repetitive events.
 */
public class LogThrottler {
    private final long throttleMs;
    private final ConcurrentHashMap<String, Long> lastLoggedByKey = new ConcurrentHashMap<>();

    /**
     * Creates a LogThrottler with the given throttle window in milliseconds.
     *
     * @param throttleMs minimum time required between executions for the same key
     */
    public LogThrottler(long throttleMs) {
        this.throttleMs = throttleMs;
    }

    /**
     * Determines whether logging should proceed for the given key based on elapsed time.
     *
     * @param key unique discriminator (such as client IP or player:destination)
     * @return true if the event should be logged, false if throttled
     */
    public boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        Long last = lastLoggedByKey.get(key);
        if (last == null || (now - last) >= throttleMs) {
            lastLoggedByKey.put(key, now);
            return true;
        }
        return false;
    }

    /**
     * Executes the given logging action if the specified key is not currently throttled.
     *
     * @param key unique discriminator
     * @param logAction runnable containing the logger invocation
     */
    public void throttle(String key, Runnable logAction) {
        if (shouldLog(key)) {
            logAction.run();
        }
    }
}
