package com.pokergame.persistence;

import com.pokergame.PokerApplication;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import tools.jackson.databind.ObjectMapper;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.SubscribableChannel;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises accepted gameplay and checkpoint recovery after a runtime save failure. */
class PersistenceAvailabilityIntegrationTest {
    @TempDir
    Path directory;

    /** Storage failure preserves accepted gameplay and effects; a retry saves the latest state. */
    @Test
    void storageOutageContinuesGameplayAndCheckpointsLatestState() {
        String roomId;
        int initialPot;
        Map<String, Integer> initialStacks;
        List<Card> initialDeck;
        try (ConfigurableApplicationContext context = startApplication()) {
            RoomService rooms = context.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Available game", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Available game", "Bob", null));
            rooms.joinRoom(new JoinRoomRequest("Available game", "Carol", null));
            WebSocketEventListener listener = context.getBean(WebSocketEventListener.class);
            listener.registerSession(new com.pokergame.security.PlayerPrincipal("Alice", roomId), "session-alice");
            listener.registerSession(new com.pokergame.security.PlayerPrincipal("Bob", roomId), "session-bob");
            listener.registerSession(new com.pokergame.security.PlayerPrincipal("Carol", roomId), "session-carol");
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);
            lifecycle.createGameFromRoom(roomId);
            Game game = lifecycle.getGame(roomId);
            EncryptedWalStore store = context.getBean(EncryptedWalStore.class);
            AtomicBoolean unavailable = context.getBean(AtomicBoolean.class);
            List<Integer> publishedPots = new CopyOnWriteArrayList<>();
            ObjectMapper json = context.getBean(ObjectMapper.class);
            SubscribableChannel broker = context.getBean("brokerChannel", SubscribableChannel.class);
            MessageHandler recorder = message -> {
                if (message.getPayload() instanceof byte[] bytes
                        && ("/game/" + roomId).equals(message.getHeaders().get("simpDestination"))) {
                    var payload = json.readTree(bytes);
                    if (payload.has("pot") && payload.has("players")) publishedPots.add(payload.get("pot").intValue());
                }
            };
            broker.subscribe(recorder);
            try {
                initialPot = game.getPot();
                initialStacks = game.getPlayers().stream().collect(Collectors.toMap(Player::getPlayerId, Player::getChips));
                initialDeck = game.getRemainingDeckSnapshot();

                unavailable.set(true);

                Player actor = game.getCurrentPlayer();
                int call = Math.max(0, game.getCurrentHighestBet() - actor.getCurrentBet());
                PlayerAction action = call > 0 ? PlayerAction.CALL : PlayerAction.CHECK;

                context.getBean(PlayerActionService.class).processPlayerAction(
                        roomId, new PlayerActionRequest(action, null), actor.getName());
                int afterAcceptedPot = game.getPot();
                assertNotEquals(initialPot, afterAcceptedPot, "Valid gameplay remains accepted during a write outage");
                assertFalse(publishedPots.isEmpty(), "Accepted broadcasts must not be suppressed by a write outage");

                assertFalse(store.isHealthy());
                var health = context.getBean("pokerPersistenceHealth", HealthIndicator.class).health();
                assertEquals("UP", health.getStatus().getCode(), "Gameplay stays available after successful startup recovery");
                assertEquals(false, health.getDetails().get("persistenceHealthy"));
                assertEquals("MEMORY_ONLY", health.getDetails().get("mode"));

                unavailable.set(false);
                Player nextActor = game.getCurrentPlayer();
                int nextCall = Math.max(0, game.getCurrentHighestBet() - nextActor.getCurrentBet());
                PlayerAction nextAction = nextCall > 0 ? PlayerAction.CALL : PlayerAction.CHECK;
                context.getBean(PlayerActionService.class).processPlayerAction(
                        roomId, new PlayerActionRequest(nextAction, null), nextActor.getName());
                context.getBean(com.pokergame.persistence.transaction.DurableMutationAspect.class).retryPendingSaves();
                assertTrue(store.isHealthy(), "A checkpoint retry restores persistence health");
                initialPot = game.getPot();
                initialStacks = game.getPlayers().stream().collect(Collectors.toMap(Player::getPlayerId, Player::getChips));
                initialDeck = game.getRemainingDeckSnapshot();
            } finally {
                broker.unsubscribe(recorder);
            }
        }
        // After restart with healthy storage, state before failed mutation is recovered
        try (ConfigurableApplicationContext recovered = startApplication()) {
            Game game = recovered.getBean(GameLifecycleService.class).getGame(roomId);
            assertNotNull(game);
            assertEquals(initialPot, game.getPot());
            assertEquals(initialStacks, game.getPlayers().stream().collect(Collectors.toMap(Player::getPlayerId, Player::getChips)));
            assertEquals(initialDeck, game.getRemainingDeckSnapshot());
        }
    }

    /** Starts an isolated context; all keys and files belong to this test. */
    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(PokerApplication.class, FaultConfiguration.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run("--server.port=0",
                        "--app.jwt.base64-secret=" + Base64.getEncoder().encodeToString(new byte[64]),
                        "--jwt.expirationMillis=3600000",
                        "--app.security.cors.allowed-origins=http://localhost",
                        "--poker.security.trust-proxy=false",
                        "--poker.rate-limiting.enabled=false",
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + directory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + Base64.getEncoder().encodeToString(new byte[32]));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean
        AtomicBoolean unavailableStorage() {
            return new AtomicBoolean();
        }

        @Bean
        @Primary
        EncryptedWalStore faultInjectingStore(PersistenceProperties properties, EncryptionKeyring keyring,
                AtomicBoolean unavailableStorage) {
            return new EncryptedWalStore(properties.getDirectory(), keyring, (point, roomId) -> {
                if (unavailableStorage.get() && point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                    throw new PersistenceException("injected storage outage");
                }
            });
        }
    }
}
