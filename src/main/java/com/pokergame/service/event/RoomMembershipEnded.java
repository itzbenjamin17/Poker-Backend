package com.pokergame.service.event;

/**
 * Event published after a player is removed from a room and the update has been saved.
 * Notifies WebSocket session listeners to clear session tracking and timers for that specific
 * membership ID only, without affecting a newer session that reuses the same display name.
 *
 * @param roomId       the unique ID of the room the player left
 * @param playerName   the sanitized player name
 * @param membershipId the ended membership ID
 */
public record RoomMembershipEnded(String roomId, String playerName, String membershipId) {
}
