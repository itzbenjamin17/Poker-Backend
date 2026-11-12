package com.pokergame.service;

import com.pokergame.model.Game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Stores the current game identity for each room. Coordinate before operating on a live game. */
final class ActiveGameRegistry {
    private final Map<String, Game> games = new ConcurrentHashMap<>();

    Game get(String gameId) {
        return games.get(gameId);
    }

    boolean contains(String gameId) {
        return games.containsKey(gameId);
    }

    /** Registers a new game after creation has checked room availability under the lease. */
    void register(Game game) {
        games.putIfAbsent(game.getGameId(), game);
    }

    /** Installs a restored game, replacing any previously registered instance. */
    void replace(Game game) {
        games.put(game.getGameId(), game);
    }

    Game remove(String gameId) {
        return games.remove(gameId);
    }

    boolean remove(String gameId, Game expectedGame) {
        return games.remove(gameId, expectedGame);
    }
}
