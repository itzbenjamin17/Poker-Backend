package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
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
import com.pokergame.persistence.DurableTransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;

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

    /**
     * Creates the projection service with room authorization context and the STOMP
     * publisher used after durable commits.
     *
     * @param roomService       room membership and host context
     * @param messagingTemplate STOMP publisher
     */
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
        synchronized (game) {
            return buildPublicGameStateResponse(gameId, game);
        }
    }

    /**
     * Builds the private game-state snapshot for a specific player.
     *
     * @param game       active game instance
     * @param playerName authenticated player name
     * @return private state containing the player's hole cards
     */
    public PrivatePlayerState getPrivatePlayerStateSnapshot(Game game, String playerName) {
        synchronized (game) {
            Player player = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Player not found in game"));

            return buildPrivatePlayerState(player);
        }
    }

    /**
     * Broadcasts the current game state to all players in the game.
     * Each player receives a personalised view showing only their own hole cards.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     * @throws BadRequestException if the game is null
     */
    public void broadcastGameState(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast game state - game {} not found", gameId);
            throw new BadRequestException("Trying to broadcast state for a non existent room: " + gameId);
        }

        logger.debug("Broadcasting game state for game {}", gameId);

        PublicGameStateResponse publicResponse;
        Map<String, PrivatePlayerState> playerPrivateStates = new HashMap<>();

        synchronized (game) {
            publicResponse = buildPublicGameStateResponse(gameId, game);
            for (Player player : game.getPlayers()) {
                playerPrivateStates.put(player.getName(), buildPrivatePlayerState(player));
            }
        }

        sendAfterCommit("/game/" + gameId, publicResponse);

        // Sending a personalised game state to each player using secure user destinations
        playerPrivateStates.forEach((playerName, privateState) -> {
            // Principal name is playerName:roomId
            String compositeName = playerName + ":" + gameId;
            sendToUserAfterCommit(
                    compositeName,
                    "/queue/private",
                    privateState);
        });
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

            // Showdown means no one is waiting to act - reporting a stale "current
            // player" pointer here previously made the client briefly think it was
            // someone's turn (showing Fold/Check) between the showdown broadcast and
            // the later GAME_END/next-hand broadcast, so these are intentionally null.
            String currentPlayerName = null;
            String currentPlayerId = null;

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
                        player.getDisconnectDeadlineEpochMs(),
                        player.getIsReadyForNextHand());
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
                    winningsPerPlayer,
                    null,
                    null,
                    game.isReadyCountdownActive(),
                    game.getReadyCountdownDeadlineEpochMs(),
                    null,
                    null);
        }

        // Broadcast showdown results to all players
        sendAfterCommit("/game/" + gameId, showdownResponse);

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
     * @param gameId  the unique identifier of the game
     * @param game    the Game object containing the current state
     * @param message the message to display to players about auto-advance
     *                status
     */
    public void broadcastGameStateWithAutoAdvance(String gameId, Game game, String message) {
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
                        player.getDisconnectDeadlineEpochMs(),
                        player.getIsReadyForNextHand());
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
                    true,
                    message,
                    game.isReadyCountdownActive(),
                    game.getReadyCountdownDeadlineEpochMs(),
                    null,
                    null);
        }

        logger.info("Broadcasting auto-advance state for game {}: {}", gameId, message);
        sendAfterCommit("/game/" + gameId, autoAdvanceResponse);
    }

    /**
     * Broadcasts a notification that auto-advance to showdown is starting.
     * Sent when no further betting actions are possible.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     */
    public void broadcastAutoAdvanceNotification(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance notification - game {} not found", gameId);
            return;
        }

        logger.info(
                "Broadcasting auto-advance notification for game {}: No further betting actions are possible, advancing to showdown",
                gameId);

        sendAfterCommit("/game/" + gameId,
                new PlayerNotificationResponse(ResponseMessage.AUTO_ADVANCE_START,
                        "No further betting actions are possible. Auto-advancing to showdown...", null, gameId));
    }

    /**
     * Broadcasts a notification that auto-advance has completed.
     * Sent after all community cards have been dealt and showdown is ready.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     */
    public void broadcastAutoAdvanceComplete(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance complete - game {} not found", gameId);
            return;
        }

        logger.info("Broadcasting auto-advance complete for game {}: {}", gameId, game.getCommunityCards());
        sendAfterCommit("/game/" + gameId,
                new PlayerNotificationResponse(ResponseMessage.AUTO_ADVANCE_COMPLETE, "", null, gameId));
    }

    /**
     * Sends a notification message to a specific player.
     * Used for action conversions and other player-specific alerts.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the name of the player to notify
     * @param message    the notification message content
     */
    public void sendPlayerNotification(String gameId, String playerName, String message) {
        PlayerNotificationResponse notification = new PlayerNotificationResponse(
                ResponseMessage.PLAYER_NOTIFICATION,
                message,
                playerName,
                gameId);

        sendAfterCommit("/game/" + gameId, notification);
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
    public void sendPrivatePlayerNotification(String gameId, String playerName, String message, ResponseMessage type) {
        PlayerNotificationResponse notification = new PlayerNotificationResponse(
                type,
                message,
                playerName,
                gameId);

        // Principal name is playerName:roomId
        String compositeName = playerName + ":" + gameId;
        
        sendToUserAfterCommit(
                compositeName,
                "/queue/private",
                notification);
    }

    /**
     * Broadcasts a game end message when a winner is determined.
     * Sent when only one player remains with chips. Carries a complete, revealed
     * final-state snapshot so the client can freeze it for the post-game review
     * screen, independent of subsequent server cleanup.
     *
     * @param gameId    the unique identifier of the game
     * @param game      the Game object containing the final state
     * @param winner    the Player object representing the game winner
     * @param isForfeit true if the game ended due to a player leaving/disconnecting
     */
    public void broadcastGameEnd(String gameId, Game game, Player winner, boolean isForfeit) {
        if (winner == null) {
            logger.warn("Cannot broadcast game end for {} - winner is null", gameId);
            return;
        }

        String message = "🏆 " + winner.getName() + " wins the game with " + winner.getChips() + " chips!";
        PublicGameStateResponse finalState = null;

        if (game != null) {
            synchronized (game) {
                finalState = buildFinalStateResponse(gameId, game, winner, !isForfeit);
            }
        }

        GameEndResponse gameEndResponse = new GameEndResponse(
                ResponseMessage.GAME_END.getMessage(),
                gameId,
                winner.getName(),
                winner.getChips(),
                isForfeit,
                message,
                finalState);

        sendAfterCommit("/game/" + gameId, gameEndResponse);

        logger.info("Game {} completed - Winner: {} with {} chips",
                gameId, winner.getName(), winner.getChips());
    }

    /**
     * Builds a revealed final-state projection for the game-end review screen. Unlike
     * the live-game projection, this reveals hole cards, hand ranks, and best hands
     * for all non-folded, non-out players when {@code revealHoleCards} is true, and
     * marks the winner explicitly.
     *
     * @param gameId          the unique identifier of the game
     * @param game            the Game object containing the final state
     * @param winner          the winning player, or null if undetermined
     * @param revealHoleCards true to reveal hole cards (showdown path); false to
     *                        withhold them (forfeit path)
     * @return a {@link PublicGameStateResponse} representing the frozen final state
     */
    private PublicGameStateResponse buildFinalStateResponse(String gameId, Game game, Player winner,
            boolean revealHoleCards) {
        Room room = roomService.getRoom(gameId);
        int maxPlayers = room != null ? room.getMaxPlayers() : 0;

        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playersList = game.getPlayers().stream().map(player -> {
            boolean isWinner = winner != null && winner.equals(player);
            boolean isActive = !player.getHasFolded() && !player.getIsOut();
            String status = resolvePlayerStatus(player);
            return new PublicPlayerState(
                    player.getPlayerId(),
                    player.getName(),
                    player.getChips(),
                    player.getCurrentBet(),
                    status,
                    player.getIsAllIn(),
                    false,
                    player.getHasFolded(),
                    player.getPlayerId().equals(smallBlindPlayerId),
                    player.getPlayerId().equals(bigBlindPlayerId),
                    isActive ? player.getHandRank() : null,
                    isActive ? player.getBestHand() : List.of(),
                    isWinner,
                    null,
                    (revealHoleCards && isActive) ? player.getHoleCards() : null,
                    player.getDisconnectDeadlineEpochMs(),
                    player.getIsReadyForNextHand());
        }).toList();

        List<String> winnerNames = winner != null ? List.of(winner.getName()) : List.of();

        return new PublicGameStateResponse(
                maxPlayers,
                game.getPot(),
                game.getPotBreakdown(),
                game.getUncalledAmount(),
                game.getCurrentPhase(),
                game.getCurrentHighestBet(),
                game.getCommunityCards(),
                playersList,
                null,
                null,
                winnerNames,
                winner != null ? winner.getChips() : null,
                false,
                null,
                game.isReadyCountdownActive(),
                game.getReadyCountdownDeadlineEpochMs(),
                null,
                null);
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
                    player.getDisconnectDeadlineEpochMs(),
                    player.getIsReadyForNextHand()));
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
                null,
                null,
                null,
                null,
                game.isReadyCountdownActive(),
                game.getReadyCountdownDeadlineEpochMs(),
                claimWinAvailable,
                claimWinPlayerName);

    }

    /**
     * Applies a single precedence order to overlapping player flags so clients receive
     * one unambiguous render state.
     *
     * @param player player whose status is projected
     * @return client-visible status value
     */
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

    /**
     * Projects claim availability only when exactly one eligible player is connected
     * and at least one opponent is disconnected.
     *
     * @param game game whose claim affordance is projected
     * @return eligible claimant name, or {@code null} when no claim is legal
     */
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

        return connectedPlayers.getFirst().getName();
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

    /**
     * Defers shared game-state visibility until recovery can reproduce the same
     * snapshot after a crash.
     *
     * @param destination shared STOMP destination
     * @param payload     committed public payload
     */
    private void sendAfterCommit(String destination, Object payload) {
        DurableTransactionContext.afterCommit(() -> messagingTemplate.convertAndSend(destination, payload));
    }

    /**
     * Applies the same commit barrier to private player data so a player never sees
     * hole cards or action results that exist only in memory.
     *
     * @param user        composite WebSocket principal name
     * @param destination private user destination
     * @param payload     committed private payload
     */
    private void sendToUserAfterCommit(String user, String destination, Object payload) {
        DurableTransactionContext.afterCommit(
                () -> messagingTemplate.convertAndSendToUser(user, destination, payload));
    }

}
