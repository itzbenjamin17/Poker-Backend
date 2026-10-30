package com.pokergame.security;

import java.security.Principal;

/**
 * Custom Principal implementation to hold player name, room ID, and unique membership ID.
 * This avoids fragile delimiter-based parsing of the Principal name and prevents
 * session collision/impersonation.
 *
 * @param playerName authenticated player's display name
 * @param roomId room to which the authentication is bound
 * @param membershipId unique membership/session identity bound to this player session
 */
public record PlayerPrincipal(String playerName, String roomId, String membershipId) implements Principal {

    /**
     * Backwards-compatible constructor for callers who do not specify a membership ID.
     *
     * @param playerName authenticated player's display name
     * @param roomId room to which the authentication is bound
     */
    public PlayerPrincipal(String playerName, String roomId) {
        this(playerName, roomId, null);
    }

    /**
     * Returns the player name.
     *
     * @return player name
     */
    public String getPlayerName() {
        return playerName;
    }

    /**
     * Returns the room ID.
     *
     * @return room ID
     */
    public String getRoomId() {
        return roomId;
    }

    /**
     * Returns the membership ID.
     *
     * @return membership ID, or null if not set
     */
    public String getMembershipId() {
        return membershipId;
    }

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
