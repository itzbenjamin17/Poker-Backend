package com.pokergame.persistence;

import com.pokergame.PokerApplication;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import com.pokergame.security.PlayerPrincipal;
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

class PersistenceRestartIntegrationTest {

    @TempDir
    Path walDirectory;

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

    private ConfigurableApplicationContext startApplication() {
        return startApplication(120000);
    }

    private ConfigurableApplicationContext startApplication(long disconnectGracePeriodMs) {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new SpringApplicationBuilder(PokerApplication.class)
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
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + walDirectory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + key);
    }

    private SessionConnectEvent connectEvent(String playerName, String roomId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(new PlayerPrincipal(playerName, roomId));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(this, message);
    }
}
