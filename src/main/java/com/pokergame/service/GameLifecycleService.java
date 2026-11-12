package com.pokergame.service;

import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates games and exposes the room-coordinated lifecycle API.
 * Durable entry points hold the room lease and dispatch to the workflow owners.
 */
@Service
public class GameLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(GameLifecycleService.class);
    public static final long FINAL_SHOWDOWN_DISPLAY_DELAY_MS = HandProgressionService.FINAL_SHOWDOWN_DISPLAY_DELAY_MS;
    public static final long TURN_ALLOWANCE_MS = GameTaskScheduler.TURN_ALLOWANCE_MS;
    public static final long GAME_INACTIVITY_ALLOWANCE_MS = GameInactivityPolicy.ALLOWANCE_MS;
    private Clock clock = Clock.systemUTC();

    /** Supplies the authoritative clock without changing legacy construction. */
    @Autowired(required = false)
    public void setClock(Clock clock) { this.clock = clock; }

    public Clock getClock() { return clock; }

    @Value("${poker.game-end.display-delay-ms:20000}")
    private long gameEndDisplayDelayMs = 20000;

    public long getGameEndDisplayDelayMs() { return gameEndDisplayDelayMs; }

    @Value("${poker.round-end.display-delay-ms:0}")
    private long roundEndDisplayDelayMs;

    @Value("${poker.ready-countdown-ms:30000}")
    private long configuredReadyCountdownMs = 30000;

    /** Returns the configured ready countdown duration in milliseconds. */
    long getConfiguredReadyCountdownMs() {
        return configuredReadyCountdownMs;
    }

    private final RoomService roomService;

    private final HandEvaluatorService handEvaluator;

    private final SimpMessagingTemplate messagingTemplate;

    private final GameTimerRegistry runtimeTimers;
    private final GameReadinessCoordinator readiness;
    private final HandProgressionService handProgression;
    private final GameCompletionHandler completion;
    private final PlayerDepartureHandler departureHandler;
    private final RoomCoordinator roomCoordinator;
    private final GameRecoveryService recovery;
    private final GameTaskScheduler scheduling;
    private GameLifecycleService self;

    private final ActiveGameRegistry activeGames = new ActiveGameRegistry();

    /**
     * Creates the lifecycle service with a runtime scheduler and a local room coordinator.
     * Scheduled intent lives on {@link Game} so timers can be rebuilt after a restart.
     */
    public GameLifecycleService(RoomService roomService,
            HandEvaluatorService handEvaluatorService,
            GameStateService gameStateService,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this(roomService, handEvaluatorService, gameStateService, messagingTemplate, taskScheduler, (RoomCoordinator) null);
    }

    /** Creates the Spring service with the application room coordinator when available. */
    @Autowired
    public GameLifecycleService(RoomService roomService,
            HandEvaluatorService handEvaluatorService,
            GameStateService gameStateService,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            ObjectProvider<RoomCoordinator> roomCoordinatorProvider) {
        this(roomService, handEvaluatorService, gameStateService, messagingTemplate, taskScheduler,
                roomCoordinatorProvider != null ? roomCoordinatorProvider.getIfAvailable(RoomCoordinator::new) : null);
    }

    /** Creates the lifecycle service and its workflow owners with a shared room coordinator. */
    public GameLifecycleService(RoomService roomService,
            HandEvaluatorService handEvaluatorService,
            GameStateService gameStateService,
            SimpMessagingTemplate messagingTemplate,
            TaskScheduler taskScheduler,
            RoomCoordinator roomCoordinator) {
        this.roomService = roomService;
        this.handEvaluator = handEvaluatorService;
        this.messagingTemplate = messagingTemplate;
        this.runtimeTimers = new GameTimerRegistry(taskScheduler);
        this.roomCoordinator = roomCoordinator != null ? roomCoordinator : new RoomCoordinator();
        this.scheduling = new GameTaskScheduler(activeGames, runtimeTimers, this.roomCoordinator, roomService,
                this::getClock,
                (gameId, task, deadline, hand, generation) -> mutationProxy().executeScheduledTask(gameId, task, deadline, hand, generation),
                (gameId, deadline) -> mutationProxy().executeInactivityCleanup(gameId, deadline));
        this.completion = new GameCompletionHandler(activeGames, roomService, gameStateService, runtimeTimers,
                scheduling, this.roomCoordinator, this::getClock, this::getGameEndDisplayDelayMs);
        this.handProgression = new HandProgressionService(activeGames, this.roomCoordinator, runtimeTimers, scheduling,
                completion, gameStateService, () -> roundEndDisplayDelayMs);
        this.readiness = new GameReadinessCoordinator(activeGames, roomService, gameStateService, runtimeTimers,
                scheduling, completion, handProgression, this.roomCoordinator, this::getClock,
                this::getConfiguredReadyCountdownMs, gameId -> mutationProxy().handleGameEnd(gameId, false));
        this.departureHandler = new PlayerDepartureHandler(roomService, gameStateService, completion, handProgression,
                readiness, runtimeTimers, activeGames, this.roomCoordinator, this::getClock);
        this.recovery = new GameRecoveryService(activeGames, roomService, gameStateService, runtimeTimers,
                this.roomCoordinator, scheduling);
    }

    public RoomCoordinator getRoomCoordinator() {
        return roomCoordinator;
    }

    /** Returns the departure owner for existing package callers. */
    PlayerDepartureHandler getDepartureHandler() {
        return departureHandler;
    }

    /**
     * Injects the Spring proxy so deferred callbacks enter {@link DurableMutation} advice.
     * Directly constructed tests can continue using the service without a proxy.
     */
    @Autowired
    void setSelf(@Lazy GameLifecycleService self) {
        this.self = self;
    }

    /** Durable batch departure for lobby and game participants, including host succession and forfeits. */
    @DurableMutation(roomId = "#roomId")
    public void departParticipants(String roomId, Collection<String> playerNames) {
        if (roomId == null || roomId.trim().isEmpty() || playerNames == null || playerNames.isEmpty()) return;
        roomCoordinator.coordinate(roomId, () -> departureHandler.departParticipants(roomId, playerNames));
    }

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
    @DurableMutation(roomId = "#roomId")
    public String createGameFromRoom(String roomId) {
        return roomCoordinator.coordinate(roomId, () -> {
            Room room = roomService.getRoom(roomId);
            if (room == null) {
                logger.warn("Game creation failed: room not found for id {}", roomId);
                throw new ResourceNotFoundException("Room not found");
            }

            if (roomService.getRoom(roomId) != room) throw new ResourceNotFoundException("Room not found");
            if (roomService.isLobbyExpired(room)) {
                logger.warn("Game creation failed: lobby {} has expired due to inactivity", roomId);
                roomService.checkAndCloseIfExpired(roomId);
                throw new BadRequestException("Room has expired due to inactivity.");
            }
            if (activeGames.contains(roomId) || room.isGameStarted()) {
                logger.warn("Game creation failed: game already exists for room {}", roomId);
                throw new BadRequestException("Game has already started for this room.");
            }

            if (room.getPlayers().size() < 2) {
                logger.warn("Game creation failed: not enough players in room {} (count: {})", roomId,
                        room.getPlayers().size());
                throw new UnauthorisedActionException(
                        "Need at least 2 players to start game. Please wait for more players to join.");
            }

            // Create the actual poker game
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

            Game game = new Game(roomId, players, room.getSmallBlind(), room.getBigBlind(), handEvaluator);
            activeGames.register(game);

            // Mark room started and capture start event payload before scheduling broadcast
            room.setGameStarted(true);
            roomService.cancelLobbyInactivity(roomId);
            long gameInactivityDeadline = GameInactivityPolicy.nextDeadline(clock.millis());
            room.setInactivityDeadlineEpochMs(gameInactivityDeadline);
            scheduling.scheduleGameInactivity(roomId, gameInactivityDeadline);
            RoomDataResponse startPayload = roomService.getRoomData(roomId);

            DurableTransactionContext.afterCommit(() -> messagingTemplate.convertAndSend("/room/" + roomId,
                    new ApiResponse<>(ResponseMessage.GAME_STARTED.getMessage(), startPayload)));

            logger.info("Game created and started for room: {} with {} players", roomId, room.getPlayers().size());

            readiness.applyHandContinuation(roomId, handProgression.startNewHand(roomId));

            return roomId;
        });
    }

    /** Starts the next eligible hand, including any immediate readiness from forced blinds. */
    @DurableMutation(roomId = "#gameId")
    public void startNewHand(String gameId) {
        roomCoordinator.coordinate(gameId,
                () -> readiness.applyHandContinuation(gameId, handProgression.startNewHand(gameId)));
    }

    /** Applies hand progression under the caller's room lease. */
    void applyTurnOutcome(String gameId, Game game, TurnOutcome outcome) {
        readiness.applyHandContinuation(gameId, handProgression.applyTurnOutcome(gameId, game, outcome));
    }

    /**
     * Applies the accepted turn outcome under its room lease.
     * The caller must derive the outcome under the same lease. The flag selects the initial all-in notification.
     */
    @DurableMutation(roomId = "#gameId")
    public void applyTurnOutcome(String gameId, Game game, TurnOutcome outcome, boolean notifyImmediateAutoAdvance) {
        roomCoordinator.coordinate(gameId, () -> readiness.applyHandContinuation(gameId,
                handProgression.applyTurnOutcome(gameId, game, outcome, notifyImmediateAutoAdvance)));
    }

    /** Deals or settles one all-in step within its own durable mutation. Returns true on completion. */
    @DurableMutation(roomId = "#gameId")
    public boolean advanceAutoAdvanceStep(String gameId) {
        return roomCoordinator.coordinate(gameId, () -> handProgression.advanceAutoAdvanceStep(gameId));
    }

    /** Freezes and publishes normal or forfeit completion, then schedules removal. */
    @DurableMutation(roomId = "#gameId")
    public void handleGameEnd(String gameId, boolean isForfeit) {
        roomCoordinator.coordinate(gameId, () -> completion.handleGameEnd(gameId, isForfeit));
    }

    /** Freezes a forfeit result while retaining the complete terminal roster. */
    @DurableMutation(roomId = "#gameId")
    public void handleGameEndWithForfeits(String gameId, Player preferredWinner, Set<String> forfeitedPlayers) {
        roomCoordinator.coordinate(gameId, () -> completion.handleGameEndWithForfeits(gameId, preferredWinner, forfeitedPlayers));
    }

    /** Registers the room's captured inactivity deadline with the runtime timer owner. */
    public void scheduleGameInactivity(String gameId, Long deadlineEpochMs) {
        if (gameId == null || deadlineEpochMs == null || deadlineEpochMs <= 0) return;
        roomCoordinator.coordinate(gameId, () -> scheduling.scheduleGameInactivity(gameId, deadlineEpochMs));
    }

    /** Cancels runtime inactivity work without changing the room's deadline. */
    public void cancelGameInactivity(String gameId) {
        runtimeTimers.cancelInactivity(gameId);
    }

    /** Applies inactivity cleanup only when the captured deadline still matches and has elapsed. */
    @DurableMutation(roomId = "#gameId")
    public void executeInactivityCleanup(String gameId, Long expectedDeadlineEpochMs) {
        if (gameId == null || expectedDeadlineEpochMs == null) return;
        roomCoordinator.coordinate(gameId, () -> completion.executeInactivityCleanup(gameId, expectedDeadlineEpochMs));
    }

    /** Freezes an abandonment result and schedules removal, leaving the unfinished pot unawarded. */
    @DurableMutation(roomId = "#gameId")
    public void abandonGameDueToInactivity(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> completion.abandonGameDueToInactivity(gameId));
    }

    /** Extends the inactivity deadline for accepted game activity. */
    @DurableMutation(roomId = "#gameId")
    public void resetGameInactivity(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> scheduling.resetGameInactivity(gameId));
    }

    /** Removes an expired room or game with accepted terminal cleanup intent. */
    @DurableMutation(roomId = "#gameId")
    public void executeTerminalCleanup(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> completion.executeTerminalCleanup(gameId));
    }

    /** Removes the game, its room, and all runtime registrations. */
    @DurableMutation(roomId = "#gameId")
    public void performGameCleanup(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> completion.performGameCleanup(gameId));
    }

    /** Returns the current live game. Callers must hold the room lease while using the result. */
    public Game getGame(String gameId) {
        return activeGames.get(gameId);
    }

    /** Installs an already rehydrated game under its room lease without dealing a new hand. */
    public void restoreGame(Game game) {
        if (game == null) return;
        roomCoordinator.coordinate(game.getGameId(), () -> recovery.restoreGame(game));
    }

    /** Evicts the game and its timers while retaining the room. */
    public void evictGame(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> recovery.evictGame(gameId));
    }

    /** Normalizes recovered readiness and rebuilds pending timers after the recovery mutation. */
    @DurableMutation(roomId = "#gameId")
    public void resumeRecoveredTimers(String gameId) {
        roomCoordinator.coordinate(gameId, () -> recovery.resumeRecoveredTimers(gameId, configuredReadyCountdownMs));
    }

    /**
     * Restores accepted runtime registrations from exact deadlines and generations.
     * Rollback does not normalize readiness, broadcast, or queue work in the failed mutation.
     */
    public void resumeRollbackTimers(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> recovery.resumeRollbackTimers(gameId));
    }

    /** Persists the next-hand deadline in the mutation that requested it. */
    @DurableMutation(roomId = "#gameId")
    void scheduleNewHand(String gameId, long delayMs) {
        scheduling.scheduleNewHand(gameId, delayMs);
    }

    /** Records the post-showdown delay, or opens readiness immediately in the current mutation. */
    @DurableMutation(roomId = "#gameId")
    void scheduleReadyCountdownOpen(String gameId, long delayMs) {
        if (scheduling.scheduleReadyCountdownOpen(gameId, delayMs) == GameTaskScheduler.ReadyOpening.OPEN_IMMEDIATELY) {
            roomCoordinator.coordinate(gameId, () -> readiness.openConfiguredCountdown(gameId));
        }
    }

    /** Persists cleanup before returning from the game-end mutation. */
    @DurableMutation(roomId = "#gameId")
    void scheduleGameCleanup(String gameId, long delayMs) {
        scheduling.scheduleGameCleanup(gameId, delayMs);
    }

    /** Records when the final board display ends and the terminal result is due. */
    @DurableMutation(roomId = "#gameId")
    void scheduleGameEnd(String gameId, long delayMs) {
        scheduling.scheduleGameEnd(gameId, delayMs);
    }

    /** Persists the first all-in progression deadline before publishing auto-advance. */
    @DurableMutation(roomId = "#gameId")
    void scheduleAutoAdvance(String gameId) {
        roomCoordinator.coordinate(gameId, () -> scheduling.scheduleAutoAdvance(gameId));
    }

    /** Handles a turn deadline using the captured task identity. */
    @DurableMutation(roomId = "#gameId")
    public void executeTurnDeadline(String gameId, Long expectedDeadlineEpochMs, Integer expectedHandNumber, Integer expectedGeneration) {
        if (expectedDeadlineEpochMs == null || expectedHandNumber == null || expectedGeneration == null) {
            return;
        }
        executeTurnDeadline(gameId, expectedDeadlineEpochMs, expectedHandNumber.longValue(), expectedGeneration.longValue());
    }

    @DurableMutation(roomId = "#gameId")
    public void executeTurnDeadline(String gameId, Long expectedDeadlineEpochMs, Long expectedHandNumber, Long expectedGeneration) {
        if (gameId == null || expectedDeadlineEpochMs == null || expectedHandNumber == null || expectedGeneration == null) {
            return;
        }
        executeCapturedTask(gameId, ScheduledGameTask.TURN_TIMEOUT, expectedDeadlineEpochMs, expectedHandNumber, expectedGeneration);
    }

    /** Handles auto-advance using deadline and hand identity for compatibility callers. */
    @DurableMutation(roomId = "#gameId")
    public void executeAutoAdvanceHand(String gameId, Long expectedDeadlineEpochMs, Integer expectedHandNumber) {
        if (expectedDeadlineEpochMs == null || expectedHandNumber == null) {
            return;
        }
        executeAutoAdvanceHand(gameId, expectedDeadlineEpochMs, expectedHandNumber.longValue(), null);
    }

    @DurableMutation(roomId = "#gameId")
    public void executeAutoAdvanceHand(String gameId, Long expectedDeadlineEpochMs, Long expectedHandNumber) {
        if (expectedDeadlineEpochMs == null || expectedHandNumber == null) {
            return;
        }
        executeAutoAdvanceHand(gameId, expectedDeadlineEpochMs, expectedHandNumber, null);
    }

    @DurableMutation(roomId = "#gameId")
    public void executeAutoAdvanceHand(String gameId, Long expectedDeadlineEpochMs, Long expectedHandNumber, Long expectedGeneration) {
        if (gameId == null || expectedDeadlineEpochMs == null || expectedHandNumber == null) return;
        executeCapturedTask(gameId, ScheduledGameTask.AUTO_ADVANCE, expectedDeadlineEpochMs, expectedHandNumber, expectedGeneration);
    }

    /** Uses the current task identity for compatibility callers, with a deadline-only ready timeout. */
    @DurableMutation(roomId = "#gameId")
    void executeScheduledTask(String gameId, ScheduledGameTask task, long expectedDeadline) {
        if (gameId == null || task == null) return;
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) return;
            if (task == ScheduledGameTask.READY_TIMEOUT) {
                expireReadyCountdown(gameId, expectedDeadline);
                return;
            }
            long generation = game.getScheduledTaskGeneration(task);
            long handNumber = game.getHandNumber();
            executeScheduledTask(gameId, task, expectedDeadline, handNumber, generation);
        });
    }

    /** Dispatches delayed work after validating its captured deadline, hand, and generation. */
    @DurableMutation(roomId = "#gameId")
    public void executeScheduledTask(String gameId, ScheduledGameTask task, long expectedDeadline, long expectedHandNumber, long expectedGeneration) {
        if (gameId == null || task == null) return;
        executeCapturedTask(gameId, task, expectedDeadline, expectedHandNumber, expectedGeneration);
    }

    /** Claims delayed work under the existing room lease before dispatching its effect. */
    private void executeCapturedTask(String gameId, ScheduledGameTask task, long deadline, long hand, Long generation) {
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (scheduling.claim(gameId, game, task, deadline, hand, generation) != GameTaskScheduler.Claim.EXECUTE) return;
            switch (task) {
                case TURN_TIMEOUT -> departureHandler.handleTurnTimeoutUnderLock(gameId, game);
                case AUTO_ADVANCE -> readiness.applyHandContinuation(gameId, handProgression.applyAutoAdvanceTimeout(gameId, game));
                case READY_TIMEOUT -> readiness.applyProgression(gameId, readiness.expire(gameId, game, deadline));
                case NEW_HAND -> readiness.applyHandContinuation(gameId, handProgression.startNewHand(gameId));
                case READY_OPEN -> readiness.openConfiguredCountdown(gameId);
                case GAME_END -> completion.handleGameEnd(gameId, false);
                case CLEANUP -> DurableTransactionContext.afterCommit(() -> mutationProxy().performGameCleanup(gameId));
            }
        });
    }

    public void cancelRuntimeTasks(String gameId, boolean keepCleanup) {
        runtimeTimers.cancelAllForGame(gameId, keepCleanup);
    }

    public Long getInactivityDeadline(String gameId) {
        return roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            return GameInactivityPolicy.deadline(room);
        });
    }

    /** Reports whether the configured inactivity deadline has been reached. */
    public boolean isGameInactivityExpired(String gameId) {
        Long deadline = getInactivityDeadline(gameId);
        return GameInactivityPolicy.isExpired(deadline, clock.millis());
    }

    public int getRuntimeTaskCount(String gameId) {
        return runtimeTimers.countForGame(gameId);
    }

    /** Reports whether a supplied game has reached its cleanup deadline. */
    public boolean isGameExpired(Game game) {
        return completion.isGameExpired(game);
    }

    /** Checks whether the current game exists and its cleanup deadline has not elapsed. */
    public boolean gameExists(String gameId) {
        return roomCoordinator.coordinate(gameId, () -> {
            Game game = activeGames.get(gameId);
            return game != null && !completion.isGameExpired(game);
        });
    }

    /**
     * Checks whether a player currently exists in an active game.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the player name to check
     * @return true if the player exists in the game, false otherwise
     */
    public boolean playerExistsInGame(String gameId, String playerName) {
        if (gameId == null || playerName == null) {
            return false;
        }
        return roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                return false;
            }

            List<Player> players = game.getPlayers();
            if (players == null) {
                return false;
            }
            return players.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(player -> playerName.equals(player.getName()) && !player.getIsOut());
        });
    }

    /**
     * Validates that the provided membership ID matches the active membership for the player in the room.
     * If the player presents a token with a stale or mismatched membership ID, the request is rejected.
     *
     * @param gameId       game/room identifier
     * @param playerName   player name
     * @param membershipId claimed membership ID from token
     * @throws AccessDeniedException if token membership is stale or mismatched
     */
    public void validatePlayerMembership(String gameId, String playerName, String membershipId) {
        if (membershipId == null || membershipId.isBlank() || playerName == null || playerName.isBlank() || gameId == null) {
            throw new AccessDeniedException("Token membership is stale or invalid.");
        }
        roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            if (room != null) {
                String activeMembershipId = room.getMembershipId(playerName);
                if (activeMembershipId == null || !activeMembershipId.equals(membershipId)) {
                    logger.warn("Rejecting request: stale or mismatched membership for player {} in game {} (token: {}, active: {})",
                            playerName, gameId, membershipId, activeMembershipId);
                    throw new AccessDeniedException("Token membership is stale or invalid.");
                }
            }
        });
    }

    /** Marks a player disconnected and records their exact reconnect deadline. */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerDisconnected(String gameId, String playerName, long disconnectDeadlineEpochMs) {
        roomCoordinator.coordinate(gameId,
                () -> departureHandler.markPlayerDisconnected(gameId, playerName, disconnectDeadlineEpochMs));
    }

    /** Reconciles room and game connectivity after a player reconnects. */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReconnected(String gameId, String playerName) {
        roomCoordinator.coordinate(gameId, () -> departureHandler.markPlayerReconnected(gameId, playerName));
    }

    /** Opens the ready gate and schedules a timeout for the given duration in milliseconds. */
    @DurableMutation(roomId = "#gameId")
    public void startReadyCountdown(String gameId, long countdownMs) {
        roomCoordinator.coordinate(gameId, () -> readiness.applyProgression(gameId,
                readiness.startReadyCountdown(gameId, countdownMs)));
    }

    /** Marks an eligible player ready for the next deal. */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReadyForNextHand(String gameId, String playerName) {
        roomCoordinator.coordinate(gameId, () -> readiness.applyProgression(gameId,
                readiness.markPlayerReadyForNextHand(gameId, playerName)));
    }

    /** Removes a player from the ready set while the gate is open. */
    @DurableMutation(roomId = "#gameId")
    public void unmarkPlayerReadyForNextHand(String gameId, String playerName) {
        roomCoordinator.coordinate(gameId, () -> readiness.applyProgression(gameId,
                readiness.unmarkPlayerReadyForNextHand(gameId, playerName)));
    }

    /**
     * Expires the ready gate by deadline for compatibility callers.
     * Scheduled callbacks also validate the captured hand and generation.
     */
    @DurableMutation(roomId = "#gameId")
    public void expireReadyCountdown(String gameId, Long expectedDeadlineEpochMs) {
        if (gameId == null || expectedDeadlineEpochMs == null) return;
        roomCoordinator.coordinate(gameId, () -> readiness.applyProgression(gameId,
                readiness.expireReadyCountdown(gameId, expectedDeadlineEpochMs)));
    }

    /**
     * Expires the ready gate by deadline for compatibility callers.
     * The deadline alone cannot distinguish two gates with the same expiry.
     */
    @DurableMutation(roomId = "#gameId")
    public void handleReadyCountdownTimeout(String gameId, long expectedDeadlineEpochMs) {
        expireReadyCountdown(gameId, expectedDeadlineEpochMs);
    }

    /** Cancels the obsolete ready timer and closes the gate before starting the next hand. */
    void completeReadyCountdownAndStartNextHand(String gameId, Game game) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        readiness.complete(gameId, game);
        readiness.applyHandContinuation(gameId, handProgression.startNewHand(gameId));
    }

    public void cancelTurnTimeout(String gameId) {
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
    }

    /**
     * Claims a disconnect win when the other contenders are disconnected.
     * Repeat claims return the retained result without settlement, broadcast, or a new timer.
     */
    @DurableMutation(roomId = "#gameId")
    public GameEndResponse claimWin(String gameId, String playerName) {
        return roomCoordinator.coordinate(gameId, () -> departureHandler.claimWin(gameId, playerName));
    }

    /** Retrieves the frozen terminal result for an authorized participant. */
    public GameEndResponse getGameReview(String gameId, String playerName) {
        return roomCoordinator.coordinate(gameId, () -> completion.getGameReview(gameId, playerName));
    }

    /** Removes a player only while the accepted grace deadline still matches. */
    @DurableMutation(roomId = "#gameId")
    public void executeDisconnectGraceExpiry(String gameId, String playerName, Long expectedDeadlineEpochMs) {
        if (gameId == null || playerName == null || expectedDeadlineEpochMs == null) return;
        roomCoordinator.coordinate(gameId,
                () -> departureHandler.executeDisconnectGraceExpiry(gameId, playerName, expectedDeadlineEpochMs));
    }

    /** Removes a player only when both membership identity and grace deadline match. */
    @DurableMutation(roomId = "#gameId")
    public void removeDisconnectedPlayer(String gameId, String playerName, String expectedMembershipId, long expectedDeadlineEpochMs) {
        if (gameId == null || playerName == null || expectedMembershipId == null) return;
        roomCoordinator.coordinate(gameId, () -> departureHandler.removeDisconnectedPlayer(
                gameId, playerName, expectedMembershipId, expectedDeadlineEpochMs));
    }

    /** Returns the durable Spring proxy, or the service itself in directly constructed tests. */
    private GameLifecycleService mutationProxy() {
        return self == null ? this : self;
    }
}
