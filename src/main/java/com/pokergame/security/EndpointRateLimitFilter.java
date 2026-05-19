package com.pokergame.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to enforce rate limits on sensitive REST endpoints using RateLimitService.
 */
@Component
public class EndpointRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;

    public EndpointRateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        
        // We only rate limit room creation and joining currently, may need to change this
        if (path.equals("/api/room/create") || path.equals("/api/room/join")) {
            String clientIp = getClientIp(request);
            // Key by IP + Path to prevent IP-based flooding across different actions
            String key = clientIp + ":" + path;

            if (!rateLimitService.tryConsumeRest(key)) {
                sendRateLimitError(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }

    private void sendRateLimitError(HttpServletResponse response) throws IOException {
        response.setStatus(429); // Too Many Requests
        response.setContentType("application/json");
        response.getWriter().write("{\"message\": \"Too many requests. Please try again in 15 minutes.\"}");
    }
}
