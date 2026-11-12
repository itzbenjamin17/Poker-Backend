package com.pokergame.service.event;

/**
 * Published after a room is created or joined and the membership is accepted.
 * Lets the transport layer schedule the member's first-connection grace period.
 *
 * @param roomId          room the member belongs to
 * @param playerName      sanitized player name
 * @param membershipId    membership identifier issued for this join, or null if unavailable
 * @param deadlineEpochMs deadline for the member's first connection, in milliseconds since the Unix epoch
 */
public record RoomMemberRegistered(String roomId, String playerName, String membershipId, long deadlineEpochMs) {
}
