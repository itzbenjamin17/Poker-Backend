package com.pokergame.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Manages rate limit buckets for REST and WebSocket endpoints.
 *
 * <p>REST limits are keyed by client IP, never by player name: create and join happen
 * before authentication, so a typed name is free to change and would give every
 * invented name a fresh allowance.
 */
@Service
public class RateLimitService {
    /** REST requests allowed per IP and endpoint per {@link #REST_WINDOW}; generous enough for a shared network. */
    public static final int REST_CAPACITY = 20;
    /** Window over which {@link #REST_CAPACITY} refills, one request at a time. */
    public static final Duration REST_WINDOW = Duration.ofMinutes(1);
    /** Wrong join passwords allowed per IP and room per {@link #JOIN_PASSWORD_WINDOW}. */
    public static final int JOIN_PASSWORD_FAILURES = 10;
    /** Window over which {@link #JOIN_PASSWORD_FAILURES} refills, one attempt at a time. */
    public static final Duration JOIN_PASSWORD_WINDOW = Duration.ofMinutes(5);
    // Idle REST buckets have fully refilled by now, so evicting them never resets an active limit.
    private static final long REST_IDLE_EVICTION_MS = Duration.ofMinutes(10).toMillis();
    // Refilled one token at a time, so a rejected client waits seconds rather than the whole window.
    private static final Bandwidth REST_LIMIT = Bandwidth.builder()
            .capacity(REST_CAPACITY)
            .refillGreedy(REST_CAPACITY, REST_WINDOW)
            .build();
    // Wrong-password limit per IP and room: stops password guessing without affecting other rooms.
    private static final Bandwidth JOIN_PASSWORD_LIMIT = Bandwidth.builder()
            .capacity(JOIN_PASSWORD_FAILURES)
            .refillGreedy(JOIN_PASSWORD_FAILURES, JOIN_PASSWORD_WINDOW)
            .build();
    // WS Limit: 5 messages per second (per Session)
    private static final Bandwidth WS_LIMIT = Bandwidth.builder()
            .capacity(5)
            .refillGreedy(5, Duration.ofSeconds(1))
            .build();

    @Value("${poker.rate-limiting.enabled:true}")
    private boolean enabled;
    private final ConcurrentMap<String, TrackedBucket> restBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TrackedBucket> wsBuckets = new ConcurrentHashMap<>();

    /**
     * Consumes a token for a REST request.
     *
     * @param key unique key (client IP + path)
     * @return whether the request is allowed and, if not, how long until it would be
     */
    public Decision consumeRest(String key) {
        if (!enabled) return Decision.ALLOWED;
        ConsumptionProbe probe = touch(restBuckets, key, REST_LIMIT).tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Decision.ALLOWED : Decision.rejected(toSeconds(probe.getNanosToWaitForRefill()));
    }

    /**
     * Checks, without spending anything, whether a client may still attempt to join a room.
     * Only wrong passwords spend this budget (see {@link #recordFailedJoinPassword}).
     *
     * @param clientIp resolved client IP
     * @param roomId room being joined
     * @return whether the attempt is allowed and, if not, how long until it would be
     */
    public Decision checkJoinPasswordAttempt(String clientIp, String roomId) {
        if (!enabled) return Decision.ALLOWED;
        TrackedBucket tracked = restBuckets.get(joinPasswordKey(clientIp, roomId));
        if (tracked == null) return Decision.ALLOWED;
        var estimate = tracked.bucket.estimateAbilityToConsume(1);
        return estimate.canBeConsumed()
                ? Decision.ALLOWED
                : Decision.rejected(toSeconds(estimate.getNanosToWaitForRefill()));
    }

    /**
     * Records a wrong join password, spending one attempt from that client's budget for the room.
     *
     * @param clientIp resolved client IP
     * @param roomId room whose password was wrong
     */
    public void recordFailedJoinPassword(String clientIp, String roomId) {
        if (!enabled) return;
        touch(restBuckets, joinPasswordKey(clientIp, roomId), JOIN_PASSWORD_LIMIT).tryConsume(1);
    }

    /**
     * Builds the bucket key for one client's wrong-password budget against one room.
     *
     * @param clientIp resolved client IP
     * @param roomId room being joined
     * @return the REST bucket key
     */
    public static String joinPasswordKey(String clientIp, String roomId) {
        return "join-password:" + clientIp + ":" + roomId;
    }

    /**
     * Builds the client-facing 429 message stating the real wait in seconds, or in
     * rounded-up minutes when longer than a minute.
     *
     * @param retryAfterSeconds seconds until a retry can succeed
     * @return the message to return to the client
     */
    public static String tooManyRequestsMessage(long retryAfterSeconds) {
        long seconds = Math.max(1, retryAfterSeconds);
        if (seconds <= 60) {
            return "Too many requests. Please try again in " + seconds + (seconds == 1 ? " second." : " seconds.");
        }
        long minutes = (seconds + 59) / 60;
        return "Too many requests. Please try again in " + minutes + " minutes.";
    }

