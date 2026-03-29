package com.pokergame.config;

import com.pokergame.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
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
                        // Public endpoints - where tokens are ISSUED (no token required)
                        .requestMatchers("/api/room/create", "/api/room/join").permitAll()

                        // WebSocket handshake must be public
                        .requestMatchers("/ws/**").permitAll()

                        // Everything else requires authentication
                        .anyRequest().authenticated())

                // Add our JWT filter before Spring's pre-auth filter (since we use
                // PreAuthenticatedAuthenticationToken)
                .addFilterBefore(jwtAuthenticationFilter, AbstractPreAuthenticatedProcessingFilter.class);

        return http.build();
    }

    // CORS configuration to allow requests from frontend from certain origins
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Allows cookies, authorization headers to be sent in CORS requests
        config.setAllowCredentials(true);

        config.setAllowedOriginPatterns(List.of(
                "http://localhost:5173",
                "https://*.ngrok-free.app"));

        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));

        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        config.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Applies this CORS configuration to all endpoints
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
