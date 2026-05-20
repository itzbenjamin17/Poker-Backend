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

    public WebSocketAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

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
