package com.pokergame.service.event;

/**
 * Published after a member leaves a room and the departure is accepted.
 * Lets the transport layer discard sessions and deadlines tied to that membership only,
 * preserving any replacement membership under the same player name.
 *
 * @param roomId       room the member left
 * @param playerName   sanitized player name
 * @param membershipId membership identifier that was retired
 */
public record RoomMembershipRetired(String roomId, String playerName, String membershipId) {
}
