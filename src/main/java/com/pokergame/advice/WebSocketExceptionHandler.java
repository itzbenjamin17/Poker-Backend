package com.pokergame.advice;

import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.RejectedAction;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.exception.ActionFeedbackException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameStateService;
import com.pokergame.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Centralized exception handler for STOMP-over-WebSocket messaging traffic.
 *
 * <h3>Why this class is needed</h3>
 * <p>
 * Unlike REST endpoints (handled by {@code ControllerExceptionHandler}),
 * WebSocket
 * connections cannot return HTTP status codes (400, 403, 404) once the
 * connection is
 * established. Instead, exceptions occurring during STOMP frame processing must
 * be caught,
 * translated into domain-specific payloads, and pushed to the offending
 * player's private
 * queue ({@code /queue/private}) so other players at the table are not
 * disrupted.
 * </p>
 *
 * <h3>Security Guarantee</h3>
 * <p>
 * Routing is strictly anchored to the authenticated {@link PlayerPrincipal}
 * session identity,
 * completely ignoring any client-spoofed destination headers.
 * </p>
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
    public WebSocketExceptionHandler(GameStateService gameStateService) {
        this.gameStateService = gameStateService;
    }

    /**
     * Handles exceptions occurring during WebSocket message processing.
     * Propagates errors back to the specific initiating player via their private
     * channel.
     *
     * @param exception message-processing failure to translate
     * @param principal player that sent the failed message
     * @param message   original message used to recover the game destination
     */
    @MessageExceptionHandler
    public void handleMessageException(Exception exception, Principal principal,
            Message<?> message) {
        PlayerPrincipal playerPrincipal = SecurityUtils.getPlayer(principal);
        String playerName = playerPrincipal.playerName();
        String gameId = playerPrincipal.roomId();

        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        String destination = accessor.getDestination();

        logger.warn("WebSocket failure for player {} in game {}: {}", playerName, gameId, exception.getMessage());

        if (destination != null && (destination.endsWith("/ready") || destination.endsWith("/unready"))) {
            RejectedAction readinessAction = destination.endsWith("/ready") ? RejectedAction.READY
                    : RejectedAction.UNREADY;
            gameStateService.sendActionError(playerPrincipal, "ACTION_REJECTED", readinessAction,
                    technicalMessage(exception));
            return;
        }

        if (destination == null || !destination.endsWith("/action")) {
            gameStateService.sendPrivatePlayerNotification(playerPrincipal,
                    technicalMessage(exception), ResponseMessage.PLAYER_NOTIFICATION);
            return;
        }

        PlayerAction action = rejectedAction(message);
        if (action == null) {
            logger.warn("Cannot publish ACTION_ERROR without a supported rejected action");
            gameStateService.sendPrivatePlayerNotification(playerPrincipal,
                    technicalMessage(exception), ResponseMessage.PLAYER_NOTIFICATION);
            return;
        }

        ActionFeedbackException actionFeedback = null;
        ResourceNotFoundException resourceNotFound = null;
        boolean validationOrConversion = false;
        String validationMessage = null;

        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable cause = exception;
        while (cause != null && visited.add(cause)) {
            if (actionFeedback == null && cause instanceof ActionFeedbackException afe
                    && afe.actionErrorCode() != null) {
                actionFeedback = afe;
            }
            if (resourceNotFound == null && cause instanceof ResourceNotFoundException rnfe) {
                resourceNotFound = rnfe;
            }
            if (isValidationOrConversionFailure(cause)) {
                validationOrConversion = true;
                if (validationMessage == null) {
                    validationMessage = extractValidationMessage(cause);
                }
            }
            cause = cause.getCause();
        }

        String code = "ACTION_REJECTED";
        String displayMessage = "Action unavailable";

        if (actionFeedback != null) {
            code = actionFeedback.actionErrorCode();
            displayMessage = actionFeedback.actionErrorMessage() != null ? actionFeedback.actionErrorMessage()
                    : actionFeedback.getMessage();
        } else if (resourceNotFound != null) {
            code = "GAME_NOT_FOUND";
            displayMessage = "Game unavailable";
        } else if (validationOrConversion) {
            code = "INVALID_ACTION_FORMAT";
            displayMessage = (validationMessage != null && !validationMessage.isBlank())
                    ? validationMessage
                    : "Enter a valid action amount";
        }

        gameStateService.sendActionError(playerPrincipal, code, action, displayMessage);
    }

    private boolean isValidationOrConversionFailure(Throwable throwable) {
        return throwable instanceof MessageConversionException
                || throwable instanceof org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException
                || throwable instanceof org.springframework.web.bind.MethodArgumentNotValidException
                || throwable instanceof org.springframework.validation.BindException
                || throwable instanceof jakarta.validation.ValidationException
                || throwable instanceof org.springframework.validation.method.MethodValidationException
                || (throwable != null && throwable.getClass().getName().contains("JacksonException"));
    }

    private String extractValidationMessage(Throwable throwable) {
        if (throwable instanceof org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException e) {
            if (e.getBindingResult() != null && e.getBindingResult().getFieldError() != null) {
                return e.getBindingResult().getFieldError().getDefaultMessage();
            }
        }
        if (throwable instanceof org.springframework.web.bind.MethodArgumentNotValidException e) {
            if (e.getBindingResult() != null && e.getBindingResult().getFieldError() != null) {
                return e.getBindingResult().getFieldError().getDefaultMessage();
            }
        }
        if (throwable instanceof org.springframework.validation.BindException e) {
            if (e.getFieldError() != null) {
                return e.getFieldError().getDefaultMessage();
            }
        }
        if (throwable instanceof jakarta.validation.ConstraintViolationException e) {
            if (e.getConstraintViolations() != null && !e.getConstraintViolations().isEmpty()) {
                return e.getConstraintViolations().iterator().next().getMessage();
            }
        }
        if (throwable instanceof org.springframework.validation.method.MethodValidationException e) {
            if (e.getAllErrors() != null && !e.getAllErrors().isEmpty()) {
                return e.getAllErrors().get(0).getDefaultMessage();
            }
        }
        return null;
    }

    /**
     * Recovers the requested action from the original frame without guessing from
     * exception text.
     */
    private PlayerAction rejectedAction(Message<?> message) {
        Object payload = message.getPayload();
        if (payload instanceof PlayerActionRequest request)
            return request.action();
        try {
            String json = payload instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : String.valueOf(payload);
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
        if (message == null || message.isBlank())
            message = "Request failed";
        return message.length() > 80 ? message.substring(0, 77) + "..." : message;
    }
}
