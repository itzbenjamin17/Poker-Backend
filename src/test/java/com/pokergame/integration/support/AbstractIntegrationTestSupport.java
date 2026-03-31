package com.pokergame.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractIntegrationTestSupport {

    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    protected static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @LocalServerPort
    protected int port;

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected RestClient restClient;

    @BeforeEach
    void initialiseRestClient() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

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
        assertThat(data.path("playerName").asText()).isEqualTo(hostName);
        return data;
    }

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

    protected JsonNode startGame(String roomId, String hostToken) throws Exception {
        String body = restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    protected JsonNode readGameState(String gameId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

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

    protected WebSocketStompClient createStompClient() {
        List<Transport> transports = new ArrayList<>();
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));

        SockJsClient sockJsClient = new SockJsClient(transports);
        WebSocketStompClient client = new WebSocketStompClient(sockJsClient);

        List<MessageConverter> converters = new ArrayList<>();
        converters.add(new StringMessageConverter());
        converters.add(new JacksonJsonMessageConverter());
        client.setMessageConverter(new CompositeMessageConverter(converters));
        return client;
    }

    protected StompSession connectSession(WebSocketStompClient stompClient, String token) throws Exception {
        String wsUrl = "ws://localhost:" + port + "/ws";
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", FRONTEND_ORIGIN);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }

            @Override
            public void handleException(
                    @NonNull StompSession session,
                    StompCommand command,
                    @NonNull StompHeaders headers,
                    byte @NonNull [] payload,
                    @NonNull Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }

            @Override
            public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        });

        return sessionFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    protected WebSocketHttpHeaders createHandshakeHeaders() {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", FRONTEND_ORIGIN);
        return handshakeHeaders;
    }

    protected String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
