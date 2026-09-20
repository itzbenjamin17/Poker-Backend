package com.pokergame.advice;

import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.TooManyRequestsException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.exception.PokerException;

import com.pokergame.enums.ResponseMessage;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameStateService;
import com.pokergame.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.security.Principal;

/**
 * Global advice for handling exceptions occurring during WebSocket message processing.
 */
@ControllerAdvice
public class WebSocketExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketExceptionHandler.class);

    private final GameStateService gameStateService;

    /**
     * Creates the WebSocket exception handler with the game state service.
     *
     * @param gameStateService creates and publishes client notifications
     */
    public WebSocketExceptionHandler(@Autowired(required = false) GameStateService gameStateService) {
        this.gameStateService = gameStateService;
    }

    /**
     * Handles exceptions occurring during WebSocket message processing.
     * Propagates errors back to the specific initiating player via their private
     * channel.
     *
     * @param exception message-processing failure to translate
     * @param principal player that sent the failed message
     * @param message original message used to recover the game destination
     */
    @MessageExceptionHandler
    public void handleMessageException(Exception exception, Principal principal,
            Message<?> message) {
        PlayerPrincipal playerPrincipal = SecurityUtils.getPlayer(principal);
        String playerName = playerPrincipal.playerName();

        // Extract gameId from the destination header manually
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        String destination = accessor.getDestination();
        String gameId = "unknown";

        if (destination != null) {
            String[] parts = destination.split("/");
            if (parts.length >= 3) {
                gameId = parts[2];
            }
        }

        String userFriendlyMessage = exception.getMessage();

        // Sanitise technical messages like Jackson deserialization errors
        if (exception instanceof MessageConversionException ||
                (userFriendlyMessage != null && userFriendlyMessage.contains("Cannot deserialize"))) {
            userFriendlyMessage = "Invalid action request format. Please try again with a valid amount.";
            logger.warn("Technical WebSocket Action Error (sanitized) for {} in game {}: {}", playerName, gameId,
                    exception.getMessage());
        } else {
            logger.warn("WebSocket Action Error for player {} in game {}: {}", playerName, gameId, userFriendlyMessage);
        }

        // Ensure error messages are not too long
        if (userFriendlyMessage != null && userFriendlyMessage.length() > 80) {
            userFriendlyMessage = userFriendlyMessage.substring(0, 77) + "...";
        }

        if (gameStateService != null) {
            gameStateService.sendPrivatePlayerNotification(gameId, playerName, userFriendlyMessage,
                    ResponseMessage.ACTION_ERROR);
        }
    }
}
