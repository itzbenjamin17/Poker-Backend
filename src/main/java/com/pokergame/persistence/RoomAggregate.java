package com.pokergame.persistence;

import com.pokergame.model.Game;
import com.pokergame.model.Room;

/**
 * A room together with its game, which is the unit that is saved, rolled back and evicted.
 *
 * @param room the live room
 * @param game the room's game, or {@code null} while the room is still a lobby
 */
public record RoomAggregate(Room room, Game game) {
}
