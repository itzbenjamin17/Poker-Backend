package com.pokergame.exception;

import com.pokergame.enums.ResponseMessage;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WebSocketExceptionHandler}.
 */
@Tag("unit")
@DisplayName("WebSocketExceptionHandler")
class WebSocketExceptionHandlerTest {

    private GameStateService gameStateService;
    private WebSocketExceptionHandler handler;

    private final String gameId = "game-123";
    private final String playerName = "Player1";
    private PlayerPrincipal principal;

    @BeforeEach
    void setUp() {
        gameStateService = mock(GameStateService.class);
        handler = new WebSocketExceptionHandler(gameStateService);
        principal = new PlayerPrincipal(playerName, gameId);
    }

    @Test
    @DisplayName("WS handleMessageException - Sanitizes Technical Errors")
    void handleMessageException_SanitizesJacksonErrors() {
        Exception technicalException = new org.springframework.messaging.converter.MessageConversionException("Cannot deserialize something technical");
        Message<String> message = MessageBuilder.withPayload("bad payload")
                .setHeader("simpDestination", "/app/" + gameId + "/action")
                .build();

        handler.handleMessageException(technicalException, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq(gameId),
                eq(playerName),
                eq("Invalid action request format. Please try again with a valid amount."),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Truncates Long Messages")
    void handleMessageException_TruncatesLongMessages() {
        String longMessage = "This is a very long error message that should definitely be truncated because it exceeds the eighty character limit that we have established in the controller logic.";
        Exception ex = new RuntimeException(longMessage);
        Message<String> message = MessageBuilder.withPayload("info")
                .setHeader("simpDestination", "/app/" + gameId + "/action")
                .build();

        handler.handleMessageException(ex, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq(gameId),
                eq(playerName),
                argThat(msg -> msg.length() <= 80 && msg.endsWith("...")),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Null Destination")
    void handleMessageException_NullDestination() {
        Exception ex = new RuntimeException("Test error");
        Message<String> message = MessageBuilder.withPayload("payload").build();

        handler.handleMessageException(ex, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq("unknown"),
                eq(playerName),
                eq("Test error"),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Short Destination")
    void handleMessageException_ShortDestination() {
        Exception ex = new RuntimeException("Test error");
        Message<String> message = MessageBuilder.withPayload("payload")
                .setHeader("simpDestination", "/app")
                .build();

        handler.handleMessageException(ex, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq("unknown"),
                eq(playerName),
                eq("Test error"),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Exact 80 Characters")
    void handleMessageException_Exact80Characters() {
        String exact80Message = "12345678901234567890123456789012345678901234567890123456789012345678901234567890";
        Exception ex = new RuntimeException(exact80Message);
        Message<String> message = MessageBuilder.withPayload("payload")
                .setHeader("simpDestination", "/app/" + gameId + "/action")
                .build();

        handler.handleMessageException(ex, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq(gameId),
                eq(playerName),
                eq(exact80Message),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Handles Authentication Wrapper")
    void handleMessageException_HandlesAuthenticationWrapper() {
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());
        Exception ex = new RuntimeException("Auth wrapped error");
        Message<String> message = MessageBuilder.withPayload("payload")
                .setHeader("simpDestination", "/app/" + gameId + "/action")
                .build();

        handler.handleMessageException(ex, auth, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq(gameId),
                eq(playerName),
                eq("Auth wrapped error"),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Invalid Principal Throws UnauthorisedActionException")
    void handleMessageException_InvalidPrincipalThrows() {
        java.security.Principal invalidPrincipal = () -> "invalid";
        Exception ex = new RuntimeException("Error");
        Message<String> message = MessageBuilder.withPayload("payload").build();

        assertThatThrownBy(() -> handler.handleMessageException(ex, invalidPrincipal, message))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Invalid authentication principal in WebSocket");
    }
}
