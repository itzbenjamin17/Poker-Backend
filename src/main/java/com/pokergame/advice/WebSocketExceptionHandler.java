package com.pokergame.advice;

import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.PlayerAction;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.exception.ActionFeedbackException;
import com.pokergame.exception.ResourceNotFoundException;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Global advice for handling exceptions occurring during WebSocket message processing.
 */
@ControllerAdvice
public class WebSocketExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketExceptionHandler.class);

    private final GameStateService gameStateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        logger.warn("WebSocket failure for player {} in game {}: {}", playerName, gameId, exception.getMessage());
        if (gameStateService == null) return;

        if (destination == null || !destination.endsWith("/action")) {
            gameStateService.sendPrivatePlayerNotification(gameId, playerName,
                    technicalMessage(exception), ResponseMessage.PLAYER_NOTIFICATION);
            return;
        }

        PlayerAction action = rejectedAction(message);
        if (action == null) {
            logger.warn("Cannot publish ACTION_ERROR without a supported rejected action");
            gameStateService.sendPrivatePlayerNotification(gameId, playerName,
                    technicalMessage(exception), ResponseMessage.PLAYER_NOTIFICATION);
            return;
        }

        String code = "ACTION_REJECTED";
        String displayMessage = "Action unavailable";
        if (exception instanceof ActionFeedbackException details && details.actionErrorCode() != null) {
            code = details.actionErrorCode();
            displayMessage = details.actionErrorMessage();
        } else if (exception instanceof ResourceNotFoundException) {
            code = "GAME_NOT_FOUND";
            displayMessage = "Game unavailable";
        } else if (exception instanceof MessageConversionException) {
            code = "INVALID_ACTION_FORMAT";
            displayMessage = "Enter a valid action amount";
        }
        gameStateService.sendActionError(gameId, playerName, code, action, displayMessage);
    }

    /** Recovers the requested action from the original frame without guessing from exception text. */
    private PlayerAction rejectedAction(Message<?> message) {
        Object payload = message.getPayload();
        if (payload instanceof PlayerActionRequest request) return request.action();
        try {
            String json = payload instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(payload);
            Map<?, ?> fields = objectMapper.readValue(json, Map.class);
            Object value = fields.get("action");
            return value instanceof String action ? PlayerAction.valueOf(action) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Retains technical feedback for frames without a supported poker action. */
    private String technicalMessage(Exception exception) {
        String message = exception.getMessage();
        if (exception instanceof MessageConversionException ||
                (message != null && message.contains("Cannot deserialize"))) {
            message = "Invalid action request format. Please try again with a valid amount.";
        }
        if (message == null || message.isBlank()) message = "Request failed";
        return message.length() > 80 ? message.substring(0, 77) + "..." : message;
    }
}
