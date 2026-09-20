package com.pokergame.persistence.snapshot;

import com.pokergame.model.Game;
import com.pokergame.model.Room;

/**
 * A simple container holding the fully restored Room and Game objects
 * after they have been loaded from the database during startup.
 *
 * @param room        the restored room, or null if this was just a marker saying the room was deleted
 * @param currentHost the player currently hosting the room
 * @param game        the restored game, or null if the room hasn't started playing yet
 * @param deleted     true if this is a record of a room that was deleted
 */
public record RecoveredAggregate(Room room, String currentHost, Game game, boolean deleted) {
}
