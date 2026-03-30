package com.pokergame.dto.response;

/**
 * Response DTO containing JWT token and room/game identifiers.
 * Returned when a player creates or joins a room.
 * 
 * @param token      The JWT token.
 * @param roomId     The ID of the room.
 * @param playerName The name of the player.
 */
public record TokenResponse(String token, String roomId, String playerName) {
}
