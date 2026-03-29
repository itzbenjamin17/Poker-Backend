package com.pokergame.service;

import com.pokergame.dto.response.ApiResponse;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.event.GameCleanupEvent;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service class responsible for game lifecycle management.
 * Handles game creation, starting new hands, and game termination.
 */
@Service
public class GameLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(GameLifecycleService.class);
    private static final long GAME_END_DISPLAY_DELAY_MS = 7000;

    private final RoomService roomService;

    private final HandEvaluatorService handEvaluator;

    private final GameStateService gameStateService;

    private final SimpMessagingTemplate messagingTemplate;

    private final ApplicationEventPublisher eventPublisher;

    // Dependency Injection
    GameLifecycleService(RoomService roomService,
            HandEvaluatorService handEvaluatorService,
            GameStateService gameStateService,
            SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher) {
        this.roomService = roomService;
        this.handEvaluator = handEvaluatorService;
        this.gameStateService = gameStateService;
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
    }

    private final Map<String, Game> activeGames = new ConcurrentHashMap<>();

    /**
     * Creates and initialises an actual poker game from an existing room.
     * Requires at least 2 players in the room to start. Converts room players
     * to game players with buy-in chips and starts the first hand.
     *
     * @param roomId the unique identifier of the room to convert to a game
     * @return the game ID (same as room ID) of the newly created game
     * @throws UnauthorisedActionException if the room has fewer than 2 players
     * @throws ResourceNotFoundException   if the room is not found
     *
     */
    public String createGameFromRoom(String roomId) {
        Room room = roomService.getRoom(roomId);
        if (room == null) {
            logger.warn("Game creation failed: room not found for id {}", roomId);
            throw new ResourceNotFoundException("Room not found");
        }

        if (room.getPlayers().size() < 2) {
            logger.warn("Game creation failed: not enough players in room {} (count: {})", roomId,
                    room.getPlayers().size());
            throw new UnauthorisedActionException(
                    "Need at least 2 players to start game. Please wait for more players to join.");
        }

        // Create the actual poker game
        List<String> playerNames = new ArrayList<>(room.getPlayers());
        List<Player> players = playerNames.stream()
                .map(name -> new Player(name, UUID.randomUUID().toString(), room.getBuyIn()))
                .collect(Collectors.toList());

        Game game = new Game(roomId, players, room.getSmallBlind(), room.getBigBlind(), handEvaluator);
        activeGames.put(roomId, game);

        // Broadcast to all players in the room that the game has started
        Map<String, Object> gameStartMessage = new ConcurrentHashMap<>();
        gameStartMessage.put("gameId", roomId);
        gameStartMessage.put("message", "Game started! Redirecting to game...");

        messagingTemplate.convertAndSend("/rooms" + roomId,
                new ApiResponse<>(ResponseMessage.GAME_STARTED.getMessage(), gameStartMessage));

        room.setGameStarted(true);
        logger.info("Game created and started for room: {} with {} players", roomId, players.size());


        startNewHand(roomId);

        return roomId;
    }

    /**
     * Starts a new hand of poker for the specified game.
     * Resets game state, deals cards, posts blinds, and broadcasts initial state.
     *
     * @param gameId The unique identifier of the game
     */
    public void startNewHand(String gameId) {
        Game game = getGame(gameId);
        if (game == null) {
            logger.warn("Cannot start new hand - game {} not found", gameId);
            return;
        }
        logger.info("Starting new hand for game: {}", gameId);

        // Was having issues with duplicate calls
        if (game.isGameOver()) {
            logger.warn("Cannot start new hand - game {} is over", gameId);
            return;
        }
        // Weird structure here and in resetForNewHand because I wanted the game to hang
        // at the end if the game was over and show the winner message for long
        boolean gameEnded = game.resetForNewHand();
        if (gameEnded) {
            logger.warn("Game {} became over after reset", gameId);
            handleGameEnd(gameId);
            return;
        }

        game.dealHoleCards();
        game.postBlinds();
        gameStateService.broadcastGameState(gameId, game);

        logger.info("New hand started successfully for game: {} | Current player: {} | Phase: {} | Pot: {}",
                gameId, game.getCurrentPlayer().getName(), game.getCurrentPhase(), game.getPot());
    }

    /**
     * Removes a player from an active game. If the last player leaves or only
     * one player remains, the game ends and associated resources are cleaned up.
     * If the leaving player is the current player, advances to the next player.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the name of the player leaving the game
     * @throws BadRequestException       if game or player not found
     * @throws ResourceNotFoundException if the game is not found
     */
    public void leaveGame(String gameId, String playerName) {
        Game game = getGame(gameId);
        if (game == null) {
            logger.warn("Remove player failed: game not found for id {} (player: {})", gameId, playerName);
            throw new ResourceNotFoundException("Game not found");
        }

        // Keep leave flow serialized with player actions to avoid transient stale-seat
        // reads while another thread mutates turn state.
        synchronized (game) {
            // Room may already be destroyed (e.g. host left via room flow).
            // In that case, clean up the game without attempting room-backed broadcasts.
            if (roomService.getRoom(gameId) == null) {
                logger.info(
                        "Room {} not found while removing player {} from game {}. Cleaning up game without broadcast.",
                        gameId,
                        playerName,
                        gameId);
                activeGames.remove(gameId);
                return;
            }

            // Find and remove the player from the game
            Player playerToRemove = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElse(null);

            if (playerToRemove == null) {
                logger.warn("Remove player failed: player '{}' not found in game {}", playerName, gameId);
                throw new BadRequestException("Player not found in game");
            }

            // Check if the leaving player was the current player.
            boolean wasCurrentPlayer = !game.getActivePlayers().isEmpty()
                    && game.getCurrentPlayer().equals(playerToRemove);

            // Remove player and reconcile turn index to prevent stale current-player
            // pointer.
            game.removePlayerFromGame(playerToRemove);

            logger.info("Player {} left game {} | Remaining players: {}",
                    playerName, gameId, game.getPlayers().size());

            // Check if no players left in the game
            if (game.getPlayers().isEmpty()) {
                logger.info("All players left game {}, destroying game and room", gameId);

                // Clean up game and room data
                activeGames.remove(gameId);
                roomService.destroyRoom(gameId);
            } else {
                logger.info("Game {} continues with {} players", gameId, game.getPlayers().size());

                // If only one player remains, end the game immediately
                if (game.getPlayers().size() == 1) {
                    logger.info("Only one player remaining in game {}, ending game", gameId);
                    handleGameEnd(gameId);
                    return;
                }

                // If the leaving player was the current player, advance to the next player
                if (wasCurrentPlayer && !game.getActivePlayers().isEmpty()) {
                    game.nextPlayer();
                }

                if (roomService.getRoom(gameId) == null) {
                    logger.info("Room {} was destroyed during leave flow. Cleaning up active game state.", gameId);
                    activeGames.remove(gameId);
                    return;
                }

                gameStateService.broadcastGameState(gameId, game);
            }
        }
    }

    /**
     * Handles the end of a game when all players except one have been eliminated.
     * Broadcasts the game end event to all participants and cleans up game
     * resources.
     *
     * @param gameId the unique identifier of the game
     */
    public void handleGameEnd(String gameId) {
        Game game = getGame(gameId);
        if (game == null)
            return;

        Player winner;
        synchronized (game) {
            // Find the winner (last remaining player)
            winner = game.getActivePlayers().stream()
                    .filter(player -> !player.getIsDisconnected())
                    .findFirst()
                    .orElse(null);

            if (winner == null) {
                winner = game.getPlayers().stream()
                        .filter(p -> !p.getIsOut() && !p.getIsDisconnected())
                        .findFirst()
                        .orElse(game.getPlayers().stream().findFirst().orElse(null));
            }
        }

        if (winner == null) {
            logger.warn("Cannot broadcast game end for {} - no winner could be determined", gameId);
            return;
        }

        gameStateService.broadcastGameEnd(gameId, winner);

        // Wait a few seconds for players to see the result, then destroy the room and
        // game, on a different thread
        eventPublisher.publishEvent(new GameCleanupEvent(gameId, GAME_END_DISPLAY_DELAY_MS));
    }

    /**
     * Cleans up the finished game so it no longer uses resources
     *
     * @param gameId id of the game that is now done
     */

    public void performGameCleanup(String gameId) {
        activeGames.remove(gameId);
        roomService.destroyRoom(gameId);
        logger.info("Game {} and associated room cleaned up", gameId);
    }

    /**
     * Retrieves a game by its unique identifier.
     *
     * @param gameId the unique identifier of the game to retrieve
     * @return the Game object if found, null otherwise
     */
    public Game getGame(String gameId) {
        return activeGames.get(gameId);
    }

    /**
     * Checks if a game with the specified ID exists in the active games.
     *
     * @param gameId the unique identifier of the game to check
     * @return true if the game exists in active games, false otherwise
     */
    public boolean gameExists(String gameId) {
        return activeGames.containsKey(gameId);
    }

    /**
     * Checks whether a player currently exists in an active game.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the player name to check
     * @return true if the player exists in the game, false otherwise
     */
    public boolean playerExistsInGame(String gameId, String playerName) {
        Game game = getGame(gameId);
        if (game == null) {
            return false;
        }

        return game.getPlayers().stream()
                .anyMatch(player -> player.getName().equals(playerName));
    }

    /**
     * Marks an active player as disconnected and broadcasts updated game state.
     * Disconnected players stay in the game seat while reconnect grace is active.
     *
     * @param gameId                    the game identifier
     * @param playerName                the disconnected player name
     * @param disconnectDeadlineEpochMs disconnect grace expiry timestamp (UTC epoch
     *                                  ms)
     */
    public void markPlayerDisconnected(String gameId, String playerName, long disconnectDeadlineEpochMs) {
        Game game = getGame(gameId);
        if (game == null) {
            return;
        }

        synchronized (game) {
            Player player = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElse(null);

            if (player == null || player.getIsDisconnected()) {
                return;
            }

            player.setDisconnected(true);
            player.setDisconnectDeadlineEpochMs(disconnectDeadlineEpochMs);

            if (roomService.getRoom(gameId) != null) {
                gameStateService.broadcastGameState(gameId, game);
            }
        }
    }

    /**
     * Clears disconnect flag for a player that reconnects within grace period.
     *
     * @param gameId     the game identifier
     * @param playerName the reconnecting player name
     */
    public void markPlayerReconnected(String gameId, String playerName) {
        Game game = getGame(gameId);
        if (game == null) {
            return;
        }

        synchronized (game) {
            Player player = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElse(null);

            if (player == null || !player.getIsDisconnected()) {
                return;
            }

            player.setDisconnected(false);
            player.setDisconnectDeadlineEpochMs(null);

            if (roomService.getRoom(gameId) != null) {
                gameStateService.broadcastGameState(gameId, game);
            }
        }
    }

    /**
     * Determines whether the specified player may claim an immediate win because
     * all other non-out players are disconnected.
     *
     * @param gameId     game identifier
     * @param playerName claimant name
     * @return true if the claim is currently valid
     */
    public boolean canPlayerClaimWin(String gameId, String playerName) {
        Game game = getGame(gameId);
        if (game == null) {
            return false;
        }

        synchronized (game) {
            return canPlayerClaimWinInternal(game, playerName);
        }
    }

    /**
     * Claims an immediate game win for a connected player when all other
     * non-out players are disconnected.
     *
     * @param gameId     game identifier
     * @param playerName claimant name
     */
    public void claimWin(String gameId, String playerName) {
        Game game = getGame(gameId);
        if (game == null) {
            throw new ResourceNotFoundException("Game not found");
        }

        List<String> disconnectedOpponents;
        synchronized (game) {
            if (!canPlayerClaimWinInternal(game, playerName)) {
                throw new UnauthorisedActionException("Cannot claim win right now.");
            }

            disconnectedOpponents = game.getPlayers().stream()
                    .filter(player -> !player.getName().equals(playerName))
                    .filter(player -> !player.getIsOut())
                    .filter(Player::getIsDisconnected)
                    .map(Player::getName)
                    .toList();
        }

        for (String opponentName : disconnectedOpponents) {
            if (roomService.getRoom(gameId) != null) {
                roomService.leaveRoom(gameId, opponentName, false);
            }
            if (playerExistsInGame(gameId, opponentName)) {
                leaveGame(gameId, opponentName);
            }
        }
    }

    private boolean canPlayerClaimWinInternal(Game game, String playerName) {
        List<Player> eligiblePlayers = game.getPlayers().stream()
                .filter(player -> !player.getIsOut())
                .toList();

        if (eligiblePlayers.size() < 2) {
            return false;
        }

        Player claimant = eligiblePlayers.stream()
                .filter(player -> player.getName().equals(playerName))
                .findFirst()
                .orElse(null);

        if (claimant == null || claimant.getIsDisconnected()) {
            return false;
        }

        List<Player> others = eligiblePlayers.stream()
                .filter(player -> !player.getName().equals(playerName))
                .toList();

        return !others.isEmpty() && others.stream().allMatch(Player::getIsDisconnected);
    }
}
