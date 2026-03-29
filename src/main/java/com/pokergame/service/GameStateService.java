package com.pokergame.service;

import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.dto.response.PlayerNotificationResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.PlayerStatus;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service class responsible for managing and broadcasting game state.
 * Handles all game state updates, notifications, and WebSocket broadcasts.
 */
@Service
public class GameStateService {

    private static final Logger logger = LoggerFactory.getLogger(GameStateService.class);

    private final SimpMessagingTemplate messagingTemplate;

    private final RoomService roomService;

    public GameStateService(RoomService roomService, SimpMessagingTemplate messagingTemplate) {
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Builds the current public game-state snapshot for REST consumers.
     *
     * @param gameId game identifier
     * @param game   active game instance
     * @return public game-state DTO for the requested game
     */
    public PublicGameStateResponse getPublicGameStateSnapshot(String gameId, Game game) {
        return buildPublicGameStateResponse(gameId, game);
    }

    /**
     * Builds the private game-state snapshot for a specific player.
     *
     * @param game       active game instance
     * @param playerName authenticated player name
     * @return private state containing the player's hole cards
     */
    public PrivatePlayerState getPrivatePlayerStateSnapshot(Game game, String playerName) {
        Player player = game.getPlayers().stream()
                .filter(p -> p.getName().equals(playerName))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Player not found in game"));

        return buildPrivatePlayerState(player);
    }

    /**
     * Broadcasts the current game state to all players in the game.
     * Each player receives a personalised view showing only their own hole cards.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     * @throws BadRequestException if the game is null
     */
    @Async("gameExecutor")
    public void broadcastGameState(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast game state - game {} not found", gameId);
            throw new BadRequestException("Trying to broadcast state for a non existent room: " + gameId);
        }

        logger.debug("Broadcasting game state for game {}", gameId);

        PublicGameStateResponse publicResponse;
        List<PrivatePlayerState> privateStates = new ArrayList<>();
        List<String> encodedNames = new ArrayList<>();

        synchronized (game) {
            publicResponse = buildPublicGameStateResponse(gameId, game);
            for (Player targetPlayer : game.getPlayers()) {
                String encodedPlayerName = java.net.URLEncoder.encode(
                        targetPlayer.getName(),
                        java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
                encodedNames.add(encodedPlayerName);
                privateStates.add(buildPrivatePlayerState(targetPlayer));
            }
        }

        messagingTemplate.convertAndSend("/game/" + gameId, publicResponse);

        // Sending a personalised game state to each player
        for (int i = 0; i < privateStates.size(); i++) {
            messagingTemplate.convertAndSend(
                    "/game/" + gameId + "/player-name/" + encodedNames.get(i) + "/private",
                    privateStates.get(i));
        }
    }

    /**
     * Broadcasts showdown results with winner information to all players.
     * Reveals hole cards, hand ranks, and best hands for winning players only.
     *
     * @param gameId            the unique identifier of the game
     * @param game              the Game object containing the current state
     * @param winners           the list of Player objects who won the hand
     * @param winningsPerPlayer the number of chips each winner receives
     */
    @Async("gameExecutor")
    public void broadcastShowdownResults(String gameId, Game game, List<Player> winners, int winningsPerPlayer) {
        if (game == null) {
            logger.warn("Cannot broadcast showdown - game {} not found", gameId);
            return;
        }

        PublicGameStateResponse showdownResponse;
        List<String> winnerNames;

        synchronized (game) {
            // Get room information
            Room room = roomService.getRoom(gameId);
            int maxPlayers = room != null ? room.getMaxPlayers() : 0;

            // Get current player information
            Player currentPlayer = game.getActivePlayers().isEmpty() ? null : game.getCurrentPlayer();
            if (currentPlayer != null) {
                logger.debug("Showdown - current player: {} (ID: {})", currentPlayer.getName(),
                        currentPlayer.getPlayerId());
            } else {
                logger.debug("Showdown - no active players found");
            }
            String currentPlayerName = currentPlayer != null ? currentPlayer.getName() : null;
            String currentPlayerId = currentPlayer != null ? currentPlayer.getPlayerId() : null;

            // Get winner names
            winnerNames = winners.stream().map(Player::getName).toList();

            // Check if this is an actual showdown (i.e. not a win by fold)
            boolean isActualShowdown = winners.stream()
                    .anyMatch(w -> w.getHandRank() != null && w.getHandRank() != com.pokergame.enums.HandRank.NO_HAND);
            String smallBlindPlayerId = game.getSmallBlindPlayerId();
            String bigBlindPlayerId = game.getBigBlindPlayerId();

            // Convert players to PublicPlayerState DTOs with showdown information
            List<PublicPlayerState> playersList = game.getPlayers().stream().map(player -> {
                boolean isWinner = winners.contains(player);
                boolean isActive = !player.getHasFolded() && !player.getIsOut();
                String status = resolvePlayerStatus(player);
                return new PublicPlayerState(
                        player.getPlayerId(),
                        player.getName(),
                        player.getChips(),
                        player.getCurrentBet(),
                        status,
                        player.getIsAllIn(),
                        false, // isCurrentPlayer not relevant during showdown
                        player.getHasFolded(),
                        player.getPlayerId().equals(smallBlindPlayerId),
                        player.getPlayerId().equals(bigBlindPlayerId),
                        isActive ? player.getHandRank() : null,
                        isActive ? player.getBestHand() : List.of(),
                        isWinner,
                        isWinner ? winningsPerPlayer : 0,
                        (isActive && isActualShowdown) ? player.getHoleCards() : null,
                        player.getDisconnectDeadlineEpochMs());
            }).toList();

            // Create PublicGameStateResponse DTO with showdown information
            showdownResponse = new PublicGameStateResponse(
                    maxPlayers,
                    game.getPot(),
                    game.getPotBreakdown(),
                    game.getUncalledAmount(),
                    game.getCurrentPhase(),
                    game.getCurrentHighestBet(),
                    game.getCommunityCards(),
                    playersList,
                    currentPlayerName,
                    currentPlayerId,
                    winnerNames,
                    winningsPerPlayer);
        }

        // Broadcast showdown results to all players
        messagingTemplate.convertAndSend("/game/" + gameId, showdownResponse);

        logger.info("Broadcasted showdown results for game {} with {} winner(s): {}",
                gameId, winnerNames.size(), winnerNames);
        logger.debug("Showdown game state - winners: {}, winnerCount: {}, winnings per player: {}",
                winnerNames, winnerNames.size(), winningsPerPlayer);
    }

    /**
     * Broadcasts game state with auto-advance information when all players are
     * all-in.
     * Includes special flags and messages to notify clients of automatic
     * progression.
     *
     * @param gameId          the unique identifier of the game
     * @param game            the Game object containing the current state
     * @param isAutoAdvancing true if auto-advancing to showdown, false otherwise
     * @param message         the message to display to players about auto-advance
     *                        status
     */
    @Async("gameExecutor")
    public void broadcastGameStateWithAutoAdvance(String gameId, Game game, boolean isAutoAdvancing, String message) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance state - game {} not found", gameId);
            return;
        }

