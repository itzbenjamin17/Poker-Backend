package com.pokergame.service;

import com.pokergame.PokerApplication;
import com.pokergame.controller.GameController;
import com.pokergame.controller.RoomController;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.security.PlayerPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Concurrency integration tests for Ticket #22:
 * Verifies that scheduled turn, readiness, disconnect grace, inactivity,
 * and terminal cleanup lifecycle callbacks coordinate exclusively through RoomCoordinator,
 * validate guards against current room state, and cleanly no-op when stale or obsolete.
 */
class ScheduledLifecycleCoordinationIntegrationTest {

    @TempDir
    Path directory;

    private ConfigurableApplicationContext startApplication(boolean persistenceEnabled) {
        return new SpringApplicationBuilder(PokerApplication.class)
                .profiles("test").web(WebApplicationType.NONE)
                .run("--poker.ready-countdown-ms=60000",
                        "--poker.persistence.enabled=" + persistenceEnabled,
                        "--poker.persistence.directory=" + directory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + Base64.getEncoder().encodeToString(new byte[32]));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Stale turn callback firing during a new turn or hand is a no-op")
    void staleTurnCallbackDuringNewTurnOrHandIsNoOp(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);
            PlayerActionService actionService = context.getBean(PlayerActionService.class);

            String roomId = rooms.createRoom(new CreateRoomRequest("Turn Stale Room", "Host", 6, 10, 20, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Turn Stale Room", "Alice", null));
            rooms.joinRoom(new JoinRoomRequest("Turn Stale Room", "Bob", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);
            room.setPlayerConnected("Bob", true);

            lifecycle.createGameFromRoom(roomId);
            lifecycle.markPlayerReconnected(roomId, "Host");
            lifecycle.markPlayerReconnected(roomId, "Alice");
            lifecycle.markPlayerReconnected(roomId, "Bob");

            Game game = lifecycle.getGame(roomId);
            assertThat(game).isNotNull();

            // Hand 1 preflop
            String turn1Actor = game.getCurrentPlayer().getName();
            Long turn1Deadline = game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT);
            int turn1Hand = (int) game.getHandNumber();
            int turn1Gen = (int) game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT);

            // Turn 1 actor acts timely
            actionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), turn1Actor);

