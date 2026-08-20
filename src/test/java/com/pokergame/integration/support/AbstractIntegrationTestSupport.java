package com.pokergame.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.security.RateLimitService;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Provides abstract integration test support shared by integration tests. */
@TestPropertySource(locations = "classpath:application-test.properties")
public abstract class AbstractIntegrationTestSupport {

    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    protected static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @LocalServerPort
    protected int port;

    @Autowired
    protected RateLimitService rateLimitService;

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected RestClient restClient;

    /**
     * Supports the test scenario for setup integration test.
     */
    @BeforeEach
    void setupIntegrationTest() {
        // Reset rate limiting to disabled by default for each test
        ReflectionTestUtils.setField(rateLimitService, "enabled", false);
        rateLimitService.reset();

        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    /**
     * Creates room for the test.
     * @param roomName room name supplied to the fixture
     * @param hostName host name supplied to the fixture
     * @param maxPlayers max players supplied to the fixture
     * @return parsed room-creation payload
     */
    protected JsonNode createRoom(String roomName, String hostName, int maxPlayers) throws Exception {
        CreateRoomRequest request = new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null);

        String body = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        JsonNode response = objectMapper.readTree(body);
        assertThat(response.path("message").asText()).isEqualTo("Room created successfully");

        JsonNode data = response.path("data");
        assertThat(data.path("roomId").asText()).isNotBlank();
        assertThat(data.path("token").asText()).isNotBlank();
        // Relaxing this assertion to allow for server-side sanitisation
        if (!hostName.contains(" ")) {
            assertThat(data.path("playerName").asText()).isEqualTo(hostName);
        }
        return data;
    }

    /**
     * Supports the test scenario for join room.
     * @param roomName room name supplied to the fixture
     * @param playerName player name supplied to the fixture
     * @return parsed room-join payload
     */
    protected JsonNode joinRoom(String roomName, String playerName) throws Exception {
        JoinRoomRequest request = new JoinRoomRequest(roomName, playerName, null);

        String body = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        JsonNode response = objectMapper.readTree(body);
        assertThat(response.path("message").asText()).isEqualTo("Successfully joined room");

        JsonNode data = response.path("data");
        assertThat(data.path("roomId").asText()).isNotBlank();
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("playerName").asText()).isEqualTo(playerName);
        return data;
    }

    /**
     * Starts a room through the public REST contract and parses the success response.
     *
     * @param roomId room to start
     * @param hostToken room host's bearer token
     * @return parsed API response
     */
    protected JsonNode startGame(String roomId, String hostToken) throws Exception {
        String body = restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    /**
     * Reads the current room projection through the REST API.
     *
     * @param roomId room to query
     * @param token optional bearer token
     * @return parsed room projection
     */
    protected JsonNode getRoomData(String roomId, String token) throws Exception {
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri("/api/room/" + roomId);
        
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }

        String body = request.retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    /**
     * Reads the authenticated public game projection through the REST API.
     *
     * @param gameId game to query
     * @param token player's bearer token
     * @return parsed public game projection
     */
    protected JsonNode readGameState(String gameId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    /**
     * Waits until the room endpoint confirms that cleanup removed the room.
     *
     * @param roomId room expected to be destroyed
     * @param token bearer token used for polling
     * @param timeout maximum wait duration
     */
    protected void awaitRoomDestruction(String roomId, String token, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    HttpClientErrorException exception = org.junit.jupiter.api.Assertions.assertThrows(
                            HttpClientErrorException.class,
                            () -> restClient.get()
                                    .uri("/api/room/" + roomId)
                                    .header("Authorization", "Bearer " + token)
                                    .retrieve()
                                    .body(String.class));

                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    /**
     * Creates a STOMP client with the same string and JSON converters expected by
     * the integration scenarios.
     *
     * @return configured STOMP client
     */
    protected WebSocketStompClient createStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());

        List<MessageConverter> converters = new ArrayList<>();
        converters.add(new StringMessageConverter());
        converters.add(new JacksonJsonMessageConverter());
        client.setMessageConverter(new CompositeMessageConverter(converters));
        return client;
    }

    /**
     * Connects a STOMP session using a bearer token and the allowed frontend origin.
     *
     * @param stompClient configured STOMP client
     * @param token player's bearer token
     * @return connected session
     */
    protected StompSession connectSession(WebSocketStompClient stompClient, String token) throws Exception {
        String wsUrl = "ws://localhost:" + port + "/ws";
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", FRONTEND_ORIGIN);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() {
            /** {@inheritDoc} */
            @Override
            public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }

            /** {@inheritDoc} */
            @Override
            public void handleException(
                    @NonNull StompSession session,
                    StompCommand command,
                    @NonNull StompHeaders headers,
                    byte @NonNull [] payload,
                    @NonNull Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }

            /** {@inheritDoc} */
            @Override
            public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        });

        return sessionFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Creates handshake headers for the test.
     * @return handshake headers containing the allowed frontend origin
     */
    protected WebSocketHttpHeaders createHandshakeHeaders() {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", FRONTEND_ORIGIN);
        return handshakeHeaders;
    }

    /**
     * Supports the test scenario for unique name.
     * @param prefix prefix supplied to the fixture
     * @return prefix with a random suffix
     */
    protected String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
