package com.pokergame.persistence;

import com.pokergame.PokerApplication;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.persistence.transaction.DurableMutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

/** Tests persistence restart integration behavior. */
class PersistenceRestartIntegrationTest {

    @TempDir
    Path walDirectory;

    /**
     * Protects the contract that private lobby survives application restart from encrypted WAL.
     */
    @Test
    void privateLobbySurvivesApplicationRestartFromEncryptedWal() throws Exception {
        String roomId;
        try (ConfigurableApplicationContext first = startApplication()) {
            roomId = first.getBean(RoomService.class).createRoom(
                    new CreateRoomRequest("Durable Room", "Alice", 6, 5, 10, 1000, "swordfish"));
            assertNotNull(first.getBean(RoomService.class).getRoom(roomId));
        }

        byte[] wal = Files.readAllBytes(walDirectory.resolve(roomId + ".wal"));
        String raw = new String(wal, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("swordfish"));
        assertFalse(raw.contains("Durable Room"));

        try (ConfigurableApplicationContext second = startApplication()) {
            Room restored = second.getBean(RoomService.class).getRoom(roomId);
            assertNotNull(restored);
            assertEquals("Durable Room", restored.getRoomName());
            assertEquals("Alice", restored.getPlayers().getFirst());
            assertTrue(restored.checkPassword("swordfish"));
        }
    }

