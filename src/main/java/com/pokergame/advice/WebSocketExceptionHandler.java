package com.pokergame.advice;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.RejectedAction;
import com.pokergame.exception.ActionFeedbackException;
import com.pokergame.exception.PokerException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameStateService;
import com.pokergame.util.SecurityUtils;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.validation.BindException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Centralized exception handler for STOMP-over-WebSocket messaging traffic.
 *
 * <h2>Why this class is needed</h2>
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
 * <h2>Security Guarantee</h2>
 * <p>
 * Routing is strictly anchored to the authenticated {@link PlayerPrincipal}
 * session identity,
 * completely ignoring any client-spoofed destination headers.
 * </p>
 */
@ControllerAdvice
public class WebSocketExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketExceptionHandler.class);
    private static final String READINESS_FALLBACK_MESSAGE = "Could not update readiness. Please try again.";
    private static final String REQUEST_FALLBACK_MESSAGE = "Request failed. Please try again.";
    private static final String INVALID_FORMAT_FALLBACK_MESSAGE =
            "Invalid action request format. Please try again with a valid amount.";
    private static final int MAX_NOTIFICATION_LENGTH = 80;

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
                    userFriendlyMessage(exception, READINESS_FALLBACK_MESSAGE, false));
            return;
        }

        if (destination == null || !destination.endsWith("/action")) {
            gameStateService.sendPrivatePlayerNotification(playerPrincipal,
                    userFriendlyMessage(exception, REQUEST_FALLBACK_MESSAGE, true));
            return;
        }

        PlayerAction action = rejectedAction(message);
        if (action == null) {
            logger.warn("Cannot publish ACTION_ERROR without a supported rejected action");
            gameStateService.sendPrivatePlayerNotification(playerPrincipal,
                    userFriendlyMessage(exception, REQUEST_FALLBACK_MESSAGE, true));
            return;
        }

        List<Throwable> causes = causeChain(exception);
        ActionFeedbackException feedback = findCause(causes, ActionFeedbackException.class,
                e -> e.actionErrorCode() != null);
        if (feedback != null) {
            String displayMessage = firstNonBlank(feedback.actionErrorMessage(), feedback.getMessage(),
                    "That move isn't available right now.");
            gameStateService.sendActionError(playerPrincipal, feedback.actionErrorCode(),
                    RejectedAction.from(action), displayMessage);
            return;
        }
        if (findCause(causes, ResourceNotFoundException.class) != null) {
            gameStateService.sendActionError(playerPrincipal, "GAME_NOT_FOUND",
                    RejectedAction.from(action), "This game is no longer available.");
            return;
        }
        if (causes.stream().anyMatch(this::isValidationOrConversionFailure)) {
            String displayMessage = firstNonBlank(findValidationMessage(causes), "Enter a valid action amount");
            gameStateService.sendActionError(playerPrincipal, "INVALID_ACTION_FORMAT",
                    RejectedAction.from(action), displayMessage);
            return;
        }
        gameStateService.sendActionError(playerPrincipal, "ACTION_REJECTED",
                RejectedAction.from(action), "That move isn't available right now.");
    }

    private boolean isValidationOrConversionFailure(Throwable throwable) {
        return throwable instanceof MessageConversionException
                || throwable instanceof org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException
                || throwable instanceof org.springframework.web.bind.MethodArgumentNotValidException
                || throwable instanceof BindException
                || throwable instanceof ValidationException
                || throwable instanceof MethodValidationException
                || throwable instanceof JacksonException;
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
        if (throwable instanceof BindException e) {
            if (e.getFieldError() != null) {
                return e.getFieldError().getDefaultMessage();
            }
        }
        if (throwable instanceof ConstraintViolationException e) {
            if (e.getConstraintViolations() != null && !e.getConstraintViolations().isEmpty()) {
                return e.getConstraintViolations().iterator().next().getMessage();
            }
        }
        if (throwable instanceof MethodValidationException e) {
            if (e.getAllErrors() != null && !e.getAllErrors().isEmpty()) {
                return e.getAllErrors().get(0).getDefaultMessage();
            }
        }
        return null;
    }

    // Recovers the requested action from the original frame without guessing from exception text.
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

    private String userFriendlyMessage(Exception exception, String fallbackMessage, boolean allowFormatFallback) {
        List<Throwable> causes = causeChain(exception);
        PokerException domainError = findCause(causes, PokerException.class);
        if (domainError != null) {
            String text = domainError instanceof ActionFeedbackException feedback
                    ? firstNonBlank(feedback.actionErrorMessage(), feedback.getMessage())
                    : firstNonBlank(domainError.getMessage());
            return text != null ? truncateNotification(text) : fallbackMessage;
        }
        if (allowFormatFallback && isFormatFailure(causes)) {
            String text = firstNonBlank(findValidationMessage(causes), INVALID_FORMAT_FALLBACK_MESSAGE);
            return truncateNotification(text);
        }
        return fallbackMessage;
    }

    private static List<Throwable> causeChain(Throwable root) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = root; cause != null && visited.add(cause); cause = cause.getCause()) {
            causes.add(cause);
        }
        return causes;
    }

    private static <T extends Throwable> T findCause(List<Throwable> causes, Class<T> type) {
        return findCause(causes, type, ignored -> true);
    }

    private static <T extends Throwable> T findCause(
            List<Throwable> causes, Class<T> type, Predicate<T> predicate) {
        for (Throwable cause : causes) {
            if (type.isInstance(cause)) {
                T match = type.cast(cause);
                if (predicate.test(match)) {
                    return match;
                }
            }
        }
        return null;
    }

    private boolean isFormatFailure(List<Throwable> causes) {
        return causes.stream().anyMatch(cause -> isValidationOrConversionFailure(cause)
                || (cause.getMessage() != null && cause.getMessage().contains("Cannot deserialize")));
    }

    private String findValidationMessage(List<Throwable> causes) {
        for (Throwable cause : causes) {
            if (isValidationOrConversionFailure(cause)) {
                String extracted = extractValidationMessage(cause);
                if (extracted != null && !extracted.isBlank()) {
                    return extracted;
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String truncateNotification(String message) {
        return message.length() > MAX_NOTIFICATION_LENGTH
                ? message.substring(0, MAX_NOTIFICATION_LENGTH - 3) + "..."
                : message;
    }
}
