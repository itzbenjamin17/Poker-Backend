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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import com.pokergame.dto.response.ErrorResponse;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;

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
    private final ObjectMapper objectMapper;

    /**
     * Creates the security configuration with the filters that protect HTTP traffic.
     *
     * @param jwtAuthenticationFilter authenticates bearer tokens
     * @param payloadSizeFilter rejects oversized request bodies
     * @param endpointRateLimitFilter throttles sensitive public endpoints
     * @param objectMapper serializes error responses
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          com.pokergame.security.PayloadSizeFilter payloadSizeFilter,
                          com.pokergame.security.EndpointRateLimitFilter endpointRateLimitFilter,
                          ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.payloadSizeFilter = payloadSizeFilter;
        this.endpointRateLimitFilter = endpointRateLimitFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * Exposes a BCrypt password encoder for hashing and constant-time matching of sensitive secrets.
     *
     * @return BCryptPasswordEncoder instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Builds the stateless HTTP security chain and orders the application filters.
     *
     * @param http Spring Security's chain builder
     * @return configured security filter chain
     * @throws Exception if Spring Security cannot build the chain
     */
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

                // Configure authentication entry point for unauthenticated requests
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    new ErrorResponse(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Session expired or invalid token")));
                        }))

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

                // Reject oversized payloads first (before any other processing or token consumption)
                .addFilterBefore(payloadSizeFilter, org.springframework.security.web.session.DisableEncodeUrlFilter.class)

                // Enforce rate limits next
                .addFilterBefore(endpointRateLimitFilter, org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter.class)

                // Add our JWT filter before Spring's pre-auth filter (since we use
                // PreAuthenticatedAuthenticationToken)
                .addFilterBefore(jwtAuthenticationFilter, AbstractPreAuthenticatedProcessingFilter.class);

        return http.build();
    }

    @Value("${app.security.cors.allowed-origins}")
    private List<String> allowedOrigins;

    /**
     * Defines the cross-origin policy for configured frontend origins.
     *
     * @return URL-based CORS configuration for all endpoints
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
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
