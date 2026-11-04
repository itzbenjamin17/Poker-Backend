package com.pokergame.service.event;

/**
 * Published after a room is destroyed and the removal is accepted.
 * Lets the transport layer discard every transient session and deadline for the room.
 *
 * @param roomId identifier of the destroyed room
 */
public record RoomDestroyed(String roomId) {
}
