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
    @DisplayName("should allow up to 5 REST requests per key and reject the 6th")
    void givenRestEndpoint_whenConsumedUpToLimit_thenRejectSubsequentRequests() {
        String key = "192.168.1.1:/api/room/create";

        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsumeRest(key))
                    .as("REST request %d should be allowed", i + 1)
                    .isTrue();
        }

        assertThat(service.tryConsumeRest(key))
                .as("6th REST request should be rejected by rate limit")
                .isFalse();
    }

    @Test
    @DisplayName("should isolate REST rate limit buckets across different keys")
    void givenDifferentRestKeys_whenOneIsExhausted_thenOtherKeyStillSucceeds() {
        String key1 = "192.168.1.1:/api/room/create";
        String key2 = "192.168.1.2:/api/room/create";

        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsumeRest(key1)).isTrue();
        }
        assertThat(service.tryConsumeRest(key1)).isFalse();

        // Key 2 should still have full quota
        assertThat(service.tryConsumeRest(key2))
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
            assertThat(service.tryConsumeRest(key)).isTrue();
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

        for (int i = 0; i < 5; i++) {
            service.tryConsumeRest(restKey);
            service.tryConsumeWs(wsUser);
        }
        assertThat(service.tryConsumeRest(restKey)).isFalse();
        assertThat(service.tryConsumeWs(wsUser)).isFalse();

        service.reset();

        assertThat(service.tryConsumeRest(restKey)).isTrue();
        assertThat(service.tryConsumeWs(wsUser)).isTrue();
    }
}
