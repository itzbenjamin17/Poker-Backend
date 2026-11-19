package com.pokergame.service;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Coordinates player removals, disconnects, reconnects, disconnect grace expirations, turn
 * timeouts, host transfer, and disconnect win claims across rooms and live games.
 * Once the game is over, game-seat connection tracking and disconnect removals stop; explicit
 * leaves still detach room members when {@code game.isMatchConcluded()}, and
 * {@code markPlayerReconnected} still updates room presence via {@code markRoomMemberConnected}
 * when no finished game blocks it.
 * Every method needs the room lock for the room it works on.
 */
@Component
class PlayerDepartureHandler {
    private static final Logger logger = LoggerFactory.getLogger(PlayerDepartureHandler.class);
    private final RoomService roomService;
    private final GameStateService gameStateService;
    private final GameCompletionHandler completionHandler;
    private final GameReadinessCoordinator readinessCoordinator;
    private final GameTimerRegistry timerRegistry;
    private final ActiveGameRegistry activeGames;
    private final Clock clock;
    private final GameTimingProperties timings;

    PlayerDepartureHandler(
            RoomService roomService,
            GameStateService gameStateService,
            GameCompletionHandler completionHandler,
            GameReadinessCoordinator readinessCoordinator,
            GameTimerRegistry timerRegistry,
            ActiveGameRegistry activeGames,
            Clock clock,
            GameTimingProperties timings) {
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.completionHandler = completionHandler;
        this.readinessCoordinator = readinessCoordinator;
        this.timerRegistry = timerRegistry;
        this.activeGames = activeGames;
        this.clock = clock;
        this.timings = timings;
    }

    /**
     * Removes one or more players from both the room and any active game, abandoning an
     * expired inactive game first if needed.
     *
     * @param roomId      the unique ID of the room/game
     * @param playerNames names of players leaving or timing out
     */
    void removePlayers(String roomId, Collection<String> playerNames) {
        Room room = roomService.getRoom(roomId);
        Game game = activeGames.get(roomId);
        if (room == null) {
            if (game == null || game.isGameOver()) {
                return;
            }
            logger.info("Room {} not found while removing players from game. Cleaning up game without broadcast.",
                    roomId);
            activeGames.remove(roomId);
            return;
        }
        if (game != null && room.isInactivityExpired(clock.millis())) {
            completionHandler.abandonGameDueToInactivity(roomId);
            return;
        }
        removePlayersFromRoomAndGame(roomId, playerNames, room, game);
    }

