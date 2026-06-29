package com.pokergame.config;

import com.pokergame.security.JwtAuthenticationFilter;
import com.pokergame.security.PayloadSizeFilter;
import com.pokergame.security.EndpointRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for stateless JWT authentication.
 * - No sessions (stateless)
 * - Public endpoints: create room, join room, WebSocket handshake
 * - All other endpoints require valid JWT
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PayloadSizeFilter payloadSizeFilter;
    private final EndpointRateLimitFilter endpointRateLimitFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          com.pokergame.security.PayloadSizeFilter payloadSizeFilter,
                          com.pokergame.security.EndpointRateLimitFilter endpointRateLimitFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.payloadSizeFilter = payloadSizeFilter;
        this.endpointRateLimitFilter = endpointRateLimitFilter;
    }

    /**
     * Provide a dummy UserDetailsService to suppress default password generation.
     * Authenticated state is managed entirely via JWT principal.
     */
    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Not used");
        };
    }

    // All requests go through this filter chain
    @SuppressWarnings("RedundantThrows")
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // noinspection Convert2MethodRef
        http
                // Disable CSRF (not needed for stateless JWT)
                .csrf(csrf -> csrf.disable())

                // Enable CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Stateless session - no cookies, no session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Authorisation rules
                .authorizeHttpRequests(auth -> auth
                    // Docker healthcheck must be public so the container can report healthy
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // Public endpoints - where tokens are ISSUED (no token required)
                        .requestMatchers("/api/room/create", "/api/room/join").permitAll()

                        // WebSocket handshake must be public
                        .requestMatchers("/ws/**").permitAll()

                        // Everything else requires authentication
                        .anyRequest().authenticated())

                // Explicitly disable unused login mechanisms to avoid default password generation
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                // Enforce rate limits early
                .addFilterBefore(endpointRateLimitFilter, org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter.class)

                // Reject oversized payloads early (before any processing)
                .addFilterBefore(payloadSizeFilter, org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter.class)

                // Add our JWT filter before Spring's pre-auth filter (since we use
                // PreAuthenticatedAuthenticationToken)
                .addFilterBefore(jwtAuthenticationFilter, AbstractPreAuthenticatedProcessingFilter.class);

        return http.build();
    }

    @Value("${app.security.cors.allowed-origins}")
    private List<String> allowedOrigins;

    // CORS configuration to allow requests from frontend from certain origins
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Allows cookies, authorisation headers to be sent in CORS requests
        config.setAllowCredentials(true);

        config.setAllowedOriginPatterns(allowedOrigins);

        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));

        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        config.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Applies this CORS configuration to all endpoints
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
