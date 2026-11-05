package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.enums.RejectedAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Framework-boundary coverage for {@link com.pokergame.advice.WebSocketExceptionHandler}
 * (report findings 2 &amp; 8, ADR-0011). These cases exercise the real Spring/Jackson/STOMP
 * routing a client hits, so the private {@code /user/queue/private} payload a player actually
 * receives is asserted rather than the handler being invoked directly. They complement the
 * economical direct unit matrix in {@code WebSocketExceptionHandlerTest}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("WebSocket advice integration")
class WebSocketAdviceIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    private static final String HOST_NAME = "AdviceHost";
    private static final String OTHER_NAME = "AdviceOther";

    /**
     * A wrong-typed amount can only be produced by a genuine Jackson decoding failure, which the
     * broker wraps on its way to the handler. The client must observe the INVALID_ACTION_FORMAT
     * classification on its private queue, proving the real decode path (not a name-only stand-in)
     * is what the handler sees.
     */
    @Test
    @DisplayName("wrong-typed action amount yields INVALID_ACTION_FORMAT on the private queue")
    void wrongTypedAmount_PublishesInvalidActionFormatToPrivateQueue() throws Exception {
        TestGameSession gameSession = createStartedGame();
        gameLifecycleService.createGameFromRoom(gameSession.roomId());

        String currentPlayer = gameLifecycleService.getGame(gameSession.roomId()).getCurrentPlayer().getName();
        String actingToken = HOST_NAME.equals(currentPlayer) ? gameSession.hostToken() : gameSession.otherToken();

        // A raw-bytes converter so the wrong-typed JSON reaches the server verbatim; the shared
        // JacksonJsonMessageConverter would re-serialize a byte[] payload into a base64 string.
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new RawBytesMessageConverter());
        StompSession session = connectSession(stompClient, actingToken);
        try {
            CompletableFuture<ActionErrorResponse> errorFuture = subscribePrivate(session);

            // A string where an integer amount is expected forces tools.jackson to raise a real
            // InvalidFormatException during payload decoding.
            StompHeaders headers = new StompHeaders();
            headers.setDestination("/app/" + gameSession.roomId() + "/action");
            headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
            session.send(headers, "{\"action\":\"RAISE\",\"amount\":\"not-a-number\"}".getBytes(StandardCharsets.UTF_8));

            ActionErrorResponse error = errorFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("INVALID_ACTION_FORMAT");
            assertThat(error.action()).isEqualTo(RejectedAction.RAISE);
        } finally {
            session.disconnect();
            stompClient.stop();
        }
    }

    /**
     * Marking ready while the ready gate is closed (a freshly started game has no open countdown)
     * is rejected by the lifecycle service; the client must observe the readiness-shaped
     * ACTION_REJECTED payload on its private queue.
     */
    @Test
    @DisplayName("readiness rejection yields the ACTION_REJECTED readiness payload on the private queue")
    void readinessRejection_PublishesActionRejectedToPrivateQueue() throws Exception {
        TestGameSession gameSession = createStartedGame();
        gameLifecycleService.createGameFromRoom(gameSession.roomId());

        WebSocketStompClient stompClient = createStompClient();
        StompSession session = connectSession(stompClient, gameSession.hostToken());
        try {
            CompletableFuture<ActionErrorResponse> errorFuture = subscribePrivate(session);

            // No hand has ended, so the ready countdown is not active and the send is rejected.
            session.send("/app/" + gameSession.roomId() + "/ready", "");

            ActionErrorResponse error = errorFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("ACTION_REJECTED");
            assertThat(error.action()).isEqualTo(RejectedAction.READY);
            assertThat(error.message()).isEqualTo("Ready countdown is not active.");
        } finally {
            session.disconnect();
            stompClient.stop();
        }
    }

    /**
     * Subscribes to the membership-bound private queue and completes the returned future with the
     * first {@link ActionErrorResponse} delivered there.
     *
     * @param session connected player session
     * @return future resolved with the private-queue action error
     */
    private CompletableFuture<ActionErrorResponse> subscribePrivate(StompSession session) {
        CompletableFuture<ActionErrorResponse> errorFuture = new CompletableFuture<>();
        session.subscribe("/user/queue/private", new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return ActionErrorResponse.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (payload instanceof ActionErrorResponse response && !errorFuture.isDone()) {
                    errorFuture.complete(response);
                }
            }
        });
        return errorFuture;
    }

    /**
     * Creates a two-player room and returns the identifiers needed to drive STOMP scenarios.
     *
     * @return room identifier and both player tokens
     */
    private TestGameSession createStartedGame() {
        String roomId = roomService.createRoom(new CreateRoomRequest(
                uniqueName("AdviceRoom"), HOST_NAME, 6, 10, 20, 1000, null));
        roomService.joinRoom(new JoinRoomRequest(roomService.getRoom(roomId).getRoomName(), OTHER_NAME, null));

        return new TestGameSession(
                roomId,
                jwtService.generateToken(HOST_NAME, roomId, roomService.getRoom(roomId).getMembershipId(HOST_NAME)),
                jwtService.generateToken(OTHER_NAME, roomId, roomService.getRoom(roomId).getMembershipId(OTHER_NAME)));
    }

    /** Holds a room identifier and both player tokens for a game scenario. */
    private record TestGameSession(String roomId, String hostToken, String otherToken) {
    }

    /**
     * Sends outbound byte[] payloads verbatim so wrong-typed JSON reaches the server unmodified,
     * while delegating inbound deserialization to Jackson so subscribers still receive typed
     * {@link ActionErrorResponse} payloads.
     */
    private static final class RawBytesMessageConverter extends AbstractMessageConverter {

        private final JacksonJsonMessageConverter json = new JacksonJsonMessageConverter();

        private RawBytesMessageConverter() {
            super(MimeTypeUtils.APPLICATION_JSON);
        }

        @Override
        protected boolean supports(@NonNull Class<?> clazz) {
            return true;
        }

        @Override
        protected Object convertFromInternal(@NonNull Message<?> message, @NonNull Class<?> targetClass, Object conversionHint) {
            return json.fromMessage(message, targetClass);
        }

        @Override
        protected Object convertToInternal(@NonNull Object payload, MessageHeaders headers, Object conversionHint) {
            return payload instanceof byte[] raw ? raw : String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }
}
