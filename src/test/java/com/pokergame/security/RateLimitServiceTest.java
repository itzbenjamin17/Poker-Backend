package com.pokergame.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RateLimitService}.
 */
@Tag("unit")
@DisplayName("RateLimitService")
class RateLimitServiceTest {

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService();
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    @DisplayName("should allow REST_CAPACITY requests per key and reject the next")
    void givenRestEndpoint_whenConsumedUpToLimit_thenRejectSubsequentRequests() {
        String key = "192.168.1.1:/api/room/create";

        for (int i = 0; i < RateLimitService.REST_CAPACITY; i++) {
            assertThat(service.consumeRest(key).allowed())
                    .as("REST request %d should be allowed", i + 1)
                    .isTrue();
        }

        assertThat(service.consumeRest(key).allowed())
                .as("6th REST request should be rejected by rate limit")
                .isFalse();
    }

    @Test
    @DisplayName("should isolate REST rate limit buckets across different keys")
    void givenDifferentRestKeys_whenOneIsExhausted_thenOtherKeyStillSucceeds() {
        String key1 = "192.168.1.1:/api/room/create";
        String key2 = "192.168.1.2:/api/room/create";

        for (int i = 0; i < RateLimitService.REST_CAPACITY; i++) {
            assertThat(service.consumeRest(key1).allowed()).isTrue();
        }
        assertThat(service.consumeRest(key1).allowed()).isFalse();

        // Key 2 should still have full quota
        assertThat(service.consumeRest(key2).allowed())
                .as("Distinct REST key should not be throttled by key1's exhaustion")
                .isTrue();
    }

