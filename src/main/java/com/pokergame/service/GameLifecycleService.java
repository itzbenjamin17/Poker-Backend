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

import java.util.*;

/**
 * Service class responsible for game lifecycle management.
 * Handles game creation, starting new hands, and game termination.
 */
@Service
public class GameLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(GameLifecycleService.class);
    public static final long FINAL_SHOWDOWN_DISPLAY_DELAY_MS = HandProgressionService.FINAL_SHOWDOWN_DISPLAY_DELAY_MS;
    public static final long TURN_ALLOWANCE_MS = GameTaskScheduler.TURN_ALLOWANCE_MS;
    public static final long GAME_INACTIVITY_ALLOWANCE_MS = GameInactivityPolicy.ALLOWANCE_MS;
    private java.time.Clock clock = java.time.Clock.systemUTC();

    /** Supplies the authoritative clock without changing legacy construction. */
    @Autowired(required = false)
    public void setClock(java.time.Clock clock) { this.clock = clock; }

    public java.time.Clock getClock() { return clock; }

    @Value("${poker.game-end.display-delay-ms:20000}")
    private long gameEndDisplayDelayMs = 20000;

    public long getGameEndDisplayDelayMs() { return gameEndDisplayDelayMs; }

    @Value("${poker.round-end.display-delay-ms:0}")
    private long roundEndDisplayDelayMs;

    @Value("${poker.ready-countdown-ms:30000}")
    private long configuredReadyCountdownMs = 30000;

    /**
     * Returns the configured ready countdown duration in milliseconds for package collaborators.
     *
     * @return ready countdown duration in milliseconds
     */
    long getConfiguredReadyCountdownMs() {
        return configuredReadyCountdownMs;
    }

    private final RoomService roomService;

    private final HandEvaluatorService handEvaluator;

    private final GameStateService gameStateService;

    private final SimpMessagingTemplate messagingTemplate;

    private final GameTimerRegistry runtimeTimers;
    private final GameReadinessCoordinator readiness;
    private final HandProgressionService handProgression;
    private final GameCompletionHandler completion;
    private final GameParticipantDepartureHandler departureHandler;
    private final RoomCoordinator roomCoordinator;
    private final GameRecoveryService recovery;
    private final GameTaskScheduler scheduling;
    private GameLifecycleService self;

    private final ActiveGameRegistry activeGames = new ActiveGameRegistry();

    /**
     * Creates the lifecycle service with the scheduler that owns runtime timers.
     * Scheduled intent itself lives on {@link Game} so timers can be rebuilt after a
     * restart. Backwards-compatible overload.
     *
     * @param roomService          authoritative room service
     * @param handEvaluatorService hand evaluator attached to new and restored games
     * @param gameStateService     client state publisher
     * @param messagingTemplate    direct messaging collaborator
     * @param taskScheduler        runtime timer scheduler
     */
    public GameLifecycleService(RoomService roomService,
            HandEvaluatorService handEvaluatorService,
            GameStateService gameStateService,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this(roomService, handEvaluatorService, gameStateService, messagingTemplate, taskScheduler, (RoomCoordinator) null);
    }

    /**
     * Autowired constructor with all dependencies including optional/provided RoomCoordinator.
     *
     * @param roomService              authoritative room service
     * @param handEvaluatorService     hand evaluator attached to new and restored games
     * @param gameStateService         client state publisher
     * @param messagingTemplate        direct messaging collaborator
     * @param taskScheduler            runtime timer scheduler
     * @param roomCoordinatorProvider provider for the room-scoped coordinator
     */
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

    /**
     * Creates the lifecycle service explicitly with a RoomCoordinator.
     *
     * @param roomService          authoritative room service
     * @param handEvaluatorService hand evaluator attached to new and restored games
     * @param gameStateService     client state publisher
     * @param messagingTemplate    direct messaging collaborator
     * @param taskScheduler        runtime timer scheduler
     * @param roomCoordinator      coordinator managing room-scoped concurrency boundaries
     */
    public GameLifecycleService(RoomService roomService,
            HandEvaluatorService handEvaluatorService,
            GameStateService gameStateService,
            SimpMessagingTemplate messagingTemplate,
            TaskScheduler taskScheduler,
            RoomCoordinator roomCoordinator) {
        this.roomService = roomService;
        this.handEvaluator = handEvaluatorService;
        this.gameStateService = gameStateService;
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
        this.departureHandler = new GameParticipantDepartureHandler(this, roomService, gameStateService, completion, activeGames);
        this.recovery = new GameRecoveryService(activeGames, roomService, gameStateService, runtimeTimers,
                this.roomCoordinator, scheduling);
    }

    public RoomCoordinator getRoomCoordinator() {
        return roomCoordinator;
    }

    GameParticipantDepartureHandler getDepartureHandler() {
        return departureHandler;
    }

    /**
     * We inject a proxy of this exact same service into itself to solve Spring's "self-invocation" problem.
     * <p>
     * When background timers (like the turn timer) expire, they need to call methods in this class
     * and trigger a save. If we used `this.method()`, it would bypass the {@link DurableMutation}
     * autosave trigger. By using `self.method()`, we ensure the game is properly saved.
     * </p>
     *
     * @param self the Spring proxy of this service
     */
    @Autowired
    void setSelf(@Lazy GameLifecycleService self) {
        this.self = self;
    }

    /**
     * Single durable entry point for participant departure across room and game
     * aggregates. Handles lobby host departure (room destruction), active game host
     * departure (host transfer), batch departures, forfeit classification, and
     * terminal settlement freeze.
     *
     * @param roomId room or game identifier
     * @param playerNames collection of player names departing
     */
    @DurableMutation(roomId = "#roomId")
    public void departParticipants(String roomId, Collection<String> playerNames) {
        if (roomId == null || roomId.trim().isEmpty() || playerNames == null || playerNames.isEmpty()) {
            return;
        }

        roomCoordinator.coordinate(roomId, () -> {
            Room room = roomService.getRoom(roomId);
            if (room == null) {
                Game game = getGame(roomId);
                if (game == null || game.isGameOver()) return;
                departureHandler.departParticipantsUnderLock(roomId, playerNames, null, game);
                return;
            }

            if (roomService.getRoom(roomId) != room) return;
            Game game = getGame(roomId);
            if (game != null && GameInactivityPolicy.isExpired(GameInactivityPolicy.deadline(room), clock.millis())) {
                completion.abandonGameDueToInactivity(roomId);
                return;
            }
            departureHandler.departParticipantsUnderLock(roomId, playerNames, room, game);
        });
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

    /**
     * Starts a new hand of poker for the specified game.
     * Resets game state, deals cards, posts blinds, and broadcasts initial state.
     *
     * @param gameId The unique identifier of the game
     */
    @DurableMutation(roomId = "#gameId")
    public void startNewHand(String gameId) {
        roomCoordinator.coordinate(gameId,
                () -> readiness.applyHandContinuation(gameId, handProgression.startNewHand(gameId)));
    }

    void applyTurnOutcome(String gameId, Game game, TurnOutcome outcome) {
        readiness.applyHandContinuation(gameId, handProgression.applyTurnOutcome(gameId, game, outcome));
    }

    /**
     * Applies progression and publishes an outcome from the current durable mutation.
     * Callers must derive the outcome from this game under the same room lease.
     *
     * @param gameId                     game identifier
     * @param game                       authoritative game state
     * @param outcome                    turn outcome produced by the game
     * @param notifyImmediateAutoAdvance {@code true} to emit the initial all-in notification banner
     */
    @DurableMutation(roomId = "#gameId")
    public void applyTurnOutcome(String gameId, Game game, TurnOutcome outcome, boolean notifyImmediateAutoAdvance) {
        roomCoordinator.coordinate(gameId, () -> readiness.applyHandContinuation(gameId,
                handProgression.applyTurnOutcome(gameId, game, outcome, notifyImmediateAutoAdvance)));
    }

    /**
     * Applies one authoritative auto-advance mutation for an all-in hand.
     * Every deck, phase, payout, and player mutation enters through this public
     * service seam so each step receives its own committed state image.
     *
     * @param gameId game to advance
     * @return {@code true} when the step completed showdown, otherwise false
     */
    @DurableMutation(roomId = "#gameId")
    public boolean advanceAutoAdvanceStep(String gameId) {
        return roomCoordinator.coordinate(gameId, () -> handProgression.advanceAutoAdvanceStep(gameId));
    }

    /**
     * Handles the end of a game when all players except one have been eliminated.
     * Broadcasts the game end event to all participants and cleans up game
     * resources.
     *
     * @param gameId    the unique identifier of the game
     * @param isForfeit true if the game ended due to a player leaving/disconnecting
     */
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

    /**
     * Executes inactivity cleanup under the room lease when expected deadline matches
     * current room state and has elapsed according to the authoritative clock.
     *
     * @param gameId                 game identifier
     * @param expectedDeadlineEpochMs deadline epoch ms captured when scheduled
     */
    @DurableMutation(roomId = "#gameId")
    public void executeInactivityCleanup(String gameId, Long expectedDeadlineEpochMs) {
        if (gameId == null || expectedDeadlineEpochMs == null) return;
        roomCoordinator.coordinate(gameId, () -> completion.executeInactivityCleanup(gameId, expectedDeadlineEpochMs));
    }

    /**
     * Stops an inactive game after 15 minutes without deliberate player action,
     * installs an abandonment outcome with no winner and unawarded unfinished pot,
     * and durably schedules cleanup 20 seconds later.
     *
     * @param gameId game identifier
     */
    @DurableMutation(roomId = "#gameId")
    public void abandonGameDueToInactivity(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> completion.abandonGameDueToInactivity(gameId));
    }

    /**
     * Resets the active-game inactivity deadline to 15 minutes in the future under lock.
     *
     * @param gameId game identifier
     * @param game   game instance
     */
    void resetGameInactivityUnderLock(String gameId, Game game) {
        scheduling.resetGameInactivityUnderLock(gameId, game);
    }

    /**
     * Resets the active-game inactivity deadline to 15 minutes in the future.
     *
     * @param gameId game identifier
     */
    @DurableMutation(roomId = "#gameId")
    public void resetGameInactivity(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> scheduling.resetGameInactivity(gameId));
    }

    /**
     * Executes terminal cleanup under room lease, verifying that game is terminal or cleanup was scheduled.
     *
     * @param gameId game identifier
     */
    @DurableMutation(roomId = "#gameId")
    public void executeTerminalCleanup(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> completion.executeTerminalCleanup(gameId));
    }

    /**
     * Cleans up the finished game so it no longer uses resources
     *
     * @param gameId id of the game that is now done
     */
    @DurableMutation(roomId = "#gameId")
    public void performGameCleanup(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> completion.performGameCleanup(gameId));
    }

    /**
     * Retrieves a game for internal use under its room lease.
     * Callers must not retain the live result beyond the coordinated operation.
     *
     * @param gameId the unique identifier of the game to retrieve
     * @return the Game object if found, null otherwise
     */
    public Game getGame(String gameId) {
        return activeGames.get(gameId);
    }

    /**
     * Registers an already rehydrated game without executing normal creation logic,
     * which would start a replacement hand. Restores in-memory state under the room
     * lock without dropping existing locks or leaking inconsistent player mapping.
     *
     * @param game exact game restored from a committed state image
     */
    public void restoreGame(Game game) {
        if (game == null) return;
        roomCoordinator.coordinate(game.getGameId(), () -> recovery.restoreGame(game));
    }

    /**
     * Evicts active game state and cancels pending timers for the specified game
     * without destroying the associated room container.
     *
     * @param gameId identifier of the game to evict
     */
    public void evictGame(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> recovery.evictGame(gameId));
    }

    /**
     * Rebuilds runtime futures from absolute persisted deadlines. Overdue work is
     * scheduled immediately and remains safe because callbacks verify the expected
     * deadline before mutating state.
     *
     * @param gameId recovered game whose runtime work must resume
     */
    @DurableMutation(roomId = "#gameId")
    public void resumeRecoveredTimers(String gameId) {
        roomCoordinator.coordinate(gameId, () -> recovery.resumeRecoveredTimers(gameId, configuredReadyCountdownMs));
    }

    /**
     * Restores only runtime registrations after live rollback, preserving the accepted
     * deadlines and generations exactly. Unlike startup recovery, it does not normalize
     * ready state, publish snapshots, or queue work in the failed transaction context.
     *
     * @param gameId restored game whose accepted task intents must run
     */
    public void resumeRollbackTimers(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> recovery.resumeRollbackTimers(gameId));
    }

    /**
     * Persists the next-hand deadline in the same room transaction that requested
     * it, preventing a crash from leaving a completed countdown with no next hand.
     *
     * @param gameId  game awaiting a new hand
     * @param delayMs delay before execution
     */
    @DurableMutation(roomId = "#gameId")
    void scheduleNewHand(String gameId, long delayMs) {
        scheduling.scheduleNewHand(gameId, delayMs);
    }

    /**
     * Persists the post-showdown display delay. An immediate opening stays in the
     * current durable transaction so no scheduler handoff is needed.
     *
     * @param gameId  game entering its ready gate
     * @param delayMs display delay before the gate opens
     */
    @DurableMutation(roomId = "#gameId")
    void scheduleReadyCountdownOpen(String gameId, long delayMs) {
        if (scheduling.scheduleReadyCountdownOpen(gameId, delayMs) == GameTaskScheduler.ReadyOpening.OPEN_IMMEDIATELY) {
            roomCoordinator.coordinate(gameId, () -> readiness.openConfiguredCountdown(gameId));
        }
    }

    /**
     * Persists terminal cleanup intent before returning the game-end mutation, so a
     * restart cannot leave a permanently finished room in memory.
     *
     * @param gameId  finished game
     * @param delayMs client display delay before deletion
     */
    @DurableMutation(roomId = "#gameId")
    void scheduleGameCleanup(String gameId, long delayMs) {
        scheduling.scheduleGameCleanup(gameId, delayMs);
    }

    /**
     * Persists the final game-end deadline before notifying clients of victory,
     * allowing a review window of the final board before the game-over overlay.
     *
     * @param gameId  game whose tournament has concluded
     * @param delayMs client review delay before game-over broadcast
     */
    @DurableMutation(roomId = "#gameId")
    void scheduleGameEnd(String gameId, long delayMs) {
        scheduling.scheduleGameEnd(gameId, delayMs);
    }

    /**
     * Persists the first all-in progression deadline before clients observe the
     * auto-advance state.
     *
     * @param gameId game requiring automatic board progression
     */
    @DurableMutation(roomId = "#gameId")
    void scheduleAutoAdvance(String gameId) {
        roomCoordinator.coordinate(gameId, () -> scheduling.scheduleAutoAdvance(gameId));
    }

    /**
     * Executes turn deadline only when expected deadline, hand number, and generation match current state.
     *
     * @param gameId                  game identifier
     * @param expectedDeadlineEpochMs expected deadline epoch ms
     * @param expectedHandNumber      expected hand number
     * @param expectedGeneration      expected task generation
     */
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

    /**
     * Executes auto-advance hand step only when expected deadline and hand number match current state.
     *
     * @param gameId                  game identifier
     * @param expectedDeadlineEpochMs expected deadline epoch ms
     * @param expectedHandNumber      expected hand number
     */
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

    /**
     * Executes delayed work when handNumber and generation are known from current game aggregate.
     * Package-private convenience overload for testing and internal callers.
     *
     * @param gameId          game owning the work
     * @param task            delayed work type
     * @param expectedDeadline deadline captured by this timer callback
     */
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

    /**
     * Executes delayed work only when its deadline, handNumber, and generation still match persisted intent.
     * The deadline and generation act as tokens making cancelled or duplicated timer callbacks harmless.
     *
     * @param gameId             game owning the work
     * @param task               delayed work type
     * @param expectedDeadline   deadline captured by this timer callback
     * @param expectedHandNumber hand number when scheduled
     * @param expectedGeneration generation token when scheduled
     */
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
                case TURN_TIMEOUT -> applyTurnTimeout(gameId, game);
                case AUTO_ADVANCE -> readiness.applyHandContinuation(gameId, handProgression.applyAutoAdvanceTimeout(gameId, game));
                case READY_TIMEOUT -> readiness.applyProgression(gameId, readiness.expire(gameId, game, deadline));
                case NEW_HAND -> readiness.applyHandContinuation(gameId, handProgression.startNewHand(gameId));
                case READY_OPEN -> readiness.openConfiguredCountdown(gameId);
                case GAME_END -> completion.handleGameEnd(gameId, false);
                case CLEANUP -> DurableTransactionContext.afterCommit(() -> mutationProxy().performGameCleanup(gameId));
            }
        });
    }

    private void applyTurnTimeout(String gameId, Game game) {
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (GameInactivityPolicy.isExpired(GameInactivityPolicy.deadline(room), clock.millis())) {
            logger.info("Inactivity deadline expired concurrently with turn timeout for game {}; abandonment takes precedence", gameId);
            completion.abandonGameDueToInactivity(gameId);
            return;
        }
        Player actor = game.getCurrentPlayer();
        if (actor != null) {
            String actorToDepart = actor.getName();
            departParticipants(gameId, List.of(actorToDepart));
            logger.info("Player {} timed out after 60 seconds in game {}; removing from seat", actorToDepart, gameId);
        }
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

    /**
     * Checks if the active game has reached or passed its inactivity deadline.
     *
     * @param gameId game identifier
     * @return true if an active game inactivity deadline is configured and reached
     */
    public boolean isGameInactivityExpired(String gameId) {
        Long deadline = getInactivityDeadline(gameId);
        return GameInactivityPolicy.isExpired(deadline, clock.millis());
    }

    public int getRuntimeTaskCount(String gameId) {
        return runtimeTimers.countForGame(gameId);
    }

    /**
     * Checks if a game has reached its scheduled cleanup deadline.
     *
     * @param game game to check
     * @return true if cleanup deadline is set and current time is at or past it
     */
    public boolean isGameExpired(Game game) {
        return completion.isGameExpired(game);
    }

    /**
     * Checks if a game with the specified ID exists in the active games and has not expired.
     *
     * @param gameId the unique identifier of the game to check
     * @return true if the game exists in active games and is not expired, false otherwise
     */
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

    /**
     * Marks an active player as disconnected and broadcasts updated game state.
     * Disconnected players stay in the game seat while reconnect grace is active.
     *
     * @param gameId                    the game identifier
     * @param playerName                the disconnected player name
     * @param disconnectDeadlineEpochMs disconnect grace expiry timestamp (UTC epoch
     *                                  ms)
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerDisconnected(String gameId, String playerName, long disconnectDeadlineEpochMs) {
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                logger.debug("Cannot mark player disconnected: game not found for gameId={}", gameId);
                return null;
            }

            departureHandler.markPlayerDisconnectedUnderLock(gameId, game, playerName, disconnectDeadlineEpochMs);
            return null;
        });
    }

    /**
     * Clears disconnect flag for a player that reconnects within grace period.
     *
     * @param gameId     the game identifier
     * @param playerName the reconnecting player name
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReconnected(String gameId, String playerName) {
        roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService.getRoom(gameId);
            Game game = getGame(gameId);
            departureHandler.reconcilePlayerReconnectedUnderLock(gameId, playerName, room, game);
            return null;
        });
    }

    /**
     * Opens a shared ready countdown after round end and schedules timeout
     * auto-advance.
     *
     * @param gameId      game identifier
     * @param countdownMs countdown duration in milliseconds
     */
    @DurableMutation(roomId = "#gameId")
    public void startReadyCountdown(String gameId, long countdownMs) {
        roomCoordinator.coordinate(gameId, () -> readiness.applyProgression(gameId,
                readiness.startReadyCountdown(gameId, countdownMs)));
    }

    /**
     * Marks a player as ready during an active ready countdown gate.
     *
     * @param gameId     game identifier
     * @param playerName player name from principal
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReadyForNextHand(String gameId, String playerName) {
        roomCoordinator.coordinate(gameId, () -> readiness.applyProgression(gameId,
                readiness.markPlayerReadyForNextHand(gameId, playerName)));
    }

    /**
     * Unmarks a player as ready during an active ready countdown gate.
     *
     * @param gameId     game identifier
     * @param playerName player name from principal
     */
    @DurableMutation(roomId = "#gameId")
    public void unmarkPlayerReadyForNextHand(String gameId, String playerName) {
        roomCoordinator.coordinate(gameId, () -> readiness.applyProgression(gameId,
                readiness.unmarkPlayerReadyForNextHand(gameId, playerName)));
    }

    /**
     * Expires the ready gate by deadline for compatibility callers.
     * Scheduled callbacks use the hand number and generation as well.
     *
     * @param gameId                 game whose ready gate may expire
     * @param expectedDeadlineEpochMs deadline captured when the timer was submitted
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
     *
     * @param gameId                 game whose ready gate may expire
     * @param expectedDeadlineEpochMs deadline captured when the timer was submitted
     */
    @DurableMutation(roomId = "#gameId")
    public void handleReadyCountdownTimeout(String gameId, long expectedDeadlineEpochMs) {
        expireReadyCountdown(gameId, expectedDeadlineEpochMs);
    }

    /**
     * Cancels the process-local timeout before closing durable ready state and
     * starting the hand, preventing a racing timeout from advancing twice.
     *
     * @param gameId game leaving the ready gate
     * @param game   authoritative game state
     */
    void completeReadyCountdownAndStartNextHand(String gameId, Game game) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        readiness.complete(gameId, game);
        readiness.applyHandContinuation(gameId, handProgression.startNewHand(gameId));
    }

    public void cancelTurnTimeout(String gameId) {
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
    }

    /**
     * Claims an immediate game win for a connected player when all other
     * non-out players are disconnected. Repeating claim-win on a completed
     * game returns the retained result without settlement, broadcast, or timer extension.
     *
     * @param gameId     game identifier
     * @param playerName claimant name
     * @return frozen terminal result
     */
    @DurableMutation(roomId = "#gameId")
    public GameEndResponse claimWin(String gameId, String playerName) {
        return roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null || completion.isGameExpired(game)) {
                throw new ResourceNotFoundException("Game not found");
            }

            Room room = roomService.getRoom(gameId);
            if (room == null) throw new ResourceNotFoundException("Room not found");

            completion.rejectExpiredActivity(gameId, room);
            if (game.isGameOver()) {
                GameEndResponse retained = game.getRetainedTerminalResult();
                if (retained != null && retained.abandoned()) {
                    throw new BadRequestException("Game was abandoned due to inactivity.", "GAME_ABANDONED", "Game was abandoned due to inactivity");
                }
            }
            return departureHandler.claimWinUnderLock(gameId, game, room, playerName);
        });
    }

    /**
     * Retrieves the frozen terminal result for post-game review.
     *
     * @param gameId     game identifier
     * @param playerName requesting player name
     * @return frozen terminal result
     */
    public GameEndResponse getGameReview(String gameId, String playerName) {
        return roomCoordinator.coordinate(gameId, () -> completion.getGameReview(gameId, playerName));
    }

    /**
     * Removes a grace-expired player from room and game under room lease
     * when the expected deadline matches current domain state.
     *
     * @param gameId                 room/game identity
     * @param playerName             player whose reconnect grace expired
     * @param expectedDeadlineEpochMs exact absolute deadline epoch ms expected
     */
    @DurableMutation(roomId = "#gameId")
    public void executeDisconnectGraceExpiry(String gameId, String playerName, Long expectedDeadlineEpochMs) {
        if (gameId == null || playerName == null || expectedDeadlineEpochMs == null) {
            return;
        }
        roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            if (room == null) {
                return;
            }

            if (!room.hasPlayer(playerName)) {
                return;
            }

            Game game = getGame(gameId);
            departureHandler.removeDisconnectedPlayerUnderLock(gameId, room, game, playerName, expectedDeadlineEpochMs);
        });
    }

    /**
     * Removes a grace-expired player from room and game under one room transaction
     * when the expected membership identity and expected deadline match current domain state.
     *
     * @param gameId                room/game identity
     * @param playerName            player whose reconnect grace expired
     * @param expectedMembershipId  opaque process-local membership ID expected to expire
     * @param expectedDeadlineEpochMs exact absolute deadline epoch ms expected
     */
    @DurableMutation(roomId = "#gameId")
    public void removeDisconnectedPlayer(String gameId, String playerName, String expectedMembershipId, long expectedDeadlineEpochMs) {
        if (gameId == null || playerName == null || expectedMembershipId == null) {
            return;
        }
        roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService.getRoom(gameId);
            if (room == null) {
                return;
            }

            if (!room.hasPlayer(playerName)) {
                return;
            }
            String currentMembershipId = room.getMembershipId(playerName);
            if (!Objects.equals(expectedMembershipId, currentMembershipId)) {
                return;
            }

            Game game = getGame(gameId);
            departureHandler.removeDisconnectedPlayerUnderLock(gameId, room, game, playerName, expectedDeadlineEpochMs);
        });
    }

    /**
     * Returns the Spring proxy for timer callbacks while preserving direct unit-test
     * construction, where no proxy is available.
     *
     * @return proxied service in production, otherwise this instance
     */
    private GameLifecycleService mutationProxy() {
        return self == null ? this : self;
    }
}
