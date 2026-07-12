package com.pokergame.config;

import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Intercepts STOMP messages to authenticate and authorize WebSocket actions.
 * - On CONNECT: extracts and validates the JWT to set a PlayerPrincipal.
 * - On SUBSCRIBE: ensures players only subscribe to their allowed topics.
 */
@SuppressWarnings("NullableProblems")
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    private final JwtService jwtService;

    /**
     * Creates an interceptor backed by the application's JWT service.
     *
     * @param jwtService token validator and principal extractor
     */
    public WebSocketAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
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

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            handleSubscribe(accessor);
        }

        return message;
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
                accessor.setUser(principal);
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
}
