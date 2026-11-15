package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.model.GameEligibility;
import java.time.Clock;
import java.util.Collection;
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
 */
@Component
class PlayerDepartureHandler {
    private static final Logger logger = LoggerFactory.getLogger(PlayerDepartureHandler.class);
    private final RoomService roomService;
    private final GameStateService gameStateService;
    private final GameCompletionHandler completion;
    private final HandProgressionService handProgression;
    private final GameReadinessCoordinator readiness;
    private final GameTimerRegistry runtimeTimers;
    private final ActiveGameRegistry activeGames;
    private final Clock clock;

    PlayerDepartureHandler(
            RoomService roomService,
            GameStateService gameStateService,
            GameCompletionHandler completion,
            HandProgressionService handProgression,
            GameReadinessCoordinator readiness,
            GameTimerRegistry runtimeTimers,
            ActiveGameRegistry activeGames,
            Clock clock) {
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.completion = completion;
        this.handProgression = handProgression;
        this.readiness = readiness;
        this.runtimeTimers = runtimeTimers;
        this.activeGames = activeGames;
        this.clock = clock;
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
            removePlayersFromRoomAndGame(roomId, playerNames, null, game);
            return;
        }
        if (game != null && room.isInactivityExpired(clock.millis())) {
            completion.abandonGameDueToInactivity(roomId);
            return;
        }
        removePlayersFromRoomAndGame(roomId, playerNames, room, game);
    }

    /**
     * Removes departing players from room and game state, transferring host ownership, settling
     * forfeits, or cleaning up an empty room as appropriate.
     *
     * @param roomId      the unique ID of the room/game
     * @param playerNames names of departing players
     * @param room        the backing room, or {@code null} if already removed
     * @param game        the active game, or {@code null} if still in lobby
     */
    void removePlayersFromRoomAndGame(String roomId, Collection<String> playerNames, Room room, Game game) {
        if (playerNames == null || playerNames.isEmpty()) {
            return;
        }
        List<String> departing = playerNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (departing.isEmpty()) {
            return;
        }

        if (game == null) {
            if (room == null) {
                return;
            }
            List<String> roomMembers = departing.stream().filter(room.getPlayers()::contains).toList();
            if (roomMembers.isEmpty()) {
                return;
            }
            String currentHost = roomService.getCurrentHost(roomId);
            boolean hostLeaving = currentHost != null && roomMembers.contains(currentHost);
            if (hostLeaving) {
                roomService.leaveRoom(roomId, currentHost);
            } else {
                roomService.leavePlayersBatch(roomId, roomMembers, currentHost);
            }
            return;
        }

        if (room == null) {
            logger.info("Room {} not found while removing players from game. Cleaning up game without broadcast.",
                    roomId);
            activeGames.remove(roomId);
            return;
        }

        boolean anyRoomMember = departing.stream().anyMatch(room.getPlayers()::contains);
        List<Player> departingGamePlayers = game.getPlayers().stream()
                .filter(p -> departing.contains(p.getName()))
                .toList();
        if (!anyRoomMember && departingGamePlayers.isEmpty()) {
            return;
        }

        if (game.isForfeitPending()) {
            handleDepartureDuringPendingForfeit(roomId, room, game, departing, departingGamePlayers);
            return;
        }

        if (game.isFinishedOrEliminated()) {
            room.setTerminal(true);
            if (!game.isAbandoned() && (!game.isGameOver() || !game.isFinalPotSettled())) {
                completion.handleGameEnd(roomId);
            }
            detachRoomMembers(roomId, room, game, departing);
            return;
        }

        if (departingGamePlayers.isEmpty()) {
            detachRoomMembers(roomId, room, game, departing);
            return;
        }

        int contendersBefore = GameEligibility.countContenders(game);
        List<Player> departingContenders = departingGamePlayers.stream()
                .filter(p -> GameEligibility.isContender(game, p))
                .toList();
        int contendersRemaining = contendersBefore - departingContenders.size();

        if (contendersRemaining == 1 && contendersBefore >= 2) {
            Player survivor = game.getPlayers().stream()
                    .filter(candidate -> !departing.contains(candidate.getName()))
                    .filter(candidate -> GameEligibility.isContender(game, candidate))
                    .findFirst()
                    .orElse(null);

            if (survivor != null) {
                handleSingleSurvivorForfeit(roomId, room, game, departing, departingGamePlayers, survivor);
                return;
            }
        }

        if (contendersRemaining <= 0) {
            for (Player departingPlayer : departingGamePlayers) {
                game.removePlayer(departingPlayer);
            }
            detachRoomMembersIfRoomPresent(roomId, room, game, departing);
            completion.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
            return;
        }

        for (Player departingPlayer : departingGamePlayers) {
            game.removePlayer(departingPlayer);
        }
        detachRoomMembersIfRoomPresent(roomId, room, game, departing);

        if (roomService.getRoom(roomId) == null) {
            logger.info("Room {} was destroyed during leave flow. Cleaning up active game state.", roomId);
            activeGames.remove(roomId);
            return;
        }

        if (game.getPlayers().isEmpty()) {
            activeGames.remove(roomId);
            roomService.destroyRoom(roomId);
            return;
        }

        if (game.getCurrentPhase() == GamePhase.SHOWDOWN || game.isReadyCountdownActive()) {
            gameStateService.broadcastGameState(game);
        } else {
            if (handProgression.applyTurnOutcome(roomId, game, game.advanceAfterPlayerRemoval())) {
                readiness.openReadyGate(roomId);
            }
        }

        if (game.isReadyCountdownActive() && game.areAllEligiblePlayersReady()) {
            readiness.completeReadyCountdown(game);
        }
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
        if (game.isForfeitPending() || game.isGameOver()) {
            return;
        }
        Player player = game.findPlayerByName(playerName);
        if (player == null) {
            logger.debug("Cannot mark player disconnected: player {} not found in game {}", playerName, gameId);
            return;
        }
        if (player.getIsDisconnected()) {
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

        if (game.isReadyCountdownActive() && game.areAllEligiblePlayersReady()) {
            readiness.completeReadyCountdown(game);
        }
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
            if (player != null && player.getIsDisconnected()) {
                player.setDisconnected(false);
                player.setDisconnectDeadlineEpochMs(null);
                logger.info("Player {} marked reconnected in game {}", playerName, gameId);

                if (game.isForfeitPending() && GameEligibility.countContenders(game) == 1
                        && GameEligibility.isContender(game, player)) {
                    game.setForfeitPending(false);
                    completion.handleGameEndWithForfeits(gameId, player, Set.of());
                    markRoomMemberConnected(room, playerName);
                    return;
                }

                if (room != null) {
                    gameStateService.broadcastGameState(game);
                }
                if (game.getReadyGateState() == ReadyGateState.WAITING_FOR_PLAYERS
                        && game.getReadyEligibleCount() >= 2) {
                    readiness.openReadyGate(gameId);
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
        if (game == null || completion.isGameExpired(game)) {
            throw new ResourceNotFoundException("Game not found");
        }
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            throw new ResourceNotFoundException("Room not found");
        }

        completion.rejectExpiredActivity(gameId, room);
        if (game.isGameOver()) {
            GameEndResponse retained = game.getGameEndResult();
            if (retained != null && retained.abandoned()) {
                throw new BadRequestException(
                        "Game was abandoned due to inactivity.",
                        "GAME_ABANDONED",
                        "Game was abandoned due to inactivity");
            }
            return completion.getGameReview(gameId, playerName);
        }
        if (!GameEligibility.canPlayerClaimWin(game, playerName)) {
            throw new UnauthorisedActionException("Cannot claim win right now.");
        }
        List<String> opponents = GameEligibility.disconnectedContenderNamesExcept(game, playerName);
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
        if (hasDisconnectGraceExpired(room, game, playerName, expectedDeadlineEpochMs)) {
            removePlayersFromRoomAndGame(gameId, List.of(playerName), room, game);
        }
    }

    /**
     * Handles a claimed turn timeout by forfeiting the current player or abandoning an expired game.
     *
     * @param gameId the unique ID of the game
     * @param game   the active game instance
     */
    void handleTurnTimeout(String gameId, Game game) {
        Room room = roomService.getRoom(gameId);
        if (room != null && room.isInactivityExpired(clock.millis())) {
            logger.info("Inactivity deadline expired concurrently with turn timeout for game {}; "
                    + "abandonment takes precedence", gameId);
            completion.abandonGameDueToInactivity(gameId);
            return;
        }
        Player actor = game.getCurrentPlayer();
        if (actor != null) {
            String actorToDepart = actor.getName();
            removePlayers(gameId, List.of(actorToDepart));
            logger.info("Player {} timed out after 60 seconds in game {}; removing from seat", actorToDepart, gameId);
        }
    }

    /**
     * Selects the next room host when current members depart, keeping the current host if still
     * present or otherwise picking the first remaining connected contender, disconnected contender,
     * or room member in join order.
     *
     * @param room             the current room
     * @param game             the active game, or {@code null}
     * @param departingMembers names of members about to leave
     * @return the next host's name, or {@code null} if no members remain
     */
    String selectNextHost(Room room, Game game, Collection<String> departingMembers) {
        if (room == null) {
            return null;
        }
        String currentHost = roomService.getCurrentHost(room.getRoomId());
        List<String> remaining = room.getPlayers().stream()
                .filter(name -> departingMembers == null || !departingMembers.contains(name))
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
                if (player != null && GameEligibility.isContender(game, player) && !player.getIsDisconnected()) {
                    return name;
                }
            }
            for (String name : remaining) {
                Player player = game.findPlayerByName(name);
                if (player != null && GameEligibility.isContender(game, player)) {
                    return name;
                }
            }
        }
        return remaining.getFirst();
    }

    private void handleDepartureDuringPendingForfeit(
            String roomId, Room room, Game game, List<String> departing, List<Player> departingGamePlayers) {
        List<String> roomMembersToLeave = departing.stream().filter(room.getPlayers()::contains).toList();
        if (roomMembersToLeave.isEmpty()) {
            return;
        }
        for (Player departingPlayer : departingGamePlayers) {
            if (roomMembersToLeave.contains(departingPlayer.getName())
                    && GameEligibility.isContender(game, departingPlayer)) {
                departingPlayer.setIsOut();
            }
        }
        List<String> membersToDetach = GameEligibility.countContenders(game) == 0
                ? room.getPlayers()
                : roomMembersToLeave;
        String survivingHost = selectNextHost(room, game, membersToDetach);
        roomService.leavePlayersBatch(roomId, membersToDetach, survivingHost);
        if (roomService.getRoom(roomId) != null) {
            gameStateService.broadcastGameState(game);
        }
        completion.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
    }

    private void handleSingleSurvivorForfeit(
            String roomId,
            Room room,
            Game game,
            List<String> departing,
            List<Player> departingGamePlayers,
            Player survivor) {
        if (survivor.getIsDisconnected()) {
            for (Player departingPlayer : departingGamePlayers) {
                departingPlayer.setIsOut();
            }
            game.setForfeitPending(true);
            game.waitForReadyPlayers();
            game.invalidateProgressionTasks();
            runtimeTimers.cancelAllForGame(roomId, false);
            detachRoomMembersIfRoomPresent(roomId, room, game, departing);
            if (roomService.getRoom(roomId) != null) {
                gameStateService.broadcastGameState(game);
            }
            completion.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
            return;
        }

        Set<String> forfeitedNames = departingGamePlayers.stream()
                .map(Player::getName)
                .collect(Collectors.toSet());
        completion.handleGameEndWithForfeits(roomId, survivor, forfeitedNames);
        room.setTerminal(true);
        detachRoomMembersIfRoomPresent(roomId, room, game, departing);
    }

    private void detachRoomMembers(String roomId, Room room, Game game, List<String> departing) {
        List<String> roomMembersToLeave = departing.stream().filter(room.getPlayers()::contains).toList();
        if (!roomMembersToLeave.isEmpty()) {
            String survivingHost = selectNextHost(room, game, roomMembersToLeave);
            roomService.leavePlayersBatch(roomId, roomMembersToLeave, survivingHost);
        }
    }

    private void detachRoomMembersIfRoomPresent(String roomId, Room room, Game game, List<String> departing) {
        if (roomService.getRoom(roomId) != null) {
            detachRoomMembers(roomId, room, game, departing);
        }
    }

    private void markRoomMemberConnected(Room room, String playerName) {
        if (room != null && room.hasPlayer(playerName)) {
            room.setPlayerConnected(playerName, true);
            room.setAbsenceDeadline(playerName, null);
        }
    }

    private boolean hasDisconnectGraceExpired(Room room, Game game, String playerName, long expectedDeadlineEpochMs) {
        Player player = game == null ? null : game.findPlayerByName(playerName);
        boolean disconnected = player == null ? !room.isPlayerConnected(playerName) : player.getIsDisconnected();
        if (!disconnected) {
            return false;
        }
        Long deadline = player == null ? room.getAbsenceDeadline(playerName) : player.getDisconnectDeadlineEpochMs();
        if (deadline == null && player != null && game != null && game.isFinishedOrEliminated()) {
            deadline = room.getAbsenceDeadline(playerName);
        }
        return deadline != null && deadline == expectedDeadlineEpochMs && clock.millis() >= expectedDeadlineEpochMs;
    }
}
