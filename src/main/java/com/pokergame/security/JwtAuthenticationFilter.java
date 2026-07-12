package com.pokergame.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Filter that extracts JWT from the Authorisation header and sets up Spring
 * Security context.
 * Runs once per request before hitting the controller.
 */
@SuppressWarnings("NullableProblems")
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    /**
     * Creates the request filter with the service used to validate bearer tokens.
     *
     * @param jwtService token validator and principal extractor
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Adds a pre-authenticated player token to the security context when a valid
     * bearer token is present, then continues the filter chain.
     *
     * @param request current HTTP request
     * @param response current HTTP response
     * @param filterChain remaining servlet filter chain
     * @throws ServletException if downstream filtering fails
     * @throws IOException if downstream processing cannot read or write the request
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtService.isTokenValid(token)) {
                PlayerPrincipal principal = jwtService.extractPrincipal(token);

                // Create the pre-authenticated token
                PreAuthenticatedAuthenticationToken authentication = new PreAuthenticatedAuthenticationToken(
                        principal, token, Collections.emptyList());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // Set in the security context
                SecurityContextHolder.getContext().setAuthentication(authentication);
                logger.debug("Authenticated player: {} for room: {}", principal.playerName(), principal.roomId());
            }
        }

        filterChain.doFilter(request, response);
    }
}
