package com.pokergame.config;

import com.pokergame.model.Room;
import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.RoomService;
import com.pokergame.util.MdcKeys;
import com.pokergame.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;

/**
 * Intercepts STOMP messages to authenticate and authorize WebSocket actions.
 * - On CONNECT: extracts and validates the JWT to set a PlayerPrincipal.
 * - On SUBSCRIBE: ensures players only subscribe to their allowed topics.
 * - On SEND: validates destination prefix and ensures players only publish to their allowed room destinations.
 */
@SuppressWarnings("NullableProblems")
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    private final JwtService jwtService;
    private final RoomService roomService;

    /**
     * Creates an interceptor backed by the application's JWT service and room registry.
     * The room registry is injected lazily because it depends on messaging infrastructure
     * that is itself configured with this interceptor.
     *
     * @param jwtService token validator and principal extractor
     * @param roomService room registry for active membership validation
     */
    public WebSocketAuthInterceptor(JwtService jwtService, @Lazy RoomService roomService) {
        this.jwtService = jwtService;
        this.roomService = roomService;
    }

    /**
     * Authenticates STOMP connections and authorizes subscriptions before delivery.
     *
     * @param message inbound STOMP message
     * @param channel channel receiving the message
     * @return the unchanged message when authentication and authorization succeed
     * @throws MessagingException if a connection or subscription is unauthorized
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) return message;

        if (accessor.getSessionId() != null) {
            MDC.put(MdcKeys.SESSION_ID, accessor.getSessionId());
        }

        if (accessor.getUser() instanceof PlayerPrincipal principal) {
            MDC.put(MdcKeys.ROOM_ID, principal.roomId());
            MDC.put(MdcKeys.PLAYER_NAME, principal.playerName());
        }

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            handleSubscribe(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            handleSend(accessor);
        }

        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        MDC.remove(MdcKeys.ROOM_ID);
        MDC.remove(MdcKeys.PLAYER_NAME);
        MDC.remove(MdcKeys.SESSION_ID);
    }

    /**
     * Restricts SEND frames to valid application destinations (/app/**) and verifies
     * caller authentication and room authorization for room-targeted destinations.
     *
     * @param accessor headers for the SEND frame
     * @throws AccessDeniedException if destination prefix is unauthorized, caller is unauthenticated, or room does not match
     */
    private void handleSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/app/")) {
            logger.warn("Blocked SEND to unauthorized destination: {}", destination);
            throw new AccessDeniedException("Access denied: SEND destination must start with /app/");
        }

        String sub = destination.length() > 5 ? destination.substring(5).replaceAll("^/+", "") : "";
        if (sub.isBlank()) {
            logger.warn("Blocked SEND to blank application destination: {}", destination);
            throw new AccessDeniedException("Access denied: SEND destination must start with /app/");
        }

        PlayerPrincipal principal = resolvePlayerPrincipal(accessor);
        if (principal == null) {
            logger.warn("Forbidden unauthenticated SEND attempt to destination {}", destination);
            throw new AccessDeniedException("User not authorized for this room destination");
        }

        String destinationRoomId = extractRoomIdFromSendDestination(destination);
        if (destinationRoomId != null) {
            if (!isRoomAuthorized(principal, destinationRoomId)) {
                String callerRoom = principal.roomId() != null ? principal.roomId() : "unauthenticated";
                logger.warn("Forbidden SEND attempt: player in room {} tried to send to room destination {}", callerRoom, destination);
                throw new AccessDeniedException("User not authorized for this room destination");
            }
        }

        validateMembership(principal);
    }

    /**
     * Resolves the PlayerPrincipal from user header or session attributes.
     *
     * @param accessor STOMP frame headers
     * @return resolved PlayerPrincipal or null
     */
    private PlayerPrincipal resolvePlayerPrincipal(StompHeaderAccessor accessor) {
        PlayerPrincipal principal = SecurityUtils.getPlayerOrNull(accessor.getUser());
        if (principal != null) {
            return principal;
        }
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            Object userAttr = sessionAttributes.get("user");
            if (userAttr instanceof Principal p) {
                principal = SecurityUtils.getPlayerOrNull(p);
                if (principal != null) {
                    return principal;
                }
            } else if (userAttr instanceof PlayerPrincipal pp) {
                return pp;
            }
            Object playerAttr = sessionAttributes.get("playerPrincipal");
            if (playerAttr instanceof PlayerPrincipal pp) {
                return pp;
            }
        }
        return null;
    }

    /**
     * Checks if the principal is authorized for the given room identifier.
     *
     * @param principal caller principal
     * @param destinationRoomId extracted room identifier
     * @return true if authorized, false otherwise
     */
    private boolean isRoomAuthorized(PlayerPrincipal principal, String destinationRoomId) {
        if (principal == null || principal.roomId() == null) {
            return false;
        }
        if (principal.roomId().equals(destinationRoomId)) {
            return true;
        }
        try {
            String decoded = URLDecoder.decode(destinationRoomId, StandardCharsets.UTF_8);
            return principal.roomId().equals(decoded);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Extracts a room identifier from a validated /app/ destination path.
     * Supported patterns:
     * - /app/game/{roomId}/...
     * - /app/room/{roomId}/...
     * - /app/{roomId}/... (when first segment after /app/ is not "game" or "room")
     *
     * @param destination STOMP destination
     * @return extracted roomId or null if destination is not room-scoped
     */
    private String extractRoomIdFromSendDestination(String destination) {
        if (destination == null || !destination.startsWith("/app/")) {
            return null;
        }
        String sub = destination.substring(5).replaceAll("^/+", "");
        String[] parts = sub.split("/+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        if ("room".equals(parts[0]) || "game".equals(parts[0])) {
            return parts.length >= 2 && !parts[1].isBlank() ? parts[1] : null;
        }
        if (parts.length >= 2 && !parts[0].isBlank()) {
            return parts[0];
        }
        return null;
    }

    /**
     * Validates the connection bearer token and attaches its player principal.
     *
     * @param accessor mutable headers for the CONNECT frame
     * @throws MessagingException if the authorization token is missing or invalid
     */
    private void handleConnect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtService.isTokenValid(token)) {
                PlayerPrincipal principal = jwtService.extractPrincipal(token);
                validateMembership(principal);
                accessor.setUser(principal);
                MDC.put(MdcKeys.ROOM_ID, principal.roomId());
                MDC.put(MdcKeys.PLAYER_NAME, principal.playerName());
                logger.debug("WebSocket authenticated for player: {}", principal.playerName());
            } else {
                logger.warn("Invalid JWT token in WebSocket CONNECT");
                throw new MessagingException("Invalid WebSocket authorization token");
            }
        } else {
            logger.warn("No Authorization header in WebSocket CONNECT");
            throw new MessagingException("Missing WebSocket authorization token");
        }
    }

    /**
     * Restricts room and game subscriptions to the principal's own room.
     *
     * @param accessor headers for the SUBSCRIBE frame
     * @throws MessagingException if the subscription is unauthenticated or targets another room
     */
    private void handleSubscribe(StompHeaderAccessor accessor) {
        PlayerPrincipal principal = (PlayerPrincipal) accessor.getUser();
        if (principal == null) {
            throw new MessagingException("Unauthorized: No principal found for subscription");
        }
        validateMembership(principal);

        String destination = accessor.getDestination();
        if (destination == null) return;

        // Public topics: /room/{roomId}, /game/{gameId}
        // Private topics: /user/queue/private (mapped by Spring)
        
        if (destination.startsWith("/room/") || destination.startsWith("/game/")) {
            String resourceId = destination.substring(destination.lastIndexOf("/") + 1);

            if (!principal.roomId().equals(resourceId)) {
                logger.warn("Player {} tried to subscribe to unauthorized destination {}", principal.playerName(), destination);
                throw new MessagingException("Forbidden: You are not authorized to subscribe to this destination.");
            }
        }
    }

    /** Rejects absent membership claims before checking the active room membership. */
    private void validateMembership(PlayerPrincipal principal) {
        if (principal == null || principal.roomId() == null
                || principal.membershipId() == null || principal.membershipId().isBlank()) {
            throw new AccessDeniedException("Token membership is stale or invalid.");
        }
        Room room = roomService.getRoom(principal.roomId());
        if (room != null) {
            if (!room.hasPlayer(principal.playerName())
                    || !principal.membershipId().equals(room.getMembershipId(principal.playerName()))) {
                logger.warn("Rejecting STOMP frame: stale or invalid membership for player {} in room {}",
                        principal.playerName(), principal.roomId());
                throw new AccessDeniedException("Token membership is stale or invalid.");
            }
        }
    }
}
