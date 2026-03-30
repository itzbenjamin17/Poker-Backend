package com.pokergame.event;

/**
 * Event to trigger auto-advancing the game state.
 * 
 * @param gameId The ID of the game to advance.
 */
public record AutoAdvanceEvent(String gameId) {}

