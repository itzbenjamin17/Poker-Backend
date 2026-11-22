package com.pokergame.service.event;

/**
 * Event published after a room has been destroyed and saved.
 * Notifies WebSocket session listeners to clear any remaining session tracking and timers for the room.
 *
 * @param roomId the unique ID of the destroyed room
 */
public record RoomDestroyed(String roomId) {
}
