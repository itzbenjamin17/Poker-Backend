package com.pokergame.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Configures STOMP endpoints, broker destinations, and inbound interceptors. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final WebSocketRateLimitInterceptor webSocketRateLimitInterceptor;

    /**
     * Creates the WebSocket configuration with its inbound security interceptors.
     *
     * @param webSocketAuthInterceptor authenticates and authorizes STOMP frames
     * @param webSocketRateLimitInterceptor throttles client SEND frames
     */
    public WebSocketConfig(WebSocketAuthInterceptor webSocketAuthInterceptor,
                           WebSocketRateLimitInterceptor webSocketRateLimitInterceptor) {
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
        this.webSocketRateLimitInterceptor = webSocketRateLimitInterceptor;
    }

    /**
     * Configures application, broker, and user-destination prefixes.
     *
     * @param config message-broker registry to configure
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple broker for destinations the server broadcasts to:
        // - /room/{roomId} - room lobby updates
        // - /game/{gameId} - public game state for all players
        // - /queue - standard destination for private user messages
        config.enableSimpleBroker("/room", "/game", "/queue");

        // Prefix for destinations clients send messages to (handled by @MessageMapping)
        config.setApplicationDestinationPrefixes("/app");
        
        // Prefix for user-specific destinations
        config.setUserDestinationPrefix("/user");
    }

    /**
     * Registers the STOMP handshake endpoint and its allowed browser origins.
     *
     * @param registry endpoint registry to configure
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(
                "http://localhost:5173",
                "http://localhost",
                "https://benjamins.page",
                "https://www.benjamins.page",
                "http://benjamins.page",
                "http://www.benjamins.page",
                "https://*.ngrok-free.app");
    }

    /**
     * Applies authentication before rate limiting on inbound client messages.
     *
     * @param registration inbound-channel registration to configure
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Register interceptors to authenticate STOMP CONNECT and throttle messages
        registration.interceptors(webSocketAuthInterceptor, webSocketRateLimitInterceptor);
    }
}
