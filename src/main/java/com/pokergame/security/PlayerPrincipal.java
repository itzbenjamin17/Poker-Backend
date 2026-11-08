package com.pokergame.security;

import com.pokergame.exception.UnauthorisedActionException;

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
     * Asserts this principal's token is bound to the given room/game identity.
     * <p>
     * Centralizes the token-to-room binding check that every secured endpoint performs.
     * Null-safe: a {@code null} bound {@code roomId} is treated as a mismatch and rejected
     * rather than throwing a {@link NullPointerException}, so callers need not guard it
     * separately.
     * </p>
     *
     * @param expectedRoomId the room or game identifier the request targets
     * @throws UnauthorisedActionException if this principal is bound to no room or to a different room
     */
    public void assertBoundTo(String expectedRoomId) {
        if (this.roomId == null || !this.roomId.equals(expectedRoomId)) {
            throw new UnauthorisedActionException("Token is not valid for this room.");
        }
    }

    /**
     * Asserts this principal's token is bound to the given room/game identity, using a
     * caller-supplied rejection message so transport-specific wording (for example "game"
     * versus "room") is preserved.
     *
     * @param expectedRoomId the room or game identifier the request targets
     * @param rejectionMessage client-safe message for the thrown exception on mismatch
     * @throws UnauthorisedActionException if this principal is bound to no room or to a different room
     */
    public void assertBoundTo(String expectedRoomId, String rejectionMessage) {
        if (this.roomId == null || !this.roomId.equals(expectedRoomId)) {
            throw new UnauthorisedActionException(rejectionMessage);
        }
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
     * Returns the membership-bound name used by Spring's user-destination routing.
     *
     * @return player, room, and membership identities separated by colons
     */
    @Override
    public String getName() {
        return playerName + ":" + roomId + ":" + membershipId;
    }
}
