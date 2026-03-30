package com.pokergame.dto.response;

/**
 * Response DTO for player-specific notifications during gameplay
 * 
 * @param type       The type of notification.
 * @param message    The message to be displayed.
 * @param playerName The name of the player receiving the notification.
 * @param gameId     The ID of the game.
 */

public record PlayerNotificationResponse(
        String type,
        String message,
        String playerName,
        String gameId
) {}
