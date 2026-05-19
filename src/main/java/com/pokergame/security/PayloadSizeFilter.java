package com.pokergame.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to reject requests with excessively large payloads to prevent DoS attacks.
 */
@Component
public class PayloadSizeFilter extends OncePerRequestFilter {

    private static final long MAX_PAYLOAD_SIZE = 10_240; // 10KB

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        long contentLength = request.getContentLengthLong();

        if (contentLength > MAX_PAYLOAD_SIZE) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"Request payload too large. Maximum allowed is 10KB.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