        PublicGameStateResponse autoAdvanceResponse;

        synchronized (game) {
            // Get room information
            Room room = roomService.getRoom(gameId);
            int maxPlayers = room != null ? room.getMaxPlayers() : 0;

            // Get current player information
            Player currentPlayer = game.getActivePlayers().isEmpty() ? null : game.getCurrentPlayer();
            if (currentPlayer != null) {
                logger.debug("Auto-advance - current player: {} (ID: {})", currentPlayer.getName(),
                        currentPlayer.getPlayerId());
            } else {
                logger.debug("Auto-advance - no active players found");
            }
            String currentPlayerName = currentPlayer != null ? currentPlayer.getName() : null;
            String currentPlayerId = currentPlayer != null ? currentPlayer.getPlayerId() : null;
            String smallBlindPlayerId = game.getSmallBlindPlayerId();
            String bigBlindPlayerId = game.getBigBlindPlayerId();

            // Convert players to PlayerState DTOs
            List<PublicPlayerState> playersList = game.getPlayers().stream().map(player -> {
                String status = resolvePlayerStatus(player);
                boolean isCurrentPlayer = player.equals(currentPlayer);
                return new PublicPlayerState(
                        player.getPlayerId(),
                        player.getName(),
                        player.getChips(),
                        player.getCurrentBet(),
                        status,
                        player.getIsAllIn(),
                        isCurrentPlayer,
                        player.getHasFolded(),
                        player.getPlayerId().equals(smallBlindPlayerId),
                        player.getPlayerId().equals(bigBlindPlayerId),
                        player.getHandRank(),
                        List.of(),
                        null,
                        null,
                        null,
                        player.getDisconnectDeadlineEpochMs());
            }).toList();

            // Create PublicGameStateResponse DTO with auto-advance fields
            autoAdvanceResponse = new PublicGameStateResponse(
                    maxPlayers,
                    game.getPot(),
                    game.getPotBreakdown(),
                    game.getUncalledAmount(),
                    game.getCurrentPhase(),
                    game.getCurrentHighestBet(),
                    game.getCommunityCards(),
                    playersList,
                    currentPlayerName,
                    currentPlayerId,
                    null, // winners
                    null, // winningsPerPlayer
                    isAutoAdvancing,
                    message,
                    null,
                    null);
        }