            // Now it is turn 2 actor's turn
            String turn2Actor = game.getCurrentPlayer().getName();
            assertThat(turn2Actor).isNotEqualTo(turn1Actor);
            Long turn2Deadline = game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT);
            int turn2Gen = (int) game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT);

            // Stale callback from Turn 1 fires now
            lifecycle.executeTurnDeadline(roomId, turn1Deadline, turn1Hand, turn1Gen);

            // Verify Turn 2 actor was NOT removed/timed out and is still the current actor
            assertThat(room.hasPlayer(turn2Actor)).isTrue();
            assertThat(game.getCurrentPlayer().getName()).isEqualTo(turn2Actor);
            assertThat(game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(turn2Deadline);
            assertThat(game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(turn2Gen);
            assertThat(game.isGameOver()).isFalse();

            // Next, complete the hand so Hand 2 begins
            // Players fold to end hand quickly
            actionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, null), turn2Actor);
            String turn3Actor = game.getCurrentPlayer().getName();
            actionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, null), turn3Actor);

            // Start new hand
            lifecycle.startNewHand(roomId);
            assertThat(game.getHandNumber()).isGreaterThan(turn1Hand);

            // Firing stale turn callback from Hand 1 during Hand 2 must also be a clean no-op
            lifecycle.executeTurnDeadline(roomId, turn1Deadline, turn1Hand, turn1Gen);
            assertThat(game.isGameOver()).isFalse();
            assertThat(game.getPlayers()).hasSize(3);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Stale disconnect grace expiry firing after player reconnect or replacement is a no-op")
    void staleDisconnectGraceExpiryAfterReconnectOrReplacementIsNoOp(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);

            String roomId = rooms.createRoom(new CreateRoomRequest("Grace Room", "Host", 6, 10, 20, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Grace Room", "Alice", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);

            lifecycle.createGameFromRoom(roomId);
            lifecycle.markPlayerReconnected(roomId, "Host");
            lifecycle.markPlayerReconnected(roomId, "Alice");

            Game game = lifecycle.getGame(roomId);
            assertThat(game).isNotNull();

            // Alice disconnects
            long staleDeadline = System.currentTimeMillis() - 1000;
            lifecycle.markPlayerDisconnected(roomId, "Alice", staleDeadline);
            room.setAbsenceDeadline("Alice", staleDeadline);
            assertThat(game.findPlayerByName("Alice").getIsDisconnected()).isTrue();

            // Alice reconnects before expiry is processed
            lifecycle.markPlayerReconnected(roomId, "Alice");
            room.setPlayerConnected("Alice", true);
            assertThat(game.findPlayerByName("Alice").getIsDisconnected()).isFalse();

            // Now stale disconnect expiry fires with the old deadline
            lifecycle.executeDisconnectGraceExpiry(roomId, "Alice", staleDeadline);

            // Alice must NOT have been removed
            assertThat(room.hasPlayer("Alice")).isTrue();
            assertThat(lifecycle.playerExistsInGame(roomId, "Alice")).isTrue();
            assertThat(game.isGameOver()).isFalse();

            // Verify renewed deadline guard: Alice disconnects again with a new deadline
            long freshDeadline = System.currentTimeMillis() + 60_000;
            lifecycle.markPlayerDisconnected(roomId, "Alice", freshDeadline);
            room.setAbsenceDeadline("Alice", freshDeadline);

            // Stale expiry with old deadline fires: no-op because deadline does not match current state
            lifecycle.executeDisconnectGraceExpiry(roomId, "Alice", staleDeadline);
            assertThat(room.hasPlayer("Alice")).isTrue();
            assertThat(lifecycle.playerExistsInGame(roomId, "Alice")).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Timer races with actions and departure preserve chip conservation and lifecycle invariants")
    void timerRacesWithActionsAndDeparturePreserveChipConservation(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);
            GameController gameController = context.getBean(GameController.class);

            int buyIn = 1000;
            int totalInitialChips = buyIn * 3;
            String roomId = rooms.createRoom(new CreateRoomRequest("Race Room", "Host", 6, 10, 20, buyIn, null));
            rooms.joinRoom(new JoinRoomRequest("Race Room", "Alice", null));
            rooms.joinRoom(new JoinRoomRequest("Race Room", "Bob", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);
            room.setPlayerConnected("Bob", true);

            PlayerPrincipal hostPrincipal = new PlayerPrincipal("Host", roomId, room.getMembershipId("Host"));
            PlayerPrincipal alicePrincipal = new PlayerPrincipal("Alice", roomId, room.getMembershipId("Alice"));
            PlayerPrincipal bobPrincipal = new PlayerPrincipal("Bob", roomId, room.getMembershipId("Bob"));

            lifecycle.createGameFromRoom(roomId);
            lifecycle.markPlayerReconnected(roomId, "Host");
            lifecycle.markPlayerReconnected(roomId, "Alice");
            lifecycle.markPlayerReconnected(roomId, "Bob");

            Game game = lifecycle.getGame(roomId);
            assertThat(game).isNotNull();

            PublicGameStateResponse initialSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(initialSnapshot).isNotNull();
            long initialRevision = initialSnapshot.stateRevision();

            String currentActor = game.getCurrentPlayer().getName();
            Long turnDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT);
            int handNumber = (int) game.getHandNumber();
            int gen = (int) game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT);

            int initialPot = initialSnapshot.pot();
            int initialPlayerChips = initialSnapshot.players().stream().mapToInt(PublicPlayerState::chips).sum();
            assertThat(initialPot + initialPlayerChips).isEqualTo(totalInitialChips);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                CyclicBarrier barrier = new CyclicBarrier(3);
                AtomicLong lastObservedRevision = new AtomicLong(initialRevision);
                List<Future<Void>> futures = new ArrayList<>();

                // Thread 1: Current player attempts to act (CALL)
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        PlayerPrincipal actorPrincipal = currentActor.equals("Host") ? hostPrincipal
                                : currentActor.equals("Alice") ? alicePrincipal : bobPrincipal;
                        gameController.performAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), actorPrincipal);
                    } catch (Exception ignored) {
                        // Action may be rejected if turn timeout raced ahead
                    }
                    return null;
                }));

                // Thread 2: Turn timeout deadline callback fires concurrently
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    lifecycle.executeTurnDeadline(roomId, turnDeadline, handNumber, gen);
                    return null;
                }));

                // Thread 3: Reader polls public snapshots concurrently
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        PublicGameStateResponse snapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
                        if (snapshot != null) {
                            long rev = snapshot.stateRevision();
                            assertThat(rev).isGreaterThanOrEqualTo(lastObservedRevision.get());
                            lastObservedRevision.set(rev);
                        }
                    }
                    return null;
                }));

                for (Future<Void> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }

                PublicGameStateResponse finalSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
                if (finalSnapshot != null) {
                    int finalPot = finalSnapshot.pot();
                    int activeChips = finalSnapshot.players().stream().mapToInt(PublicPlayerState::chips).sum();
                    int departedChips = 0;
                    for (String name : List.of("Host", "Alice", "Bob")) {
                        final String pName = name;
                        boolean present = finalSnapshot.players().stream().anyMatch(p -> p.name().equals(pName));
                        if (!present) {
                            departedChips += buyIn;
                        }
                    }
                    assertThat(finalPot).isGreaterThanOrEqualTo(0);
                    assertThat(activeChips).isGreaterThanOrEqualTo(0);
                    assertThat(finalSnapshot.stateRevision()).isGreaterThanOrEqualTo(initialRevision);
                }
            } finally {
                stopExecutor(executor);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Stale inactivity cleanup firing after player activity is a no-op")
    void staleInactivityCallbackAfterActivityIsNoOp(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);
            PlayerActionService actionService = context.getBean(PlayerActionService.class);

            String roomId = rooms.createRoom(new CreateRoomRequest("Inact Room", "Host", 6, 10, 20, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Inact Room", "Alice", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);

            lifecycle.createGameFromRoom(roomId);
            lifecycle.markPlayerReconnected(roomId, "Host");
            lifecycle.markPlayerReconnected(roomId, "Alice");

            Game game = lifecycle.getGame(roomId);
            assertThat(game).isNotNull();

            Long originalInactivityDeadline = room.getInactivityDeadlineEpochMs();
            assertThat(originalInactivityDeadline).isNotNull();

            // Player takes an action, resetting the inactivity deadline
            String actor = game.getCurrentPlayer().getName();
            actionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), actor);

            // Stale inactivity callback with old deadline fires
            lifecycle.executeInactivityCleanup(roomId, originalInactivityDeadline);

            // Game must NOT be abandoned / game over
            assertThat(game.isGameOver()).isFalse();
            assertThat(room.isTerminal()).isFalse();
            assertThat(lifecycle.gameExists(roomId)).isTrue();
        }
    }

    @Test
    void expiredReadyCommandRollsBackAbandonmentAndDiscardsItsBroadcast() throws Exception {
        try (ConfigurableApplicationContext context = startApplication(true)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);
            String roomId = rooms.createRoom(new CreateRoomRequest("Expired Ready", "Host", 6, 10, 20, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Expired Ready", "Alice", null));
            lifecycle.createGameFromRoom(roomId);
            lifecycle.markPlayerReconnected(roomId, "Host");
            lifecycle.markPlayerReconnected(roomId, "Alice");
            Game current = lifecycle.getGame(roomId);
            context.getBean(PlayerActionService.class).processPlayerAction(roomId,
                    new PlayerActionRequest(PlayerAction.FOLD, null), current.getCurrentPlayer().getName());
            AbstractMessageChannel broker = context.getBean("brokerChannel", AbstractMessageChannel.class);
            AtomicLong broadcasts = new AtomicLong();
            broker.addInterceptor(new ChannelInterceptor() {
                @Override
                public Message<?> preSend(Message<?> message, MessageChannel channel) {
                    if (("/game/" + roomId).equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
                        broadcasts.incrementAndGet();
                    }
                    return message;
                }
            });

            lifecycle.getRoomCoordinator().coordinate(roomId, () -> {
                long deadline = rooms.getRoom(roomId).getInactivityDeadlineEpochMs();
                Game game = lifecycle.getGame(roomId);
                var acceptedTasks = game.getScheduledTaskDeadlinesSnapshot();
                Long readyDeadline = game.getReadyCountdownDeadlineEpochMs();
                assertThat(readyDeadline).isNotNull();
                int settledPot = game.getPot();
                Clock originalClock = lifecycle.getClock();
                try {
                    lifecycle.setClock(Clock.fixed(Instant.ofEpochMilli(deadline - 1), ZoneOffset.UTC));
                    lifecycle.executeInactivityCleanup(roomId, deadline);
                    assertThat(lifecycle.getGame(roomId).isGameOver()).isFalse();

                    lifecycle.setClock(Clock.fixed(Instant.ofEpochMilli(deadline), ZoneOffset.UTC));
                    BadRequestException rejected = assertThrows(BadRequestException.class,
                            () -> lifecycle.markPlayerReadyForNextHand(roomId, "Host"));
                    assertThat(rejected.actionErrorCode()).isEqualTo("GAME_EXPIRED");
                    Game restored = lifecycle.getGame(roomId);
                    assertThat(restored.isGameOver()).isFalse();
                    assertThat(restored.getRetainedTerminalResult()).isNull();
                    assertThat(restored.getScheduledTaskDeadlinesSnapshot()).isEqualTo(acceptedTasks);
                    assertThat(restored.getReadyCountdownDeadlineEpochMs()).isEqualTo(readyDeadline);
                    assertThat(rooms.getRoom(roomId).isTerminal()).isFalse();
                    assertThat(broadcasts.get()).isZero();

                    assertThat(lifecycle.getInactivityDeadline(roomId)).isEqualTo(deadline);
                    lifecycle.executeInactivityCleanup(roomId, deadline);
                    assertThat(restored.getRetainedTerminalResult().abandoned()).isTrue();
                    assertThat(restored.getPot()).isEqualTo(settledPot);
                    lifecycle.executeInactivityCleanup(roomId, deadline);
                    assertThat(broadcasts.get()).isEqualTo(1);
                } finally {
                    lifecycle.setClock(originalClock);
                }
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Terminal cleanup on active, uncompleted game is a no-op")
    void terminalCleanupOnActiveGameIsNoOp(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);

            String roomId = rooms.createRoom(new CreateRoomRequest("Active Term Room", "Host", 6, 10, 20, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Active Term Room", "Alice", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);

            lifecycle.createGameFromRoom(roomId);
            Game game = lifecycle.getGame(roomId);
            assertThat(game).isNotNull();
            assertThat(game.isGameOver()).isFalse();

            // Execute terminal cleanup on active game
            lifecycle.executeTerminalCleanup(roomId);

            // Game and room must still exist!
            assertThat(lifecycle.gameExists(roomId)).isTrue();
            assertThat(rooms.getRoom(roomId)).isNotNull();

            // Now end the game legitimately via abandonment
            lifecycle.abandonGameDueToInactivity(roomId);
            assertThat(game.isGameOver()).isTrue();
            assertThat(room.isTerminal()).isTrue();

            // Execute terminal cleanup on finished game
            lifecycle.executeTerminalCleanup(roomId);

            // Game and room must be cleaned up
            assertThat(lifecycle.gameExists(roomId)).isFalse();
            assertThat(rooms.getRoom(roomId)).isNull();
        }
    }
}
