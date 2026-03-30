package com.pokergame.event;

/**
 * Event to trigger cleanup of a game.
 * 
 * @param gameId The ID of the game to clean up.
 * @param delay  The delay before cleanup.
 */
public record GameCleanupEvent(String gameId, long delay) {}

