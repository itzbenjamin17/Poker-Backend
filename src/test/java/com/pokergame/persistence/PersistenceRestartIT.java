package com.pokergame.persistence;

import static com.pokergame.testutil.TestRoomLocks.runWithRoomLock;
import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokergame.PokerApplication;
import com.pokergame.config.GameTimingProperties;
import com.pokergame.config.RoomTimingProperties;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.RoomId;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.ControllableTaskScheduler;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestRoomFixtures;
import com.pokergame.util.RoomCoordinator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.messaging.SessionConnectEvent;

/**
 * Verifies that rooms and games saved to the encrypted WAL come back after a server restart.
 * <p>
 * Two tests boot a second real application over the same WAL directory to prove startup recovery
 * end to end. Every other scenario runs in one cached Spring context with a {@link MutableClock}
 * and a scheduler that fires timers only on demand, so grace periods, turn timeouts, and
 * inactivity deadlines are exact rather than raced against real time. In that context a restart is
 * simulated by {@link #simulateRestart(String)}, which discards the room's live objects, timers,
 * and in-memory WAL tracking, and then {@link #recoverFromWal()} runs the startup recovery again,
 * so the only source for the room's state is the WAL on disk.
 * </p>
 */
@Tag("integration")
@DisplayName("Persistence restart integration tests")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Shorter than the 60s turn allowance, so a recovered grace expiry is always the first due timer.
        properties = "poker.room.disconnect-grace-period-ms=30000")
@ActiveProfiles("test")
@Import(PersistenceRestartIT.RestartTestConfiguration.class)
class PersistenceRestartIT {

    private static final long T0 = 1_900_000_000_000L;

