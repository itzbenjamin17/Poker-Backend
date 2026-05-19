package com.pokergame.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final WebSocketRateLimitInterceptor webSocketRateLimitInterceptor;

    public WebSocketConfig(WebSocketAuthInterceptor webSocketAuthInterceptor,
                           WebSocketRateLimitInterceptor webSocketRateLimitInterceptor) {
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
        this.webSocketRateLimitInterceptor = webSocketRateLimitInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple broker for destinations the server broadcasts to:
        // - /room/{roomId} - room lobby updates
        // - /game/{gameId} - public game state for all players
        // - /game/{gameId}/player/{playerId}/private - private data per player
        // broker is what sends messages to all subscribed clients
        config.enableSimpleBroker("/room", "/game");

        // Prefix for destinations clients send messages to (handled by @MessageMapping)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket connection endpoint (where clients connect to)
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:5173", "https://*.ngrok-free.app")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Register interceptors to authenticate STOMP CONNECT and throttle messages
        registration.interceptors(webSocketAuthInterceptor, webSocketRateLimitInterceptor);
    }
}
