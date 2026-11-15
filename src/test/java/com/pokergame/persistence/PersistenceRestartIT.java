package com.pokergame.persistence;

import com.pokergame.PokerApplication;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.messaging.SessionConnectEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Full-restart persistence integration suite verifying WAL recovery across fresh application
 * contexts, grace-period renewal, overdue cleanup, and rollback state integrity.
 */
@Tag("integration")
@DisplayName("Persistence restart integration tests")
class PersistenceRestartIT {

    @TempDir
    Path walDirectory;

    private ConfigurableApplicationContext startApplication() {
        return startApplication(120_000L, 60_000L);
    }

    private ConfigurableApplicationContext startApplication(long disconnectGracePeriodMs) {
        return startApplication(disconnectGracePeriodMs, 500L);
    }

    private ConfigurableApplicationContext startApplication(long disconnectGracePeriodMs, long gameEndDisplayDelayMs) {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new SpringApplicationBuilder(PokerApplication.class, RestartMutationProbeConfig.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(
                        "--poker.room.disconnect-grace-period-ms=" + disconnectGracePeriodMs,
                        "--poker.lifecycle.game-end-display-delay-ms=" + gameEndDisplayDelayMs,
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + walDirectory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + key,
                        "--poker.rate-limiting.enabled=false");
    }

    private SessionConnectEvent connectEvent(
            ConfigurableApplicationContext context, String playerName, String roomId, String sessionId) {
        String membershipId = withRoomLock(context, roomId,
                () -> context.getBean(RoomService.class).getRoom(roomId).getMembershipId(playerName));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(new PlayerPrincipal(playerName, roomId, membershipId));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(this, message);
    }

    private long countActiveScheduledFutures(ConfigurableApplicationContext context) {
        ThreadPoolTaskScheduler scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
        return scheduler.getScheduledThreadPoolExecutor().getQueue().stream()
                .filter(ScheduledFuture.class::isInstance)
                .map(ScheduledFuture.class::cast)
                .filter(sf -> !sf.isCancelled() && !sf.isDone())
                .count();
    }

    @Test
    @DisplayName("Private lobby metadata and players survive restart from encrypted WAL")
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
            withRoomLock(second, roomId, () -> {
                Room restored = rooms.getRoom(roomId);
                assertThat(restored).isNotNull();
                assertThat(restored.getRoomName()).isEqualTo("Private Vault");
                assertThat(restored.getPlayers()).containsExactlyInAnyOrder("Alice", "Bob");
                assertThat(restored.getMembershipId("Alice")).isEqualTo(hostMembership);
                assertThat(restored.getMembershipId("Bob")).isEqualTo(guestMembership);
                assertThat(restored.isGameStarted()).isFalse();
                assertThat(rooms.getCurrentHost(roomId)).isEqualTo("Alice");
                assertThat(rooms.resolveRoomIdByName("Private Vault")).isEqualTo(roomId);
                return null;
            });
        }
    }

    @Test
    @DisplayName("Active hand restores exact cards, deadlines, and accepts the next legal action after restart")
    void activeFirstHandRestoresExactCardsAndAcceptsTheNextLegalAction() {
        String roomId = UUID.randomUUID().toString();
        List<String> aliceHole;
        List<String> bobHole;
        long revisionBefore;
        Long turnDeadline;
        String nextActor;

        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            GameStateService states = first.getBean(GameStateService.class);
            WebSocketEventListener listener = first.getBean(WebSocketEventListener.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Active Hand Vault", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Active Hand Vault", "Bob", null));
            listener.handleWebSocketConnectListener(connectEvent(first, "Alice", roomId, "first-alice"));
            listener.handleWebSocketConnectListener(connectEvent(first, "Bob", roomId, "first-bob"));
            games.createGameFromRoom(roomId);

            String currentActor = withRoomLock(first, roomId, () -> games.getGame(roomId).getCurrentPlayer().getName());
            first.getBean(PlayerActionService.class).processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CALL, null), currentActor);

            withRoomLock(first, roomId, () -> {
                Game updated = games.getGame(roomId);
                assertThat(updated).isNotNull();
                return null;
            });
            aliceHole = withRoomLock(first, roomId,
                    () -> states.getPrivatePlayerStateSnapshot(games.getGame(roomId), "Alice").holeCards()
                            .stream().map(Object::toString).toList());
            bobHole = withRoomLock(first, roomId,
                    () -> states.getPrivatePlayerStateSnapshot(games.getGame(roomId), "Bob").holeCards()
                            .stream().map(Object::toString).toList());
            revisionBefore = withRoomLock(first, roomId, () -> games.getGame(roomId).getStateRevision());
            turnDeadline = withRoomLock(first, roomId,
                    () -> games.getGame(roomId).getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
            nextActor = withRoomLock(first, roomId, () -> games.getGame(roomId).getCurrentPlayer().getName());
            assertThat(countActiveScheduledFutures(first)).isGreaterThan(0);
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            GameStateService states = second.getBean(GameStateService.class);
            WebSocketEventListener listener = second.getBean(WebSocketEventListener.class);

            listener.handleWebSocketConnectListener(connectEvent(second, "Alice", roomId, "s-alice"));
            listener.handleWebSocketConnectListener(connectEvent(second, "Bob", roomId, "s-bob"));

            long revisionAfterRecovery = withRoomLock(second, roomId, () -> {
                Game restored = games.getGame(roomId);
                assertThat(restored).isNotNull();
                assertThat(restored.getStateRevision()).isGreaterThanOrEqualTo(revisionBefore);
                assertThat(restored.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(turnDeadline);
                PublicGameStateResponse publicAfter = states.getPublicGameStateSnapshot(restored);
                assertThat(publicAfter.stateRevision()).isEqualTo(restored.getStateRevision());
                assertThat(states.getPrivatePlayerStateSnapshot(restored, "Alice").holeCards()
                        .stream().map(Object::toString).toList()).isEqualTo(aliceHole);
                assertThat(states.getPrivatePlayerStateSnapshot(restored, "Bob").holeCards()
                        .stream().map(Object::toString).toList()).isEqualTo(bobHole);
                return restored.getStateRevision();
            });
            assertThat(countActiveScheduledFutures(second)).isGreaterThan(0);

            second.getBean(PlayerActionService.class).processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CHECK, null), nextActor);
            withRoomLock(second, roomId, () -> {
                assertThat(games.getGame(roomId).getStateRevision()).isGreaterThan(revisionAfterRecovery);
                return null;
            });
        }
    }

    @Test
    @DisplayName("Game replacement and eviction cancel runtime timers while keeping the room")
    void replacementAndEvictionCancelGameTimersAndKeepTheRoom() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext context = startApplication()) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            AggregateSnapshotMapper mapper = context.getBean(AggregateSnapshotMapper.class);
            WebSocketEventListener listener = context.getBean(WebSocketEventListener.class);

            long baselineFutures = countActiveScheduledFutures(context);
            rooms.createRoom(roomId, new CreateRoomRequest("Replacement Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Replacement Room", "Bob", null));
            listener.handleWebSocketConnectListener(connectEvent(context, "Alice", roomId, "rep-alice"));
            listener.handleWebSocketConnectListener(connectEvent(context, "Bob", roomId, "rep-bob"));
            games.createGameFromRoom(roomId);
            assertThat(countActiveScheduledFutures(context)).isGreaterThan(baselineFutures);

            Game original = withRoomLock(context, roomId, () -> games.getGame(roomId));
            byte[] image = withRoomLock(context, roomId,
                    () -> mapper.serialize(rooms.getRoom(roomId), rooms.getCurrentHost(roomId), original));
            Game replacement = mapper.deserialize(image).game();
            assertThat(replacement).isNotSameAs(original);

            withRoomLock(context, roomId, () -> {
                games.restoreGame(replacement);
                assertThat(games.getGame(roomId)).isSameAs(replacement);
                assertThat(rooms.getRoom(roomId).isGameStarted()).isTrue();
                return null;
            });
            assertThat(countActiveScheduledFutures(context)).isEqualTo(baselineFutures);

            withRoomLock(context, roomId, () -> {
                games.resumeRollbackTimers(roomId);
                return null;
            });
            assertThat(countActiveScheduledFutures(context)).isGreaterThan(baselineFutures);

            Room roomBeforeEvict = withRoomLock(context, roomId, () -> rooms.getRoom(roomId));
            withRoomLock(context, roomId, () -> {
                games.evictGame(roomId);
                return null;
            });
            assertThat(countActiveScheduledFutures(context)).isEqualTo(baselineFutures);
            withRoomLock(context, roomId, () -> {
                assertThat(games.getGame(roomId)).isNull();
                assertThat(rooms.getRoom(roomId)).isSameAs(roomBeforeEvict);
                return null;
            });
        }
    }

    @Test
    @DisplayName("Recovered game players receive fresh reconnect grace and only missing players are removed")
    void recoveredPlayersGetFreshGraceAndOnlyMissingPlayerIsRemoved() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = startApplication(350)) {
            RoomService rooms = first.getBean(RoomService.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            rooms.createRoom(roomId, new CreateRoomRequest("Grace Game", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Grace Game", "Bob", null));
            games.createGameFromRoom(roomId);
        }

        try (ConfigurableApplicationContext second = startApplication(350)) {
            second.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent(second, "Alice", roomId, "recovered-alice"));

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> withRoomLock(second, roomId, () -> {
                Room restoredRoom = second.getBean(RoomService.class).getRoom(roomId);
                Game restoredGame = second.getBean(GameLifecycleService.class).getGame(roomId);
                assertThat(restoredRoom).isNotNull();
                assertThat(restoredRoom.hasPlayer("Alice")).isTrue();
                assertThat(restoredRoom.hasPlayer("Bob")).isFalse();
                assertThat(restoredGame.isGameOver()).isTrue();
                return null;
            }));
        }
    }

    @Test
    @DisplayName("Recovered lobby players receive fresh reconnect grace and only missing players are removed")
    void recoveredLobbyPlayersGetFreshGraceAndOnlyMissingPlayerIsRemoved() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = startApplication(350)) {
            RoomService rooms = first.getBean(RoomService.class);
            rooms.createRoom(roomId, new CreateRoomRequest("Grace Lobby", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Grace Lobby", "Bob", null));
        }

        try (ConfigurableApplicationContext second = startApplication(350)) {
            second.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent(second, "Alice", roomId, "recovered-lobby-alice"));

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> withRoomLock(second, roomId, () -> {
                Room restored = second.getBean(RoomService.class).getRoom(roomId);
                assertThat(restored).isNotNull();
                assertThat(restored.hasPlayer("Alice")).isTrue();
                assertThat(restored.hasPlayer("Bob")).isFalse();
                return null;
            }));
        }
    }

    @Test
    @DisplayName("Destroyed lobby removes its WAL file and does not resurrect on restart")
    void destroyedLobbyDoesNotResurrectAndItsWalIsRemoved() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            rooms.createRoom(roomId, new CreateRoomRequest("Destroyed Lobby", "Alice", 4, 5, 10, 1000, null));
            assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isTrue();
            rooms.destroyRoom(roomId);
            assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isFalse();
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            withRoomLock(second, roomId, () -> {
                assertThat(second.getBean(RoomService.class).getRoom(roomId)).isNull();
                return null;
            });
            assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isFalse();
        }
    }

    @Test
    @DisplayName("Overdue persisted cleanup executes at startup recovery and removes the room WAL")
    void overduePersistedCleanupExecutesOnceAfterRecovery() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = startApplication(120_000L, 60_000L)) {
            RoomService rooms = first.getBean(RoomService.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            AggregateSnapshotMapper mapper = first.getBean(AggregateSnapshotMapper.class);
            EncryptedWalStore store = first.getBean(EncryptedWalStore.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Overdue Cleanup", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Overdue Cleanup", "Bob", null));
            games.createGameFromRoom(roomId);

            withRoomLock(first, roomId, () -> {
                Game game = games.getGame(roomId);
                game.markGameOver();
                game.scheduleTask(ScheduledGameTask.CLEANUP, System.currentTimeMillis() - 10_000L);
                byte[] image = mapper.serialize(rooms.getRoom(roomId), rooms.getCurrentHost(roomId), game);
                store.checkpoint(roomId, image);
                return null;
            });
            assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isTrue();
        }

        try (ConfigurableApplicationContext second = startApplication(120_000L, 60_000L)) {
            withRoomLock(second, roomId, () -> {
                assertThat(second.getBean(RoomService.class).getRoom(roomId)).isNull();
                assertThat(second.getBean(GameLifecycleService.class).getGame(roomId)).isNull();
                return null;
            });
            assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isFalse();
        }
    }

    @Test
    @DisplayName("Expired lobby is discarded during startup recovery and its WAL is deleted")
    void expiredLobbyIsDiscardedOnRestartAndWalIsDeleted() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            AggregateSnapshotMapper mapper = first.getBean(AggregateSnapshotMapper.class);
            EncryptedWalStore store = first.getBean(EncryptedWalStore.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Expired Lobby", "Alice", 4, 5, 10, 1000, null));
            withRoomLock(first, roomId, () -> {
                Room room = rooms.getRoom(roomId);
                room.setInactivityDeadlineEpochMs(System.currentTimeMillis() - 10_000L);
                byte[] image = mapper.serialize(room, rooms.getCurrentHost(roomId), null);
                store.checkpoint(roomId, image);
                return null;
            });
            assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isTrue();
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            withRoomLock(second, roomId, () -> {
                assertThat(second.getBean(RoomService.class).getRoom(roomId)).isNull();
                return null;
            });
            assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isFalse();
        }
    }

    @Test
    @DisplayName("Inactive active game is abandoned on restart and schedules cleanup")
    void inactiveGameIsAbandonedOnRestartWithCleanupScheduled() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = startApplication(120_000L, 30_000L)) {
            RoomService rooms = first.getBean(RoomService.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            AggregateSnapshotMapper mapper = first.getBean(AggregateSnapshotMapper.class);
            EncryptedWalStore store = first.getBean(EncryptedWalStore.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Inactive Game", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Inactive Game", "Bob", null));
            games.createGameFromRoom(roomId);

            withRoomLock(first, roomId, () -> {
                Room room = rooms.getRoom(roomId);
                room.setInactivityDeadlineEpochMs(System.currentTimeMillis() - 10_000L);
                byte[] image = mapper.serialize(room, rooms.getCurrentHost(roomId), games.getGame(roomId));
                store.checkpoint(roomId, image);
                return null;
            });
        }

        try (ConfigurableApplicationContext second = startApplication(120_000L, 30_000L)) {
            RoomService rooms = second.getBean(RoomService.class);
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            withRoomLock(second, roomId, () -> {
                Room restoredRoom = rooms.getRoom(roomId);
                Game restoredGame = games.getGame(roomId);
                assertThat(restoredRoom).isNotNull();
                assertThat(restoredRoom.isTerminal()).isTrue();
                assertThat(restoredGame).isNotNull();
                assertThat(restoredGame.isGameOver()).isTrue();
                assertThat(restoredGame.isAbandoned()).isTrue();
                assertThat(restoredGame.getGameEndResult()).isNotNull();
                assertThat(restoredGame.getGameEndResult().abandoned()).isTrue();
                assertThat(restoredGame.getGameEndResult().reason()).isEqualTo("INACTIVITY");
                assertThat(restoredGame.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP)).isNotNull();
                return null;
            });
            assertThat(countActiveScheduledFutures(second)).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Longer stored disconnect deadline is preserved over fresh grace period across restart")
    void longerStoredDisconnectDeadlineIsPreservedAcrossRestart() {
        String roomId = UUID.randomUUID().toString();
        long longerDeadline = System.currentTimeMillis() + 300_000L;
        try (ConfigurableApplicationContext first = startApplication(5_000L)) {
            RoomService rooms = first.getBean(RoomService.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            AggregateSnapshotMapper mapper = first.getBean(AggregateSnapshotMapper.class);
            EncryptedWalStore store = first.getBean(EncryptedWalStore.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Long Grace", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Long Grace", "Bob", null));
            games.createGameFromRoom(roomId);

            withRoomLock(first, roomId, () -> {
                Game game = games.getGame(roomId);
                game.findPlayerByName("Alice").setDisconnectDeadlineEpochMs(longerDeadline);
                game.findPlayerByName("Bob").setDisconnectDeadlineEpochMs(null);
                byte[] image = mapper.serialize(rooms.getRoom(roomId), rooms.getCurrentHost(roomId), game);
                store.checkpoint(roomId, image);
                return null;
            });
        }

        try (ConfigurableApplicationContext second = startApplication(5_000L)) {
            RoomService rooms = second.getBean(RoomService.class);
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            withRoomLock(second, roomId, () -> {
                Room restoredRoom = rooms.getRoom(roomId);
                Game restoredGame = games.getGame(roomId);
                Player alice = restoredGame.findPlayerByName("Alice");
                Player bob = restoredGame.findPlayerByName("Bob");
                assertThat(alice.getIsDisconnected()).isTrue();
                assertThat(alice.getDisconnectDeadlineEpochMs()).isEqualTo(longerDeadline);
                assertThat(restoredRoom.getAbsenceDeadline("Alice")).isEqualTo(longerDeadline);
                assertThat(bob.getIsDisconnected()).isTrue();
                assertThat(bob.getDisconnectDeadlineEpochMs()).isLessThan(longerDeadline);
                return null;
            });
        }
    }

    @Test
    @DisplayName("Recovered turn timeout timer is re-armed and executes after restart")
    void rearmedTurnTimeoutExecutesAfterRestart() {
        String roomId = UUID.randomUUID().toString();
        String timedOutActor;
        String survivingPlayer;
        try (ConfigurableApplicationContext first = startApplication(60_000L, 60_000L)) {
            RoomService rooms = first.getBean(RoomService.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            AggregateSnapshotMapper mapper = first.getBean(AggregateSnapshotMapper.class);
            EncryptedWalStore store = first.getBean(EncryptedWalStore.class);
            WebSocketEventListener listener = first.getBean(WebSocketEventListener.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Timer Rearm", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Timer Rearm", "Bob", null));
            listener.handleWebSocketConnectListener(connectEvent(first, "Alice", roomId, "t-alice"));
            listener.handleWebSocketConnectListener(connectEvent(first, "Bob", roomId, "t-bob"));
            games.createGameFromRoom(roomId);

            timedOutActor = withRoomLock(first, roomId, () -> games.getGame(roomId).getCurrentPlayer().getName());
            survivingPlayer = "Alice".equals(timedOutActor) ? "Bob" : "Alice";

            withRoomLock(first, roomId, () -> {
                Game game = games.getGame(roomId);
                game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, System.currentTimeMillis() + 500L);
                byte[] image = mapper.serialize(rooms.getRoom(roomId), rooms.getCurrentHost(roomId), game);
                store.checkpoint(roomId, image);
                return null;
            });
        }

        try (ConfigurableApplicationContext second = startApplication(60_000L, 60_000L)) {
            RoomService rooms = second.getBean(RoomService.class);
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            second.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent(second, survivingPlayer, roomId, "t-surviving"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> withRoomLock(second, roomId, () -> {
                Room restoredRoom = rooms.getRoom(roomId);
                Game restoredGame = games.getGame(roomId);
                assertThat(restoredRoom).isNotNull();
                assertThat(restoredRoom.hasPlayer(timedOutActor)).isFalse();
                assertThat(restoredRoom.hasPlayer(survivingPlayer)).isTrue();
                assertThat(restoredGame.isGameOver()).isTrue();
                return null;
            }));
        }
    }

    @Test
    @DisplayName("Normal game completion followed by cleanup removes room and deletes WAL file")
    void normalGameEndDeletesWalFile() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext context = startApplication(120_000L, 250L)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            WebSocketEventListener listener = context.getBean(WebSocketEventListener.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Normal End", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Normal End", "Bob", null));
            listener.handleWebSocketConnectListener(connectEvent(context, "Alice", roomId, "alice-s"));
            listener.handleWebSocketConnectListener(connectEvent(context, "Bob", roomId, "bob-s"));
            games.createGameFromRoom(roomId);
            assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isTrue();

            games.markPlayerDisconnected(roomId, "Bob", System.currentTimeMillis() + 60_000L);
            games.claimWin(roomId, "Alice");

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                withRoomLock(context, roomId, () -> {
                    assertThat(rooms.getRoom(roomId)).isNull();
                    assertThat(games.getGame(roomId)).isNull();
                    return null;
                });
                assertThat(Files.exists(walDirectory.resolve(roomId + ".wal"))).isFalse();
            });
        }
    }

    @Test
    @DisplayName("Rejected room creation leaves no WAL file on disk")
    void failedRoomCreationDoesNotLeaveWalFile() {
        String existingRoomId = UUID.randomUUID().toString();
        String duplicateRoomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext context = startApplication()) {
            RoomService rooms = context.getBean(RoomService.class);
            rooms.createRoom(existingRoomId, new CreateRoomRequest("Unique Name", "Alice", 4, 5, 10, 1000, null));

            assertThatThrownBy(() -> rooms.createRoom(
                    duplicateRoomId, new CreateRoomRequest("Unique Name", "Bob", 4, 5, 10, 1000, null)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Files.exists(walDirectory.resolve(duplicateRoomId + ".wal"))).isFalse();
        }
    }

    @Test
    @DisplayName("Uncommitted prepare-only WAL files are cleaned up during startup recovery")
    void uncommittedWalFilesAreCleanedUpAtStartupRecovery() {
        String uncommittedRoomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = startApplication()) {
            EncryptedWalStore store = first.getBean(EncryptedWalStore.class);
            withRoomLock(first, uncommittedRoomId, () -> store.prepare(uncommittedRoomId));
            assertThat(Files.exists(walDirectory.resolve(uncommittedRoomId + ".wal"))).isTrue();
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            withRoomLock(second, uncommittedRoomId, () -> {
                assertThat(second.getBean(RoomService.class).getRoom(uncommittedRoomId)).isNull();
                return null;
            });
            assertThat(Files.exists(walDirectory.resolve(uncommittedRoomId + ".wal"))).isFalse();
        }
    }

    @Test
    @DisplayName("Terminal game result survives restart within display window until cleanup deletes WAL")
    void terminalResultAndEmptyRoomSurviveRestartUntilCleanup() {
        String roomId = UUID.randomUUID().toString();
        GameEndResponse originalResult;
        try (ConfigurableApplicationContext first = startApplication(120_000L, 30_000L)) {
            RoomService rooms = first.getBean(RoomService.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            WebSocketEventListener listener = first.getBean(WebSocketEventListener.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Terminal Survives", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Terminal Survives", "Bob", null));
            listener.handleWebSocketConnectListener(connectEvent(first, "Alice", roomId, "a-s"));
            listener.handleWebSocketConnectListener(connectEvent(first, "Bob", roomId, "b-s"));
            games.createGameFromRoom(roomId);

            games.markPlayerDisconnected(roomId, "Bob", System.currentTimeMillis() + 60_000L);
            originalResult = games.claimWin(roomId, "Alice");
            games.removePlayers(roomId, List.of("Alice", "Bob"));

            withRoomLock(first, roomId, () -> {
                Room room = rooms.getRoom(roomId);
                assertThat(room).isNotNull();
                assertThat(room.getPlayers()).isEmpty();
                assertThat(room.isTerminal()).isTrue();
                assertThat(games.getGame(roomId).getGameEndResult()).isNotNull();
                return null;
            });
        }

        try (ConfigurableApplicationContext second = startApplication(120_000L, 30_000L)) {
            RoomService rooms = second.getBean(RoomService.class);
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            withRoomLock(second, roomId, () -> {
                Room restoredRoom = rooms.getRoom(roomId);
                Game restoredGame = games.getGame(roomId);
                assertThat(restoredRoom).isNotNull();
                assertThat(restoredRoom.getPlayers()).isEmpty();
                assertThat(restoredRoom.isTerminal()).isTrue();
                assertThat(restoredGame).isNotNull();
                assertThat(restoredGame.isGameOver()).isTrue();
                assertThat(restoredGame.getGameEndResult()).isEqualTo(originalResult);
                return null;
            });
        }
    }

    @Test
    @DisplayName("Midway room mutation failures (unchecked and checked) roll back RAM to snapshot and allow next mutation")
    void midwayMutationFailureInRoomServiceRollsBackToSnapshotAndSubsequentMutationSucceeds() {
        String roomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            RestartMutationProbe probe = first.getBean(RestartMutationProbe.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Midway Rollback", "Alice", 4, 5, 10, 1000, null));

            assertThatThrownBy(() -> probe.mutateRoomAndThrow(roomId, "DirtyUnchecked"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> probe.mutateRoomAndThrowChecked(roomId, "DirtyChecked"))
                    .isInstanceOf(IOException.class);

            withRoomLock(first, roomId, () -> {
                assertThat(rooms.getRoom(roomId).getPlayers()).containsExactly("Alice");
                return null;
            });

            rooms.joinRoom(roomId, new JoinRoomRequest("Midway Rollback", "Bob", null));
            withRoomLock(first, roomId, () -> {
                assertThat(rooms.getRoom(roomId).getPlayers()).containsExactlyInAnyOrder("Alice", "Bob");
                return null;
            });
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            withRoomLock(second, roomId, () -> {
                assertThat(second.getBean(RoomService.class).getRoom(roomId).getPlayers())
                        .containsExactlyInAnyOrder("Alice", "Bob");
                return null;
            });
        }
    }

    @Test
    @DisplayName("Repeated game mutation rollbacks preserve chip totals and recover latest accepted action across restart")
    void repeatedGameRollbacksPreserveChipsAndRecoverTheLatestAcceptedAction() {
        String roomId = UUID.randomUUID().toString();
        long expectedRevision;
        int expectedTotalChips;

        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            RestartMutationProbe probe = first.getBean(RestartMutationProbe.class);
            WebSocketEventListener listener = first.getBean(WebSocketEventListener.class);

            rooms.createRoom(roomId, new CreateRoomRequest("Chip Rollback", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(roomId, new JoinRoomRequest("Chip Rollback", "Bob", null));
            listener.handleWebSocketConnectListener(connectEvent(first, "Alice", roomId, "rb-alice"));
            listener.handleWebSocketConnectListener(connectEvent(first, "Bob", roomId, "rb-bob"));
            games.createGameFromRoom(roomId);

            String actor = withRoomLock(first, roomId, () -> games.getGame(roomId).getCurrentPlayer().getName());
            first.getBean(PlayerActionService.class).processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CALL, null), actor);

            expectedRevision = withRoomLock(first, roomId, () -> games.getGame(roomId).getStateRevision());
            expectedTotalChips = withRoomLock(first, roomId, () -> totalChipsInGame(games.getGame(roomId)));

            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> probe.mutateGameAndThrow(roomId))
                        .isInstanceOf(IllegalStateException.class);
            }

            withRoomLock(first, roomId, () -> {
                Game game = games.getGame(roomId);
                assertThat(game.getStateRevision()).isEqualTo(expectedRevision);
                assertThat(totalChipsInGame(game)).isEqualTo(expectedTotalChips);
                return null;
            });
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            withRoomLock(second, roomId, () -> {
                Game restored = games.getGame(roomId);
                assertThat(restored).isNotNull();
                assertThat(restored.getStateRevision()).isEqualTo(expectedRevision);
                assertThat(totalChipsInGame(restored)).isEqualTo(expectedTotalChips);
                return null;
            });
        }
    }

    @Test
    @DisplayName("Failed room creation cleans up room registry, active games, timers, and WAL")
    void failedRoomCreationCleansUpRoomsActiveGamesAndTimers() {
        String failedRoomId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext context = startApplication()) {
            RestartMutationProbe probe = context.getBean(RestartMutationProbe.class);
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            long baselineFutures = countActiveScheduledFutures(context);

            assertThatThrownBy(() -> probe.createRoomAndThrow(
                    failedRoomId, new CreateRoomRequest("Aborted Room", "Alice", 4, 5, 10, 1000, null)))
                    .isInstanceOf(IllegalStateException.class);

            withRoomLock(context, failedRoomId, () -> {
                assertThat(rooms.getRoom(failedRoomId)).isNull();
                assertThat(games.getGame(failedRoomId)).isNull();
                return null;
            });
            assertThat(countActiveScheduledFutures(context)).isEqualTo(baselineFutures);
            assertThat(Files.exists(walDirectory.resolve(failedRoomId + ".wal"))).isFalse();
        }
    }

    private static int totalChipsInGame(Game game) {
        return game.getPlayers().stream().mapToInt(Player::getChips).sum() + game.getPot();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestartMutationProbeConfig {
        @Bean
        RestartMutationProbe restartMutationProbe(RoomService roomService, GameLifecycleService gameLifecycleService) {
            return new RestartMutationProbe(roomService, gameLifecycleService);
        }
    }

    static class RestartMutationProbe {
        private final RoomService roomService;
        private final GameLifecycleService gameLifecycleService;

        RestartMutationProbe(RoomService roomService, GameLifecycleService gameLifecycleService) {
            this.roomService = roomService;
            this.gameLifecycleService = gameLifecycleService;
        }

        @DurableMutation(roomId = "#roomId")
        public void mutateRoomAndThrow(String roomId, String dirtyPlayer) {
            roomService.getRoom(roomId).addPlayer(dirtyPlayer);
            throw new IllegalStateException("Simulated unchecked room failure");
        }

        @DurableMutation(roomId = "#roomId")
        public void mutateRoomAndThrowChecked(String roomId, String dirtyPlayer) throws IOException {
            roomService.getRoom(roomId).addPlayer(dirtyPlayer);
            throw new IOException("Simulated checked room failure");
        }

        @DurableMutation(roomId = "#roomId")
        public void mutateGameAndThrow(String roomId) {
            Game game = gameLifecycleService.getGame(roomId);
            game.getPlayers().getFirst().addChips(888_888);
            throw new IllegalStateException("Simulated midway game failure");
        }

        @DurableMutation(roomId = "#roomId")
        public void createRoomAndThrow(String roomId, CreateRoomRequest request) {
            roomService.createRoom(roomId, request);
            throw new IllegalStateException("Simulated post-create failure");
        }
    }
}
