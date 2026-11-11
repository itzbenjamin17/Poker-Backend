package com.pokergame.service;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies Ticket #24:
 * Room ownership enforcement across internal mutation boundaries under RoomCoordinator.
 * Uncoordinated calls to internal mutating methods throw IllegalStateException immediately.
 */
class RoomOwnershipEnforcementIntegrationTest {

    private RoomCoordinator roomCoordinator;
    private RoomService roomService;
    private GameLifecycleService lifecycleService;
    private GameStateService gameStateService;
    private PlayerActionService playerActionService;
    private GameParticipantDepartureHandler departureHandler;
    private HandEvaluatorService handEvaluator;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        roomCoordinator = new RoomCoordinator();
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(inv -> mock(ScheduledFuture.class));

        roomService = new RoomService(messagingTemplate, eventPublisher, 60_000L, new BCryptPasswordEncoder(), roomCoordinator);
        handEvaluator = new HandEvaluatorService();
        gameStateService = new GameStateService(roomService, messagingTemplate);
        lifecycleService = new GameLifecycleService(
                roomService, handEvaluator, gameStateService, messagingTemplate, taskScheduler, roomCoordinator);
        playerActionService = new PlayerActionService(lifecycleService, gameStateService, roomCoordinator);
        departureHandler = lifecycleService.getDepartureHandler();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            stopExecutor(executor);
        }
    }

    @Test
    @DisplayName("assertHeldByCurrentThread throws IllegalStateException when uncoordinated")
    void assertHeldByCurrentThread_ThrowsWhenNotHeld() {
        assertThatThrownBy(() -> roomCoordinator.assertHeldByCurrentThread("uncoordinated-room"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold coordination lease for room");
    }

    @Test
    @DisplayName("assertHeldByCurrentThread succeeds when lease is held")
    void assertHeldByCurrentThread_SucceedsWhenHeld() {
        roomCoordinator.coordinate("test-room", () -> {
            assertThatCode(() -> roomCoordinator.assertHeldByCurrentThread("test-room"))
                    .doesNotThrowAnyException();
            return null;
        });
    }

    @Test
    @DisplayName("departureHandler.departParticipantsUnderLock throws IllegalStateException when called uncoordinated")
    void departureHandler_DepartParticipants_ThrowsWhenUncoordinated() {
        String roomId = createTestRoom("Host", "Guest");

        assertThatThrownBy(() -> departureHandler.departParticipantsUnderLock(roomId, List.of("Guest"), roomService.getRoom(roomId), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold coordination lease for room");
    }

    @Test
    @DisplayName("departureHandler.departParticipantsUnderLock succeeds when called under RoomCoordinator")
    void departureHandler_DepartParticipants_SucceedsWhenCoordinated() {
        String roomId = createTestRoom("Host", "Guest");

        roomCoordinator.coordinate(roomId, () -> {
            departureHandler.departParticipantsUnderLock(roomId, List.of("Guest"), roomService.getRoom(roomId), null);
            return null;
        });

        Room room = roomService.getRoom(roomId);
        assertThat(room.getPlayers()).containsExactly("Host");
    }

    @Test
    @DisplayName("departureHandler.markPlayerDisconnectedUnderLock throws IllegalStateException when called uncoordinated")
    void departureHandler_MarkPlayerDisconnected_ThrowsWhenUncoordinated() {
        String roomId = createTestRoom("Host", "Guest");
        lifecycleService.createGameFromRoom(roomId);
        Game game = lifecycleService.getGame(roomId);

        assertThatThrownBy(() -> departureHandler.markPlayerDisconnectedUnderLock(roomId, game, "Guest", System.currentTimeMillis() + 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold coordination lease for room");
    }

    @Test
    @DisplayName("departureHandler.reconcilePlayerReconnectedUnderLock throws IllegalStateException when called uncoordinated")
    void departureHandler_ReconcilePlayerReconnected_ThrowsWhenUncoordinated() {
        String roomId = createTestRoom("Host", "Guest");
        lifecycleService.createGameFromRoom(roomId);
        Game game = lifecycleService.getGame(roomId);

        assertThatThrownBy(() -> departureHandler.reconcilePlayerReconnectedUnderLock(roomId, "Guest", roomService.getRoom(roomId), game))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold coordination lease for room");
    }

    @Test
    @DisplayName("departureHandler.claimWinUnderLock throws IllegalStateException when called uncoordinated")
    void departureHandler_ClaimWin_ThrowsWhenUncoordinated() {
        String roomId = createTestRoom("Host", "Guest");
        lifecycleService.createGameFromRoom(roomId);
        Game game = lifecycleService.getGame(roomId);

        assertThatThrownBy(() -> departureHandler.claimWinUnderLock(roomId, game, roomService.getRoom(roomId), "Host"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold coordination lease for room");
    }

    @Test
    @DisplayName("departureHandler.removeDisconnectedPlayerUnderLock throws IllegalStateException when called uncoordinated")
    void departureHandler_RemoveDisconnectedPlayer_ThrowsWhenUncoordinated() {
        String roomId = createTestRoom("Host", "Guest");
        lifecycleService.createGameFromRoom(roomId);
        Game game = lifecycleService.getGame(roomId);

        assertThatThrownBy(() -> departureHandler.removeDisconnectedPlayerUnderLock(roomId, roomService.getRoom(roomId), game, "Guest", 12345L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold coordination lease for room");
    }

    @Test
    @DisplayName("roomService.leavePlayersBatch throws IllegalStateException when called uncoordinated")
    void roomService_LeavePlayersBatch_ThrowsWhenUncoordinated() {
        String roomId = createTestRoom("Host", "Guest");

        assertThatThrownBy(() -> roomService.leavePlayersBatch(roomId, List.of("Guest"), "Host"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold coordination lease for room");
    }

    @Test
    @DisplayName("roomService.leavePlayersBatch succeeds when called under RoomCoordinator")
    void roomService_LeavePlayersBatch_SucceedsWhenCoordinated() {
        String roomId = createTestRoom("Host", "Guest");

        roomCoordinator.coordinate(roomId, () -> {
            roomService.leavePlayersBatch(roomId, List.of("Guest"), "Host");
            return null;
        });

        Room room = roomService.getRoom(roomId);
        assertThat(room.getPlayers()).containsExactly("Host");
    }

    @Test
    @DisplayName("playerActionService.executePlayerAction throws IllegalStateException when called uncoordinated")
    void playerActionService_ExecutePlayerAction_ThrowsWhenUncoordinated() {
        String roomId = createTestRoom("Host", "Guest");
        lifecycleService.createGameFromRoom(roomId);
        Game game = lifecycleService.getGame(roomId);
        Player actor = game.getCurrentPlayer();

        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);
        assertThatThrownBy(() -> playerActionService.executePlayerAction(roomId, request, actor.getName()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold coordination lease for room");
    }

    @Test
    @DisplayName("playerActionService.processPlayerAction coordinates and executes player action successfully")
    void playerActionService_ProcessPlayerAction_CoordinatesAndSucceeds() {
        String roomId = createTestRoom("Host", "Guest");
        lifecycleService.createGameFromRoom(roomId);
        Game game = lifecycleService.getGame(roomId);
        Player actor = game.getCurrentPlayer();

        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);
        assertThatCode(() -> playerActionService.processPlayerAction(roomId, request, actor.getName()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nested same-room coordination succeeds reentrantly")
    void sameRoomNestedCoordination_SucceedsReentrantly() {
        String roomId = "room-nested";

        roomCoordinator.coordinate(roomId, () -> {
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            roomCoordinator.coordinate(roomId, () -> {
                assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
                roomCoordinator.assertHeldByCurrentThread(roomId);
                return "inner";
            });
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            return "outer";
        });

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
    }

    @Test
    @DisplayName("nested cross-room coordination is rejected with IllegalStateException")
    void crossRoomNestedCoordination_IsRejected() {
        String roomA = "room-A";
        String roomB = "room-B";

        roomCoordinator.coordinate(roomA, () -> {
            assertThatThrownBy(() -> roomCoordinator.coordinate(roomB, () -> "forbidden"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("while already coordinating room");
            return null;
        });
    }

    private String createTestRoom(String hostName, String guestName) {
        String roomId = UUID.randomUUID().toString();
        CreateRoomRequest request = new CreateRoomRequest("Enforcement Room " + roomId, hostName, 6, 10, 20, 1000, null);
        roomService.createRoom(roomId, request);
        Room room = roomService.getRoom(roomId);
        room.setPlayerConnected(hostName, true);
        if (guestName != null) {
            roomService.joinRoom(roomId, guestName, null);
            room.setPlayerConnected(guestName, true);
        }
        return roomId;
    }
}