    @TempDir
    static Path sharedWalDirectory;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("poker.persistence.enabled", () -> "true");
        registry.add("poker.persistence.directory", () -> sharedWalDirectory.toString().replace('\\', '/'));
    }

    @TempDir
    Path bootWalDirectory;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private PlayerActionService playerActionService;

    @Autowired
    private RoomAggregates roomAggregates;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private EncryptedWalStore walStore;

    @Autowired
    private PersistenceRecovery persistenceRecovery;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    @Autowired
    private GameTimingProperties gameTimings;

    @Autowired
    private RoomTimingProperties roomTimings;

    @Autowired
    private RestartMutationProbe probe;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ControllableTaskScheduler taskScheduler;

    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private TestRoomFixtures roomFixtures;

    @BeforeEach
    void setUp() {
        clock.setMillis(T0);
        taskScheduler.clearTrackedTasks();
        roomFixtures = new TestRoomFixtures(roomService, roomCoordinator, webSocketEventListener, trackedRoomIds);
    }

    @AfterEach
    void tearDown() {
        for (String roomId : List.copyOf(trackedRoomIds)) {
            try {
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    roomService.destroyRoom(roomId);
                    roomAggregates.remove(roomId);
                    walStore.delete(roomId);
                });
            } catch (RuntimeException ignored) {
                // Best-effort per-room cleanup so one failure does not leak later rooms.
            }
        }
        trackedRoomIds.clear();
        // Clear after the rooms are gone: destroying a room cancels its timers and queues cleanup work.
        taskScheduler.clearTrackedTasks();
    }

    @Test
    @DisplayName("Startup recovery of a second application restores a private lobby and its memberships")
    void privateLobbySurvivesApplicationRestartFromEncryptedWal() {
        String roomId = UUID.randomUUID().toString();
        String hostMembership;
        String guestMembership;
        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            rooms.createRoom(roomId, new CreateRoomRequest("Private Vault", "Alice", 4, 10, 20, 1000, "secret12"));
            rooms.joinRoom(roomId, new JoinRoomRequest("Private Vault", "Bob", "secret12"));
            hostMembership = withRoomLock(first, roomId, () -> rooms.getRoom(roomId).getMembershipId("Alice"));
            guestMembership = withRoomLock(first, roomId, () -> rooms.getRoom(roomId).getMembershipId("Bob"));
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            RoomService rooms = second.getBean(RoomService.class);
            runWithRoomLock(second, roomId, () -> {
                Room restored = rooms.getRoom(roomId);
                assertThat(restored).isNotNull();
                assertThat(restored.getRoomName()).isEqualTo("Private Vault");
                assertThat(restored.getPlayers()).containsExactlyInAnyOrder("Alice", "Bob");
                assertThat(restored.getMembershipId("Alice")).isEqualTo(hostMembership);
                assertThat(restored.getMembershipId("Bob")).isEqualTo(guestMembership);
                assertThat(restored.isGameStarted()).isFalse();
                assertThat(restored.getHostName()).isEqualTo("Alice");
                assertThat(rooms.resolveRoomIdByName("Private Vault")).isEqualTo(roomId);
            });
        }
    }

    @Test
    @DisplayName("Startup recovery of a second application restores exact cards and deadlines and accepts the next legal action")
    void activeFirstHandRestoresExactCardsAndAcceptsTheNextLegalAction() {
        String roomId;
        List<String> aliceHole;
        List<String> bobHole;
        long revisionBefore;
        Long turnDeadline;
        String nextActor;

        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            RoomCoordinator coordinator = first.getBean(RoomCoordinator.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            GameStateService states = first.getBean(GameStateService.class);
            TestRoomFixtures fixtures = new TestRoomFixtures(
                    rooms, coordinator, first.getBean(WebSocketEventListener.class), ConcurrentHashMap.newKeySet());

            roomId = fixtures.createAndConnectRoom("Active Hand Vault", "Alice", "Bob");
            games.createGameFromRoom(roomId);

            String currentActor = withRoomLock(coordinator, roomId, () -> games.getGame(roomId).getCurrentPlayer().getName());
            first.getBean(PlayerActionService.class).processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CALL, 0), currentActor);

            aliceHole = withRoomLock(coordinator, roomId,
                    () -> holeCards(states, games.getGame(roomId), "Alice"));
            bobHole = withRoomLock(coordinator, roomId,
                    () -> holeCards(states, games.getGame(roomId), "Bob"));
            revisionBefore = withRoomLock(coordinator, roomId, () -> games.getGame(roomId).getStateRevision());
            turnDeadline = withRoomLock(coordinator, roomId,
                    () -> games.getGame(roomId).getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT));
            nextActor = withRoomLock(coordinator, roomId, () -> games.getGame(roomId).getCurrentPlayer().getName());
            assertThat(countActiveScheduledFutures(first)).isGreaterThan(0);
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            RoomService rooms = second.getBean(RoomService.class);
            RoomCoordinator coordinator = second.getBean(RoomCoordinator.class);
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            GameStateService states = second.getBean(GameStateService.class);

            runWithRoomLock(coordinator, roomId, () -> {
                Game restored = games.getGame(roomId);
                assertThat(restored).isNotNull();
                assertThat(restored.getStateRevision()).isEqualTo(revisionBefore);
                assertThat(restored.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(turnDeadline);
                assertThat(states.getPublicGameStateSnapshot(restored).stateRevision()).isEqualTo(revisionBefore);
                assertThat(holeCards(states, restored, "Alice")).isEqualTo(aliceHole);
                assertThat(holeCards(states, restored, "Bob")).isEqualTo(bobHole);
            });
            assertThat(countActiveScheduledFutures(second)).isGreaterThan(0);

            WebSocketEventListener listener = second.getBean(WebSocketEventListener.class);
            listener.handleWebSocketConnectListener(connectEvent(rooms, coordinator, "Alice", roomId, "second-alice"));
            listener.handleWebSocketConnectListener(connectEvent(rooms, coordinator, "Bob", roomId, "second-bob"));
            long revisionAfterReconnect = withRoomLock(coordinator, roomId, () -> games.getGame(roomId).getStateRevision());

            second.getBean(PlayerActionService.class).processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CHECK, 0), nextActor);
            runWithRoomLock(coordinator, roomId, () ->
                    assertThat(games.getGame(roomId).getStateRevision()).isGreaterThan(revisionAfterReconnect));
        }
    }

    @Test
    @DisplayName("Recovered game players get a fresh reconnect grace and only the player who stays away is removed")
    void recoveredGamePlayersGetFreshGraceAndOnlyTheMissingPlayerIsRemoved() {
        String roomId = roomFixtures.createAndConnectRoom("Grace Game", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);

        simulateRestart(roomId);
        recoverFromWal();
        long graceDeadline = T0 + roomTimings.disconnectGracePeriodMs();

        runWithRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(game.findPlayerByName("Alice").getDisconnectDeadlineEpochMs()).isEqualTo(graceDeadline);
            assertThat(game.findPlayerByName("Bob").getDisconnectDeadlineEpochMs()).isEqualTo(graceDeadline);
        });
        reconnect("Alice", roomId, "recovered-alice");

        clock.setMillis(graceDeadline - 1);
        runDueTasks();
        runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId).getPlayers()).containsExactlyInAnyOrder("Alice", "Bob");
            assertThat(gameLifecycleService.getGame(roomId).isGameOver()).isFalse();
        });

        clock.setMillis(graceDeadline);
        runDueTasks();
        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room restoredRoom = roomService.getRoom(roomId);
            assertThat(restoredRoom).isNotNull();
            assertThat(restoredRoom.hasPlayer("Alice")).isTrue();
            assertThat(restoredRoom.hasPlayer("Bob")).isFalse();
            assertThat(gameLifecycleService.getGame(roomId).isGameOver()).isTrue();
        });
    }

    @Test
    @DisplayName("Recovered lobby players get a fresh reconnect grace and only the player who stays away is removed")
    void recoveredLobbyPlayersGetFreshGraceAndOnlyTheMissingPlayerIsRemoved() {
        String roomId = roomFixtures.createAndConnectRoom("Grace Lobby", "Alice", "Bob");

        simulateRestart(roomId);
        recoverFromWal();
        long graceDeadline = T0 + roomTimings.disconnectGracePeriodMs();

        reconnect("Alice", roomId, "recovered-lobby-alice");
        clock.setMillis(graceDeadline - 1);
        runDueTasks();
        runWithRoomLock(roomCoordinator, roomId, () ->
                assertThat(roomService.getRoom(roomId).getPlayers()).containsExactlyInAnyOrder("Alice", "Bob"));

        clock.setMillis(graceDeadline);
        runDueTasks();
        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room restored = roomService.getRoom(roomId);
            assertThat(restored).isNotNull();
            assertThat(restored.hasPlayer("Alice")).isTrue();
            assertThat(restored.hasPlayer("Bob")).isFalse();
        });
    }

    @ParameterizedTest(name = "stored deadline {0} ms after restart time: kept as {1} ms after restart time")
    @CsvSource({
            "300000, 300000",
            "10000, 30000"
    })
    @DisplayName("Recovery keeps the later of the stored disconnect deadline and a fresh grace period")
    void recoveryKeepsTheLaterOfStoredDisconnectDeadlineAndFreshGrace(long storedOffsetMs, long expectedOffsetMs) {
        String roomId = roomFixtures.createAndConnectRoom("Stored Grace", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        gameLifecycleService.markPlayerDisconnected(roomId, "Alice", T0 + storedOffsetMs);

        simulateRestart(roomId);
        recoverFromWal();

        long freshDeadline = T0 + roomTimings.disconnectGracePeriodMs();
        long expectedAliceDeadline = T0 + expectedOffsetMs;
        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room restoredRoom = roomService.getRoom(roomId);
            Game restoredGame = gameLifecycleService.getGame(roomId);
            Player alice = restoredGame.findPlayerByName("Alice");
            Player bob = restoredGame.findPlayerByName("Bob");
            assertThat(alice.isDisconnected()).isTrue();
            assertThat(alice.getDisconnectDeadlineEpochMs()).isEqualTo(expectedAliceDeadline);
            assertThat(restoredRoom.getAbsenceDeadline("Alice")).isEqualTo(expectedAliceDeadline);
            assertThat(bob.isDisconnected()).isTrue();
            assertThat(bob.getDisconnectDeadlineEpochMs()).isEqualTo(freshDeadline);
        });
        assertThat(taskScheduler.pendingStartTimes())
                .contains(Instant.ofEpochMilli(expectedAliceDeadline), Instant.ofEpochMilli(freshDeadline));
    }

    @Test
    @DisplayName("Destroyed lobby removes its WAL file and does not come back after restart")
    void destroyedLobbyDoesNotComeBackAndItsWalIsRemoved() {
        String roomId = roomFixtures.createAndConnectRoom("Destroyed Lobby", "Alice");
        assertThat(walFile(roomId)).exists();
        roomService.destroyRoom(roomId);
        assertThat(walFile(roomId)).doesNotExist();

        simulateRestart(roomId);
        recoverFromWal();

        runWithRoomLock(roomCoordinator, roomId, () -> assertThat(roomService.getRoom(roomId)).isNull());
        assertThat(walFile(roomId)).doesNotExist();
    }

    @ParameterizedTest(name = "restart {0} ms before the lobby deadline: lobby kept = {1}")
    @CsvSource({
            "1, true",
            "0, false"
    })
    @DisplayName("Lobby is discarded at recovery once its inactivity deadline has been reached")
    void lobbyIsDiscardedAtRecoveryOnceItsInactivityDeadlineIsReached(long msBeforeDeadline, boolean kept) {
        String roomId = roomFixtures.createAndConnectRoom("Expiring Lobby", "Alice");
        long lobbyDeadline = T0 + roomTimings.lobbyInactivityMs();

        simulateRestart(roomId);
        assertThat(walFile(roomId)).exists();
        clock.setMillis(lobbyDeadline - msBeforeDeadline);
        recoverFromWal();

        runWithRoomLock(roomCoordinator, roomId, () -> {
            if (kept) {
                assertThat(roomService.getRoom(roomId)).isNotNull();
            } else {
                assertThat(roomService.getRoom(roomId)).isNull();
            }
        });
        assertThat(Files.exists(walFile(roomId))).isEqualTo(kept);
    }

    @Test
    @DisplayName("Finished game whose cleanup deadline passed during downtime is deleted with its WAL at recovery")
    void finishedGameWhoseCleanupDeadlinePassedDuringDowntimeIsDeletedAtRecovery() {
        String roomId = roomFixtures.createAndConnectRoom("Overdue Cleanup", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        finishGameByClaimedWin(roomId);
        long cleanupDeadline = T0 + gameTimings.gameEndDisplayDelayMs();
        assertThat(cleanupDeadlineOf(roomId)).isEqualTo(cleanupDeadline);
        assertThat(walFile(roomId)).exists();

        simulateRestart(roomId);
        assertThat(walFile(roomId)).exists();
        clock.setMillis(cleanupDeadline);
        recoverFromWal();

        runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId)).isNull();
            assertThat(gameLifecycleService.getGame(roomId)).isNull();
        });
        assertThat(walFile(roomId)).doesNotExist();
    }

    @Test
    @DisplayName("Finished game keeps its result through a restart within the display window until the recovered cleanup timer fires")
    void finishedGameKeepsItsResultThroughRestartUntilTheRecoveredCleanupTimerFires() {
        String roomId = roomFixtures.createAndConnectRoom("Finished Survives", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        PublicGameStateResponse originalResult = finishGameByClaimedWin(roomId);
        gameLifecycleService.removePlayers(roomId, List.of("Alice", "Bob"));
        long cleanupDeadline = T0 + gameTimings.gameEndDisplayDelayMs();
        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room room = roomService.getRoom(roomId);
            assertThat(room.getPlayers()).isEmpty();
            assertThat(room.isFinished()).isTrue();
        });

        simulateRestart(roomId);
        clock.setMillis(cleanupDeadline - 1);
        recoverFromWal();

        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room restoredRoom = roomService.getRoom(roomId);
            Game restoredGame = gameLifecycleService.getGame(roomId);
            assertThat(restoredRoom).isNotNull();
            assertThat(restoredRoom.getPlayers()).isEmpty();
            assertThat(restoredRoom.isFinished()).isTrue();
            assertThat(restoredGame).isNotNull();
            assertThat(restoredGame.isGameOver()).isTrue();
            assertThat(restoredGame.getGameEndResult()).isEqualTo(originalResult);
        });
        assertThat(walFile(roomId)).exists();

        clock.setMillis(cleanupDeadline);
        runDueTasks();

        runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId)).isNull();
            assertThat(gameLifecycleService.getGame(roomId)).isNull();
        });
        assertThat(walFile(roomId)).doesNotExist();
    }

    @ParameterizedTest(name = "restart {0} ms before the inactivity deadline: game abandoned = {1}")
    @CsvSource({
            "1, false",
            "0, true"
    })
    @DisplayName("Active game is abandoned at recovery once its inactivity deadline has been reached")
    void activeGameIsAbandonedAtRecoveryOnceItsInactivityDeadlineIsReached(long msBeforeDeadline, boolean abandoned) {
        String roomId = roomFixtures.createAndConnectRoom("Inactive Game", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        long inactivityDeadline = T0 + gameTimings.gameInactivityAllowanceMs();

        simulateRestart(roomId);
        clock.setMillis(inactivityDeadline - msBeforeDeadline);
        long recoveredAt = clock.millis();
        recoverFromWal();

        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room restoredRoom = roomService.getRoom(roomId);
            Game restoredGame = gameLifecycleService.getGame(roomId);
            assertThat(restoredRoom).isNotNull();
            assertThat(restoredGame).isNotNull();
            assertThat(restoredGame.isGameOver()).isEqualTo(abandoned);
            assertThat(restoredGame.isAbandoned()).isEqualTo(abandoned);
            assertThat(restoredRoom.isFinished()).isEqualTo(abandoned);
            if (abandoned) {
                assertThat(restoredGame.getGameEndResult().gameEnd().abandoned()).isTrue();
                assertThat(restoredGame.getGameEndResult().gameEnd().reason()).isEqualTo("INACTIVITY");
                assertThat(restoredGame.getScheduledTaskTime(ScheduledGameTask.CLEANUP))
                        .isEqualTo(recoveredAt + gameTimings.gameEndDisplayDelayMs());
            } else {
                assertThat(restoredGame.getScheduledTaskTime(ScheduledGameTask.CLEANUP)).isNull();
            }
        });
        assertThat(taskScheduler.pendingStartTimes()).contains(Instant.ofEpochMilli(
                abandoned ? recoveredAt + gameTimings.gameEndDisplayDelayMs() : inactivityDeadline));
    }

    @Test
    @DisplayName("Recovered turn timeout is re-armed and fires exactly at its saved deadline")
    void recoveredTurnTimeoutIsRearmedAndFiresAtItsSavedDeadline() {
        String roomId = roomFixtures.createAndConnectRoom("Future Turn", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        String timedOutActor = currentActor(roomId);
        String survivor = "Alice".equals(timedOutActor) ? "Bob" : "Alice";
        long turnDeadline = T0 + gameTimings.turnAllowanceMs();
        runWithRoomLock(roomCoordinator, roomId, () -> assertThat(
                gameLifecycleService.getGame(roomId).getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT))
                .isEqualTo(turnDeadline));

        simulateRestart(roomId);
        recoverFromWal();
        // Both players return, which cancels their grace timers and leaves only the turn timeout due.
        reconnect(timedOutActor, roomId, "future-actor");
        reconnect(survivor, roomId, "future-survivor");

        clock.setMillis(turnDeadline - 1);
        runDueTasks();
        runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId).hasPlayer(timedOutActor)).isTrue();
            assertThat(gameLifecycleService.getGame(roomId).isGameOver()).isFalse();
        });

        clock.setMillis(turnDeadline);
        runDueTasks();
        assertTimedOutActorRemoved(roomId, timedOutActor, survivor);
    }

    @Test
    @DisplayName("Recovered turn timeout that is already overdue fires on the first timer run after recovery")
    void overdueTurnTimeoutFiresOnTheFirstTimerRunAfterRecovery() {
        String roomId = roomFixtures.createAndConnectRoom("Overdue Turn", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        String timedOutActor = currentActor(roomId);
        String survivor = "Alice".equals(timedOutActor) ? "Bob" : "Alice";
        long turnDeadline = T0 + gameTimings.turnAllowanceMs();

        simulateRestart(roomId);
        clock.setMillis(turnDeadline + 5_000L);
        recoverFromWal();
        // Only the survivor returns; the actor stays away. Reconnecting must not itself time the actor out.
        reconnect(survivor, roomId, "overdue-survivor");
        runWithRoomLock(roomCoordinator, roomId, () ->
                assertThat(roomService.getRoom(roomId).hasPlayer(timedOutActor)).isTrue());

        runDueTasks();

        assertTimedOutActorRemoved(roomId, timedOutActor, survivor);
    }

    @Test
    @DisplayName("Prepare-only WAL file left by a crash is deleted at recovery and restores no room")
    void prepareOnlyWalFileIsDeletedAtRecoveryAndRestoresNoRoom() {
        String roomId = UUID.randomUUID().toString();
        trackedRoomIds.add(roomId);
        withRoomLock(roomCoordinator, roomId, () -> walStore.prepare(roomId));
        assertThat(walFile(roomId)).exists();

        simulateRestart(roomId);
        assertThat(walFile(roomId)).exists();
        recoverFromWal();

        runWithRoomLock(roomCoordinator, roomId, () -> assertThat(roomService.getRoom(roomId)).isNull());
        assertThat(walFile(roomId)).doesNotExist();
    }

    @Test
    @DisplayName("Failed room mutations roll back memory and the WAL keeps only accepted changes across a restart")
    void failedRoomMutationsRollBackAndOnlyAcceptedChangesSurviveRestart() {
        String roomId = roomFixtures.createAndConnectRoom("Midway Rollback", "Alice");

        assertThatThrownBy(() -> probe.mutateRoomAndThrow(roomId, "DirtyUnchecked"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> probe.mutateRoomAndThrowChecked(roomId, "DirtyChecked"))
                .isInstanceOf(IOException.class);
        runWithRoomLock(roomCoordinator, roomId, () ->
                assertThat(roomService.getRoom(roomId).getPlayers()).containsExactly("Alice"));

        roomService.joinRoom(roomId, new JoinRoomRequest("Midway Rollback", "Bob", null));
        runWithRoomLock(roomCoordinator, roomId, () ->
                assertThat(roomService.getRoom(roomId).getPlayers()).containsExactlyInAnyOrder("Alice", "Bob"));

        simulateRestart(roomId);
        recoverFromWal();

        runWithRoomLock(roomCoordinator, roomId, () ->
                assertThat(roomService.getRoom(roomId).getPlayers()).containsExactlyInAnyOrder("Alice", "Bob"));
    }

    @Test
    @DisplayName("Repeated failed game mutations keep chips and revision, and the latest accepted action survives a restart")
    void repeatedFailedGameMutationsKeepChipsAndTheLatestAcceptedActionSurvivesRestart() {
        String roomId = roomFixtures.createAndConnectRoom("Chip Rollback", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        playerActionService.processPlayerAction(
                roomId, new PlayerActionRequest(PlayerAction.CALL, 0), currentActor(roomId));
        long expectedRevision = withRoomLock(roomCoordinator, roomId,
                () -> gameLifecycleService.getGame(roomId).getStateRevision());
        int expectedTotalChips = withRoomLock(roomCoordinator, roomId,
                () -> totalChipsInGame(gameLifecycleService.getGame(roomId)));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> probe.mutateGameAndThrow(roomId)).isInstanceOf(IllegalStateException.class);
        }

        runWithRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(game.getStateRevision()).isEqualTo(expectedRevision);
            assertThat(totalChipsInGame(game)).isEqualTo(expectedTotalChips);
        });

        simulateRestart(roomId);
        recoverFromWal();

        runWithRoomLock(roomCoordinator, roomId, () -> {
            Game restored = gameLifecycleService.getGame(roomId);
            assertThat(restored).isNotNull();
            assertThat(restored.getStateRevision()).isEqualTo(expectedRevision);
            assertThat(totalChipsInGame(restored)).isEqualTo(expectedTotalChips);
        });
    }

    /**
     * Simulates a process restart for {@code roomId}. The room's live objects, runtime timers,
     * pending disconnect timers, and the store's in-memory sequence tracking are all discarded, and
     * the test asserts that nothing is left in memory, so only the WAL file on disk can bring the
     * room back. No players are connected at this point, so no session state needs clearing.
     */
    private void simulateRestart(String roomId) {
        runWithRoomLock(roomCoordinator, roomId, () -> {
            roomAggregates.remove(roomId);
            webSocketEventListener.cancelRoomPendingDisconnects(roomId);
            walStore.clearRoomTracking(roomId);
        });
        // Timers queued by other work, such as membership cleanup, would not survive a real restart either.
        taskScheduler.clearTrackedTasks();
        runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId)).as("room is gone from memory").isNull();
            assertThat(gameLifecycleService.getGame(roomId)).as("game is gone from memory").isNull();
        });
    }

    /** Runs the startup recovery again over the WAL directory, as the application does on boot. */
    private void recoverFromWal() {
        persistenceRecovery.run(null);
        assertThat(persistenceRecovery.isComplete()).isTrue();
    }

    private void reconnect(String playerName, String roomId, String sessionId) {
        webSocketEventListener.handleWebSocketConnectListener(
                connectEvent(roomService, roomCoordinator, playerName, roomId, sessionId));
    }

    private void runDueTasks() {
        // Tasks can queue follow-up work that is due immediately, such as membership cleanup.
        for (int pass = 0; pass < 5 && taskScheduler.triggerDueTasks(clock.instant()) > 0; pass++) {
            // Keep draining until a pass runs nothing.
        }
    }

    private String currentActor(String roomId) {
        return withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
    }

    private Long cleanupDeadlineOf(String roomId) {
        return withRoomLock(roomCoordinator, roomId,
                () -> gameLifecycleService.getGame(roomId).getScheduledTaskTime(ScheduledGameTask.CLEANUP));
    }

    /** Ends the heads-up game by Bob's absence so Alice's claimed win leaves a saved result and a cleanup deadline. */
    private PublicGameStateResponse finishGameByClaimedWin(String roomId) {
        gameLifecycleService.markPlayerDisconnected(roomId, "Bob", clock.millis() + 60_000L);
        return gameLifecycleService.claimWin(roomId, "Alice");
    }

    private void assertTimedOutActorRemoved(String roomId, String timedOutActor, String survivor) {
        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room restoredRoom = roomService.getRoom(roomId);
            assertThat(restoredRoom).isNotNull();
            assertThat(restoredRoom.hasPlayer(timedOutActor)).isFalse();
            assertThat(restoredRoom.hasPlayer(survivor)).isTrue();
            assertThat(gameLifecycleService.getGame(roomId).isGameOver()).isTrue();
        });
    }

    private Path walFile(String roomId) {
        return sharedWalDirectory.resolve(roomId + ".wal");
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(PokerApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + bootWalDirectory.toString().replace('\\', '/'));
    }

    private static List<String> holeCards(GameStateService states, Game game, String playerName) {
        return states.getPrivatePlayerStateSnapshot(game, playerName).holeCards()
                .stream().map(Object::toString).toList();
    }

    private static SessionConnectEvent connectEvent(
            RoomService rooms, RoomCoordinator coordinator, String playerName, String roomId, String sessionId) {
        String membershipId = withRoomLock(coordinator, roomId, () -> rooms.getRoom(roomId).getMembershipId(playerName));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(new PlayerPrincipal(playerName, roomId, membershipId));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(PersistenceRestartIT.class, message);
    }

    private static long countActiveScheduledFutures(ConfigurableApplicationContext context) {
        ThreadPoolTaskScheduler scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
        return scheduler.getScheduledThreadPoolExecutor().getQueue().stream()
                .filter(ScheduledFuture.class::isInstance)
                .map(ScheduledFuture.class::cast)
                .filter(future -> !future.isCancelled() && !future.isDone())
                .count();
    }

    private static int totalChipsInGame(Game game) {
        return game.getPlayers().stream().mapToInt(Player::getChips).sum() + game.getPot();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestartTestConfiguration {
        @Bean
        @Primary
        MutableClock restartTestClock() {
            return new MutableClock(T0);
        }

        @Bean
        RestartMutationProbe restartMutationProbe(RoomService roomService, GameLifecycleService gameLifecycleService) {
            return new RestartMutationProbe(roomService, gameLifecycleService);
        }

        /**
         * Swaps the application's own {@code taskScheduler} for one whose one-shot timers run only when a
         * test asks. Recurring timers keep running on the real pool.
         */
        @Bean
        static BeanPostProcessor controllableSchedulerPostProcessor(ObjectProvider<MutableClock> clock) {
            return ControllableTaskScheduler.replacingTaskScheduler(clock);
        }
    }

    static class RestartMutationProbe {
        private final RoomService roomService;
        private final GameLifecycleService gameLifecycleService;

        RestartMutationProbe(RoomService roomService, GameLifecycleService gameLifecycleService) {
            this.roomService = roomService;
            this.gameLifecycleService = gameLifecycleService;
        }

        @DurableMutation
        public void mutateRoomAndThrow(@RoomId String roomId, String dirtyPlayer) {
            roomService.getRoom(roomId).addPlayer(dirtyPlayer);
            throw new IllegalStateException("Simulated unchecked room failure");
        }

        @DurableMutation
        public void mutateRoomAndThrowChecked(@RoomId String roomId, String dirtyPlayer) throws IOException {
            roomService.getRoom(roomId).addPlayer(dirtyPlayer);
            throw new IOException("Simulated checked room failure");
        }

        @DurableMutation
        public void mutateGameAndThrow(@RoomId String roomId) {
            Game game = gameLifecycleService.getGame(roomId);
            game.getPlayers().getFirst().addChips(888_888);
            throw new IllegalStateException("Simulated midway game failure");
        }
    }
}