        logger.info("Broadcasting auto-advance state for game {}: {}", gameId, message);
        messagingTemplate.convertAndSend("/game/" + gameId, autoAdvanceResponse);
    }

    /**
     * Broadcasts a notification that auto-advance to showdown is starting.
     * Sent when no further betting actions are possible.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     */
    @Async("gameExecutor")
    public void broadcastAutoAdvanceNotification(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance notification - game {} not found", gameId);
            return;
        }

        logger.info(
                "Broadcasting auto-advance notification for game {}: No further betting actions are possible, advancing to showdown",
                gameId);

        messagingTemplate.convertAndSend("/game/" + gameId,
                new PlayerNotificationResponse(ResponseMessage.AUTO_ADVANCE_START.getMessage(),
                        "No further betting actions are possible. Auto-advancing to showdown...", null, gameId));
    }

    /**
     * Broadcasts a notification that auto-advance has completed.
     * Sent after all community cards have been dealt and showdown is ready.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     */
    @Async("gameExecutor")
    public void broadcastAutoAdvanceComplete(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance complete - game {} not found", gameId);
            return;
        }

        logger.info("Broadcasting auto-advance complete for game {}: {}", gameId, game.getCommunityCards());
        messagingTemplate.convertAndSend("/game/" + gameId,
                new PlayerNotificationResponse(ResponseMessage.AUTO_ADVANCE_COMPLETE.getMessage(), "", null, gameId));
    }

    /**
     * Sends a notification message to a specific player.
     * Used for action conversions and other player-specific alerts.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the name of the player to notify
     * @param message    the notification message content
     */
    @Async("gameExecutor")
    public void sendPlayerNotification(String gameId, String playerName, String message) {
        PlayerNotificationResponse notification = new PlayerNotificationResponse(
                "PLAYER_NOTIFICATION",
                message,
                playerName,
                gameId);

        messagingTemplate.convertAndSend("/game/" + gameId, notification);
    }

    /**
     * Sends a private notification message to a specific player's private channel.
     * Used for action errors or private-only feedback.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the name of the player to notify
     * @param message    the notification message content
     * @param type       the type of notification (e.g. "ACTION_ERROR")
     */
    @Async("gameExecutor")
    public void sendPrivatePlayerNotification(String gameId, String playerName, String message, String type) {
        String encodedPlayerName = java.net.URLEncoder.encode(
                playerName,
                java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");

        PlayerNotificationResponse notification = new PlayerNotificationResponse(
                type,
                message,
                playerName,
                gameId);

        messagingTemplate.convertAndSend(
                "/game/" + gameId + "/player-name/" + encodedPlayerName + "/private",
                notification);
    }

    /**
     * Broadcasts a game end message when a winner is determined.
     * Sent when only one player remains with chips.
     *
     * @param gameId the unique identifier of the game
     * @param winner the Player object representing the game winner
     * @param isForfeit true if the game ended due to a player leaving/disconnecting
     */
    @Async("gameExecutor")
    public void broadcastGameEnd(String gameId, Player winner, boolean isForfeit) {
        if (winner == null) {
            logger.warn("Cannot broadcast game end for {} - winner is null", gameId);
            return;
        }

        Map<String, Object> gameEndData = new HashMap<>();
        gameEndData.put("type", ResponseMessage.GAME_END.getMessage());
        gameEndData.put("winner", winner.getName());
        gameEndData.put("winnerChips", winner.getChips());
        gameEndData.put("gameId", gameId);
        gameEndData.put("isForfeit", isForfeit);
        gameEndData.put("message", "🏆 " + winner.getName() + " wins the game with " + winner.getChips() + " chips!");

        messagingTemplate.convertAndSend("/game/" + gameId, (Object) gameEndData);

        logger.info("Game {} completed - Winner: {} with {} chips",
                gameId, winner.getName(), winner.getChips());
    }

    /**
     * Builds a PublicGameStateResponse object to be shown to all players in a game.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     * @return a {@link PublicGameStateResponse}
     * 
     * @throws ResourceNotFoundException if the room is not found
     */
    private PublicGameStateResponse buildPublicGameStateResponse(String gameId, Game game) {
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            logger.warn("Game state build failed: room not found for gameId {}", gameId);
            throw new ResourceNotFoundException("Room not found");
        }
        List<PublicPlayerState> playerStateList = new ArrayList<>();
        Player currentPlayer = game.getActivePlayers().isEmpty() ? null : game.getCurrentPlayer();
        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();
        if (currentPlayer != null) {
            logger.debug("Building game state - current player: {} (ID: {})", currentPlayer.getName(),
                    currentPlayer.getPlayerId());
        } else {
            logger.debug("Building game state - no active players found");
        }
        for (Player player : game.getPlayers()) {
            String status = resolvePlayerStatus(player);
            playerStateList.add(new PublicPlayerState(
                    player.getPlayerId(),
                    player.getName(),
                    player.getChips(),
                    player.getCurrentBet(),
                    status,
                    player.getIsAllIn(),
                    player.equals(currentPlayer),
                    player.getHasFolded(),
                    player.getPlayerId().equals(smallBlindPlayerId),
                    player.getPlayerId().equals(bigBlindPlayerId),
                    null,
                    null,
                    null,
                    null,
                    null,
                    player.getDisconnectDeadlineEpochMs()));
        }

        String claimWinPlayerName = computeClaimWinPlayerName(game);
        boolean claimWinAvailable = claimWinPlayerName != null;

        return new PublicGameStateResponse(
                room.getMaxPlayers(),
                game.getPot(),
                game.getPotBreakdown(),
                game.getUncalledAmount(),
                game.getCurrentPhase(),
                game.getCurrentHighestBet(),
                game.getCommunityCards(),
                playerStateList,
                currentPlayer != null ? currentPlayer.getName() : null,
                currentPlayer != null ? currentPlayer.getPlayerId() : null,
                claimWinAvailable,
                claimWinPlayerName);

    }

    private String resolvePlayerStatus(Player player) {
        if (player.getIsOut()) {
            return PlayerStatus.OUT.getStatus();
        }
        if (player.getIsDisconnected()) {
            return PlayerStatus.DISCONNECTED.getStatus();
        }
        if (player.getHasFolded()) {
            return PlayerStatus.FOLDED.getStatus();
        }
        if (player.getIsAllIn()) {
            return PlayerStatus.ALL_IN.getStatus();
        }
        return PlayerStatus.ACTIVE.getStatus();
    }

    private String computeClaimWinPlayerName(Game game) {
        List<Player> eligiblePlayers = game.getPlayers().stream()
                .filter(player -> !player.getIsOut())
                .toList();

        if (eligiblePlayers.size() < 2) {
            return null;
        }

        List<Player> connectedPlayers = eligiblePlayers.stream()
                .filter(player -> !player.getIsDisconnected())
                .toList();

        boolean anyDisconnected = eligiblePlayers.stream().anyMatch(Player::getIsDisconnected);

        if (!anyDisconnected || connectedPlayers.size() != 1) {
            return null;
        }

        return connectedPlayers.get(0).getName();
    }

    /**
     * Builds a PrivatePlayerState object to show to specific players.
     *
     * @param player object
     * @return a {@link PrivatePlayerState}
     */
    private PrivatePlayerState buildPrivatePlayerState(Player player) {
        return new PrivatePlayerState(
                player.getPlayerId(),
                player.getHoleCards());

    }

}
