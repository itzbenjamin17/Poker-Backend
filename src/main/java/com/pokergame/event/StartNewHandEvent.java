package com.pokergame.event;

/**
 * Event to trigger the start of a new hand.
 * 
 * @param gameId The ID of the game to start a new hand in.
 * @param delay  The delay before starting the new hand.
 */
public record StartNewHandEvent(String gameId, long delay) {}

