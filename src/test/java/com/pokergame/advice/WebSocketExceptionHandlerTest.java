package com.pokergame.advice;

import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.RejectedAction;
import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.dto.response.PlayerNotificationResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameStateService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.exception.ResourceNotFoundException;
import java.lang.reflect.Method;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import com.pokergame.model.Room;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import tools.jackson.databind.ObjectMapper;

/** Client-observable private action error contract at the WebSocket exception boundary. */
@Tag("unit")
@DisplayName("WebSocket action errors")
class WebSocketExceptionHandlerTest {
    /** Delayed failures remain owned by their original membership across every feedback branch. */
    @ParameterizedTest
    @ValueSource(strings = {"action", "ready", "unready", "malformed", "other"})
    void delayedRetiredFrameCannotNotifyReplacementMembership(String kind) {
        var rooms = mock(RoomService.class);
        var room = new Room(gameId, "Room", "Host", 6, 5, 10, 100, null);
        room.addPlayer(playerName);
        PlayerPrincipal original = new PlayerPrincipal(playerName, gameId, room.getMembershipId(playerName));
        room.removePlayer(playerName);
        room.addPlayer(playerName);
        PlayerPrincipal replacement = new PlayerPrincipal(playerName, gameId, room.getMembershipId(playerName));
        Mockito.when(rooms.getRoom(gameId)).thenReturn(room);
        var realHandler = new WebSocketExceptionHandler(new GameStateService(rooms, messagingTemplate));
        String suffix = kind.equals("malformed") ? "action" : kind;
        String payload = kind.equals("malformed") ? "{\"action\":\"INVALID\"}" : "{\"action\":\"CHECK\"}";
        Message<String> frame = MessageBuilder.withPayload(payload)
                .setHeader("simpDestination", "/app/" + gameId + "/" + suffix).build();
        realHandler.handleMessageException(new RuntimeException("Delayed failure"), original, frame);
        verify(messagingTemplate).convertAndSendToUser(ArgumentMatchers.eq(original.getName()),
                ArgumentMatchers.eq("/queue/private"), ArgumentMatchers.any(Object.class));
        Mockito.verify(messagingTemplate, Mockito.never()).convertAndSendToUser(
                ArgumentMatchers.eq(replacement.getName()), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Object.class));
    }

    private SimpMessagingTemplate messagingTemplate;
    private WebSocketExceptionHandler handler;
    private final String gameId = "game-123";
    private final String playerName = "Player1";
    private final PlayerPrincipal principal = new PlayerPrincipal(playerName, gameId, "original-membership");

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        handler = new WebSocketExceptionHandler(new GameStateService(mock(RoomService.class), messagingTemplate));
    }

    /** Every supported rejected action is carried explicitly in private feedback. */
    @ParameterizedTest
    @EnumSource(PlayerAction.class)
    void eachRejectedActionCarriesCodeActionAndConciseMessage(PlayerAction action) {
        handler.handleMessageException(
                new UnauthorisedActionException("old detail", "NOT_YOUR_TURN", "Wait for your turn"),
                principal, actionFrame(action));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("NOT_YOUR_TURN", action, "Wait for your turn"));
    }

    /** The exact corrective values survive the handler without a generic length cap. */
    @Test
    void minimumRaiseCopyIsDeliveredWithoutTruncation() {
        String copy = "Min raise: 50; yours: 25";
        handler.handleMessageException(new BadRequestException("old detail", "MIN_RAISE", copy),
                principal, actionFrame(PlayerAction.RAISE));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("MIN_RAISE", PlayerAction.RAISE, copy));

        String longCopy = "A deliberately long rule description with relevant values 123456789 and 987654321 that exceeds eighty characters";
        handler.handleMessageException(new BadRequestException("old detail", "FUTURE_RULE", longCopy),
                principal, actionFrame(PlayerAction.BET));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("FUTURE_RULE", PlayerAction.BET, longCopy));
    }

    /** An invalid frame cannot produce a partially populated action error. */
    @Test
    void malformedActionIsPublishedAsTechnicalFeedback() {
        Message<String> frame = MessageBuilder.withPayload("{\"action\":\"PUSH\"}")
                .setHeader("simpDestination", "/app/" + gameId + "/action").build();
        handler.handleMessageException(new RuntimeException("malformed"), principal, frame);
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION, "malformed", playerName, gameId));
    }

    /** Ordinary transient readiness errors remain structured and retryable. */
    @Test
    void readinessRejectionKeepsTheRequestedActionAndMessage() {
        Message<String> frame = MessageBuilder.withPayload("{}")
                .setHeader("simpDestination", "/app/" + gameId + "/ready").build();
        handler.handleMessageException(new UnauthorisedActionException("Ready countdown is not active."),
                principal, frame);
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("ACTION_REJECTED", RejectedAction.READY, "Ready countdown is not active."));
    }

    /** Authentication still guards access to the private response path. */
    @Test
    void invalidPrincipalIsRejected() {
        assertThatThrownBy(() -> handler.handleMessageException(new RuntimeException("Error"),
                () -> "invalid", actionFrame(PlayerAction.CHECK)))
                .isInstanceOf(UnauthorisedActionException.class);
    }

    /** Builds a private action frame for the exception boundary. */
    private Message<String> actionFrame(PlayerAction action) {
        return MessageBuilder.withPayload("{\"action\":\"" + action + "\"}")
                .setHeader("simpDestination", "/app/" + gameId + "/action").build();
    }

    /**
     * Routing uses playerPrincipal.roomId() and playerName(), not the destination header.
     */
    @Test
    void routingUsesPrincipalRoomIdNotDestinationHeader() {
        Message<String> spoofedFrame = MessageBuilder.withPayload("{\"action\":\"BET\",\"amount\":100}")
                .setHeader("simpDestination", "/app/spoofed-game-999/action").build();

        handler.handleMessageException(
                new UnauthorisedActionException("old detail", "NOT_YOUR_TURN", "Wait for your turn"),
                principal, spoofedFrame);

        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("NOT_YOUR_TURN", PlayerAction.BET, "Wait for your turn"));
        verify(messagingTemplate, Mockito.never()).convertAndSendToUser(
                ArgumentMatchers.contains("spoofed-game-999"),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any());
    }

    /**
     * Cause chain traversal prefers nested ActionFeedbackException over outer MessageConversionException.
     */
    @Test
    void nestedActionFeedbackExceptionIsPreferredOverMessageConversion() {
        BadRequestException nested = new BadRequestException("Action amount cannot be negative",
                "NEGATIVE_AMOUNT", "Amount must be positive; yours: -10");
        MessageConversionException wrapped =
                new MessageConversionException("conversion failed", nested);

        handler.handleMessageException(wrapped, principal, actionFrame(PlayerAction.BET));

        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("NEGATIVE_AMOUNT", PlayerAction.BET, "Amount must be positive; yours: -10"));
    }

    /**
     * Cause chain traversal preserves AMOUNT_REQUIRED code.
     */
    @Test
    void nestedAmountRequiredInCauseChainIsPreserved() {
        BadRequestException nested = new BadRequestException("Bet/raise amount must be greater than 0",
                "AMOUNT_REQUIRED", "Enter a positive amount");
        RuntimeException wrapped = new RuntimeException("outer", nested);

        handler.handleMessageException(wrapped, principal, actionFrame(PlayerAction.BET));

        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("AMOUNT_REQUIRED", PlayerAction.BET, "Enter a positive amount"));
    }

    /**
     * Cause chain traversal preserves UNEXPECTED_AMOUNT code.
     */
    @Test
    void nestedUnexpectedAmountInCauseChainIsPreserved() {
        BadRequestException nested = new BadRequestException("No amount is needed for CALL actions",
                "UNEXPECTED_AMOUNT", "No amount needed; yours: 50");
        RuntimeException wrapped = new RuntimeException("outer", nested);

        handler.handleMessageException(wrapped, principal, actionFrame(PlayerAction.CALL));

        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("UNEXPECTED_AMOUNT", PlayerAction.CALL, "No amount needed; yours: 50"));
    }

    /**
     * Cause chain traversal preserves ResourceNotFoundException as GAME_NOT_FOUND.
     */
    @Test
    void nestedResourceNotFoundExceptionPreservesGameNotFound() {
        ResourceNotFoundException nested =
                new ResourceNotFoundException("Game not found");
        RuntimeException wrapped = new RuntimeException("outer", nested);

        handler.handleMessageException(wrapped, principal, actionFrame(PlayerAction.CHECK));

        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("GAME_NOT_FOUND", PlayerAction.CHECK, "Game unavailable"));
    }

    /**
     * Bean Validation failure on amount > 10,000,000 extracts exact validation message and uses INVALID_ACTION_FORMAT.
     */
    @Test
    void beanValidationFailureAboveMaxAmountReturnsInvalidActionFormatWithExactMessage() throws Exception {
        Message<String> frame = MessageBuilder.withPayload("{\"action\":\"RAISE\",\"amount\":10000001}")
                .setHeader("simpDestination", "/app/" + gameId + "/action").build();

        Method method = WebSocketExceptionHandlerTest.class.getDeclaredMethod("actionFrame", PlayerAction.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "actionRequest");
        bindingResult.addError(new FieldError(
                "actionRequest", "amount", "Amount cannot exceed 10,000,000"));
        MethodArgumentNotValidException validationException =
                new MethodArgumentNotValidException(
                        frame, methodParameter, bindingResult);

        handler.handleMessageException(validationException, principal, frame);

        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("INVALID_ACTION_FORMAT", PlayerAction.RAISE, "Amount cannot exceed 10,000,000"));
    }

    /**
     * Action is recovered from typed PlayerActionRequest payload when already deserialized.
     */
    @Test
    void typedRequestPayloadRecoversAction() {
        PlayerActionRequest request =
                new PlayerActionRequest(PlayerAction.FOLD, null);
        Message<PlayerActionRequest> frame = MessageBuilder.withPayload(request)
                .setHeader("simpDestination", "/app/" + gameId + "/action").build();

        handler.handleMessageException(new RuntimeException("processing failed"), principal, frame);

        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("ACTION_REJECTED", PlayerAction.FOLD, "Action unavailable"));
    }

    private static final String FALLBACK = "Enter a valid action amount";

    /**
     * Produces a genuine decoding failure from the configured messaging mapper by feeding it a
     * wrong-typed amount, so the classification matrix is anchored to the real
     * {@code tools.jackson} exception the broker surfaces rather than a name-only stand-in.
     */
    private static Throwable realJacksonDecodingFailure() {
        try {
            new ObjectMapper().readValue("{\"action\":\"RAISE\",\"amount\":\"not-a-number\"}",
                    PlayerActionRequest.class);
        } catch (RuntimeException decodingFailure) {
            return decodingFailure;
        }
        throw new AssertionError("Expected the configured mapper to reject a wrong-typed amount");
    }

    /** Builds a frame addressed to the given suffix of the principal's room. */
    private Message<?> frame(Object payload, String suffix) {
        return MessageBuilder.withPayload(payload)
                .setHeader("simpDestination", "/app/" + gameId + "/" + suffix).build();
    }

    /** Builds the parameter handle that Spring's argument-validation exceptions require. */
    private MethodParameter methodParameter() throws Exception {
        Method method = WebSocketExceptionHandlerTest.class.getDeclaredMethod("actionFrame", PlayerAction.class);
        return new MethodParameter(method, 0);
    }

    /** Builds a binding result holding one field error with the given message. */
    private BindingResult bindingResultWithError(String message) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "actionRequest");
        result.addError(new FieldError("actionRequest", "amount", message));
        return result;
    }

    /** Builds a binding result without any errors. */
    private BindingResult emptyBindingResult() {
        return new BeanPropertyBindingResult(new Object(), "actionRequest");
    }

    @SuppressWarnings("unchecked")
    private ConstraintViolation<Object> violation(String message) {
        ConstraintViolation<Object> violation = Mockito.mock(ConstraintViolation.class);
        Mockito.when(violation.getMessage()).thenReturn(message);
        return violation;
    }

    /** Builds a method-validation failure whose only error carries the given message. */
    private MethodValidationException methodValidationException(String message) {
        MethodValidationResult result = Mockito.mock(MethodValidationResult.class);
        List<ParameterValidationResult> parameterResults = List.of();
        if (message != null) {
            ParameterValidationResult parameterResult = Mockito.mock(ParameterValidationResult.class);
            Mockito.when(parameterResult.getResolvableErrors())
                    .thenReturn(List.of(new DefaultMessageSourceResolvable(new String[] { "code" }, null, message)));
            parameterResults = List.of(parameterResult);
        }
        Mockito.when(result.getParameterValidationResults()).thenReturn(parameterResults);
        return new MethodValidationException(result);
    }

    /** Runs the handler for an action frame and returns after the private feedback was sent. */
    private void verifyInvalidActionFormat(Exception exception, String expectedMessage) {
        handler.handleMessageException(exception, principal, actionFrame(PlayerAction.CHECK));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("INVALID_ACTION_FORMAT", PlayerAction.CHECK, expectedMessage));
    }

    /** The messaging argument-validation exception surfaces its first field error. */
    @Test
    @DisplayName("Messaging validation failure with a field error reports that field message")
    void messagingValidationFailureWithFieldErrorReportsFieldMessage() throws Exception {
        Message<?> frame = frame("{}", "action");
        verifyInvalidActionFormat(new MethodArgumentNotValidException(frame, methodParameter(),
                bindingResultWithError("Amount must be positive")), "Amount must be positive");
    }

    /** A messaging validation failure without a binding result falls back to the generic copy. */
    @Test
    @DisplayName("Messaging validation failure without binding result falls back to generic message")
    void messagingValidationFailureWithoutBindingResultFallsBack() throws Exception {
        verifyInvalidActionFormat(new MethodArgumentNotValidException(frame("{}", "action"), methodParameter()),
                FALLBACK);
    }

    /** A messaging validation failure without field errors falls back to the generic copy. */
    @Test
    @DisplayName("Messaging validation failure with empty binding result falls back to generic message")
    void messagingValidationFailureWithEmptyBindingResultFallsBack() throws Exception {
        verifyInvalidActionFormat(new MethodArgumentNotValidException(frame("{}", "action"), methodParameter(),
                emptyBindingResult()), FALLBACK);
    }

    /** The web argument-validation exception surfaces its first field error. */
    @Test
    @DisplayName("Web validation failure with a field error reports that field message")
    void webValidationFailureWithFieldErrorReportsFieldMessage() throws Exception {
        verifyInvalidActionFormat(new org.springframework.web.bind.MethodArgumentNotValidException(
                methodParameter(), bindingResultWithError("Amount too large")), "Amount too large");
    }

    /** A web validation failure whose binding result is null falls back to the generic copy. */
    @Test
    @DisplayName("Web validation failure with null binding result falls back to generic message")
    void webValidationFailureWithNullBindingResultFallsBack() {
        var exception = Mockito.mock(org.springframework.web.bind.MethodArgumentNotValidException.class);
        Mockito.when(exception.getBindingResult()).thenReturn(null);
        verifyInvalidActionFormat(exception, FALLBACK);
    }

    /** A web validation failure without field errors falls back to the generic copy. */
    @Test
    @DisplayName("Web validation failure with empty binding result falls back to generic message")
    void webValidationFailureWithEmptyBindingResultFallsBack() throws Exception {
        verifyInvalidActionFormat(new org.springframework.web.bind.MethodArgumentNotValidException(
                methodParameter(), emptyBindingResult()), FALLBACK);
    }

    /** A bind failure surfaces its first field error. */
    @Test
    @DisplayName("Bind failure with a field error reports that field message")
    void bindFailureWithFieldErrorReportsFieldMessage() {
        verifyInvalidActionFormat(new BindException(bindingResultWithError("Amount is not a number")),
                "Amount is not a number");
    }

    /** A bind failure without field errors falls back to the generic copy. */
    @Test
    @DisplayName("Bind failure without field errors falls back to generic message")
    void bindFailureWithoutFieldErrorsFallsBack() {
        verifyInvalidActionFormat(new BindException(emptyBindingResult()), FALLBACK);
    }

    /** A blank field message is treated like a missing one. */
    @Test
    @DisplayName("Blank field message falls back to generic message")
    void blankFieldMessageFallsBack() {
        verifyInvalidActionFormat(new BindException(bindingResultWithError("   ")), FALLBACK);
    }

    /** A constraint violation surfaces its message. */
    @Test
    @DisplayName("Constraint violation reports the violation message")
    void constraintViolationReportsViolationMessage() {
        verifyInvalidActionFormat(new ConstraintViolationException(Set.of(violation("Amount must be positive"))),
                "Amount must be positive");
    }

    /** A constraint violation exception without violations falls back to the generic copy. */
    @Test
    @DisplayName("Constraint violation exception without violations falls back to generic message")
    void constraintViolationExceptionWithoutViolationsFallsBack() {
        verifyInvalidActionFormat(new ConstraintViolationException(Set.of()), FALLBACK);
    }

    /** A constraint violation exception with a null violation set falls back to the generic copy. */
    @Test
    @DisplayName("Constraint violation exception with null violations falls back to generic message")
    void constraintViolationExceptionWithNullViolationsFallsBack() {
        verifyInvalidActionFormat(new ConstraintViolationException((Set<ConstraintViolation<?>>) null), FALLBACK);
    }

    /** A method-validation failure surfaces its first error message. */
    @Test
    @DisplayName("Method validation failure reports the first error message")
    void methodValidationFailureReportsFirstErrorMessage() {
        verifyInvalidActionFormat(methodValidationException("Amount must be positive"), "Amount must be positive");
    }

    /** A method-validation failure without errors falls back to the generic copy. */
    @Test
    @DisplayName("Method validation failure without errors falls back to generic message")
    void methodValidationFailureWithoutErrorsFallsBack() {
        verifyInvalidActionFormat(methodValidationException(null), FALLBACK);
    }

    /** Failure types that carry no extractable message still map to the generic invalid-format copy. */
    @ParameterizedTest
    @ValueSource(strings = {"conversion", "validation"})
    @DisplayName("Conversion-style failures without a message fall back to generic message")
    void conversionStyleFailuresWithoutMessageFallBack(String kind) {
        Exception exception = kind.equals("conversion")
                ? new MessageConversionException("cannot convert")
                : new ValidationException("invalid");
        verifyInvalidActionFormat(exception, FALLBACK);
    }

    /** The cause chain supplies a validation message when the outer conversion failure has none. */
    @Test
    @DisplayName("Validation message nested inside a conversion failure is reported")
    void nestedValidationMessageIsReported() {
        MessageConversionException wrapped = new MessageConversionException("conversion failed",
                new ConstraintViolationException(Set.of(violation("Too big"))));
        verifyInvalidActionFormat(wrapped, "Too big");
    }

    /**
     * A real {@code tools.jackson} decoding failure, surfaced the way the broker wraps it (inside a
     * {@link MessageConversionException}), is classified as an invalid action format. This anchors
     * the deep-cause branch to the concrete exception the configured mapper actually throws instead
     * of to a class whose name merely contains {@code JacksonException}.
     */
    @Test
    @DisplayName("Real Jackson decoding failure in the cause chain gives invalid action format")
    void jacksonFailureDeepInCauseChainIsInvalidActionFormat() {
        MessageConversionException chain =
                new MessageConversionException("conversion failed", realJacksonDecodingFailure());
        verifyInvalidActionFormat(chain, FALLBACK);
    }

    /** A cause chain that loops back on itself terminates and still classifies the failure. */
    @Test
    @DisplayName("Cyclic cause chain terminates and still classifies the failure")
    void cyclicCauseChainTerminates() {
        RuntimeException first = new RuntimeException("first");
        MessageConversionException second = new MessageConversionException("second");
        first.initCause(second);
        second.initCause(first);
        verifyInvalidActionFormat(first, FALLBACK);
    }

    /** A cause chain without any recognised failure keeps the generic rejection. */
    @Test
    @DisplayName("Cyclic cause chain without recognised failures keeps the generic rejection")
    void cyclicCauseChainWithoutRecognisedFailureKeepsGenericRejection() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);
        handler.handleMessageException(first, principal, actionFrame(PlayerAction.CALL));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("ACTION_REJECTED", PlayerAction.CALL, "Action unavailable"));
    }

    /** Deeply nested missing games map to GAME_NOT_FOUND. */
    @Test
    @DisplayName("Deeply nested ResourceNotFoundException gives GAME_NOT_FOUND")
    void deeplyNestedResourceNotFoundGivesGameNotFound() {
        RuntimeException chain = new RuntimeException("outer", new IllegalStateException("middle",
                new ResourceNotFoundException("Game not found")));
        handler.handleMessageException(chain, principal, actionFrame(PlayerAction.FOLD));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("GAME_NOT_FOUND", PlayerAction.FOLD, "Game unavailable"));
    }

    /** A missing game outranks a validation failure in the same chain. */
    @Test
    @DisplayName("ResourceNotFoundException outranks a validation failure in the same chain")
    void resourceNotFoundOutranksValidationFailure() {
        MessageConversionException wrapped = new MessageConversionException("conversion failed",
                new ResourceNotFoundException("Game not found"));
        handler.handleMessageException(wrapped, principal, actionFrame(PlayerAction.CHECK));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("GAME_NOT_FOUND", PlayerAction.CHECK, "Game unavailable"));
    }

    /** The outermost action-feedback exception in the chain wins. */
    @Test
    @DisplayName("Outermost action feedback in the chain wins")
    void outermostActionFeedbackWins() {
        BadRequestException inner = new BadRequestException("inner", "INNER_CODE", "Inner message");
        BadRequestException outer = new BadRequestException("outer", "OUTER_CODE", "Outer message");
        outer.initCause(inner);
        handler.handleMessageException(outer, principal, actionFrame(PlayerAction.BET));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("OUTER_CODE", PlayerAction.BET, "Outer message"));
    }

    /** Action feedback without a client message falls back to the exception message. */
    @Test
    @DisplayName("Action feedback without a client message uses the exception message")
    void actionFeedbackWithoutClientMessageUsesExceptionMessage() {
        handler.handleMessageException(new BadRequestException("Plain detail", "SOME_CODE", null),
                principal, actionFrame(PlayerAction.RAISE));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("SOME_CODE", PlayerAction.RAISE, "Plain detail"));
    }

    /** Action feedback without a code is ignored in favour of later classification. */
    @Test
    @DisplayName("Action feedback without a code is ignored")
    void actionFeedbackWithoutCodeIsIgnored() {
        BadRequestException codeless = new BadRequestException("No code");
        codeless.initCause(new MessageConversionException("conversion failed"));
        verifyInvalidActionFormat(codeless, FALLBACK);
    }

    /** Payload shapes from which the rejected action can be recovered. */
    static Stream<Object> recoverablePayloads() {
        return Stream.<Object>of(
                "{\"action\":\"FOLD\"}",
                "{\"action\":\"FOLD\",\"amount\":5}".getBytes(StandardCharsets.UTF_8));
    }

    /** The rejected action is recovered from string and byte[] payloads. */
    @ParameterizedTest
    @MethodSource("recoverablePayloads")
    @DisplayName("Rejected action is recovered from string and byte[] payloads")
    void rejectedActionIsRecoveredFromRawPayloads(Object payload) {
        handler.handleMessageException(new RuntimeException("boom"), principal, frame(payload, "action"));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("ACTION_REJECTED", PlayerAction.FOLD, "Action unavailable"));
    }

    /** Payload shapes from which no action can be recovered. */
    static Stream<Object> unrecoverablePayloads() {
        return Stream.<Object>of(
                "not json",
                "{bad".getBytes(StandardCharsets.UTF_8),
                "{\"action\":5}",
                "{\"action\":null}",
                "{\"action\":\"PUSH\"}",
                "{\"amount\":5}",
                "null",
                "[\"CHECK\"]",
                42);
    }

    /** Unrecoverable actions are reported as a plain private notification. */
    @ParameterizedTest
    @MethodSource("unrecoverablePayloads")
    @DisplayName("Unrecoverable action payload is reported as a private notification")
    void unrecoverableActionPayloadIsReportedAsNotification(Object payload) {
        handler.handleMessageException(new RuntimeException("boom"), principal, frame(payload, "action"));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION, "boom", playerName, gameId));
        verify(messagingTemplate, Mockito.times(1)).convertAndSendToUser(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class));
    }

    /** Missing or blank exception messages are replaced with a default. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Blank exception message is replaced with a default")
    void blankExceptionMessageIsReplacedWithDefault(String message) {
        handler.handleMessageException(new RuntimeException(message), principal, frame("{}", "other"));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION, "Request failed", playerName,
                        gameId));
    }

    /** A null exception message is replaced with a default. */
    @Test
    @DisplayName("Null exception message is replaced with a default")
    void nullExceptionMessageIsReplacedWithDefault() {
        handler.handleMessageException(new RuntimeException((String) null), principal, frame("{}", "other"));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION, "Request failed", playerName,
                        gameId));
    }

    /** A frame without a destination is treated as a non-action frame. */
    @Test
    @DisplayName("Frame without a destination is reported as a private notification")
    void frameWithoutDestinationIsReportedAsNotification() {
        Message<String> noDestination = MessageBuilder.withPayload("{}").build();
        handler.handleMessageException(new RuntimeException("boom"), principal, noDestination);
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION, "boom", playerName, gameId));
    }

    /** Messages longer than 80 characters are cut to 77 characters plus an ellipsis. */
    @Test
    @DisplayName("Message longer than 80 characters is truncated with an ellipsis")
    void messageLongerThanEightyCharactersIsTruncated() {
        String longMessage = "x".repeat(81);
        handler.handleMessageException(new RuntimeException(longMessage), principal, frame("{}", "other"));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION, "x".repeat(77) + "...",
                        playerName, gameId));
    }

    /** A message of exactly 80 characters is delivered unchanged. */
    @Test
    @DisplayName("Message of exactly 80 characters is not truncated")
    void messageOfExactlyEightyCharactersIsNotTruncated() {
        String message = "y".repeat(80);
        handler.handleMessageException(new RuntimeException(message), principal, frame("{}", "other"));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION, message, playerName, gameId));
    }

    /** Conversion failures and deserialization messages are replaced with friendly copy. */
    @ParameterizedTest
    @ValueSource(strings = {"conversion", "deserialize"})
    @DisplayName("Conversion and deserialization failures use friendly copy on non-action frames")
    void conversionFailuresUseFriendlyCopyOnNonActionFrames(String kind) {
        Exception exception = kind.equals("conversion")
                ? new MessageConversionException("raw conversion detail")
                : new RuntimeException("Cannot deserialize value of type int");
        handler.handleMessageException(exception, principal, frame("{}", "other"));
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION,
                        "Invalid action request format. Please try again with a valid amount.", playerName, gameId));
    }

    /** Readiness rejections share the same message normalisation as other frames. */
    @ParameterizedTest
    @ValueSource(strings = {"ready", "unready"})
    @DisplayName("Readiness rejection truncates long messages and keeps the requested action")
    void readinessRejectionTruncatesLongMessages(String suffix) {
        handler.handleMessageException(new RuntimeException("z".repeat(100)), principal, frame("{}", suffix));
        RejectedAction expected = suffix.equals("ready") ? RejectedAction.READY : RejectedAction.UNREADY;
        verify(messagingTemplate).convertAndSendToUser(principal.getName(), "/queue/private",
                new ActionErrorResponse("ACTION_REJECTED", expected, "z".repeat(77) + "..."));
    }
}
