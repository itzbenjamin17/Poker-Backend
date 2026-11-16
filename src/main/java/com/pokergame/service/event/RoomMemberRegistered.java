package com.pokergame.service.event;

/**
 * Event published after a player creates or joins a room and the update has been saved.
 * Notifies WebSocket session listeners to start tracking the player's initial connection timeout.
 *
 * @param roomId          the unique ID of the room the player joined
 * @param playerName      the sanitized player name
 * @param membershipId    the unique membership ID assigned for this join
 * @param deadlineEpochMs initial connection timeout deadline in epoch milliseconds
 */
public record RoomMemberRegistered(String roomId, String playerName, String membershipId, long deadlineEpochMs) {
}
