package com.pokergame.dto.internal;

/**
 * Represents information about a player joining the game.
 * 
 * @param name     The name of the player.
 * @param isHost   Whether the player is the host of the game.
 * @param joinedAt The time the player joined the game.
 */

public record PlayerJoinInfo(
        String name,
        boolean isHost,
        String joinedAt
) {}