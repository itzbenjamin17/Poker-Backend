package com.pokergame.persistence.config;

import com.pokergame.persistence.PersistenceRecovery;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns away web requests with "503 Service Unavailable" until saved games have been reloaded,
 * so nobody sees a lobby or game that is only missing because it has not been restored yet.
 * Health check requests are always let through so monitoring can see that the server is still starting.
 */
final class RecoveryTrafficGate extends OncePerRequestFilter {
    private static final String HEALTH_PATH_PREFIX = "/actuator/health";

    private final PersistenceRecovery recovery;

    /**
     * Creates the gate.
     *
     * @param recovery the startup recovery whose completion opens the gate
     */
    RecoveryTrafficGate(PersistenceRecovery recovery) {
        this.recovery = recovery;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!recovery.isComplete() && !request.getRequestURI().startsWith(HEALTH_PATH_PREFIX)) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Recovery is not complete");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
