package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request-boundary coverage for invalid player-action frames (findings 4 & 6, ADR-0011).
 *
 * <p>These cases exercise what a real client receives when it sends an invalid action over STOMP.
 * They complement the service-level unit tests in {@code PlayerActionServiceTest}, whose
 * impossible-DTO cases can only be reached with a mocked record. The amount combinations tested
 * here (a non-zero amount on a CALL, a zero amount on a BET/RAISE) are rejected by the
 * {@code PlayerActionRequest} record constructor during payload decoding, so the only way to
 * deliver them is as raw JSON bytes. The decode failure is classified by
 * {@code WebSocketExceptionHandler} into the client {@code ActionErrorResponse} code/message
 * asserted below, and the authoritative game must be left unchanged.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Player action boundary integration")
class PlayerActionBoundaryIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    private static final String HOST_NAME = "BoundaryHost";
    private static final String OTHER_NAME = "BoundaryOther";

    /** Each case is a human label, the raw JSON frame, and the expected client code and message. */
    private static Stream<Arguments> invalidActionFrames() {
        return Stream.of(
                // A CALL carrying a negative amount: the record rejects a non-zero amount on a
                // non-betting action with UNEXPECTED_AMOUNT before the service is ever reached.
                Arguments.of("CALL with a negative amount",
                        "{\"action\":\"CALL\",\"amount\":-5}",
                        "UNEXPECTED_AMOUNT", "No amount needed; yours: -5"),
                // A BET of zero: the record rejects a non-positive BET/RAISE amount. Zero is not
                // negative, so the code is AMOUNT_REQUIRED; the detail still reports the value.
                Arguments.of("BET of zero",
                        "{\"action\":\"BET\",\"amount\":0}",
                        "AMOUNT_REQUIRED", "Amount must be positive; yours: 0"),
                // A RAISE of zero: same request-boundary rule as BET.
                Arguments.of("RAISE of zero",
                        "{\"action\":\"RAISE\",\"amount\":0}",
                        "AMOUNT_REQUIRED", "Amount must be positive; yours: 0"),
                // A negative BET amount is reported as NEGATIVE_AMOUNT by the record constructor.
                Arguments.of("BET with a negative amount",
                        "{\"action\":\"BET\",\"amount\":-10}",
                        "NEGATIVE_AMOUNT", "Amount must be positive; yours: -10"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidActionFrames")
    @DisplayName("an invalid action frame yields a structured client error and leaves the game unchanged")
    void invalidActionFrame_PublishesClientErrorAndLeavesGameUnchanged(
            String label, String rawJson, String expectedCode, String expectedMessage) throws Exception {
        TestGameSession gameSession = createStartedGame();
        gameLifecycleService.createGameFromRoom(gameSession.roomId());

        String currentPlayer = gameLifecycleService.getGame(gameSession.roomId()).getCurrentPlayer().getName();
        String actingToken = HOST_NAME.equals(currentPlayer) ? gameSession.hostToken() : gameSession.otherToken();

        // Snapshot the authoritative state through the public REST projection before acting. The
        // /state endpoint returns the PublicGameStateResponse directly (no data envelope).
        JsonNode stateBefore = readGameState(gameSession.roomId(), actingToken);
        String currentPlayerBefore = stateBefore.path("currentPlayerName").asText();
        String phaseBefore = stateBefore.path("phase").asText();
        int potBefore = stateBefore.path("pot").asInt();

        // A raw-bytes converter so the malformed JSON reaches the server verbatim. The shared
        // JacksonJsonMessageConverter would re-serialize a byte[] payload into a base64 string.
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new RawBytesMessageConverter());
        StompSession session = connectSession(stompClient, actingToken);
        try {
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

            // Send raw JSON bytes so the invalid amount combination reaches payload decoding; a
            // typed PlayerActionRequest cannot be constructed for these combinations.
            StompHeaders headers = new StompHeaders();
            headers.setDestination("/app/" + gameSession.roomId() + "/action");
            headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
            session.send(headers, rawJson.getBytes(StandardCharsets.UTF_8));

            ActionErrorResponse error = errorFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).as(label).isEqualTo(expectedCode);
            assertThat(error.message()).as(label).isEqualTo(expectedMessage);
        } finally {
            session.disconnect();
            stompClient.stop();
        }

        // The rejected frame must not have mutated the authoritative game.
        JsonNode stateAfter = readGameState(gameSession.roomId(), actingToken);
        assertThat(stateAfter.path("currentPlayerName").asText()).isEqualTo(currentPlayerBefore);
        assertThat(stateAfter.path("phase").asText()).isEqualTo(phaseBefore);
        assertThat(stateAfter.path("pot").asInt()).isEqualTo(potBefore);
    }

    /**
     * Creates a two-player room, starts its game, and returns the identifiers needed to drive
     * STOMP scenarios.
     *
     * @return room identifier and both player tokens
     */
    private TestGameSession createStartedGame() {
        String roomId = roomService.createRoom(new CreateRoomRequest(
                uniqueName("BoundaryRoom"), HOST_NAME, 6, 10, 20, 1000, null));
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
     * Sends outbound byte[] payloads verbatim so malformed JSON reaches the server unmodified,
     * while delegating inbound deserialization to Jackson so subscribers still receive typed
     * {@link ActionErrorResponse} payloads. Extending {@link AbstractMessageConverter} preserves
     * the STOMP header accessor the client codec requires.
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
        protected Object convertToInternal(@NonNull Object payload, org.springframework.messaging.MessageHeaders headers, Object conversionHint) {
            return payload instanceof byte[] raw ? raw : String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }
}