    @Test
    @DisplayName("should allow up to 5 WebSocket messages per user and reject the 6th")
    void givenWsUser_whenConsumedUpToLimit_thenRejectSubsequentMessages() {
        String username = "Alice:room-123";

        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsumeWs(username))
                    .as("WS message %d should be allowed", i + 1)
                    .isTrue();
        }

        assertThat(service.tryConsumeWs(username))
                .as("6th WS message should be rejected by rate limit")
                .isFalse();
    }

    @Test
    @DisplayName("should isolate WebSocket rate limit buckets across different users")
    void givenDifferentWsUsers_whenOneIsExhausted_thenOtherUserStillSucceeds() {
        String user1 = "Alice:room-123";
        String user2 = "Bob:room-123";

        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsumeWs(user1)).isTrue();
        }
        assertThat(service.tryConsumeWs(user1)).isFalse();

        // User 2 should still have full quota
        assertThat(service.tryConsumeWs(user2))
                .as("Distinct WS user should not be throttled by user1's exhaustion")
                .isTrue();
    }

    @Test
    @DisplayName("should bypass rate limits when enabled is false")
    void givenRateLimitingDisabled_whenConsumedBeyondLimit_thenAllRequestsSucceed() {
        ReflectionTestUtils.setField(service, "enabled", false);
        String key = "10.0.0.1:/api/room/create";

        for (int i = 0; i < 10; i++) {
            assertThat(service.consumeRest(key).allowed()).isTrue();
            assertThat(service.tryConsumeWs("Player1")).isTrue();
        }
    }

    @Test
    @DisplayName("should clean up WebSocket bucket and reset quota")
    void givenExhaustedWsUser_whenCleanedUp_thenNewQuotaAllocated() {
        String username = "Alice:room-123";

        for (int i = 0; i < 5; i++) {
            service.tryConsumeWs(username);
        }
        assertThat(service.tryConsumeWs(username)).isFalse();

        service.cleanUpWs(username);

        // After cleanup, a fresh bucket is created on next call
        assertThat(service.tryConsumeWs(username))
                .as("After cleanup, player gets a fresh bucket")
                .isTrue();
    }

    @Test
    @DisplayName("should reset all buckets when reset is invoked")
    void givenExhaustedBuckets_whenReset_thenQuotasRefilled() {
        String restKey = "192.168.1.1:/api/room/create";
        String wsUser = "Alice:room-123";

        for (int i = 0; i < RateLimitService.REST_CAPACITY; i++) {
            service.consumeRest(restKey);
            service.tryConsumeWs(wsUser);
        }
        assertThat(service.consumeRest(restKey).allowed()).isFalse();
        assertThat(service.tryConsumeWs(wsUser)).isFalse();

        service.reset();

        assertThat(service.consumeRest(restKey).allowed()).isTrue();
        assertThat(service.tryConsumeWs(wsUser)).isTrue();
    }
    @Test
    @DisplayName("should evict idle WebSocket buckets after idle threshold and retain active ones")
    void givenActiveAndIdleWsBuckets_whenEvicted_thenOnlyIdleBucketsRemoved() {
        String idleUser = "IdleUser:room-1";
        String activeUser = "ActiveUser:room-1";

        service.tryConsumeWs(idleUser);
        service.tryConsumeWs(activeUser);

        assertThat(service.wsBucketCount()).isEqualTo(2);

        // Manually simulate time passing for idleUser by setting its last access to 65 seconds ago
        service.setLastAccessForTesting(idleUser, System.currentTimeMillis() - 65_000);

        service.evictIdleWsBuckets(60_000);

        assertThat(service.wsBucketCount()).isEqualTo(1);
        assertThat(service.hasWsBucket(activeUser)).isTrue();
        assertThat(service.hasWsBucket(idleUser)).isFalse();
    }

    @Test
    @DisplayName("should allow at least 20 REST requests per minute so a shared network is not blocked")
    void restCapacityFitsAGroupOfFriendsOnOneNetwork() {
        assertThat(RateLimitService.REST_CAPACITY).isGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("should report the real wait until the next REST request is allowed")
    void givenExhaustedRestKey_whenConsumed_thenReportsSecondsUntilNextToken() {
        String key = "192.168.1.9:/api/room/join";
        for (int i = 0; i < RateLimitService.REST_CAPACITY; i++) {
            assertThat(service.consumeRest(key).allowed()).isTrue();
        }

        RateLimitService.Decision rejected = service.consumeRest(key);

        assertThat(rejected.allowed()).isFalse();
        // Tokens refill one at a time, so the wait is one refill interval, never the whole window.
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 3L);
    }

    @Test
    @DisplayName("should block join attempts on a room only after repeated wrong passwords from one IP")
    void givenRepeatedWrongPasswords_whenChecked_thenOnlyThatIpAndRoomAreBlocked() {
        for (int i = 0; i < RateLimitService.JOIN_PASSWORD_FAILURES; i++) {
            assertThat(service.checkJoinPasswordAttempt("10.0.0.5", "room-a").allowed()).isTrue();
            service.recordFailedJoinPassword("10.0.0.5", "room-a");
        }

        RateLimitService.Decision blocked = service.checkJoinPasswordAttempt("10.0.0.5", "room-a");
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 30L);

        assertThat(service.checkJoinPasswordAttempt("10.0.0.5", "room-b").allowed())
                .as("other rooms are unaffected").isTrue();
        assertThat(service.checkJoinPasswordAttempt("10.0.0.6", "room-a").allowed())
                .as("other IPs are unaffected").isTrue();
    }

    @Test
    @DisplayName("should not spend the join-password budget when only checking")
    void givenOnlyChecks_whenCheckedManyTimes_thenStillAllowed() {
        for (int i = 0; i < RateLimitService.JOIN_PASSWORD_FAILURES * 3; i++) {
            assertThat(service.checkJoinPasswordAttempt("10.0.0.7", "room-a").allowed()).isTrue();
        }
    }

    @Test
    @DisplayName("should bypass the join-password limit when rate limiting is disabled")
    void givenRateLimitingDisabled_whenWrongPasswordsRecorded_thenNeverBlocked() {
        ReflectionTestUtils.setField(service, "enabled", false);
        for (int i = 0; i < RateLimitService.JOIN_PASSWORD_FAILURES * 2; i++) {
            service.recordFailedJoinPassword("10.0.0.8", "room-a");
        }
        assertThat(service.checkJoinPasswordAttempt("10.0.0.8", "room-a").allowed()).isTrue();
    }

    @Test
    @DisplayName("should evict idle REST buckets and keep recently used ones")
    void givenIdleAndActiveRestBuckets_whenEvicted_thenOnlyIdleRemoved() {
        service.consumeRest("idle-key");
        service.recordFailedJoinPassword("10.0.0.9", "room-idle");
        service.consumeRest("active-key");
        assertThat(service.restBucketCount()).isEqualTo(3);

        long longAgo = System.currentTimeMillis() - 20 * 60_000L;
        service.setRestLastAccessForTesting("idle-key", longAgo);
        service.setRestLastAccessForTesting(RateLimitService.joinPasswordKey("10.0.0.9", "room-idle"), longAgo);

        service.evictIdleRestBuckets(10 * 60_000L);

        assertThat(service.restBucketCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should phrase the wait in seconds or rounded-up minutes")
    void retryMessageUsesReadableUnits() {
        assertThat(RateLimitService.tooManyRequestsMessage(1))
                .isEqualTo("Too many requests. Please try again in 1 second.");
        assertThat(RateLimitService.tooManyRequestsMessage(40))
                .isEqualTo("Too many requests. Please try again in 40 seconds.");
        assertThat(RateLimitService.tooManyRequestsMessage(61))
                .isEqualTo("Too many requests. Please try again in 2 minutes.");
    }
}
