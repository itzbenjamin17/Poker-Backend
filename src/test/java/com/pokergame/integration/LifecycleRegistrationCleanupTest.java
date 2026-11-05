package com.pokergame.integration;

import com.pokergame.config.WebSocketEventListener;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"poker.rate-limiting.enabled=true"})
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
class LifecycleRegistrationCleanupTest {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private DurableMutationAspect durableMutationAspect;

    @Test
    @DisplayName("Repeated create, connect, disconnect, leave, and destroy cycles leave no registrations or lock reservations")
    void repeatedCyclesLeaveNoRegistrationsOrLocks() {
        String host = "Host1";
        String guest = "Guest1";

        // 1. Create Room
        String roomId = roomService.createRoom(new CreateRoomRequest("Cleanup Room 1", host, 6, 10, 20, 1000, null));
        roomService.joinRoom(roomId, new JoinRoomRequest("Cleanup Room 1", guest, null));

        // 2. Connect WebSocket sessions
        PlayerPrincipal hostPrincipal = new PlayerPrincipal(host, roomId, roomService.getRoom(roomId).getMembershipId(host));
        PlayerPrincipal guestPrincipal = new PlayerPrincipal(guest, roomId, roomService.getRoom(roomId).getMembershipId(guest));
        webSocketEventListener.registerSession(hostPrincipal, "session-host-1");
        webSocketEventListener.registerSession(guestPrincipal, "session-guest-1");

        // Consume some WS rate limits
        rateLimitService.tryConsumeWs(hostPrincipal.playerName() + ":" + hostPrincipal.roomId());
        rateLimitService.tryConsumeWs(guestPrincipal.playerName() + ":" + guestPrincipal.roomId());
        assertThat(rateLimitService.hasWsBucket(hostPrincipal.playerName() + ":" + hostPrincipal.roomId())).isTrue();
        assertThat(rateLimitService.hasWsBucket(guestPrincipal.playerName() + ":" + guestPrincipal.roomId())).isTrue();

        // 3. Start game
        gameLifecycleService.createGameFromRoom(roomId);

        // 4. Guest departs explicitly
        gameLifecycleService.departParticipants(roomId, List.of(guest));

        // Cleanup runs asynchronously on the scheduler: guest WS bucket and sessions should be retired
        await().atMost(CLEANUP_TIMEOUT).untilAsserted(() -> {
            assertThat(rateLimitService.hasWsBucket(guestPrincipal.playerName() + ":" + guestPrincipal.roomId())).isFalse();
            assertThat(webSocketEventListener.getActiveSessionCount(guestPrincipal.playerName() + ":" + guestPrincipal.roomId())).isEqualTo(0);
            assertThat(webSocketEventListener.hasPendingDisconnect(guestPrincipal.playerName() + ":" + guestPrincipal.roomId())).isFalse();
        });

        // 5. Host leaves during retained review: terminal cleanup task is preserved
        gameLifecycleService.departParticipants(roomId, List.of(host));

        // Host membership is retired from WebSocket registrations
        await().atMost(CLEANUP_TIMEOUT).untilAsserted(() -> {
            assertThat(webSocketEventListener.getActiveSessionCount(hostPrincipal.playerName() + ":" + hostPrincipal.roomId())).isEqualTo(0);
            assertThat(webSocketEventListener.hasPendingDisconnect(hostPrincipal.playerName() + ":" + hostPrincipal.roomId())).isFalse();
            assertThat(rateLimitService.hasWsBucket(hostPrincipal.playerName() + ":" + hostPrincipal.roomId())).isFalse();
            assertThat(webSocketEventListener.getActiveSessionLockCount()).isEqualTo(0);
            assertThat(webSocketEventListener.getPendingDisconnectCount()).isEqualTo(0);
            assertThat(webSocketEventListener.getSessionToPrincipalCount()).isEqualTo(0);
        });

        // Terminal cleanup task is preserved during review retention
        assertThat(gameLifecycleService.getRuntimeTaskCount(roomId)).isEqualTo(1);

        // 6. Terminal cleanup deadline fires / performGameCleanup is invoked
        gameLifecycleService.performGameCleanup(roomId);

        // All runtime tasks, games, rooms, and locks are completely retired
        await().atMost(CLEANUP_TIMEOUT).untilAsserted(() -> {
            assertThat(gameLifecycleService.getRuntimeTaskCount(roomId)).isEqualTo(0);
            assertThat(gameLifecycleService.getGame(roomId)).isNull();
            assertThat(roomService.getRoom(roomId)).isNull();
            assertThat(durableMutationAspect.getLockRegistry().reservationCount(roomId)).isEqualTo(0);
        });
    }
}
