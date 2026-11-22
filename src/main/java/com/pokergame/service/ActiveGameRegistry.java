package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.model.Game;
import com.pokergame.util.RoomCoordinator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Stores live poker games keyed by room ID and verifies room lock ownership on every access.
 */
@Component
class ActiveGameRegistry {
    private final RoomCoordinator roomCoordinator;

    private final Map<String, Game> games = new ConcurrentHashMap<>();

    ActiveGameRegistry(RoomCoordinator roomCoordinator) {
        this.roomCoordinator = roomCoordinator;
    }

    /**
     * Returns the live game for {@code gameId}, or {@code null} if none exists.
     *
     * @param gameId the room/game ID to look up
     * @return the live game, or {@code null} if not found
     */
    Game get(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        return games.get(gameId);
    }

    /**
     * Returns whether a live game exists for {@code gameId}.
     *
     * @param gameId the room/game ID to check
     * @return {@code true} if a live game is registered for {@code gameId}
     */
    boolean contains(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        return games.containsKey(gameId);
    }

    /**
     * Registers a new live game for its room if none is currently stored.
     *
     * @param game the game to register
     */
    void register(Game game) {
        requireNonNull(game, "game must not be null");
        roomCoordinator.assertHeldByCurrentThread(game.getGameId());
        games.putIfAbsent(game.getGameId(), game);
    }

    /**
     * Stores or replaces a live game for its room (used when restoring a saved game).
     *
     * @param game the game instance to store
     */
    void replace(Game game) {
        requireNonNull(game, "game must not be null");
        roomCoordinator.assertHeldByCurrentThread(game.getGameId());
        games.put(game.getGameId(), game);
    }

    /**
     * Removes the live game associated with {@code gameId}.
     *
     * @param gameId the room/game ID to remove
     */
    void remove(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        games.remove(gameId);
    }
}
