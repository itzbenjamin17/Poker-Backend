package com.pokergame.security;

import com.pokergame.util.LogThrottler;
import com.pokergame.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to reject requests with excessively large payloads to prevent DoS attacks.
 */
@Component
public class PayloadSizeFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(PayloadSizeFilter.class);
    private static final long MAX_PAYLOAD_SIZE = 10_240; // 10KB

    @Value("${poker.security.trust-proxy:false}")
    private boolean trustProxy;

    private final LogThrottler logThrottler = new LogThrottler(5000L);

    /**
     * Rejects declared payloads larger than 10 KiB before downstream processing.
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

        long contentLength = request.getContentLengthLong();

        if (contentLength > MAX_PAYLOAD_SIZE) {
            String clientIp = SecurityUtils.getClientIp(request, trustProxy);
            String path = request.getRequestURI();
            String key = clientIp + ":" + path;
            logThrottler.throttle(key, () -> logger.warn(
                    "Payload size limit exceeded (413): method={}, path={}, clientIp={}, size={} bytes, limit={} bytes",
                    request.getMethod(), path, clientIp, contentLength, MAX_PAYLOAD_SIZE));
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"Request payload too large. Maximum allowed is 10KB.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
