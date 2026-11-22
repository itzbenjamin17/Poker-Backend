package com.pokergame.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokergame.testutil.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitService}.
 */
@Tag("unit")
@DisplayName("RateLimitService")
class RateLimitServiceTest {

    private static final long INITIAL_EPOCH_MS = 1_700_000_000_000L;

    private MutableClock clock;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(INITIAL_EPOCH_MS);
        service = new RateLimitService(true, clock);
    }

    @Test
    @DisplayName("should allow REST_CAPACITY requests per key, reject the 21st, and refill one token after 3 seconds")
    void givenRestEndpoint_whenConsumedUpToLimit_thenRejectAndRefillDeterministically() {
        String key = "192.168.1.1:/api/room/create";

        for (int i = 0; i < RateLimitService.REST_CAPACITY; i++) {
            assertThat(service.consumeRest(key).allowed())
                    .as("REST request %d should be allowed", i + 1)
                    .isTrue();
        }

        RateLimitService.Decision rejected = service.consumeRest(key);
        assertThat(rejected.allowed())
                .as("21st REST request should be rejected by rate limit")
                .isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(3L);

        clock.advanceByMillis(2_999L);
        assertThat(service.consumeRest(key).allowed()).isFalse();

        clock.advanceByMillis(1L);
        assertThat(service.consumeRest(key).allowed()).isTrue();
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

        assertThat(service.consumeRest(key2).allowed())
                .as("Distinct REST key should not be throttled by key1's exhaustion")
                .isTrue();
    }

    @Test
    @DisplayName("should allow up to 5 WebSocket messages per user, reject the 6th, and refill one token after 200ms")
    void givenWsUser_whenConsumedUpToLimit_thenRejectAndRefillDeterministically() {
        String username = "Alice:room-123";

        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsumeWs(username))
                    .as("WS message %d should be allowed", i + 1)
                    .isTrue();
        }

        assertThat(service.tryConsumeWs(username))
                .as("6th WS message should be rejected by rate limit")
                .isFalse();

        clock.advanceByMillis(199L);
        assertThat(service.tryConsumeWs(username)).isFalse();

        clock.advanceByMillis(1L);
        assertThat(service.tryConsumeWs(username)).isTrue();
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

        assertThat(service.tryConsumeWs(user2))
                .as("Distinct WS user should not be throttled by user1's exhaustion")
                .isTrue();
    }

    @Test
    @DisplayName("should bypass rate limits when enabled is false")
    void givenRateLimitingDisabled_whenConsumedBeyondLimit_thenAllRequestsSucceed() {
        RateLimitService disabledService = new RateLimitService(false, clock);
        String key = "10.0.0.1:/api/room/create";

        for (int i = 0; i < RateLimitService.REST_CAPACITY + 5; i++) {
            assertThat(disabledService.consumeRest(key).allowed()).isTrue();
            assertThat(disabledService.tryConsumeWs("Player1")).isTrue();
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
    @DisplayName("should evict idle WebSocket buckets via periodicWsCleanup and retain recently touched ones")
    void givenActiveAndIdleWsBuckets_whenPeriodicWsCleanupRuns_thenOnlyIdleBucketsRemoved() {
        String idleUser = "IdleUser:room-1";
        String activeUser = "ActiveUser:room-1";

        service.tryConsumeWs(idleUser);
        service.tryConsumeWs(activeUser);
        assertThat(service.wsBucketCount()).isEqualTo(2);

        clock.advanceByMillis(35_000L);
        service.tryConsumeWs(activeUser);

        clock.advanceByMillis(30_000L);
        service.periodicWsCleanup();

        assertThat(service.wsBucketCount()).isEqualTo(1);
        assertThat(service.hasWsBucket(activeUser)).isTrue();
        assertThat(service.hasWsBucket(idleUser)).isFalse();
    }

    @Test
    @DisplayName("should block join attempts on a room after repeated wrong passwords and refill one attempt after 30s")
    void givenRepeatedWrongPasswords_whenChecked_thenOnlyThatIpAndRoomAreBlockedAndRefillDeterministically() {
        for (int i = 0; i < RateLimitService.JOIN_PASSWORD_FAILURES; i++) {
            assertThat(service.checkJoinPasswordAttempt("10.0.0.5", "room-a").allowed()).isTrue();
            service.recordFailedJoinPassword("10.0.0.5", "room-a");
        }

        RateLimitService.Decision blocked = service.checkJoinPasswordAttempt("10.0.0.5", "room-a");
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(30L);

        assertThat(service.checkJoinPasswordAttempt("10.0.0.5", "room-b").allowed())
                .as("other rooms are unaffected").isTrue();
        assertThat(service.checkJoinPasswordAttempt("10.0.0.6", "room-a").allowed())
                .as("other IPs are unaffected").isTrue();

        clock.advanceByMillis(29_999L);
        assertThat(service.checkJoinPasswordAttempt("10.0.0.5", "room-a").allowed()).isFalse();

        clock.advanceByMillis(1L);
        assertThat(service.checkJoinPasswordAttempt("10.0.0.5", "room-a").allowed()).isTrue();
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
        RateLimitService disabledService = new RateLimitService(false, clock);
        for (int i = 0; i < RateLimitService.JOIN_PASSWORD_FAILURES * 2; i++) {
            disabledService.recordFailedJoinPassword("10.0.0.8", "room-a");
        }
        assertThat(disabledService.checkJoinPasswordAttempt("10.0.0.8", "room-a").allowed()).isTrue();
    }

    @Test
    @DisplayName("should evict idle REST buckets via periodicRestCleanup and keep recently touched ones")
    void givenIdleAndActiveRestBuckets_whenPeriodicRestCleanupRuns_thenOnlyIdleRemoved() {
        service.consumeRest("idle-key");
        service.recordFailedJoinPassword("10.0.0.9", "room-idle");
        service.consumeRest("active-key");
        assertThat(service.restBucketCount()).isEqualTo(3);

        clock.advanceByMillis(6 * 60_000L);
        service.consumeRest("active-key");

        clock.advanceByMillis(5 * 60_000L);
        service.periodicRestCleanup();

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
