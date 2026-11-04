package com.pokergame.service.event;

/**
 * Published after a room is created or joined and the membership is accepted.
 * Lets the transport layer start the reconnect grace period for the member's first connection.
 *
 * @param roomId          room the member belongs to
 * @param playerName      sanitized player name
 * @param membershipId    membership identifier issued for this join, or null if unavailable
 * @param deadlineEpochMs time by which the first connection must arrive
 */
public record RoomMemberRegistered(String roomId, String playerName, String membershipId, long deadlineEpochMs) {
}
