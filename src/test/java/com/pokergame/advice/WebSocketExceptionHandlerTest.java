package com.pokergame.advice;

import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ResponseMessage;
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

/** Client-observable private action error contract at the WebSocket exception boundary. */
@Tag("unit")
@DisplayName("WebSocket action errors")
class WebSocketExceptionHandlerTest {
    private SimpMessagingTemplate messagingTemplate;
    private WebSocketExceptionHandler handler;
    private final String gameId = "game-123";
    private final String playerName = "Player1";
    private final PlayerPrincipal principal = new PlayerPrincipal(playerName, gameId);

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
        verify(messagingTemplate).convertAndSendToUser(playerName + ":" + gameId, "/queue/private",
                new ActionErrorResponse("NOT_YOUR_TURN", action, "Wait for your turn"));
    }

    /** The exact corrective values survive the handler without a generic length cap. */
    @Test
    void minimumRaiseCopyIsDeliveredWithoutTruncation() {
        String copy = "Min raise: 50; yours: 25";
        handler.handleMessageException(new BadRequestException("old detail", "MIN_RAISE", copy),
                principal, actionFrame(PlayerAction.RAISE));
        verify(messagingTemplate).convertAndSendToUser(playerName + ":" + gameId, "/queue/private",
                new ActionErrorResponse("MIN_RAISE", PlayerAction.RAISE, copy));

        String longCopy = "A deliberately long rule description with relevant values 123456789 and 987654321 that exceeds eighty characters";
        handler.handleMessageException(new BadRequestException("old detail", "FUTURE_RULE", longCopy),
                principal, actionFrame(PlayerAction.BET));
        verify(messagingTemplate).convertAndSendToUser(playerName + ":" + gameId, "/queue/private",
                new ActionErrorResponse("FUTURE_RULE", PlayerAction.BET, longCopy));
    }

    /** An invalid frame cannot produce a partially populated action error. */
    @Test
    void malformedActionIsPublishedAsTechnicalFeedback() {
        Message<String> frame = MessageBuilder.withPayload("{\"action\":\"PUSH\"}")
                .setHeader("simpDestination", "/app/" + gameId + "/action").build();
        handler.handleMessageException(new RuntimeException("malformed"), principal, frame);
        verify(messagingTemplate).convertAndSendToUser(playerName + ":" + gameId, "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION, "malformed", playerName, gameId));
    }

    /** Non-action failures retain their useful technical feedback. */
    @Test
    void readyFailureKeepsItsMessage() {
        Message<String> frame = MessageBuilder.withPayload("{}")
                .setHeader("simpDestination", "/app/" + gameId + "/ready").build();
        handler.handleMessageException(new BadRequestException("Ready countdown is not active."), principal, frame);
        verify(messagingTemplate).convertAndSendToUser(playerName + ":" + gameId, "/queue/private",
                new PlayerNotificationResponse(ResponseMessage.PLAYER_NOTIFICATION,
                        "Ready countdown is not active.", playerName, gameId));
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
}