    /**
     * Protects the contract that active first hand restores exact cards and accepts the next legal action.
     */
    @Test
    void activeFirstHandRestoresExactCardsAndAcceptsTheNextLegalAction() {
        String roomId;
        PublicGameStateResponse publicBefore;
        PrivatePlayerState privateBefore;
        List<Card> deckBefore;
        String currentPlayerName;
        PlayerAction nextAction;

        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Hand Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Hand Room", "Bob", null));
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            games.createGameFromRoom(roomId);
            Game game = games.getGame(roomId);
            GameStateService states = first.getBean(GameStateService.class);
            publicBefore = states.getPublicGameStateSnapshot(roomId, game);
            currentPlayerName = game.getCurrentPlayer().getName();
            privateBefore = states.getPrivatePlayerStateSnapshot(game, currentPlayerName);
            deckBefore = game.getRemainingDeckSnapshot();
            nextAction = game.getCurrentPlayer().getCurrentBet() < game.getCurrentHighestBet()
                    ? PlayerAction.CALL : PlayerAction.CHECK;
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            Game restored = games.getGame(roomId);
            assertNotNull(restored);
            GameStateService states = second.getBean(GameStateService.class);
            PublicGameStateResponse publicAfter = states.getPublicGameStateSnapshot(roomId, restored);
            assertEquals(publicBefore.pot(), publicAfter.pot());
            assertEquals(publicBefore.phase(), publicAfter.phase());
            assertEquals(publicBefore.currentPlayerId(), publicAfter.currentPlayerId());
            assertEquals(publicBefore.communityCards(), publicAfter.communityCards());
            assertEquals(privateBefore, states.getPrivatePlayerStateSnapshot(restored, currentPlayerName));
            assertEquals(deckBefore, restored.getRemainingDeckSnapshot());
            assertTrue(restored.getPlayers().stream().allMatch(player -> player.getIsDisconnected()));

            games.markPlayerReconnected(roomId, currentPlayerName);
            assertDoesNotThrow(() -> second.getBean(PlayerActionService.class).processPlayerAction(
                    roomId, new PlayerActionRequest(nextAction, null), currentPlayerName));
        }
    }

    /**
     * Protects the contract that recovered players get fresh grace and only missing player is removed.
     */
    @Test
    void recoveredPlayersGetFreshGraceAndOnlyMissingPlayerIsRemoved() {
        String roomId;
        try (ConfigurableApplicationContext first = startApplication(200)) {
            RoomService rooms = first.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Reconnect Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Reconnect Room", "Bob", null));
            first.getBean(GameLifecycleService.class).createGameFromRoom(roomId);
        }

        try (ConfigurableApplicationContext second = startApplication(200)) {
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            second.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent("Alice", roomId, "recovered-session"));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertTrue(second.getBean(RoomService.class).getRoom(roomId).hasPlayer("Alice"));
                assertFalse(second.getBean(RoomService.class).getRoom(roomId).hasPlayer("Bob"));
                assertTrue(games.playerExistsInGame(roomId, "Alice"));
                assertFalse(games.playerExistsInGame(roomId, "Bob"));
            });
        }
    }

    /**
     * Protects the contract that recovered lobby players get fresh grace and only missing player is removed.
     */
    @Test
    void recoveredLobbyPlayersGetFreshGraceAndOnlyMissingPlayerIsRemoved() {
        String roomId;
        try (ConfigurableApplicationContext first = startApplication(200)) {
            RoomService rooms = first.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Lobby Reconnect Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Lobby Reconnect Room", "Bob", null));
        }

        try (ConfigurableApplicationContext second = startApplication(200)) {
            second.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent("Alice", roomId, "recovered-lobby-session"));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                Room restored = second.getBean(RoomService.class).getRoom(roomId);
                assertNotNull(restored);
                assertTrue(restored.hasPlayer("Alice"));
                assertFalse(restored.hasPlayer("Bob"));
            });
        }
    }

    /**
     * Protects the contract that destroyed lobby does not resurrect and its WAL is removed.
     */
    @Test
    void destroyedLobbyDoesNotResurrectAndItsWalIsRemoved() {
        String roomId;
        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Temporary Room", "Alice", 6, 5, 10, 1000, null));
            rooms.leaveRoom(roomId, "Alice");
            assertNull(rooms.getRoom(roomId));
        }

        assertFalse(Files.exists(walDirectory.resolve(roomId + ".wal")));
        try (ConfigurableApplicationContext second = startApplication()) {
            assertNull(second.getBean(RoomService.class).getRoom(roomId));
        }
    }

    /**
     * Protects the contract that overdue persisted cleanup executes once after recovery.
     */
    @Test
    void overduePersistedCleanupExecutesOnceAfterRecovery() {
        String roomId;
        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Cleanup Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Cleanup Room", "Bob", null));
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            games.createGameFromRoom(roomId);
            games.scheduleGameCleanup(roomId, 300);
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            RoomService rooms = second.getBean(RoomService.class);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertNull(rooms.getRoom(roomId)));
            assertNull(second.getBean(GameLifecycleService.class).getGame(roomId));
        }
        assertFalse(Files.exists(walDirectory.resolve(roomId + ".wal")));
    }

    /**
     * Protects the contract that a normal game end deletes the WAL file from disk.
     */
    @Test
    void normalGameEndDeletesWalFile() throws Exception {
        String roomId;
        try (ConfigurableApplicationContext context = startApplication()) {
            RoomService rooms = context.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("End Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("End Room", "Bob", null));
            context.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent("Alice", roomId, "alice-session"));
            context.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent("Bob", roomId, "bob-session"));
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            games.createGameFromRoom(roomId);

            // Bob leaves the game, ending the game and triggering the cleanup schedule
            games.leaveGame(roomId, "Bob");

            // Wait for display delay (7s) plus a bit extra for cleanup task to execute
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertNull(rooms.getRoom(roomId));
                assertNull(games.getGame(roomId));
                assertFalse(Files.exists(walDirectory.resolve(roomId + ".wal")));
            });
        }
    }

    /**
     * Protects the contract that a failed room creation does not leak a WAL file on disk.
     */
    @Test
    void failedRoomCreationDoesNotLeaveWalFile() throws Exception {
        String uniqueRoomName = "Duplicate Room";
        try (ConfigurableApplicationContext context = startApplication()) {
            RoomService rooms = context.getBean(RoomService.class);
            // Create first room successfully
            String firstRoomId = rooms.createRoom(new CreateRoomRequest(uniqueRoomName, "Alice", 6, 5, 10, 1000, null));
            assertTrue(Files.exists(walDirectory.resolve(firstRoomId + ".wal")));

            // Attempt to create second room with duplicate name - should fail
            assertThrows(com.pokergame.exception.BadRequestException.class, () -> {
                rooms.createRoom(new CreateRoomRequest(uniqueRoomName, "Bob", 6, 5, 10, 1000, null));
            });

            // Verify that no other WAL file was leaked
            try (var paths = Files.list(walDirectory)) {
                List<Path> walFiles = paths.filter(p -> p.toString().endsWith(".wal")).toList();
                assertEquals(1, walFiles.size());
                assertEquals(firstRoomId + ".wal", walFiles.getFirst().getFileName().toString());
            }
        }
    }

    /**
     * Protects the contract that uncommitted WAL files are cleaned up during startup recovery.
     */
    @Test
    void uncommittedWalFilesAreCleanedUpAtStartupRecovery() throws Exception {
        String leakedRoomId = "leaked-room-id";
        // Create an uncommitted WAL file manually in the walDirectory
        Path walFile = walDirectory.resolve(leakedRoomId + ".wal");
        Files.createFile(walFile);
        assertTrue(Files.exists(walFile));

        // Start the application, which should run PersistenceRecovery and clean up the file
        try (ConfigurableApplicationContext context = startApplication()) {
            assertTrue(context.isActive());
            // File should be deleted during startup recovery
            assertFalse(Files.exists(walFile));
        }
    }

    @Test
    void terminalResultAndEmptyRoomSurviveRestartUntilCleanup() throws Exception {
        String roomId;
        GameEndResponse originalResult;

        try (ConfigurableApplicationContext first = startApplication(120000, 20000)) {
            RoomService rooms = first.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Terminal Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Terminal Room", "Bob", null));
            first.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent("Alice", roomId, "alice-session"));
            first.getBean(WebSocketEventListener.class)
                    .handleWebSocketConnectListener(connectEvent("Bob", roomId, "bob-session"));
            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            games.createGameFromRoom(roomId);

            games.markPlayerDisconnected(roomId, "Bob", System.currentTimeMillis() + 60_000);
            originalResult = games.claimWin(roomId, "Alice");
            assertNotNull(originalResult);
            assertEquals("Alice", originalResult.winner());

            // Alice leaves the finished game -> room becomes empty
            games.leaveGame(roomId, "Alice");
            Room room = rooms.getRoom(roomId);
            assertNotNull(room);
            assertTrue(room.getPlayers().isEmpty());
            assertTrue(room.isTerminal());
        }

        // Restart with long cleanup window so we can inspect
        try (ConfigurableApplicationContext second = startApplication(120000, 20000)) {
            RoomService rooms = second.getBean(RoomService.class);
            GameLifecycleService games = second.getBean(GameLifecycleService.class);

            Room recoveredRoom = rooms.getRoom(roomId);
            assertNotNull(recoveredRoom, "Terminal empty room container must survive restart");
            assertTrue(recoveredRoom.getPlayers().isEmpty(), "Recovered terminal room has 0 members");
            assertTrue(recoveredRoom.isTerminal(), "Recovered room remains terminal");

            // Joining terminal room must be rejected
            assertThrows(BadRequestException.class, () ->
                    rooms.joinRoom(new JoinRoomRequest("Terminal Room", "Charlie", null)));

            Game recoveredGame = games.getGame(roomId);
            assertNotNull(recoveredGame, "Finished game aggregate must survive restart");
            assertTrue(recoveredGame.isGameOver());

            // Review reads return the exact frozen terminal result
            GameEndResponse reviewAlice = games.getGameReview(roomId, "Alice");
            assertNotNull(reviewAlice);
            assertEquals(originalResult.winner(), reviewAlice.winner());
            assertEquals(originalResult.winnerChips(), reviewAlice.winnerChips());
            assertEquals(originalResult.type(), reviewAlice.type());

            GameEndResponse reviewBob = games.getGameReview(roomId, "Bob");
            assertNotNull(reviewBob);
            assertEquals(originalResult.winner(), reviewBob.winner());

            // Unauthorized stranger cannot review
            assertThrows(UnauthorisedActionException.class, () ->
                    games.getGameReview(roomId, "Charlie"));
        }
    }

    /**
     * Finding B9-1 / Ticket 32: When a mutation in RoomService fails midway, in-memory state is
     * rolled back to its pre-failure snapshot and subsequent mutations succeed.
     */
    @Test
    void midwayMutationFailureInRoomServiceRollsBackToSnapshotAndSubsequentMutationSucceeds() throws Exception {
        String roomId;
        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Midway Fail Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Midway Fail Room", "Bob", null));

            TestMutationHelper helper = first.getBean(TestMutationHelper.class);
            assertThrows(IllegalStateException.class, () ->
                    helper.mutateRoomAndThrow(roomId, "DirtyCharlie"));

            // Read operation returns uncorrupted pre-failure state
            Room room = rooms.getRoom(roomId);
            assertNotNull(room);
            assertEquals(List.of("Alice", "Bob"), room.getPlayers());
            assertFalse(room.hasPlayer("DirtyCharlie"));

            // Subsequent valid mutation succeeds and commits
            rooms.joinRoom(new JoinRoomRequest("Midway Fail Room", "David", null));
            assertEquals(List.of("Alice", "Bob", "David"), rooms.getRoom(roomId).getPlayers());
        }

        // Restart application and verify uncorrupted state persisted
        try (ConfigurableApplicationContext second = startApplication()) {
            RoomService rooms = second.getBean(RoomService.class);
            Room restored = rooms.getRoom(roomId);
            assertNotNull(restored);
            assertEquals(List.of("Alice", "Bob", "David"), restored.getPlayers());
            assertFalse(restored.hasPlayer("DirtyCharlie"));
        }
    }

    /**
     * Finding B9-1 / Ticket 32: When a mutation in GameLifecycleService fails midway,
     * the aggregate is restored to its pre-failure snapshot and subsequent valid mutations succeed.
     */
    @Test
    void midwayMutationFailureInGameLifecycleServiceRollsBackToSnapshotAndSubsequentMutationSucceeds() throws Exception {
        String roomId;
        String currentPlayerName;
        PlayerAction nextAction;
        int aliceInitialChips;

        try (ConfigurableApplicationContext first = startApplication()) {
            RoomService rooms = first.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Game Rollback Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Game Rollback Room", "Bob", null));

            GameLifecycleService games = first.getBean(GameLifecycleService.class);
            games.createGameFromRoom(roomId);
            Game game = games.getGame(roomId);
            aliceInitialChips = game.getPlayers().getFirst().getChips();
            currentPlayerName = game.getCurrentPlayer().getName();
            nextAction = game.getCurrentPlayer().getCurrentBet() < game.getCurrentHighestBet()
                    ? PlayerAction.CALL : PlayerAction.CHECK;

            games.markPlayerReconnected(roomId, "Alice");
            games.markPlayerReconnected(roomId, "Bob");

            TestMutationHelper helper = first.getBean(TestMutationHelper.class);
            assertThrows(IllegalStateException.class, () ->
                    helper.mutateGameAndThrow(roomId));

            // Verify chips rolled back to pre-mutation snapshot
            Game rolledBackGame = games.getGame(roomId);
            assertNotNull(rolledBackGame);
            assertEquals(aliceInitialChips, rolledBackGame.getPlayers().getFirst().getChips());

            // Subsequent valid mutation succeeds
            first.getBean(PlayerActionService.class).processPlayerAction(
                    roomId, new PlayerActionRequest(nextAction, null), currentPlayerName);
        }

        // Restart application and verify uncorrupted game persisted
        try (ConfigurableApplicationContext second = startApplication()) {
            GameLifecycleService games = second.getBean(GameLifecycleService.class);
            Game restored = games.getGame(roomId);
            assertNotNull(restored);
            assertNotEquals(888888, restored.getPlayers().getFirst().getChips());
        }
    }

    /**
     * Finding B9-1 / Ticket 32: Failed room creation cleans up rooms, activeGames, and pending timers.
     */
    @Test
    void failedRoomCreationCleansUpRoomsActiveGamesAndTimers() throws Exception {
        String failedRoomId = java.util.UUID.randomUUID().toString();
        try (ConfigurableApplicationContext context = startApplication()) {
            TestMutationHelper helper = context.getBean(TestMutationHelper.class);
            assertThrows(IllegalStateException.class, () ->
                    helper.createRoomAndThrow(failedRoomId,
                            new CreateRoomRequest("Aborted Room", "Alice", 6, 5, 10, 1000, null)));

            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);

            assertNull(rooms.getRoom(failedRoomId));
            assertNull(rooms.getCurrentHost(failedRoomId));
            assertNull(games.getGame(failedRoomId));
            assertEquals(0, games.getRuntimeTaskCount(failedRoomId));
            assertFalse(Files.exists(walDirectory.resolve(failedRoomId + ".wal")));
        }
    }

    /**
     * Starts a persistence-enabled application with the default disconnect grace.
     *
     * @return running application context
     */
    private ConfigurableApplicationContext startApplication() {
        return startApplication(120000);
    }

    private ConfigurableApplicationContext startApplication(long disconnectGracePeriodMs) {
        return startApplication(disconnectGracePeriodMs, 500);
    }

    /**
     * Starts an isolated persistence-enabled application against the test WAL directory.
     *
     * @param disconnectGracePeriodMs reconnect grace duration for the context
     * @param gameEndDisplayDelayMs   review delay before cleanup
     * @return running application context
     */
    private ConfigurableApplicationContext startApplication(long disconnectGracePeriodMs, long gameEndDisplayDelayMs) {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new SpringApplicationBuilder(PokerApplication.class, TestMutationHelper.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .properties(
                        "server.port=0",
                        "app.jwt.base64-secret=" + Base64.getEncoder().encodeToString(new byte[64]),
                        "jwt.expirationMillis=3600000",
                        "app.security.cors.allowed-origins=http://localhost",
                        "poker.security.trust-proxy=false",
                        "poker.rate-limiting.enabled=false")
                .run(
                        "--poker.disconnect.grace-period-ms=" + disconnectGracePeriodMs,
                        "--poker.game-end.display-delay-ms=" + gameEndDisplayDelayMs,
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + walDirectory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + key);
    }

    /**
     * Connects event for the test.
     * @param playerName player name supplied to the fixture
     * @param roomId room ID supplied to the fixture
     * @param sessionId session ID supplied to the fixture
     * @return synthetic STOMP connect event
     */
    private SessionConnectEvent connectEvent(String playerName, String roomId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(new PlayerPrincipal(playerName, roomId));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(this, message);
    }

    @org.springframework.stereotype.Component
    public static class TestMutationHelper {
        @org.springframework.beans.factory.annotation.Autowired
        private RoomService roomService;

        @org.springframework.beans.factory.annotation.Autowired
        private GameLifecycleService gameLifecycleService;

        @DurableMutation(roomId = "#roomId")
        public void mutateRoomAndThrow(String roomId, String dirtyPlayer) {
            Room room = roomService.getRoom(roomId);
            room.addPlayer(dirtyPlayer);
            throw new IllegalStateException("Simulated midway mutation failure in room");
        }

        @DurableMutation(roomId = "#roomId")
        public void mutateGameAndThrow(String roomId) {
            Game game = gameLifecycleService.getGame(roomId);
            game.getPlayers().getFirst().setChips(888888);
            throw new IllegalStateException("Simulated midway mutation failure in game");
        }

        @DurableMutation(roomId = "#roomId")
        public void createRoomAndThrow(String roomId, CreateRoomRequest request) {
            roomService.createRoom(roomId, request);
            throw new IllegalStateException("Simulated failure during room creation");
        }
    }
}
