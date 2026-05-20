package com.pokergame.security;

import java.security.Principal;

/**
 * Custom Principal implementation to hold both player name and room ID.
 * This avoids fragile delimiter-based parsing of the Principal name.
 */
public record PlayerPrincipal(String playerName, String roomId) implements Principal {
    
    @Override
    public String getName() {
        return playerName + ":" + roomId;
    }
}
