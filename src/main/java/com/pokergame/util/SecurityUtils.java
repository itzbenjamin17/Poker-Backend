package com.pokergame.util;

import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.security.PlayerPrincipal;
import org.springframework.security.core.Authentication;

import java.security.Principal;

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
}
