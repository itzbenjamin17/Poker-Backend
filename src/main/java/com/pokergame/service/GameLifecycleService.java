package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.GameId;
import com.pokergame.persistence.transaction.RoomId;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Coordinates game creation, player departures, scheduled timers, and public game
 * lifecycle queries under entry-point and durable-mutation room lock boundaries.
 */
@Service
public class GameLifecycleService {
    private static final Logger logger = LoggerFactory.getLogger(GameLifecycleService.class);
    private final RoomService roomService;
    private final ActiveGameRegistry activeGames;
    private final GameTimerRegistry timerRegistry;
    private final GameTaskScheduler taskScheduler;
    private final GameCompletionHandler completionHandler;
    private final GameReadinessCoordinator readinessCoordinator;
    private final PlayerDepartureHandler departureHandler;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;
    private final GameTimingProperties timings;

    GameLifecycleService(
            RoomService roomService,
            ActiveGameRegistry activeGames,
            GameTimerRegistry timerRegistry,
            GameTaskScheduler taskScheduler,
            GameCompletionHandler completionHandler,
            GameReadinessCoordinator readinessCoordinator,
            PlayerDepartureHandler departureHandler,
            RoomCoordinator roomCoordinator,
            Clock clock,
            GameTimingProperties timings) {
        this.roomService = roomService;
        this.activeGames = activeGames;
        this.timerRegistry = timerRegistry;
        this.taskScheduler = taskScheduler;
        this.completionHandler = completionHandler;
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
    @DurableMutation
    public void removePlayers(@RoomId String roomId, Collection<String> playerNames) {
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
    @DurableMutation
    public String createGameFromRoom(@RoomId String roomId) {
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
        if (room.getPlayers().size() < Room.MIN_PLAYERS) {
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
                roomId, players, room.getMaxPlayers(), room.getSmallBlind(), room.getBigBlind());
        activeGames.register(game);

        room.setGameStarted(true);
        roomService.cancelLobbyInactivity(roomId);
        room.extendGameInactivityDeadline(clock.millis(), timings.gameInactivityAllowanceMs());
        taskScheduler.scheduleGameInactivity(roomId, room.getInactivityDeadlineEpochMs());
        roomService.broadcastGameStarted(roomId);

        logger.info("Game created and started for room: {} with {} players", roomId, room.getPlayers().size());
        readinessCoordinator.startNewHandAndOpenReadyGateIfNeeded(roomId);
        return roomId;
    }

    /**
     * Abandons the game due to inactivity only if {@code expectedDeadlineEpochMs} still matches
     * the room's current deadline and has expired.
     *
     * @param gameId                  the unique ID of the game
     * @param expectedDeadlineEpochMs the inactivity deadline captured when the timer was scheduled
     */
    @DurableMutation
    public void abandonGameIfInactive(@GameId String gameId, Long expectedDeadlineEpochMs) {
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
    @DurableMutation
    public void abandonGameDueToInactivity(@GameId String gameId) {
        completionHandler.abandonGameDueToInactivity(gameId);
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
     * Executes a scheduled game timer if the game still expects that deadline, hand number, and
     * timer version.
     *
     * @param gameId               the unique ID of the game
     * @param task                 the timer type that fired
     * @param expectedDeadline     the captured execution epoch time in milliseconds
     * @param expectedHandNumber   the hand number active when the timer was scheduled
     * @param expectedTimerVersion the timer version active when the timer was scheduled
     */
    @DurableMutation
    public void executeScheduledTask(
            @GameId String gameId,
            ScheduledGameTask task,
            long expectedDeadline,
            long expectedHandNumber,
            long expectedTimerVersion) {
        if (task == null) {
            return;
        }
        Game game = activeGames.get(gameId);
        if (!taskScheduler.claimTimer(game, task, expectedDeadline, expectedHandNumber, expectedTimerVersion)) {
            return;
        }
        switch (task) {
            case TURN_TIMEOUT -> departureHandler.handleTurnTimeout(game);
            case AUTO_ADVANCE -> readinessCoordinator.applyAutoAdvanceTimeoutAndOpenReadyGateIfNeeded(game);
            case READY_TIMEOUT -> readinessCoordinator.handleReadyTimeout(game, expectedDeadline);
            case NEW_HAND -> readinessCoordinator.startNewHandAndOpenReadyGateIfNeeded(gameId);
            case READY_OPEN -> readinessCoordinator.openReadyGate(game);
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
        timerRegistry.cancelAllForGame(gameId, keepCleanup);
    }

    /**
     * Returns whether an unexpired game currently exists for {@code gameId}.
     * Caller must hold the room lock.
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
     * Caller must hold the room lock.
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
        if (game == null) {
            return false;
        }
        return game.getPlayers().stream()
                .anyMatch(player -> playerName.equals(player.getName()) && !player.isOut());
    }

    /**
     * Verifies that {@code membershipId} matches the active session membership for
     * {@code playerName} in {@code gameId}.
     * Caller must hold the room lock.
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
    @DurableMutation
    public void markPlayerDisconnected(@GameId String gameId, String playerName, long disconnectDeadlineEpochMs) {
        departureHandler.markPlayerDisconnected(gameId, playerName, disconnectDeadlineEpochMs);
    }

    /**
     * Marks a reconnecting player as connected across room and game state and resumes pending
     * forfeit/ready handling if needed.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the reconnecting player's name
     */
    @DurableMutation
    public void markPlayerReconnected(@GameId String gameId, String playerName) {
        departureHandler.markPlayerReconnected(gameId, playerName);
    }

    /**
     * Marks an eligible player ready for the next hand and starts the next hand if all eligible
     * players are now ready.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player marking ready
     */
    @DurableMutation
    public void markPlayerReadyForNextHand(@GameId String gameId, String playerName) {
        readinessCoordinator.markPlayerReadyForNextHand(gameId, playerName);
    }

    /**
     * Removes a player's ready status while the between-hand ready countdown is open.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player unmarking ready
     */
    @DurableMutation
    public void unmarkPlayerReadyForNextHand(@GameId String gameId, String playerName) {
        readinessCoordinator.unmarkPlayerReadyForNextHand(gameId, playerName);
    }

    /**
     * Claims an immediate win when all other contenders are disconnected, or returns the saved
     * final summary if the game already finished.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the claiming player's name
     * @return the final {@link GameEndResponse}
     */
    @DurableMutation
    public GameEndResponse claimWin(@GameId String gameId, String playerName) {
        return departureHandler.claimWin(gameId, playerName);
    }

    /**
     * Returns the saved final summary of a finished game for {@code playerName}.
     * Caller must hold the room lock.
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
    @DurableMutation
    public void removeDisconnectedPlayer(
            @GameId String gameId, String playerName, String expectedMembershipId, long expectedDeadlineEpochMs) {
        if (playerName == null || expectedMembershipId == null) {
            return;
        }
        departureHandler.removeDisconnectedPlayer(
                gameId, playerName, expectedMembershipId, expectedDeadlineEpochMs);
    }
}
