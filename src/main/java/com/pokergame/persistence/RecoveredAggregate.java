package com.pokergame.persistence;

import com.pokergame.model.Game;
import com.pokergame.model.Room;

/**
 * Carries explicitly rehydrated domain state from the compatibility mapper to the
 * startup recovery coordinator.
 *
 * @param room        restored room, absent for a deletion tombstone
 * @param currentHost restored current host
 * @param game        restored game, or {@code null} for a lobby
 * @param deleted     whether the committed image represents deletion
 */
public record RecoveredAggregate(Room room, String currentHost, Game game, boolean deleted) {
}
