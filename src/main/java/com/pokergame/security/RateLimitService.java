package com.pokergame.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to manage rate limit buckets for REST and WebSocket endpoints.
 */
@Service
public class RateLimitService {

    @Value("${poker.rate-limiting.enabled:true}")
    private boolean enabled;

    private final Map<String, Bucket> restBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> wsBuckets = new ConcurrentHashMap<>();

    // REST Limit: 5 attempts per 15 minutes (per IP + PlayerName/Path)
    private static final Bandwidth REST_LIMIT = Bandwidth.builder()
            .capacity(5)
            .refillGreedy(5, Duration.ofMinutes(15))
            .build();

    // WS Limit: 5 messages per second (per Session)
    private static final Bandwidth WS_LIMIT = Bandwidth.builder()
            .capacity(5)
            .refillGreedy(5, Duration.ofSeconds(1))
            .build();

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Try to consume a token for a REST request.
     * @param key unique key (e.g., clientIp + path)
     * @return true if token consumed, false if rate limited
     */
    public boolean tryConsumeRest(String key) {
        if (!enabled) return true;
        Bucket bucket = restBuckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(REST_LIMIT).build());
        return bucket.tryConsume(1);
    }

    /**
     * Try to consume a token for a WebSocket message.
     * @param sessionId the WS session ID
     * @return true if token consumed, false if rate limited
     */
    public boolean tryConsumeWs(String sessionId) {
        if (!enabled) return true;
        Bucket bucket = wsBuckets.computeIfAbsent(sessionId, k -> Bucket.builder().addLimit(WS_LIMIT).build());
        return bucket.tryConsume(1);
    }

    public void cleanUpWs(String sessionId) {
        wsBuckets.remove(sessionId);
    }

    /**
     * Resets all buckets. Used for testing.
     */
    public void reset() {
        restBuckets.clear();
        wsBuckets.clear();
    }
}
