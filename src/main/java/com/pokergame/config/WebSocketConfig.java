package com.pokergame.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.util.SecurityUtils;
import java.security.Principal;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/** Configures STOMP endpoints, broker destinations, and inbound interceptors. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final WebSocketRateLimitInterceptor webSocketRateLimitInterceptor;
    private final List<String> allowedOrigins;
    private TaskScheduler messageBrokerTaskScheduler;

    @Autowired
    public void setMessageBrokerTaskScheduler(
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler taskScheduler) {
        this.messageBrokerTaskScheduler = taskScheduler;
    }

    /**
     * Creates the WebSocket configuration with its inbound security interceptors.
     *
     * @param webSocketAuthInterceptor authenticates and authorizes STOMP frames
     * @param webSocketRateLimitInterceptor throttles client SEND frames
     * @param allowedOrigins allowed origin patterns for WebSocket handshake
     */
    public WebSocketConfig(WebSocketAuthInterceptor webSocketAuthInterceptor,
                           WebSocketRateLimitInterceptor webSocketRateLimitInterceptor,
                           @Value("${app.security.cors.allowed-origins}") List<String> allowedOrigins) {
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
        this.webSocketRateLimitInterceptor = webSocketRateLimitInterceptor;
        this.allowedOrigins = allowedOrigins;
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
        config.enableSimpleBroker("/room", "/game", "/queue")
                .setHeartbeatValue(new long[] {10000, 10000})
                .setTaskScheduler(this.messageBrokerTaskScheduler);

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
        String[] patterns = allowedOrigins.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(patterns.length > 0 ? patterns : new String[]{"*"});
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

    /**
     * Registers custom argument resolver to support @AuthenticationPrincipal PlayerPrincipal
     * in STOMP @MessageMapping handler methods.
     *
     * @param argumentResolvers argument resolvers to configure
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && PlayerPrincipal.class.isAssignableFrom(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, Message<?> message) {
                Principal user = SimpMessageHeaderAccessor.getUser(message.getHeaders());
                return SecurityUtils.getPlayer(user);
            }
        });
    }
}