    /**
     * Marks {@code playerName} as disconnected with the specified reconnect deadline and
     * broadcasts the updated game state.
     *
     * @param gameId                    the unique ID of the game
     * @param playerName                the disconnected player's name
     * @param disconnectDeadlineEpochMs reconnect grace deadline epoch time in milliseconds
     */
    void markPlayerDisconnected(String gameId, String playerName, long disconnectDeadlineEpochMs) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            logger.debug("Cannot mark player disconnected: game not found for gameId={}", gameId);
            return;
        }
        if (game.isPausedForDisconnectedSurvivor() || game.isGameOver()) {
            return;
        }
        Player player = game.findPlayerByName(playerName);
        if (player == null) {
            logger.debug("Cannot mark player disconnected: player {} not found in game {}", playerName, gameId);
            return;
        }
        if (player.isDisconnected()) {
            logger.debug("Player {} already disconnected in game {}", playerName, gameId);
            return;
        }

        player.setDisconnected(true);
        player.setReadyForNextHand(false);
        player.setDisconnectDeadlineEpochMs(disconnectDeadlineEpochMs);
        logger.info("Player {} marked disconnected in game {} with deadlineEpochMs={}",
                playerName, gameId, disconnectDeadlineEpochMs);

        if (roomService.getRoom(gameId) != null) {
            gameStateService.broadcastGameState(game);
        }

        readinessCoordinator.completeReadyCountdownIfEveryoneReady(game);
    }

    /**
     * Clears disconnect state when {@code playerName} reconnects, resolving any pending forfeit win
     * or resuming the ready countdown when enough players are back.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the reconnecting player's name
     */
    void markPlayerReconnected(String gameId, String playerName) {
        Room room = roomService.getRoom(gameId);
        Game game = activeGames.get(gameId);
        if (game != null) {
            if (game.isGameOver()) {
                logger.debug("Skipping reconnect for player {} because game {} is already over", playerName, gameId);
                return;
            }
            Player player = game.findPlayerByName(playerName);
            if (player != null && player.isDisconnected()) {
                player.setDisconnected(false);
                player.setDisconnectDeadlineEpochMs(null);
                logger.info("Player {} marked reconnected in game {}", playerName, gameId);

                if (game.isPausedForDisconnectedSurvivor() && game.countContenders() == 1
                        && game.isContender(player)) {
                    game.clearPausedForDisconnectedSurvivor();
                    completionHandler.handleGameEndWithForfeits(gameId, player, Set.of());
                    markRoomMemberConnected(room, playerName);
                    return;
                }

                if (room != null) {
                    gameStateService.broadcastGameState(game);
                }
                if (game.getReadyGateState() == ReadyGateState.WAITING_FOR_PLAYERS
                        && game.getReadyEligibleCount() >= Room.MIN_PLAYERS) {
                    readinessCoordinator.openReadyGate(game);
                }
            }
        }
        markRoomMemberConnected(room, playerName);
    }

    /**
     * Resolves an immediate disconnect win claim when only disconnected opponents remain, or
     * returns the existing finished-game summary if the game already completed.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the claiming player's name
     * @return the final {@link GameEndResponse}
     * @throws ResourceNotFoundException   if the game or room does not exist or has expired
     * @throws BadRequestException         if the game was already abandoned due to inactivity
     * @throws UnauthorisedActionException if {@code playerName} cannot claim a win right now
     */
    GameEndResponse claimWin(String gameId, String playerName) {
        Game game = activeGames.get(gameId);
        if (game == null || completionHandler.isGameExpired(game)) {
            throw new ResourceNotFoundException("Game not found");
        }
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            throw new ResourceNotFoundException("Room not found");
        }

        completionHandler.rejectExpiredActivity(gameId);
        if (game.isGameOver()) {
            GameEndResponse retained = game.getGameEndResult();
            if (retained != null && retained.abandoned()) {
                throw new BadRequestException(
                        "Game was abandoned due to inactivity.",
                        "GAME_ABANDONED",
                        "Game was abandoned due to inactivity");
            }
            return completionHandler.getGameReview(gameId, playerName);
        }
        if (!game.canClaimWin(playerName)) {
            throw new UnauthorisedActionException("Cannot claim win right now.");
        }
        List<String> opponents = game.disconnectedOpponentsOf(playerName);
        removePlayersFromRoomAndGame(gameId, opponents, room, game);
        return game.getGameEndResult();
    }

    /**
     * Removes a disconnected player whose reconnect grace period has expired, after verifying both
     * membership ID and expected deadline.
     *
     * @param gameId                  the unique ID of the game
     * @param playerName              the disconnected player's name
     * @param expectedMembershipId    the membership ID active when grace expiration was scheduled
     * @param expectedDeadlineEpochMs the expected reconnect deadline epoch time in milliseconds
     */
    void removeDisconnectedPlayer(
            String gameId, String playerName, String expectedMembershipId, long expectedDeadlineEpochMs) {
        Room room = roomService.getRoom(gameId);
        if (room == null || !room.hasPlayer(playerName)) {
            return;
        }
        String currentMembershipId = room.getMembershipId(playerName);
        if (!Objects.equals(expectedMembershipId, currentMembershipId)) {
            return;
        }

        Game game = activeGames.get(gameId);
        if (game != null && game.isGameOver()) {
            logger.debug("Skipping disconnect removal for player {} because game {} is already over", playerName, gameId);
            return;
        }
        if (hasDisconnectGraceExpired(room, game, playerName, expectedDeadlineEpochMs)) {
            removePlayersFromRoomAndGame(gameId, List.of(playerName), room, game);
        }
    }

    /**
     * Handles a claimed turn timeout by forfeiting the current player or abandoning an expired game.
     *
     * @param game the active game instance
     */
    void handleTurnTimeout(Game game) {
        String gameId = game.getGameId();
        String actorToDepart = game.getCurrentPlayer().getName();
        removePlayers(gameId, List.of(actorToDepart));
        logger.info("Player {} timed out after {} ms in game {}; removing from seat",
                actorToDepart, timings.turnAllowanceMs(), gameId);
    }

    private void removePlayersFromRoomAndGame(String roomId, Collection<String> playerNames, Room room, Game game) {
        List<String> departing = List.copyOf(new LinkedHashSet<>(playerNames));
        List<String> departingRoomMembers = departing.stream().filter(room.getPlayers()::contains).toList();
        if (game == null) {
            removeFromLobby(roomId, room, departingRoomMembers);
        } else {
            removeFromGame(roomId, room, game, departing, departingRoomMembers);
        }
    }

    private void removeFromLobby(String roomId, Room room, List<String> departingRoomMembers) {
        if (departingRoomMembers.isEmpty()) {
            return;
        }
        String currentHost = room.getHostName();
        if (currentHost != null && departingRoomMembers.contains(currentHost)) {
            // leaveRoom destroys the whole lobby when the host leaves, so the other departing
            // players need no separate removal.
            roomService.leaveRoom(roomId, currentHost);
        } else {
            roomService.removeMembers(roomId, departingRoomMembers, currentHost);
        }
    }

    private void removeFromGame(
            String roomId, Room room, Game game, List<String> departing, List<String> departingRoomMembers) {
        List<Player> departingGamePlayers = game.getPlayers().stream()
                .filter(p -> departing.contains(p.getName()))
                .toList();
        if (departingRoomMembers.isEmpty() && departingGamePlayers.isEmpty()) {
            return;
        }

        if (game.isPausedForDisconnectedSurvivor()) {
            handleDepartureWhilePausedForDisconnectedSurvivor(
                    roomId, room, game, departingRoomMembers, departingGamePlayers);
            return;
        }

        if (game.isMatchConcluded()) {
            room.setFinished(true);
            completionHandler.handleGameEnd(roomId);
            detachRoomMembers(roomId, room, game, departingRoomMembers);
            return;
        }

        if (departingGamePlayers.isEmpty()) {
            detachRoomMembers(roomId, room, game, departingRoomMembers);
            return;
        }

        List<Player> departingContenders = departingGamePlayers.stream()
                .filter(game::isContender)
                .toList();
        int contendersRemaining = game.countContenders() - departingContenders.size();

        if (contendersRemaining == 1 && !departingContenders.isEmpty()) {
            Player survivor = game.getPlayers().stream()
                    .filter(candidate -> !departing.contains(candidate.getName()))
                    .filter(game::isContender)
                    .findFirst()
                    .orElseThrow();
            handleSingleSurvivorForfeit(roomId, room, game, departingRoomMembers, departingGamePlayers, survivor);
            return;
        }

        for (Player departingPlayer : departingGamePlayers) {
            game.removePlayer(departingPlayer);
        }
        detachRoomMembers(roomId, room, game, departingRoomMembers);

        if (contendersRemaining <= 0) {
            completionHandler.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
            return;
        }

        if (roomService.getRoom(roomId) == null) {
            logger.info("Room {} was destroyed during leave flow. Cleaning up active game state.", roomId);
            activeGames.remove(roomId);
            return;
        }

        if (game.getCurrentPhase() == GamePhase.SHOWDOWN || game.isReadyCountdownActive()) {
            gameStateService.broadcastGameState(game);
        } else {
            readinessCoordinator.applyTurnOutcomeAndOpenReadyGateIfNeeded(game, game.advanceAfterPlayerRemoval(), false);
        }

        readinessCoordinator.completeReadyCountdownIfEveryoneReady(game);
    }

    // Keeps the current host if still present, or otherwise picks the first remaining connected
    // contender, disconnected contender, or room member in join order.
    private String selectNextHost(Room room, Game game, Collection<String> departingMembers) {
        String currentHost = room.getHostName();
        List<String> remaining = room.getPlayers().stream()
                .filter(name -> !departingMembers.contains(name))
                .toList();
        if (remaining.isEmpty()) {
            return null;
        }
        if (currentHost != null && remaining.contains(currentHost)) {
            return currentHost;
        }
        if (game != null) {
            for (String name : remaining) {
                Player player = game.findPlayerByName(name);
                if (player != null && game.isContender(player) && !player.isDisconnected()) {
                    return name;
                }
            }
            for (String name : remaining) {
                Player player = game.findPlayerByName(name);
                if (player != null && game.isContender(player)) {
                    return name;
                }
            }
        }
        return remaining.getFirst();
    }

    private void handleDepartureWhilePausedForDisconnectedSurvivor(
            String roomId,
            Room room,
            Game game,
            List<String> roomMembersToLeave,
            List<Player> departingGamePlayers) {
        if (roomMembersToLeave.isEmpty()) {
            return;
        }
        for (Player departingPlayer : departingGamePlayers) {
            if (roomMembersToLeave.contains(departingPlayer.getName())
                    && game.isContender(departingPlayer)) {
                departingPlayer.setIsOut();
            }
        }
        List<String> membersToDetach = game.countContenders() == 0
                ? room.getPlayers()
                : roomMembersToLeave;
        String survivingHost = selectNextHost(room, game, membersToDetach);
        roomService.removeMembers(roomId, membersToDetach, survivingHost);
        if (roomService.getRoom(roomId) != null) {
            gameStateService.broadcastGameState(game);
        }
        completionHandler.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
    }

    private void handleSingleSurvivorForfeit(
            String roomId,
            Room room,
            Game game,
            List<String> departingRoomMembers,
            List<Player> departingGamePlayers,
            Player survivor) {
        if (survivor.isDisconnected()) {
            game.pauseForDisconnectedSurvivor(departingGamePlayers);
            timerRegistry.cancelAllForGame(roomId, false);
            detachRoomMembers(roomId, room, game, departingRoomMembers);
            if (roomService.getRoom(roomId) != null) {
                gameStateService.broadcastGameState(game);
            }
            completionHandler.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
            return;
        }

        Set<String> forfeitedNames = departingGamePlayers.stream()
                .map(Player::getName)
                .collect(Collectors.toSet());
        completionHandler.handleGameEndWithForfeits(roomId, survivor, forfeitedNames);
        room.setFinished(true);
        detachRoomMembers(roomId, room, game, departingRoomMembers);
    }

    private void detachRoomMembers(String roomId, Room room, Game game, List<String> departingRoomMembers) {
        if (!departingRoomMembers.isEmpty()) {
            String survivingHost = selectNextHost(room, game, departingRoomMembers);
            roomService.removeMembers(roomId, departingRoomMembers, survivingHost);
        }
    }

    private void markRoomMemberConnected(Room room, String playerName) {
        if (room != null && room.hasPlayer(playerName)) {
            room.setPlayerConnected(playerName, true);
            room.setAbsenceDeadline(playerName, null);
        }
    }

    private boolean hasDisconnectGraceExpired(Room room, Game game, String playerName, long expectedDeadlineEpochMs) {
        // A seated game player keeps their disconnect state on the game player. A room member with
        // no game seat (lobby, or out of the game) keeps it on the room. Once the game is finished,
        // a seated player with no game deadline falls back to the room's deadline.
        Player player = game == null ? null : game.findPlayerByName(playerName);
        boolean disconnected = player == null ? !room.isPlayerConnected(playerName) : player.isDisconnected();
        if (!disconnected) {
            return false;
        }
        Long deadline = player == null ? room.getAbsenceDeadline(playerName) : player.getDisconnectDeadlineEpochMs();
        if (deadline == null && player != null && game.isMatchConcluded()) {
            deadline = room.getAbsenceDeadline(playerName);
        }
        return deadline != null && deadline == expectedDeadlineEpochMs && clock.millis() >= expectedDeadlineEpochMs;
    }
}
