package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.config.LifecycleTimingProperties;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.service.GameTaskScheduler.ScheduledTimer;
import com.pokergame.util.MdcUtils;
import com.pokergame.util.MdcUtils.MdcScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Coordinates game creation, player departures, scheduled timers, recovery, and public game
 * lifecycle queries under entry-point and durable-mutation room lock boundaries.
 */
@Service
public class GameLifecycleService {
    private static final Logger logger = LoggerFactory.getLogger(GameLifecycleService.class);
    private final RoomService roomService;
    private final HandEvaluatorService handEvaluator;
    private final GameStateService gameStateService;
    private final ActiveGameRegistry activeGames;
    private final GameTimerRegistry runtimeTimers;
    private final GameTaskScheduler taskScheduler;
    private final GameCompletionHandler completionHandler;
    private final HandProgressionService handProgression;
    private final GameReadinessCoordinator readinessCoordinator;
    private final PlayerDepartureHandler departureHandler;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;
    private final LifecycleTimingProperties timings;

    GameLifecycleService(
            RoomService roomService,
            HandEvaluatorService handEvaluator,
            GameStateService gameStateService,
            ActiveGameRegistry activeGames,
            GameTimerRegistry runtimeTimers,
            GameTaskScheduler taskScheduler,
            GameCompletionHandler completionHandler,
            HandProgressionService handProgression,
            GameReadinessCoordinator readinessCoordinator,
            PlayerDepartureHandler departureHandler,
            RoomCoordinator roomCoordinator,
            Clock clock,
            LifecycleTimingProperties timings) {
        this.roomService = roomService;
        this.handEvaluator = handEvaluator;
        this.gameStateService = gameStateService;
        this.activeGames = activeGames;
        this.runtimeTimers = runtimeTimers;
        this.taskScheduler = taskScheduler;
        this.completionHandler = completionHandler;
        this.handProgression = handProgression;
        this.readinessCoordinator = readinessCoordinator;
        this.departureHandler = departureHandler;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.timings = timings;
    }

    /**
     * Removes one or more players from a room and its active game, transferring host ownership or
     * settling forfeits as needed.
     *
     * @param roomId      the unique ID of the room/game
     * @param playerNames names of players leaving or timing out
     */
    @DurableMutation(roomId = "#roomId")
    public void removePlayers(String roomId, Collection<String> playerNames) {
        if (roomId.trim().isEmpty() || playerNames == null || playerNames.isEmpty()) {
            return;
        }
        departureHandler.removePlayers(roomId, playerNames);
    }

    /**
     * Creates and starts a new poker game from an existing lobby room, converts all room members
     * into players with buy-in chips, broadcasts the game start, and deals the first hand.
     *
     * @param roomId the unique ID of the room to start
     * @return the newly created game ID (same as {@code roomId})
     * @throws ResourceNotFoundException   if the room does not exist
     * @throws BadRequestException         if the lobby expired or the game already started
     * @throws UnauthorisedActionException if fewer than 2 players are in the room
     */
    @DurableMutation(roomId = "#roomId")
    public String createGameFromRoom(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        Room room = roomService.getRoom(roomId);
        if (room == null) {
            logger.warn("Game creation failed: room not found for id {}", roomId);
            throw new ResourceNotFoundException("Room not found");
        }
        if (roomService.isLobbyExpired(room)) {
            logger.warn("Game creation failed: lobby {} has expired due to inactivity", roomId);
            throw new BadRequestException("Room has expired due to inactivity.");
        }
        if (activeGames.contains(roomId) || room.isGameStarted()) {
            logger.warn("Game creation failed: game already exists for room {}", roomId);
            throw new BadRequestException("Game has already started for this room.");
        }
        if (room.getPlayers().size() < 2) {
            logger.warn("Game creation failed: not enough players in room {} (count: {})",
                    roomId, room.getPlayers().size());
            throw new UnauthorisedActionException(
                    "Need at least 2 players to start game. Please wait for more players to join.");
        }

        List<String> playerNames = new ArrayList<>(room.getPlayers());
        List<Player> players = new ArrayList<>();
        for (String name : playerNames) {
            Player player = new Player(name, UUID.randomUUID().toString(), room.getBuyIn());
            Long absenceDeadline = room.getAbsenceDeadline(name);
            boolean connected = room.isPlayerConnected(name);
            if (!connected && absenceDeadline != null) {
                player.setDisconnected(true);
                player.setDisconnectDeadlineEpochMs(absenceDeadline);
            }
            players.add(player);
        }

        Game game = new Game(
                roomId, players, room.getMaxPlayers(), room.getSmallBlind(), room.getBigBlind(), handEvaluator);
        activeGames.register(game);

        room.setGameStarted(true);
        roomService.cancelLobbyInactivity(roomId);
        room.extendGameInactivityDeadline(clock.millis());
        taskScheduler.scheduleGameInactivity(roomId, room.getInactivityDeadlineEpochMs());
        roomService.broadcastGameStarted(roomId);

        logger.info("Game created and started for room: {} with {} players", roomId, room.getPlayers().size());
        if (handProgression.startNewHand(roomId)) {
            readinessCoordinator.openReadyGate(roomId);
        }
        return roomId;
    }

