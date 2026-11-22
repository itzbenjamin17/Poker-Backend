package com.pokergame.util;

import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.security.PlayerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.security.core.Authentication;

/**
 * Utility class for extracting player principals from security contexts.
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // Utility class
    }

    /**
     * Extracts the PlayerPrincipal from a Principal, handling direct instances
     * and Authentication wrappers. Throws an UnauthorisedActionException if invalid.
     *
     * @param principal the security principal to extract from
     * @return resolved PlayerPrincipal
     * @throws UnauthorisedActionException if principal is null or cannot be resolved to a PlayerPrincipal
     */
    public static PlayerPrincipal getPlayer(Principal principal) {
        PlayerPrincipal playerPrincipal = getPlayerOrNull(principal);
        if (playerPrincipal != null) {
            return playerPrincipal;
        }
        throw new UnauthorisedActionException("Invalid authentication principal");
    }

    /**
     * Extracts the PlayerPrincipal from a Principal, handling direct instances
     * and Authentication wrappers. Returns null if principal is null or invalid.
     *
     * @param principal the security principal to extract from
     * @return resolved PlayerPrincipal, or null if unresolvable
     */
    public static PlayerPrincipal getPlayerOrNull(Principal principal) {
        if (principal instanceof PlayerPrincipal playerPrincipal) {
            return playerPrincipal;
        }
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof PlayerPrincipal playerPrincipal) {
            return playerPrincipal;
        }
        return null;
    }

    /**
     * Resolves the client IP address from the request, respecting proxy headers
     * when trust-proxy is enabled.
     *
     * @param request current HTTP request
     * @param trustProxy whether to trust X-Forwarded-For headers
     * @return resolved client IP address
     */
    public static String getClientIp(HttpServletRequest request, boolean trustProxy) {
        if (trustProxy) {
            String xfHeader = request.getHeader("X-Forwarded-For");
            if (xfHeader != null && !xfHeader.isBlank()) {
                int commaIndex = xfHeader.indexOf(',');
                String firstIp = (commaIndex >= 0 ? xfHeader.substring(0, commaIndex) : xfHeader).trim();
                if (!firstIp.isEmpty()) {
                    return firstIp;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
