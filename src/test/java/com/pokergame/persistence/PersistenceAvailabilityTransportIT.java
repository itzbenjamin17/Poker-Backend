package com.pokergame.persistence;

import com.pokergame.persistence.RoomAggregates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies live HTTP REST and WebSocket STOMP gameplay remain available during a transient
 * WAL write outage and return to {@code DURABLE} mode once storage heals.
 */
@Tag("integration")
@DisplayName("Persistence availability HTTP/STOMP integration tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
        locations = "classpath:application-test.properties",
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "poker.rate-limiting.enabled=false",
                "poker.game.game-end-display-delay-ms=60000",
                "poker.game.ready-countdown-ms=60000"
        })
@Import(PersistenceAvailabilityTransportIT.FaultyStorageConfig.class)
class PersistenceAvailabilityTransportIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final AtomicBoolean UNAVAILABLE_STORAGE = new AtomicBoolean(false);

    @TempDir
    static Path walDirectory;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        String encodedKey = Base64.getEncoder().encodeToString(new byte[32]);
        registry.add("poker.persistence.enabled", () -> "true");
        registry.add("poker.persistence.directory", () -> walDirectory.toString().replace('\\', '/'));
        registry.add("poker.persistence.current-key-id", () -> "v1");
        registry.add("poker.persistence.keys.v1", () -> encodedKey);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private EncryptedWalStore walStore;

    @Autowired
    private DurableMutationAspect durableMutationAspect;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomAggregates roomAggregates;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    @Qualifier("pokerPersistenceHealth")
    private HealthIndicator pokerPersistenceHealth;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;
    private WebSocketStompClient stompClient;
    private StompSession session;
    private StompSession bobSession;
    private String createdRoomId;

    @BeforeEach
    void setUpTransport() {
        UNAVAILABLE_STORAGE.set(false);
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void tearDownTransport() {
        UNAVAILABLE_STORAGE.set(false);
        disconnectQuietly(session);
        disconnectQuietly(bobSession);
        if (stompClient != null) {
            try {
                stompClient.stop();
            } catch (RuntimeException ignored) {
                // Best-effort client stop on teardown.
            }
        }
        awaitPendingSavesFlushed();
        if (createdRoomId != null) {
            try (LockLease _ = roomCoordinator.acquire(createdRoomId)) {
                roomService.destroyRoom(createdRoomId);
                roomAggregates.remove(createdRoomId);
                walStore.delete(createdRoomId);
            }
            createdRoomId = null;
        }
        awaitPendingSavesFlushed();
    }

    private void awaitPendingSavesFlushed() {
        Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            durableMutationAspect.retryPendingSaves();
            assertThat(walStore.isHealthy()).isTrue();
            assertThat(walStore.unsavedRoomCount()).isZero();
        });
    }

    @Test
    @DisplayName("HTTP actions and STOMP game broadcasts continue smoothly during storage write outage")
    void httpAndStompContinueDuringWriteOutage() throws Exception {
        String roomName = "TransportOutage-" + System.nanoTime();

        JsonNode createData = postJson("/api/room/create", null,
                new CreateRoomRequest(roomName, "Alice", 6, 5, 10, 1000, null)).path("data");
        String roomId = createData.path("roomId").asText();
        createdRoomId = roomId;
        String aliceToken = createData.path("token").asText();

        JsonNode joinData = postJson("/api/room/join", null,
                new JoinRoomRequest(roomName, "Bob", null)).path("data");
        String bobToken = joinData.path("token").asText();

        postJson("/api/room/" + roomId + "/start-game", aliceToken, "");

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));

        session = connectStomp(aliceToken);
        bobSession = connectStomp(bobToken);

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        session.subscribe("/game/" + roomId, new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (payload != null) {
                    messages.add(payload.toString());
                }
            }
        });
        awaitBrokerSubscription("/game/" + roomId);
        Awaitility.await().atMost(TIMEOUT).until(() -> {
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                var room = roomService.getRoom(roomId);
                return room != null && room.isPlayerConnected("Alice") && room.isPlayerConnected("Bob");
            }
        });

        UNAVAILABLE_STORAGE.set(true);

        String actorName;
        long revisionBefore;
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            Game game = gameLifecycleService.getGame(roomId);
            actorName = game.getCurrentPlayer().getName();
            revisionBefore = game.getStateRevision();
        }
        StompSession actorSession = "Alice".equals(actorName) ? session : bobSession;

        JsonNode httpState = getJson("/api/game/" + roomId + "/state", aliceToken);
        assertThat(httpState.path("stateRevision").asLong()).isGreaterThanOrEqualTo(revisionBefore);

        actorSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.CALL, null));

        Awaitility.await().atMost(TIMEOUT).until(() -> !messages.isEmpty());
        assertThat(walStore.isHealthy()).isFalse();

        Health degraded = pokerPersistenceHealth.health();
        assertThat(degraded).isNotNull();
        assertThat(degraded.getStatus().getCode()).isEqualTo("UP");
        assertThat(degraded.getDetails()).containsEntry("mode", "MEMORY_ONLY");

        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            assertThat(gameLifecycleService.getGame(roomId).getStateRevision()).isGreaterThan(revisionBefore);
        }

        UNAVAILABLE_STORAGE.set(false);
        awaitPendingSavesFlushed();
    }

    private StompSession connectStomp(String token) throws Exception {
        CompletableFuture<StompSession> connected = new CompletableFuture<>();
        WebSocketHttpHeaders wsHeaders = new WebSocketHttpHeaders();
        wsHeaders.add("Origin", ALLOWED_ORIGIN);
        StompHeaders stompHeaders = new StompHeaders();
        stompHeaders.add("Authorization", "Bearer " + token);
        stompClient.connectAsync("ws://localhost:" + port + "/ws", wsHeaders, stompHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession s, @NonNull StompHeaders headers) {
                        connected.complete(s);
                    }

                    @Override
                    public void handleTransportError(@NonNull StompSession s, @NonNull Throwable ex) {
                        connected.completeExceptionally(ex);
                    }
                });
        return connected.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private void disconnectQuietly(StompSession target) {
        if (target != null && target.isConnected()) {
            try {
                target.disconnect();
            } catch (RuntimeException ignored) {
                // Best-effort disconnect on teardown.
            }
        }
    }

    private JsonNode getJson(String uri, String bearerToken) throws Exception {
        String raw = restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .body(String.class);
        assertThat(raw).isNotBlank();
        return objectMapper.readTree(raw);
    }

    private JsonNode postJson(String uri, String bearerToken, Object body) throws Exception {
        RestClient.RequestBodySpec spec = restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            spec.header("Authorization", "Bearer " + bearerToken);
        }
        String raw = spec.body(body).retrieve().body(String.class);
        assertThat(raw).isNotBlank();
        return objectMapper.readTree(raw);
    }

    private void awaitBrokerSubscription(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        var probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        Awaitility.await().atMost(TIMEOUT)
                .until(() -> !broker.getSubscriptionRegistry().findSubscriptions(probe).isEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultyStorageConfig {
        @Bean
        @Primary
        EncryptedWalStore encryptedWalStore(PersistenceProperties properties, EncryptionKeyring keyring,
                RoomCoordinator roomCoordinator) {
            return new EncryptedWalStore(properties.getDirectory(), keyring, (point, roomId) -> {
                if (UNAVAILABLE_STORAGE.get()) {
                    throw new PersistenceException("injected transport-test storage outage");
                }
            }, roomCoordinator);
        }
    }
}
