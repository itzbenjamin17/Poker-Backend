package com.pokergame.config;

import com.pokergame.security.RateLimitService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Interceptor to throttle WebSocket messages per session to prevent spamming.
 */
@Component
public class WebSocketRateLimitInterceptor implements ChannelInterceptor {

    private final RateLimitService rateLimitService;

    public WebSocketRateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        // Only throttle SEND commands (player actions, chat, etc.)
        if (StompCommand.SEND.equals(accessor.getCommand())) {
            String sessionId = accessor.getSessionId();
            if (sessionId != null) {
                if (!rateLimitService.tryConsumeWs(sessionId)) {
                    throw new MessageDeliveryException("Message rate limit exceeded. Maximum 5 messages per second allowed.");
                }
            }
        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            // Clean up bucket on disconnect
            String sessionId = accessor.getSessionId();
            if (sessionId != null) {
                rateLimitService.cleanUpWs(sessionId);
            }
        }

        return message;
    }
}
