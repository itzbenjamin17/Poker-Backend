package com.pokergame.dto.internal;

/**
 * Represents information about a player in a room.
 *
 * @param name   The name of the player.
 * @param isHost Whether the player is the host of the room.
 */
public record PlayerJoinInfo(
        String name,
        boolean isHost
) {}