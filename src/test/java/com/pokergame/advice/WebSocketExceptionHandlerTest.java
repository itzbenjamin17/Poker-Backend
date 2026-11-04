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
}
