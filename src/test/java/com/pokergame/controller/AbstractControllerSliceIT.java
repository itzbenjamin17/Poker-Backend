package com.pokergame.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokergame.config.GameClockConfig;
import com.pokergame.security.JwtAuthenticationFilter;
import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.MutableClock;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared web-layer slice for {@link RoomController} and {@link GameController}. Both
 * controller ITs extend this class so they reuse one cached Spring context. The room lock
 * and the REST rate limiter are real; the services behind the controllers are mocks.
 */
@WebMvcTest(controllers = {GameController.class, RoomController.class})
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = "poker.rate-limiting.enabled=true")
@Import({RoomCoordinator.class, RateLimitService.class, GameClockConfig.class, ControllerSliceClockConfig.class})
abstract class AbstractControllerSliceIT {
    /** Fixed time source for rooms built by the tests. */
    static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RoomCoordinator roomCoordinator;

    @Autowired
    RateLimitService rateLimitService;

    /** Drives rate-limit windows; the same instance backs {@link RateLimitService}. */
    @Autowired
    MutableClock clock;

    @MockitoBean
    RoomService roomService;

    @MockitoBean
    GameLifecycleService gameLifecycleService;

    /** Required by the WebSocket exception advice that the controller slice loads. */
    @MockitoBean
    GameStateService gameStateService;

    @MockitoBean
    PlayerActionService playerActionService;

    @MockitoBean
    JwtService jwtService;

    /** Replaces the real filter bean the slice would otherwise construct; filters are disabled for MockMvc. */
    @MockitoBean
    JwtAuthenticationFilter jwtAuthenticationFilter;

    /** Gives every test a full REST and join-password allowance at a known time. */
    @BeforeEach
    void resetRateLimits() {
        clock.setMillis(ControllerSliceClockConfig.START_EPOCH_MS);
        rateLimitService.reset();
    }

    /** Every request must release its room lock, whether it succeeds or is rejected. */
    @AfterEach
    void assertNoRoomLockLeaked() {
        assertThat(roomCoordinator.activeLockCount()).as("room locks still held after the request").isZero();
    }

    /**
     * Builds the authentication a request carries after the JWT filter accepted a token.
     *
     * @param playerName   authenticated player
     * @param roomId       room (or game) the token is bound to
     * @param membershipId membership the token was issued for
     * @return pre-authenticated token wrapping a {@link PlayerPrincipal}
     */
    static Authentication authenticated(String playerName, String roomId, String membershipId) {
        return new PreAuthenticatedAuthenticationToken(
                new PlayerPrincipal(playerName, roomId, membershipId), "token", Collections.emptyList());
    }

    /**
     * Creates a stub answer that records whether the calling thread held the room lock for
     * {@code roomId} at the moment the service was reached, then returns {@code result}.
     *
     * @param roomId room whose lock must be held
     * @param held   receives {@code true} when the lock was held during the call
     * @param result value the stubbed call returns ({@code null} for void methods)
     * @param <T>    stubbed return type
     * @return answer to pass to {@code thenAnswer} or {@code doAnswer}
     */
    <T> Answer<T> recordingLockHeld(String roomId, AtomicBoolean held, T result) {
        return _ -> {
            held.set(roomCoordinator.isHeldByCurrentThread(roomId));
            return result;
        };
    }
}
