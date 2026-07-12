package com.pokergame.persistence;

import com.pokergame.model.Game;
import com.pokergame.model.Room;

public record RecoveredAggregate(Room room, String currentHost, Game game, boolean deleted) {
}