    /**
     * Applies the result of a player decision under the room lock, updating timers and
     * broadcasting the next turn, all-in progression, or showdown state.
     *
     * @param gameId                     the unique ID of the game
     * @param game                       the active game instance
     * @param outcome                    the turn outcome returned by {@link Game}
     * @param notifyImmediateAutoAdvance {@code true} to broadcast an immediate auto-advance notice
     */
    @DurableMutation(roomId = "#gameId")
    public void applyTurnOutcome(String gameId, Game game, TurnOutcome outcome, boolean notifyImmediateAutoAdvance) {
        if (handProgression.applyTurnOutcome(gameId, game, outcome, notifyImmediateAutoAdvance)) {
            readinessCoordinator.openReadyGate(gameId);
        }
    }

    /**
     * Schedules the inactivity expiration timer for an active game.
     *
     * @param gameId          the unique ID of the game
     * @param deadlineEpochMs target expiration epoch time in milliseconds
     */
    public void scheduleGameInactivity(String gameId, Long deadlineEpochMs) {
        if (gameId == null || deadlineEpochMs == null) {
            return;
        }
        roomCoordinator.assertHeldByCurrentThread(gameId);
        taskScheduler.scheduleGameInactivity(gameId, deadlineEpochMs);
    }

    /**
     * Abandons the game due to inactivity only if {@code expectedDeadlineEpochMs} still matches
     * the room's current deadline and has expired.
     *
     * @param gameId                  the unique ID of the game
     * @param expectedDeadlineEpochMs the inactivity deadline captured when the timer was scheduled
     */
    @DurableMutation(roomId = "#gameId")
    public void abandonGameIfInactive(String gameId, Long expectedDeadlineEpochMs) {
        if (expectedDeadlineEpochMs == null) {
            return;
        }
        completionHandler.abandonGameIfInactive(gameId, expectedDeadlineEpochMs);
    }

    /**
     * Terminates an active game immediately due to inactivity, broadcasts the abandonment summary,
     * and schedules cleanup.
     *
     * @param gameId the unique ID of the game
     */
    @DurableMutation(roomId = "#gameId")
    public void abandonGameDueToInactivity(String gameId) {
        completionHandler.abandonGameDueToInactivity(gameId);
    }

    /**
     * Extends the active game's inactivity deadline and reschedules its inactivity timer.
     *
     * @param gameId the unique ID of the game
     */
    @DurableMutation(roomId = "#gameId")
    public void resetGameInactivity(String gameId) {
        taskScheduler.resetGameInactivity(gameId);
    }

    /**
     * Returns the live game for {@code gameId} when the calling thread holds its room lock.
     *
     * @param gameId the unique ID of the game
     * @return the live game, or {@code null} if none exists
     */
    public Game getGame(String gameId) {
        return activeGames.get(gameId);
    }

    /**
     * Restores a game from saved state into memory without dealing a new hand.
     *
     * @param game the restored game instance, ignored if {@code null}
     */
    public void restoreGame(Game game) {
        if (game == null) {
            return;
        }
        String gameId = game.getGameId();
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Room room = roomService.getRoom(gameId);
        Game existing = activeGames.get(gameId);
        if (existing != null && existing != game) {
            runtimeTimers.cancelAllForGame(gameId, false);
        }
        activeGames.replace(game);
        if (room != null) {
            room.setTerminal(game.isGameOver());
            room.setGameStarted(true);
        }
    }

    /**
     * Removes a game and its runtime timers from memory while leaving its room container in place.
     *
     * @param gameId the unique ID of the game
     */
    public void evictGame(String gameId) {
        if (gameId == null) {
            return;
        }
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Room room = roomService.getRoom(gameId);
        runtimeTimers.cancelAllForGame(gameId, false);
        activeGames.remove(gameId);
        if (room != null) {
            room.setGameStarted(false);
            room.setTerminal(false);
        }
    }

