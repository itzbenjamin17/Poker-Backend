package com.pokergame.persistence;

import com.pokergame.PokerApplication;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Verifies that save failures cannot block the actual HTTP and STOMP gameplay boundaries. */
@Tag("integration")
@SpringBootTest(classes = PokerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PersistenceAvailabilityTransportIntegrationTest.FaultConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersistenceAvailabilityTransportIntegrationTest extends AbstractIntegrationTestSupport {
    @TempDir
    static Path walDirectory;

    @Autowired
    private AtomicBoolean unavailableStorage;
    @Autowired
    private EncryptedWalStore store;
    @Autowired
    private DurableMutationAspect mutations;
    @Autowired
    private SimpleBrokerMessageHandler broker;

    /** Enables persistence only for this isolated fixture, including when the suite defaults disable it. */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("poker.persistence.enabled", () -> true);
        registry.add("poker.persistence.directory", () -> walDirectory.toString());
    }

    /** HTTP mutations/reads and successive STOMP actions remain usable until storage catches up. */
    @Test
    void httpAndStompContinueDuringWriteOutage() throws Exception {
        var alice = createRoom("Transport outage", "Alice", 6);
        var bob = joinRoom("Transport outage", "Bob");
        var carol = joinRoom("Transport outage", "Carol");
        String roomId = alice.path("roomId").asText();
        String aliceToken = alice.path("token").asText();
        WebSocketStompClient client = createStompClient();
        Map<String, StompSession> sessions = new LinkedHashMap<>();
        String destination = "/game/" + roomId;
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        var probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        try {
            sessions.put("Alice", connectSession(client, aliceToken));
            sessions.put("Bob", connectSession(client, bob.path("token").asText()));
            sessions.put("Carol", connectSession(client, carol.path("token").asText()));
            AtomicReference<PublicGameStateResponse> latest = new AtomicReference<>();
            sessions.get("Alice").subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(@NonNull StompHeaders headers) {
                    return PublicGameStateResponse.class;
                }

                @Override
                public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                    if (payload instanceof PublicGameStateResponse state) latest.set(state);
                }
            });
            await().atMost(DEFAULT_TIMEOUT).until(() -> !broker.getSubscriptionRegistry()
                    .findSubscriptions(probe).isEmpty());

            unavailableStorage.set(true);
            assertThat(startGame(roomId, aliceToken).path("message").asText())
                    .isEqualTo("Game started successfully");
            await().atMost(DEFAULT_TIMEOUT).until(() -> latest.get() != null);
            assertThat(store.isHealthy()).isFalse();
            assertThat(getRoomData(roomId, aliceToken).path("gameStarted").asBoolean()).isTrue();
            assertThat(readGameState(roomId, aliceToken).path("handNumber").asLong()).isEqualTo(1);

            for (int actionIndex = 0; actionIndex < 2; actionIndex++) {
                PublicGameStateResponse before = latest.get();
                PublicPlayerState actor = before.players().stream()
                        .filter(player -> player.name().equals(before.currentPlayerName())).findFirst().orElseThrow();
                assertThat(before.currentBet() - actor.currentBet()).isPositive();
                sessions.get(actor.name()).send("/app/" + roomId + "/action",
                        new PlayerActionRequest(PlayerAction.CALL, null));
                await().atMost(DEFAULT_TIMEOUT).until(() -> latest.get().pot() > before.pot());
                assertThat(latest.get().players()).anySatisfy(player -> {
                    assertThat(player.name()).isEqualTo(actor.name());
                    assertThat(player.chips()).isLessThan(actor.chips());
                });
                assertThat(store.isHealthy()).isFalse();
                assertThat(readGameState(roomId, aliceToken).path("pot").asInt()).isEqualTo(latest.get().pot());
            }

            assertThat(createRoom("Second unsaved room", "Dave", 6).path("roomId").asText()).isNotBlank();
            assertThat(store.unsavedRoomCount()).isGreaterThanOrEqualTo(2);
            unavailableStorage.set(false);
            mutations.retryPendingSaves();
            await().atMost(DEFAULT_TIMEOUT).until(store::isHealthy);
            assertThat(store.unsavedRoomCount()).isZero();
        } finally {
            unavailableStorage.set(false);
            try {
                sessions.values().stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
                await().atMost(DEFAULT_TIMEOUT).until(() -> broker.getSubscriptionRegistry()
                        .findSubscriptions(probe).isEmpty());
            } finally {
                client.stop();
            }
        }
    }

    /** Injects an outage at the storage seam without changing application routing or security. */
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
                if (unavailableStorage.get()) throw new PersistenceException("injected transport-test storage outage");
            });
        }
    }
}
