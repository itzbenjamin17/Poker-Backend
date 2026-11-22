package com.pokergame.security;

import com.pokergame.util.LogThrottler;
import com.pokergame.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Filter to enforce rate limits on sensitive REST endpoints using RateLimitService.
 */
@Component
public class EndpointRateLimitFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(EndpointRateLimitFilter.class);

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final boolean trustProxy;

    private final LogThrottler logThrottler = new LogThrottler(5000L);

    /**
     * Creates the endpoint filter with its shared bucket service, JSON mapper, and trusted-proxy setting.
     *
     * @param rateLimitService REST rate limiter
     * @param objectMapper     object mapper for problem detail responses
     * @param trustProxy       whether to trust {@code X-Forwarded-For} headers for client IP resolution
     */
    public EndpointRateLimitFilter(
            RateLimitService rateLimitService,
            ObjectMapper objectMapper,
            @Value("${poker.security.trust-proxy:false}") boolean trustProxy) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.trustProxy = trustProxy;
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

        // We only rate limit room creation and joining currently, may need to change this.
        // CORS preflight (OPTIONS) requests are skipped: they carry no room action, and counting them would
        // halve a browser client's allowance because every real request is preceded by one.
        boolean preflight = HttpMethod.OPTIONS.matches(request.getMethod());
        if (!preflight && (path.equals("/api/room/create") || path.equals("/api/room/join"))) {
            String clientIp = getClientIp(request);
            // Key by IP + Path to prevent IP-based flooding across different actions
            String key = clientIp + ":" + path;

            RateLimitService.Decision decision = rateLimitService.consumeRest(key);
            if (!decision.allowed()) {
                logThrottler.throttle(key, () -> logger.warn(
                        "Rate limit exceeded (429): method={}, path={}, clientIp={}, limit={} req/{}",
                        request.getMethod(), path, clientIp, RateLimitService.REST_CAPACITY, RateLimitService.REST_WINDOW));
                sendRateLimitError(response, decision.retryAfterSeconds());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        return SecurityUtils.getClientIp(request, trustProxy);
    }

    private void sendRateLimitError(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.TOO_MANY_REQUESTS,
                        RateLimitService.tooManyRequestsMessage(retryAfterSeconds))));
    }
}
