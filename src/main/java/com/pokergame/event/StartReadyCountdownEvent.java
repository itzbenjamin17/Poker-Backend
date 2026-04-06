package com.pokergame.event;

/**
 * Event to trigger the post-round ready countdown gate.
 *
 * @param gameId      The target game ID.
 * @param delayMs     Delay before opening countdown (winner display window).
 * @param countdownMs Countdown duration once opened.
 */
public record StartReadyCountdownEvent(String gameId, long delayMs, long countdownMs) {
}
