package com.pokergame.security;

import com.pokergame.util.LogThrottler;
import com.pokergame.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to enforce rate limits on sensitive REST endpoints using RateLimitService.
 */
@Component
public class EndpointRateLimitFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(EndpointRateLimitFilter.class);

    private final RateLimitService rateLimitService;
    private final LogThrottler logThrottler = new LogThrottler(5000L);

    @Value("${poker.security.trust-proxy:false}")
    private boolean trustProxy;

    /**
     * Creates the endpoint filter with its shared bucket service.
     *
     * @param rateLimitService REST rate limiter
     */
    public EndpointRateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    /**
     * Applies per-client limits to room creation and join requests.
     *
     * @param request current HTTP request
     * @param response current HTTP response
     * @param filterChain remaining servlet filter chain
     * @throws ServletException if downstream filtering fails
     * @throws IOException if the rejection or downstream response cannot be written
     */
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
                logThrottler.throttle(key, () -> logger.warn(
                        "Rate limit exceeded (429): method={}, path={}, clientIp={}, limit={}",
                        request.getMethod(), path, clientIp, "5 req/15min"));
                sendRateLimitError(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolves the client address, honoring the first forwarded address only when
     * proxy trust is explicitly enabled.
     *
     * @param request current HTTP request
     * @return address used as the rate-limit identity
     */
    private String getClientIp(HttpServletRequest request) {
        return SecurityUtils.getClientIp(request, trustProxy);
    }

    /**
     * Writes the REST rate-limit response.
     *
     * @param response current HTTP response
     * @throws IOException if the response body cannot be written
     */
    private void sendRateLimitError(HttpServletResponse response) throws IOException {
        response.setStatus(429); // Too Many Requests
        response.setContentType("application/json");
        response.getWriter().write("{\"message\": \"Too many requests. Please try again in 15 minutes.\"}");
    }
}
