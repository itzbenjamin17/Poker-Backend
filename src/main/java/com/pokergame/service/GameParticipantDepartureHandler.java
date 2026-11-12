package com.pokergame.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.model.TournamentEligibility;

/**
 * Handles participant departure, disconnect transitions, reconnect reconciliation,
 * and disconnect win claims across room and game aggregates under lock.
 */
class GameParticipantDepartureHandler {
    private static final Logger logger = LoggerFactory.getLogger(GameParticipantDepartureHandler.class);

    private final GameLifecycleService lifecycleService;
    private final RoomService roomService;
    private final GameStateService gameStateService;
    private final GameCompletionHandler completion;
    private final ActiveGameRegistry activeGames;

    GameParticipantDepartureHandler(GameLifecycleService lifecycleService,
            RoomService roomService,
            GameStateService gameStateService,
            GameCompletionHandler completion,
            ActiveGameRegistry activeGames) {
        this.lifecycleService = lifecycleService;
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.completion = completion;
        this.activeGames = activeGames;
    }

    /**
     * Reconciles host succession following participant departure.
     * Selects:
     * 1. Preserves current host if still present.
     * 2. First remaining connected contender in room join order.
     * 3. First remaining disconnected contender in room join order.
     * 4. First remaining member in room join order as spectator/terminal fallback.
     * 5. Null if no members remain.
     *
     * @param room             current room aggregate
     * @param game             active game aggregate, or null
     * @param departingMembers names of members departing
     * @return surviving host name, or null if no members remain
     */
    String determineSurvivingHost(Room room, Game game, Collection<String> departingMembers) {
        if (room == null) return null;
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
            // Priority 1: first remaining connected contender in room join order
            for (String name : remaining) {
                Player player = game.getPlayers().stream()
                        .filter(p -> p.getName().equals(name))
                        .findFirst()
                        .orElse(null);
                if (player != null && TournamentEligibility.isContender(game, player) && !player.getIsDisconnected()) {
                    return name;
                }
            }
            // Priority 2: first remaining disconnected contender in room join order
            for (String name : remaining) {
                Player player = game.getPlayers().stream()
                        .filter(p -> p.getName().equals(name))
                        .findFirst()
                        .orElse(null);
                if (player != null && TournamentEligibility.isContender(game, player)) {
                    return name;
                }
            }
        }
        // Priority 3: first remaining member in room join order (spectator/terminal fallback)
        return remaining.getFirst();
    }

    /**
     * Removes departing participants from active game and room state while holding appropriate locks.
     *
     * @param roomId      identifier of the room/game
     * @param playerNames collection of player names departing
     * @param room        current room aggregate
     * @param game        active game aggregate, or null
     */
    void departParticipantsUnderLock(String roomId, Collection<String> playerNames, Room room, Game game) {
        assertHeld(roomId);
        if (playerNames == null || playerNames.isEmpty()) return;
        List<String> departing = playerNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (departing.isEmpty()) return;

        // If no game exists or game is not started (lobby phase)
        if (game == null) {
            if (room == null) return;
            List<String> roomMembers = departing.stream().filter(room.getPlayers()::contains).toList();
            if (roomMembers.isEmpty()) return;

            String currentHost = roomService.getCurrentHost(roomId);
            boolean hostLeaving = currentHost != null && roomMembers.contains(currentHost);
            if (hostLeaving) {
                // Lobby host leaves destroys room
                roomService.leaveRoom(roomId, currentHost);
            } else {
                roomService.leavePlayersBatch(roomId, roomMembers, currentHost);
            }
            return;
        }

        // Game exists. If room is null (already cleaned up or missing)
        if (room == null) {
            logger.info("Room {} not found while removing players from game. Cleaning up game without broadcast.", roomId);
            activeGames.remove(roomId);
            if (gameStateService != null) {
                gameStateService.clearRetainedSettlement(roomId);
            }
            return;
        }

        // Both room and game exist
        boolean anyRoomMember = departing.stream().anyMatch(room.getPlayers()::contains);
        List<Player> departingGamePlayers = game.getPlayers().stream()
                .filter(p -> departing.contains(p.getName()))
                .toList();

        if (!anyRoomMember && departingGamePlayers.isEmpty()) {
            return;
        }

        // A pending forfeit retains departed OUT rows for final standings. Ignore
        // retries for those detached players, and let only actual Room members leave.
        if (game.isForfeitPending()) {
            List<String> roomMembersToLeave = departing.stream().filter(room.getPlayers()::contains).toList();
            if (roomMembersToLeave.isEmpty()) return;
            for (Player departingPlayer : departingGamePlayers) {
                if (roomMembersToLeave.contains(departingPlayer.getName())
                        && TournamentEligibility.isContender(game, departingPlayer)) {
                    departingPlayer.setIsOut();
                }
            }
            List<String> membersToDetach = TournamentEligibility.countContenders(game) == 0
                    ? room.getPlayers() : roomMembersToLeave;
            String survivingHost = determineSurvivingHost(room, game, membersToDetach);
            roomService.leavePlayersBatch(roomId, membersToDetach, survivingHost);
            if (roomService.getRoom(roomId) != null) gameStateService.broadcastGameState(roomId, game);
            completion.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
            return;
        }

        // Terminal game check: once terminal, roster, winner, balances, results are frozen.
        // Later departures update Room membership only.
        if (game.isSettledTerminal()) {
            if (room != null) {
                room.setTerminal(true);
            }
            if (!game.isGameOver() || !game.isTerminalCarryOverSettled()) {
                completion.handleGameEnd(roomId, false);
            }
            List<String> roomMembersToLeave = departing.stream().filter(room.getPlayers()::contains).toList();
            if (!roomMembersToLeave.isEmpty()) {
                String survivingHost = determineSurvivingHost(room, game, roomMembersToLeave);
                roomService.leavePlayersBatch(roomId, roomMembersToLeave, survivingHost);
            }
            return;
        }

        // Active game: if no game members are departing (e.g. spectator in room)
        if (departingGamePlayers.isEmpty()) {
            List<String> roomMembersToLeave = departing.stream().filter(room.getPlayers()::contains).toList();
            if (!roomMembersToLeave.isEmpty()) {
                String survivingHost = determineSurvivingHost(room, game, roomMembersToLeave);
                roomService.leavePlayersBatch(roomId, roomMembersToLeave, survivingHost);
            }
            return;
        }

        // Determine contenders using TournamentEligibility
        int contendersBefore = TournamentEligibility.countContenders(game);
        List<Player> departingContenders = departingGamePlayers.stream()
                .filter(p -> TournamentEligibility.isContender(game, p))
                .toList();
        int contendersRemaining = contendersBefore - departingContenders.size();

        // Check if departure causes completion
        if (contendersRemaining == 1 && contendersBefore >= 2) {
            Player survivor = game.getPlayers().stream()
                    .filter(candidate -> !departing.contains(candidate.getName()))
                    .filter(candidate -> TournamentEligibility.isContender(game, candidate))
                    .findFirst().orElse(null);

            if (survivor != null) {
                if (survivor.getIsDisconnected()) {
                    for (Player departingPlayer : departingGamePlayers) {
                        departingPlayer.setIsOut();
                    }
                    game.setForfeitPending(true);
                    game.waitForReadyPlayers();
                    game.invalidateProgressionTasks();
                    lifecycleService.cancelRuntimeTasks(roomId, false);
                    List<String> roomMembers = departing.stream().filter(room.getPlayers()::contains).toList();
                    if (!roomMembers.isEmpty() && roomService.getRoom(roomId) != null) {
                        String survivingHost = determineSurvivingHost(room, game, roomMembers);
                        roomService.leavePlayersBatch(roomId, roomMembers, survivingHost);
                    }
                    if (roomService.getRoom(roomId) != null) gameStateService.broadcastGameState(roomId, game);
                    completion.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
                } else {
                    completion.handleGameEndWithForfeits(roomId, survivor, departingGamePlayers.stream().map(Player::getName).collect(Collectors.toSet()));
                    if (room != null) {
                        room.setTerminal(true);
                    }
                    List<String> roomMembers = departing.stream().filter(room.getPlayers()::contains).toList();
                    if (!roomMembers.isEmpty() && roomService.getRoom(roomId) != null) {
                        String survivingHost = determineSurvivingHost(room, game, roomMembers);
                        roomService.leavePlayersBatch(roomId, roomMembers, survivingHost);
                    }
                }
                return;
            }
        }

        if (contendersRemaining <= 0) {
            for (Player departingPlayer : departingGamePlayers) {
                game.removePlayerFromGame(departingPlayer);
            }
            List<String> roomMembers = departing.stream().filter(room.getPlayers()::contains).toList();
            if (!roomMembers.isEmpty() && roomService.getRoom(roomId) != null) {
                String survivingHost = determineSurvivingHost(room, game, roomMembers);
                roomService.leavePlayersBatch(roomId, roomMembers, survivingHost);
            }
            completion.cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
            return;
        }

        // contendersRemaining > 1: remove departing players from game and update room
        for (Player departingPlayer : departingGamePlayers) {
            game.removePlayerFromGame(departingPlayer);
        }
        List<String> roomMembers = departing.stream().filter(room.getPlayers()::contains).toList();
        if (!roomMembers.isEmpty() && roomService.getRoom(roomId) != null) {
            String survivingHost = determineSurvivingHost(room, game, roomMembers);
            roomService.leavePlayersBatch(roomId, roomMembers, survivingHost);
        }

        if (roomService.getRoom(roomId) == null) {
            logger.info("Room {} was destroyed during leave flow. Cleaning up active game state.", roomId);
            activeGames.remove(roomId);
            if (gameStateService != null) {
                gameStateService.clearRetainedSettlement(roomId);
            }
            return;
        }

        if (game.getPlayers().isEmpty()) {
            activeGames.remove(roomId);
            if (gameStateService != null) {
                gameStateService.clearRetainedSettlement(roomId);
            }
            roomService.destroyRoom(roomId);
            return;
        }

        if (game.getCurrentPhase() == GamePhase.SHOWDOWN || game.isReadyCountdownActive()) {
            gameStateService.broadcastGameState(roomId, game);
        } else {
            lifecycleService.applyTurnOutcome(roomId, game, game.reconcileAfterPlayerRemoval());
        }

        if (game.isReadyCountdownActive() && game.areAllReadyEligiblePlayersReady()) {
            lifecycleService.completeReadyCountdownAndStartNextHand(roomId, game);
        }
    }

    /**
     * Marks a player as disconnected under the game lock and broadcasts state.
     *
     * @param gameId                    identifier of the game
     * @param game                      authoritative game state
     * @param playerName                name of the disconnecting player
     * @param disconnectDeadlineEpochMs epoch millisecond deadline for reconnect
     */
    void markPlayerDisconnectedUnderLock(String gameId, Game game, String playerName, long disconnectDeadlineEpochMs) {
        assertHeld(gameId);
        if (game.isForfeitPending()) return;
        if (game.isGameOver()) return;
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
        logger.info("Player {} marked disconnected in game {} with deadlineEpochMs={}", playerName, gameId, disconnectDeadlineEpochMs);

        if (roomService.getRoom(gameId) != null) {
            gameStateService.broadcastGameState(gameId, game);
        }

        if (game.isReadyCountdownActive() && game.areAllReadyEligiblePlayersReady()) {
            lifecycleService.completeReadyCountdownAndStartNextHand(gameId, game);
        }
    }

    /**
     * Reconciles a reconnecting player's state under aggregate locks.
     *
     * @param gameId     identifier of the game
     * @param playerName name of the reconnecting player
     * @param room       current room aggregate, or null
     * @param game       authoritative game state, or null
     */
    void reconcilePlayerReconnectedUnderLock(String gameId, String playerName, Room room, Game game) {
        assertHeld(gameId);
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

                if (game.isForfeitPending() && TournamentEligibility.countContenders(game) == 1
                        && TournamentEligibility.isContender(game, player)) {
                    game.setForfeitPending(false);
                    completion.handleGameEndWithForfeits(gameId, player, Set.of());
                    if (room != null && room.hasPlayer(playerName)) {
                        room.setPlayerConnected(playerName, true);
                        room.setAbsenceDeadline(playerName, null);
                    }
                    return;
                }

                if (room != null) {
                    gameStateService.broadcastGameState(gameId, game);
                }
                if (game.getReadyGateState() == ReadyGateState.WAITING_FOR_PLAYERS
                        && game.getReadyEligibleCount() >= 2) {
                    lifecycleService.startReadyCountdown(gameId, lifecycleService.getConfiguredReadyCountdownMs());
                }
            }
        }

        if (room != null && room.hasPlayer(playerName)) {
            room.setPlayerConnected(playerName, true);
            room.setAbsenceDeadline(playerName, null);
        }
    }

    /**
     * Processes an immediate win claim under room and game locks.
     *
     * @param gameId     identifier of the game
     * @param game       authoritative game state
     * @param room       current room aggregate
     * @param playerName claimant name
     * @return terminal game end response
     */
    GameEndResponse claimWinUnderLock(String gameId, Game game, Room room, String playerName) {
        assertHeld(gameId);
        if (game.isGameOver()) {
            return completion.resolveTerminalResultForParticipant(gameId, game, room, playerName, true);
        }
        if (!TournamentEligibility.canPlayerClaimWin(game, playerName)) {
            throw new UnauthorisedActionException("Cannot claim win right now.");
        }
        List<String> opponents = TournamentEligibility.disconnectedContenderNamesExcept(game, playerName);
        departParticipantsUnderLock(gameId, opponents, room, game);
        return completion.ensureRetainedClaimResult(gameId, game, room, playerName);
    }

    /**
     * Removes a disconnected player whose grace period expired under lock.
     *
     * @param gameId                  identifier of the game
     * @param room                    current room aggregate
     * @param game                    authoritative game state, or null
     * @param playerName              name of the expired player
     * @param expectedDeadlineEpochMs expected deadline epoch millis
     */
    void removeDisconnectedPlayerUnderLock(String gameId, Room room, Game game, String playerName,
            long expectedDeadlineEpochMs) {
        assertHeld(gameId);
        if (hasDisconnectGraceExpired(room, game, playerName, expectedDeadlineEpochMs)) {
            departParticipantsUnderLock(gameId, List.of(playerName), room, game);
        }
    }

    private void assertHeld(String roomId) {
        if (lifecycleService != null && lifecycleService.getRoomCoordinator() != null) {
            lifecycleService.getRoomCoordinator().assertHeldByCurrentThread(roomId);
        }
    }

    /**
     * Verifies whether the reconnect grace period has expired for a disconnected player.
     *
     * @param room                    current room aggregate
     * @param game                    authoritative game state, or null
     * @param playerName              player name being evaluated
     * @param expectedDeadlineEpochMs expected deadline timestamp
     * @return true if grace period has expired
     */
    boolean hasDisconnectGraceExpired(Room room, Game game, String playerName, long expectedDeadlineEpochMs) {
        Player player = game == null ? null : game.findPlayerByName(playerName);
        boolean disconnected = player == null ? !room.isPlayerConnected(playerName) : player.getIsDisconnected();
        if (!disconnected) return false;

        Long deadline = player == null ? room.getAbsenceDeadline(playerName) : player.getDisconnectDeadlineEpochMs();
        if (deadline == null && player != null && game != null && game.isSettledTerminal()) {
            deadline = room.getAbsenceDeadline(playerName);
        }
        return deadline != null && deadline == expectedDeadlineEpochMs
                && System.currentTimeMillis() >= expectedDeadlineEpochMs;
    }
}