    /**
     * Try to consume a token for a WebSocket message.
     * @param username the player's username
     * @return true if token consumed, false if rate limited
     */
    public boolean tryConsumeWs(String username) {
        if (!enabled) return true;
        return touch(wsBuckets, username, WS_LIMIT).tryConsume(1);
    }

    /**
     * Removes the WebSocket bucket for a player that no longer has active sessions.
     *
     * @param username composite principal name used as the bucket key
     */
    public void cleanUpWs(String username) {
        wsBuckets.remove(username);
    }

    /**
     * Evicts idle WebSocket buckets where inactivity duration exceeds the given window.
     *
     * @param idleWindowMs minimum milliseconds of inactivity before eviction
     */
    public void evictIdleWsBuckets(long idleWindowMs) {
        evictIdle(wsBuckets, idleWindowMs);
    }

    /**
     * Evicts idle REST buckets (per-IP and wrong-password budgets) where inactivity exceeds the window.
     *
     * @param idleWindowMs minimum milliseconds of inactivity before eviction
     */
    public void evictIdleRestBuckets(long idleWindowMs) {
        evictIdle(restBuckets, idleWindowMs);
    }

    /**
     * Periodically clear idle WebSocket buckets (every 60 seconds) to prevent memory leaks.
     */
    @Scheduled(fixedRate = 60000)
    public void periodicWsCleanup() {
        evictIdleWsBuckets(60_000);
    }

    /**
     * Periodically evicts idle REST buckets (every 60 seconds) so per-IP and per-room keys
     * do not accumulate.
     */
    @Scheduled(fixedRate = 60000)
    public void periodicRestCleanup() {
        evictIdleRestBuckets(REST_IDLE_EVICTION_MS);
    }

    /** Clears every REST and WebSocket bucket, restoring full quotas. */
    public void reset() {
        restBuckets.clear();
        wsBuckets.clear();
    }

    /**
     * Test hook: backdates a WebSocket bucket's last access.
     *
     * @param username WebSocket bucket key
     * @param epochMs last-access time to set
     */
    void setLastAccessForTesting(String username, long epochMs) {
        backdate(wsBuckets, username, epochMs);
    }

    /**
     * Test hook: backdates a REST bucket's last access.
     *
     * @param key REST bucket key
     * @param epochMs last-access time to set
     */
    void setRestLastAccessForTesting(String key, long epochMs) {
        backdate(restBuckets, key, epochMs);
    }

    private static Bucket touch(ConcurrentMap<String, TrackedBucket> buckets, String key, Bandwidth limit) {
        long now = System.currentTimeMillis();
        return buckets.compute(key, (k, existing) -> {
            if (existing == null) {
                return new TrackedBucket(Bucket.builder().addLimit(limit).build(), now);
            }
            existing.lastAccessEpochMs = now;
            return existing;
        }).bucket;
    }

    private static void evictIdle(ConcurrentMap<String, TrackedBucket> buckets, long idleWindowMs) {
        long cutoff = System.currentTimeMillis() - idleWindowMs;
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessEpochMs < cutoff);
    }

    private static void backdate(ConcurrentMap<String, TrackedBucket> buckets, String key, long epochMs) {
        TrackedBucket entry = buckets.get(key);
        if (entry != null) {
            entry.lastAccessEpochMs = epochMs;
        }
    }

    private static long toSeconds(long nanos) {
        long nanosPerSecond = TimeUnit.SECONDS.toNanos(1);
        return Math.max(1, (nanos + nanosPerSecond - 1) / nanosPerSecond);
    }

    public int wsBucketCount() {
        return wsBuckets.size();
    }

    public boolean hasWsBucket(String username) {
        return wsBuckets.containsKey(username);
    }

    public int restBucketCount() {
        return restBuckets.size();
    }

    /**
     * Outcome of a rate-limit check: allowed, or rejected with the seconds until the next attempt.
     *
     * @param allowed whether the request may proceed
     * @param retryAfterSeconds seconds to wait when rejected; {@code 0} when allowed
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        /** An allowed request. */
        public static final Decision ALLOWED = new Decision(true, 0);

        /**
         * Creates a rejection that asks the client to wait at least one second.
         *
         * @param retryAfterSeconds seconds until a retry can succeed
         * @return the rejection
         */
        public static Decision rejected(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }

    private static final class TrackedBucket {
        final Bucket bucket;
        volatile long lastAccessEpochMs;

        TrackedBucket(Bucket bucket, long now) {
            this.bucket = bucket;
            this.lastAccessEpochMs = now;
        }
    }
}