    /**
     * Repairs ready-gate timers after startup recovery and schedules all pending timers after save.
     *
     * @param gameId the unique ID of the recovered game
     */
    @DurableMutation(roomId = "#gameId")
    public void resumeRecoveredTimers(String gameId) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            return;
        }
        try (MdcScope _ = MdcUtils.withRoomId(gameId)) {
            boolean normalized = game.repairRecoveredReadyGate(clock.millis() + timings.readyCountdownMs());
            List<ScheduledTimer> timers = GameTaskScheduler.listActiveTimers(gameId, game);
            PostSaveActions.runAfterSave(() -> timers.forEach(taskScheduler::registerTimer));
            if (normalized) {
                gameStateService.broadcastGameState(game);
            }
            logger.info("Resumed {} recovered timers for game {}", timers.size(), gameId);
        }
    }

    /**
     * Restores runtime timers from saved game and room deadlines after rolling back a failed
     * update, without queuing new side effects onto the failed transaction.
     *
     * @param gameId the unique ID of the game
     */
    public void resumeRollbackTimers(String gameId) {
        if (gameId == null) {
            return;
        }
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) {
            return;
        }
        GameTaskScheduler.listActiveTimers(gameId, game).forEach(taskScheduler::registerTimer);
        Room room = roomService.getRoom(gameId);
        if (room != null && room.getInactivityDeadlineEpochMs() != null && !game.isGameOver()) {
            taskScheduler.scheduleGameInactivity(gameId, room.getInactivityDeadlineEpochMs());
        }
    }

    /**
     * Executes a scheduled game timer if the game still expects that deadline, hand number, and
     * timer generation.
     *
     * @param gameId             the unique ID of the game
     * @param task               the timer type that fired
     * @param expectedDeadline   the captured execution epoch time in milliseconds
     * @param expectedHandNumber the hand number active when the timer was scheduled
     * @param expectedGeneration the timer generation active when the timer was scheduled
     */
    @DurableMutation(roomId = "#gameId")
    public void executeScheduledTask(
            String gameId,
            ScheduledGameTask task,
            long expectedDeadline,
            long expectedHandNumber,
            long expectedGeneration) {
        if (task == null) {
            return;
        }
        Game game = activeGames.get(gameId);
        if (!taskScheduler.claimTimer(game, task, expectedDeadline, expectedHandNumber, expectedGeneration)) {
            return;
        }
        switch (task) {
            case TURN_TIMEOUT -> departureHandler.handleTurnTimeout(gameId, game);
            case AUTO_ADVANCE -> {
                if (handProgression.applyAutoAdvanceTimeout(gameId, game)) {
                    readinessCoordinator.openReadyGate(gameId);
                }
            }
            case READY_TIMEOUT -> readinessCoordinator.handleReadyTimeout(gameId, game, expectedDeadline);
            case NEW_HAND -> {
                if (handProgression.startNewHand(gameId)) {
                    readinessCoordinator.openReadyGate(gameId);
                }
            }
            case READY_OPEN -> readinessCoordinator.openReadyGate(gameId);
            case GAME_END -> completionHandler.handleGameEnd(gameId);
            case CLEANUP -> completionHandler.deleteGameAndRoom(gameId);
        }
    }

    /**
     * Cancels scheduled runtime timers for {@code gameId}, optionally preserving the cleanup timer.
     *
     * @param gameId      the unique ID of the game
     * @param keepCleanup {@code true} to keep {@link ScheduledGameTask#CLEANUP} scheduled
     */
    public void cancelTimers(String gameId, boolean keepCleanup) {
        runtimeTimers.cancelAllForGame(gameId, keepCleanup);
    }

    /**
     * Returns whether {@code gameId} has an active inactivity deadline that has already elapsed.
     *
     * @param gameId the unique ID of the game
     * @return {@code true} if the game's inactivity deadline has passed
     */
    public boolean isGameInactivityExpired(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Room room = roomService.getRoom(gameId);
        return room != null && room.isInactivityExpired(clock.millis());
    }

    /**
     * Returns whether an unexpired game currently exists for {@code gameId}.
     *
     * @param gameId the unique ID of the game
     * @return {@code true} if a live or uncleaned game exists
     */
    public boolean gameExists(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        return game != null && !completionHandler.isGameExpired(game);
    }

    /**
     * Returns whether {@code playerName} is currently seated (not eliminated) in {@code gameId}.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player name to check
     * @return {@code true} if the player is present and not out
     */
    public boolean playerExistsInGame(String gameId, String playerName) {
        if (gameId == null || playerName == null) {
            return false;
        }
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null || game.getPlayers() == null) {
            return false;
        }
        return game.getPlayers().stream()
                .filter(Objects::nonNull)
                .anyMatch(player -> playerName.equals(player.getName()) && !player.getIsOut());
    }

    /**
     * Verifies that {@code membershipId} matches the active session membership for
     * {@code playerName} in {@code gameId}.
     *
     * @param gameId       the unique ID of the room/game
     * @param playerName   the authenticated player name
     * @param membershipId the membership ID claimed by the request token
     * @throws AccessDeniedException     if membership claims are blank or do not match the room
     * @throws ResourceNotFoundException if the room does not exist
     */
    public void validatePlayerMembership(String gameId, String playerName, String membershipId) {
        if (membershipId == null || membershipId.isBlank()
                || playerName == null || playerName.isBlank()
                || gameId == null) {
            throw new AccessDeniedException("Token membership is stale or invalid.");
        }
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            throw new ResourceNotFoundException("Room not found");
        }
        String activeMembershipId = room.getMembershipId(playerName);
        if (activeMembershipId == null || !activeMembershipId.equals(membershipId)) {
            logger.warn("Rejecting request: stale or mismatched membership for player {} in game {} "
                    + "(token: {}, active: {})", playerName, gameId, membershipId, activeMembershipId);
            throw new AccessDeniedException("Token membership is stale or invalid.");
        }
    }

    /**
     * Marks a player as disconnected in the active game with a reconnect grace deadline.
     *
     * @param gameId                    the unique ID of the game
     * @param playerName                the disconnected player's name
     * @param disconnectDeadlineEpochMs reconnect grace deadline epoch time in milliseconds
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerDisconnected(String gameId, String playerName, long disconnectDeadlineEpochMs) {
        departureHandler.markPlayerDisconnected(gameId, playerName, disconnectDeadlineEpochMs);
    }

    /**
     * Marks a reconnecting player as connected across room and game state and resumes pending
     * forfeit/ready handling if needed.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the reconnecting player's name
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReconnected(String gameId, String playerName) {
        departureHandler.markPlayerReconnected(gameId, playerName);
    }

    /**
     * Marks an eligible player ready for the next hand and starts the next hand if all eligible
     * players are now ready.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player marking ready
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReadyForNextHand(String gameId, String playerName) {
        readinessCoordinator.markPlayerReadyForNextHand(gameId, playerName);
    }

    /**
     * Removes a player's ready status while the between-hand ready countdown is open.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player unmarking ready
     */
    @DurableMutation(roomId = "#gameId")
    public void unmarkPlayerReadyForNextHand(String gameId, String playerName) {
        readinessCoordinator.unmarkPlayerReadyForNextHand(gameId, playerName);
    }

    /**
     * Cancels the current turn timeout timer for {@code gameId}.
     *
     * @param gameId the unique ID of the game
     */
    public void cancelTurnTimeout(String gameId) {
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
    }

    /**
     * Claims an immediate win when all other contenders are disconnected, or returns the saved
     * final summary if the game already finished.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the claiming player's name
     * @return the final {@link GameEndResponse}
     */
    @DurableMutation(roomId = "#gameId")
    public GameEndResponse claimWin(String gameId, String playerName) {
        return departureHandler.claimWin(gameId, playerName);
    }

    /**
     * Returns the saved final summary of a finished game for {@code playerName}.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the requesting player's name
     * @return the final {@link GameEndResponse}
     */
    public GameEndResponse getGameReview(String gameId, String playerName) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        return completionHandler.getGameReview(gameId, playerName);
    }

    /**
     * Removes a player whose reconnect grace period expired when both membership ID and grace
     * deadline match the scheduled expiration.
     *
     * @param gameId                  the unique ID of the game
     * @param playerName              the disconnected player's name
     * @param expectedMembershipId    the membership ID active when grace expiration was scheduled
     * @param expectedDeadlineEpochMs the expected reconnect deadline epoch time in milliseconds
     */
    @DurableMutation(roomId = "#gameId")
    public void removeDisconnectedPlayer(
            String gameId, String playerName, String expectedMembershipId, long expectedDeadlineEpochMs) {
        if (playerName == null || expectedMembershipId == null) {
            return;
        }
        departureHandler.removeDisconnectedPlayer(
                gameId, playerName, expectedMembershipId, expectedDeadlineEpochMs);
    }
}
