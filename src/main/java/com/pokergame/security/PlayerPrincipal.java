package com.pokergame.security;

import java.security.Principal;

/**
 * Custom Principal implementation to hold both player name and room ID.
 * This avoids fragile delimiter-based parsing of the Principal name.
 *
 * @param playerName authenticated player's display name
 * @param roomId room to which the authentication is bound
 */
public record PlayerPrincipal(String playerName, String roomId) implements Principal {

    /**
     * Returns the composite name used by Spring's user-destination routing.
     *
     * @return player and room identities separated by a colon
     */
    @Override
    public String getName() {
        return playerName + ":" + roomId;
    }
}
