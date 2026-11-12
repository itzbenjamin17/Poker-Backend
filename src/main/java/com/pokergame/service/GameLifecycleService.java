package com.pokergame.service;

import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ConflictException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import com.pokergame.service.GameRuntimeTimers.TaskIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pokergame.util.MdcUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import com.pokergame.enums.GamePhase;

/**
 * Service class responsible for game lifecycle management.
 * Handles game creation, starting new hands, and game termination.
 */
@Service
public class GameLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(GameLifecycleService.class);
    public static final long FINAL_SHOWDOWN_DISPLAY_DELAY_MS = 6000;
    private static final long AUTO_ADVANCE_STEP_DELAY_MS = 4000;
    public static final long TURN_ALLOWANCE_MS = 60_000;
    public static final long GAME_INACTIVITY_ALLOWANCE_MS = 15 * 60_000L;
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

    private TaskScheduler inactivityScheduler;

    private final Map<String, ScheduledFuture<?>> gameInactivityFutures = new ConcurrentHashMap<>();

    @Autowired(required = false)
    void setInactivityScheduler(@Qualifier("taskScheduler") TaskScheduler inactivityScheduler) {
        this.inactivityScheduler = inactivityScheduler;
    }

    private final GameRuntimeTimers runtimeTimers;
    private final GameTerminalSettlement terminalSettlement;
    private final GameParticipantDepartureHandler departureHandler;
    private final RoomCoordinator roomCoordinator;
    private GameLifecycleService self;

    private final Map<String, Game> activeGames = new ConcurrentHashMap<>();

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
        this.runtimeTimers = new GameRuntimeTimers(taskScheduler);
        this.terminalSettlement = new GameTerminalSettlement(gameStateService, runtimeTimers);
        this.departureHandler = new GameParticipantDepartureHandler(this, roomService, gameStateService, terminalSettlement, activeGames);
        this.roomCoordinator = roomCoordinator != null ? roomCoordinator : new RoomCoordinator();
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
            if (game != null) {
                Long inactivityDeadline = room.getInactivityDeadlineEpochMs();
                if (inactivityDeadline != null && inactivityDeadline > 0 && clock.millis() >= inactivityDeadline) {
                    mutationProxy().abandonGameDueToInactivity(roomId);
                    return;
                }
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
            if (activeGames.containsKey(roomId) || room.isGameStarted()) {
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
            activeGames.put(roomId, game);

            // Mark room started and capture start event payload before scheduling broadcast
            room.setGameStarted(true);
            roomService.cancelLobbyInactivity(roomId);
            long gameInactivityDeadline = clock.millis() + GAME_INACTIVITY_ALLOWANCE_MS;
            room.setInactivityDeadlineEpochMs(gameInactivityDeadline);
            scheduleGameInactivity(roomId, gameInactivityDeadline);
            RoomDataResponse startPayload = roomService.getRoomData(roomId);

            DurableTransactionContext.afterCommit(() -> messagingTemplate.convertAndSend("/room/" + roomId,
                    new ApiResponse<>(ResponseMessage.GAME_STARTED.getMessage(), startPayload)));

            logger.info("Game created and started for room: {} with {} players", roomId, room.getPlayers().size());

            startNewHand(roomId);

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
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                logger.warn("Cannot start new hand - game {} not found", gameId);
                return;
            }
            logger.info("Starting new hand for game: {}", gameId);
            if (game.isForfeitPending()) return;
            if (game.isGameOver()) {
                if (!game.isTerminalCarryOverSettled()) handleGameEnd(gameId, false);
                return;
            }
            if (game.getHandNumber() > 0 && (game.getCurrentPhase() != GamePhase.SHOWDOWN
                    || !game.isHandSettled())) {
                logger.warn("Ignoring new-hand request for unsettled game {} hand {}", gameId, game.getHandNumber());
                return;
            }
            cancelRuntimeTasks(gameId, false);
            game.invalidateProgressionTasks();
            cancelReadyTimeout(gameId);
            game.closeReadyCountdown();
            game.clearScheduledTask(ScheduledGameTask.CLEANUP);

            // Reset determines game completion before cards are dealt so the final
            // winner state remains visible during the configured display window.
            boolean gameEnded = game.resetForNewHand();
            if (gameEnded) {
                logger.warn("Game {} became over after reset", gameId);
                handleGameEnd(gameId, false);
                return;
            }
            if (gameStateService != null) {
                gameStateService.clearRetainedSettlement(gameId);
            }

            game.beginHand();

            game.dealHoleCards();
            game.postBlinds();
            applyTurnOutcome(gameId, game, game.reconcileAfterForcedBlinds());

            logger.info("New hand started successfully for game: {} | Current player: {} | Phase: {} | Pot: {}",
                    gameId, game.getCurrentPlayer() == null ? "none" : game.getCurrentPlayer().getName(),
                    game.getCurrentPhase(), game.getPot());
        });
    }

    void applyTurnOutcome(String gameId, Game game, TurnOutcome outcome) {
        applyTurnOutcomeUnderLock(gameId, game, outcome, false);
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
        roomCoordinator.coordinate(gameId, () -> {
            applyTurnOutcomeUnderLock(gameId, game, outcome, notifyImmediateAutoAdvance);
        });
    }

    private void applyTurnOutcomeUnderLock(String gameId, Game game, TurnOutcome outcome, boolean notifyImmediateAutoAdvance) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        switch (outcome.type()) {
            case NEXT_PLAYER, PHASE_ADVANCED -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.AUTO_ADVANCE);
                if (game.getCurrentPlayer() != null) {
                    persistScheduledTask(gameId, ScheduledGameTask.TURN_TIMEOUT, clock.millis() + TURN_ALLOWANCE_MS);
                } else {
                    game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                    runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                }
                gameStateService.broadcastGameState(gameId, game);
            }
            case AUTO_ADVANCING -> {
                game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                if (game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE) == null) {
                    scheduleAutoAdvance(gameId);
                }
                if (notifyImmediateAutoAdvance) {
                    gameStateService.broadcastAutoAdvanceNotification(gameId, game);
                } else {
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Auto-advancing...");
                }
            }
            case SHOWDOWN -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                schedulePostShowdownProgression(gameId, game);
                gameStateService.broadcastShowdownResults(gameId, game, outcome.winners(), outcome.winningsPerPlayer());
            }
        }
    }

    private void schedulePostShowdownProgression(String gameId, Game game) {
        if (game.isTournamentOver()) {
            scheduleGameEnd(gameId, FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
        } else {
            scheduleReadyCountdownOpen(gameId, roundEndDisplayDelayMs);
        }
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
        return roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                return true;
            }

            if (game.isForfeitPending()) return true;
            if (game.isGameOver()) return true;
            if (game.isHandSettled()
                    || game.isTerminalCarryOverSettled()) {
                logger.info("Skipping stale auto-advance callback for settled game {}", gameId);
                return true;
            }
            return switch (game.getCurrentPhase()) {
                case PRE_FLOP -> {
                    game.dealFlop();
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing flop...");
                    yield false;
                }
                case FLOP -> {
                    game.dealTurn();
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing turn...");
                    yield false;
                }
                case TURN -> {
                    game.dealRiver();
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing river...");
                    yield false;
                }
                case RIVER -> {
                    int potBeforeDistribution = game.getPot();
                    List<Player> winners = game.conductShowdown();
                    int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();
                    gameStateService.broadcastShowdownResults(gameId, game, winners, winningsPerPlayer);
                    gameStateService.broadcastAutoAdvanceComplete(gameId, game);
                    yield true;
                }
                case SHOWDOWN -> {
                    logger.warn("Auto-advance step invoked for game {} already in SHOWDOWN - skipping duplicate distribution", gameId);
                    yield true;
                }
            };
        });
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
        handleGameEndInternal(gameId, isForfeit, null, Set.of());
    }

    /** Freezes a forfeit result while retaining the complete terminal roster. */
    @DurableMutation(roomId = "#gameId")
    public void handleGameEndWithForfeits(String gameId, Player preferredWinner, Set<String> forfeitedPlayers) {
        handleGameEndInternal(gameId, true, preferredWinner, forfeitedPlayers == null ? Set.of() : Set.copyOf(forfeitedPlayers));
    }

    private void handleGameEndInternal(String gameId, boolean isForfeit, Player preferredWinner, Set<String> forfeitedPlayers) {
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) return;

            Room room = roomService.getRoom(gameId);
            GameTerminalSettlement.SettlementOutcome outcome;
            outcome = terminalSettlement.settleUnderLock(gameId, game, room, isForfeit, preferredWinner, forfeitedPlayers);
            if (!outcome.settledNow()) return;

            if (room != null) {
                room.setTerminal(true);
            }
            if (outcome.winner() == null) {
                logger.warn("Cannot broadcast game end for {} - no winner could be determined", gameId);
                return;
            }
            if (outcome.event() != null) {
                gameStateService.sendGameEnd(outcome.event());
            }
            scheduleGameCleanup(gameId, gameEndDisplayDelayMs);
        });
    }

    /**
     * Abandons an active game authoritatively due to inactivity.
     * Stops gameplay immediately without declaring a winner, preserves completed-hand payouts,
     * leaves unfinished pots unawarded, broadcasts the abandonment terminal outcome,
     * and durably schedules cleanup 20 seconds later.
     *
     * @param gameId game identifier
     */
    public void scheduleGameInactivity(String gameId, Long deadlineEpochMs) {
        if (inactivityScheduler == null || deadlineEpochMs == null || deadlineEpochMs <= 0) return;
        cancelGameInactivity(gameId);
        long delay = Math.max(0, deadlineEpochMs - clock.millis());
        ScheduledFuture<?> future = inactivityScheduler.schedule(
                () -> {
                    try {
                        mutationProxy().executeInactivityCleanup(gameId, deadlineEpochMs);
                    } catch (Exception e) {
                        logger.error("Error executing game inactivity abandonment for game {}", gameId, e);
                    }
                },
                Instant.ofEpochMilli(System.currentTimeMillis() + delay)
        );
        if (future != null) {
            gameInactivityFutures.put(gameId, future);
        }
    }

    public void cancelGameInactivity(String gameId) {
        if (gameId == null) return;
        ScheduledFuture<?> future = gameInactivityFutures.remove(gameId);
        if (future != null) {
            future.cancel(false);
        }
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
        if (gameId == null || expectedDeadlineEpochMs == null) {
            return;
        }
        roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            if (room == null) {
                return;
            }
            Game game = getGame(gameId);
            if (game == null) {
                return;
            }
            if (game.isGameOver() && game.getRetainedTerminalResult() != null) {
                return;
            }
            Long currentDeadline = room.getInactivityDeadlineEpochMs();
            if (currentDeadline == null || !currentDeadline.equals(expectedDeadlineEpochMs)) {
                logger.debug("Skipping inactivity cleanup: deadline changed for game {}", gameId);
                return;
            }
            if (clock.millis() < expectedDeadlineEpochMs) {
                logger.debug("Skipping inactivity cleanup: deadline has not elapsed for game {}", gameId);
                return;
            }
            abandonGameDueToInactivity(gameId);
        });
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
        roomCoordinator.coordinate(gameId, () -> {
            cancelGameInactivity(gameId);
            Game game = getGame(gameId);
            if (game == null) return;
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            GameEndResponse event;
            if (game.isGameOver() && game.getRetainedTerminalResult() != null) {
                return;
            }
            game.markGameOver();
            game.closeReadyCountdown();
            game.invalidateProgressionTasks();
            runtimeTimers.cancelAllForGame(gameId, true);
            game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
            game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
            game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
            game.clearScheduledTask(ScheduledGameTask.READY_OPEN);
            game.clearScheduledTask(ScheduledGameTask.NEW_HAND);
            if (gameStateService != null) {
                gameStateService.clearRetainedSettlement(gameId);
            }
            int maxPlayers = room != null ? room.getMaxPlayers() : game.getPlayers().size();
            event = gameStateService != null
                    ? gameStateService.captureAbandonment(gameId, game, maxPlayers)
                    : null;
            game.setRetainedTerminalResult(event);

            if (room != null) {
                room.setTerminal(true);
            }
            if (event != null) {
                gameStateService.sendGameEnd(event);
            }
            scheduleGameCleanup(gameId, gameEndDisplayDelayMs);
            logger.info("Game {} abandoned due to inactivity; scheduled {}ms cleanup", gameId, gameEndDisplayDelayMs);
        });
    }

    /**
     * Resets the active-game inactivity deadline to 15 minutes in the future under lock.
     *
     * @param gameId game identifier
     * @param game   game instance
     */
    void resetGameInactivityUnderLock(String gameId, Game game) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (game == null || game.isGameOver()) return;
        long newDeadline = clock.millis() + GAME_INACTIVITY_ALLOWANCE_MS;
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (room != null) {
            room.setInactivityDeadlineEpochMs(newDeadline);
        }
        scheduleGameInactivity(gameId, newDeadline);
        logger.debug("Active-game inactivity deadline reset for game {} to {}", gameId, newDeadline);
    }

    /**
     * Resets the active-game inactivity deadline to 15 minutes in the future.
     *
     * @param gameId game identifier
     */
    @DurableMutation(roomId = "#gameId")
    public void resetGameInactivity(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) return;
            resetGameInactivityUnderLock(gameId, game);
        });
    }

    /**
     * Executes terminal cleanup under room lease, verifying that game is terminal or cleanup was scheduled.
     *
     * @param gameId game identifier
     */
    @DurableMutation(roomId = "#gameId")
    public void executeTerminalCleanup(String gameId) {
        if (gameId == null) {
            return;
        }
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            if (game == null && room == null) {
                return;
            }
            if (game != null) {
                if (!game.isGameOver() && game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP) == null) {
                    logger.debug("Skipping terminal cleanup for uncompleted game {}", gameId);
                    return;
                }
            }
            performGameCleanup(gameId);
        });
    }

    /**
     * Cleans up the finished game so it no longer uses resources
     *
     * @param gameId id of the game that is now done
     */
    @DurableMutation(roomId = "#gameId")
    public void performGameCleanup(String gameId) {
        if (gameId == null) return;
        roomCoordinator.coordinate(gameId, () -> {
            cancelGameInactivity(gameId);
            cancelRuntimeTasks(gameId, false);
            activeGames.remove(gameId);
            if (gameStateService != null) {
                gameStateService.clearRetainedSettlement(gameId);
            }
            roomService.destroyRoom(gameId);
            logger.info("Game {} and associated room cleaned up", gameId);
        });
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
        if (game == null) {
            return;
        }
        String gameId = game.getGameId();
        roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService.getRoom(gameId);
            Game existing = activeGames.get(gameId);
            applyRestoreGame(game, room, existing);
            return null;
        });
    }

    private void applyRestoreGame(Game game, Room room, Game existing) {
        String gameId = game.getGameId();
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (existing != null && existing != game) {
            cancelRuntimeTasks(gameId, false);
        }
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
        }
        activeGames.put(gameId, game);
        if (room != null) {
            room.setTerminal(game.isGameOver());
            room.setGameStarted(true);
        }
    }

    /**
     * Evicts active game state and cancels pending timers for the specified game
     * without destroying the associated room container.
     *
     * @param gameId identifier of the game to evict
     */
    public void evictGame(String gameId) {
        if (gameId == null) {
            return;
        }
        roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService.getRoom(gameId);
            cancelRuntimeTasks(gameId, false);
            activeGames.remove(gameId);
            if (gameStateService != null) {
                gameStateService.clearRetainedSettlement(gameId);
            }
            if (room != null) {
                room.setGameStarted(false);
                room.setTerminal(false);
            }
            return null;
        });
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
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                return;
            }
            MdcUtils.runWithRoomId(gameId, () -> {
                boolean normalized = game.normalizeRecoveredReadyCountdown(System.currentTimeMillis() + configuredReadyCountdownMs);
                List<TaskIntent> intents = GameRuntimeTimers.captureAllIntents(gameId, game);
                int rebuiltCount = intents.size();
                DurableTransactionContext.afterCommit(() -> intents.forEach(this::scheduleRuntimeTask));
                if (normalized) gameStateService.broadcastGameState(gameId, game);
                logger.info("Resumed {} recovered timers for game {}", rebuiltCount, gameId);
            });
        });
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
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) return null;
            List<TaskIntent> intents = GameRuntimeTimers.captureAllIntents(gameId, game);
            intents.forEach(this::scheduleRuntimeTask);
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            if (room != null && room.getInactivityDeadlineEpochMs() != null && !game.isGameOver()) {
                scheduleGameInactivity(gameId, room.getInactivityDeadlineEpochMs());
            }
            return null;
        });
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
        persistScheduledTask(gameId, ScheduledGameTask.NEW_HAND, System.currentTimeMillis() + Math.max(0, delayMs));
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
        if (delayMs <= 0) {
            startReadyCountdown(gameId, configuredReadyCountdownMs);
            return;
        }
        persistScheduledTask(gameId, ScheduledGameTask.READY_OPEN, System.currentTimeMillis() + delayMs);
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
        persistScheduledTask(gameId, ScheduledGameTask.CLEANUP, clock.millis() + Math.max(0, delayMs));
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
        persistScheduledTask(gameId, ScheduledGameTask.GAME_END, clock.millis() + Math.max(0, delayMs));
    }

    /**
     * Persists the first all-in progression deadline before clients observe the
     * auto-advance state.
     *
     * @param gameId game requiring automatic board progression
     */
    @DurableMutation(roomId = "#gameId")
    void scheduleAutoAdvance(String gameId) {
        roomCoordinator.coordinate(gameId, () -> {
            persistScheduledTask(gameId, ScheduledGameTask.AUTO_ADVANCE,
                    System.currentTimeMillis() + AUTO_ADVANCE_STEP_DELAY_MS);
            return null;
        });
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
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                logger.debug("Cannot execute turn deadline: game not found for gameId={}", gameId);
                return;
            }
            String actorToDepart = null;
            switch (game.claimScheduledTaskExecution(ScheduledGameTask.TURN_TIMEOUT, expectedDeadlineEpochMs, expectedHandNumber, expectedGeneration)) {
                case IGNORE -> { return; }
                case CANCEL_GAME_OVER -> {
                    runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                    return;
                }
                case EXECUTE -> { }
            }
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            Long inactivityDeadline = room != null ? room.getInactivityDeadlineEpochMs() : null;
            boolean inactivityDue = inactivityDeadline != null && inactivityDeadline > 0 && clock.millis() >= inactivityDeadline;

            if (inactivityDue) {
                logger.info("Inactivity deadline expired concurrently with turn timeout for game {}; abandonment takes precedence", gameId);
                abandonGameDueToInactivity(gameId);
                return;
            }
            Player actor = game.getCurrentPlayer();
            if (actor != null) {
                actorToDepart = actor.getName();
            }
            if (actorToDepart != null) {
                departParticipants(gameId, List.of(actorToDepart));
                logger.info("Player {} timed out after 60 seconds in game {}; removing from seat", actorToDepart, gameId);
            }
        });
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
        if (gameId == null || expectedDeadlineEpochMs == null || expectedHandNumber == null) {
            return;
        }
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                logger.debug("Cannot execute auto advance: game not found for gameId={}", gameId);
                return;
            }
            long generation = expectedGeneration != null ? expectedGeneration : game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE);
            switch (game.claimScheduledTaskExecution(ScheduledGameTask.AUTO_ADVANCE, expectedDeadlineEpochMs, expectedHandNumber, generation)) {
                case IGNORE -> { return; }
                case CANCEL_GAME_OVER -> {
                    runtimeTimers.cancelTask(gameId, ScheduledGameTask.AUTO_ADVANCE);
                    return;
                }
                case EXECUTE -> { }
            }
            if (game.getCurrentPhase() == GamePhase.SHOWDOWN) {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                return;
            }
            boolean complete = advanceAutoAdvanceStep(gameId);
            if (complete) {
                schedulePostShowdownProgression(gameId, game);
            } else {
                persistScheduledTask(gameId, ScheduledGameTask.AUTO_ADVANCE,
                        System.currentTimeMillis() + AUTO_ADVANCE_STEP_DELAY_MS);
            }
        });
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
        if (gameId == null || task == null) {
            return;
        }
        roomCoordinator.coordinate(gameId, () -> {
            switch (task) {
                case TURN_TIMEOUT -> {
                    executeTurnDeadline(gameId, expectedDeadline, expectedHandNumber, expectedGeneration);
                    return;
                }
                case AUTO_ADVANCE -> {
                    executeAutoAdvanceHand(gameId, expectedDeadline, expectedHandNumber, expectedGeneration);
                    return;
                }
                case READY_TIMEOUT -> {
                    expireReadyCountdown(gameId, expectedDeadline);
                    return;
                }
                default -> { }
            }

            Game game = getGame(gameId);
            if (game == null) {
                logger.debug("Cannot execute task {}: game not found for gameId={}", task, gameId);
                return;
            }
            boolean cleanupRequested = false;
            switch (game.claimScheduledTaskExecution(task, expectedDeadline, expectedHandNumber, expectedGeneration)) {
                case IGNORE -> { return; }
                case CANCEL_GAME_OVER -> {
                    runtimeTimers.cancelTask(gameId, task);
                    return;
                }
                case EXECUTE -> { }
            }
            logger.debug("Executing task {} for game {}", task, gameId);
            switch (task) {
                case NEW_HAND -> startNewHand(gameId);
                case READY_OPEN -> startReadyCountdown(gameId, configuredReadyCountdownMs);
                case CLEANUP -> cleanupRequested = true;
                case GAME_END -> handleGameEnd(gameId, false);
                default -> { }
            }
            if (cleanupRequested) {
                DurableTransactionContext.afterCommit(() -> mutationProxy().performGameCleanup(gameId));
            }
        });
    }

    /**
     * Records scheduler intent as aggregate state first, then submits the runtime
     * future only after the surrounding WAL commit succeeds.
     *
     * @param gameId  game owning the work
     * @param task    delayed work type
     * @param deadline absolute execution deadline
     */
    private void persistScheduledTask(String gameId, ScheduledGameTask task, long deadline) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = getGame(gameId);
        if (game == null) {
            return;
        }
        if (game.isForfeitPending()) return;
        game.scheduleTask(task, deadline);
        TaskIntent intent = GameRuntimeTimers.captureIntent(gameId, game, task, deadline);
        DurableTransactionContext.afterCommit(() -> scheduleRuntimeTask(intent));
    }

    /** Registers immutable task intent while its current aggregate is protected. */
    private void scheduleRuntimeTask(TaskIntent intent) {
        roomCoordinator.assertHeldByCurrentThread(intent.gameId());
        runtimeTimers.schedule(intent, getGame(intent.gameId()), mutationProxy()::executeScheduledTask);
    }

    public void cancelRuntimeTasks(String gameId, boolean keepCleanup) {
        cancelGameInactivity(gameId);
        runtimeTimers.cancelAllForGame(gameId, keepCleanup);
    }

    public Long getInactivityDeadline(String gameId) {
        return roomCoordinator.coordinate(gameId, () -> {
            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            return room != null ? room.getInactivityDeadlineEpochMs() : null;
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
        return deadline != null && deadline > 0 && clock.millis() >= deadline;
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
        return game == null || game.isCleanupExpired(clock.millis());
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
            return game != null && !isGameExpired(game);
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
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                return;
            }

            long deadlineEpochMs = clock.millis() + countdownMs;
            TaskIntent readyIntent;
            Game.ReadyCountdownStartOutcome outcome = game.startOrWaitReadyCountdown(deadlineEpochMs);
            switch (outcome) {
                case IGNORE -> { return; }
                case TOURNAMENT_OVER -> {
                    DurableTransactionContext.afterCommit(() -> mutationProxy().handleGameEnd(gameId, false));
                    return;
                }
                case WAITING_FOR_PLAYERS -> {
                    cancelReadyTimeout(gameId);
                    gameStateService.broadcastGameState(gameId, game);
                    return;
                }
                case ALL_READY -> {
                    gameStateService.broadcastGameState(gameId, game);
                    completeReadyCountdownAndStartNextHand(gameId, game);
                    return;
                }
                case OPENED -> {
                    readyIntent = GameRuntimeTimers.captureIntent(gameId, game, ScheduledGameTask.READY_TIMEOUT, deadlineEpochMs);
                    gameStateService.broadcastGameState(gameId, game);
                }
                default -> throw new IllegalStateException("Unexpected ready start outcome: " + outcome);
            }
            DurableTransactionContext.afterCommit(() -> scheduleRuntimeTask(readyIntent));
        });
    }

    /**
     * Marks a player as ready during an active ready countdown gate.
     *
     * @param gameId     game identifier
     * @param playerName player name from principal
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReadyForNextHand(String gameId, String playerName) {
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                throw new ResourceNotFoundException("Game not found");
            }

            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            Long inactivityDeadline = room != null ? room.getInactivityDeadlineEpochMs() : null;
            if (inactivityDeadline != null && inactivityDeadline > 0 && clock.millis() >= inactivityDeadline) {
                mutationProxy().abandonGameDueToInactivity(gameId);
                throw new BadRequestException("Game has expired due to inactivity.", "GAME_EXPIRED", "Game has expired due to inactivity");
            }

            Player player = game.findPlayerByName(playerName);
            boolean previousReady = player != null && player.getIsReadyForNextHand();

            game.setPlayerReadyForNextHand(playerName, true);
            if (!previousReady) {
                resetGameInactivityUnderLock(gameId, game);
            }
            logger.debug("Player {} marked ready for next hand in game {}", playerName, gameId);

            if (game.areAllReadyEligiblePlayersReady()) {
                completeReadyCountdownAndStartNextHand(gameId, game);
            } else {
                gameStateService.broadcastGameState(gameId, game);
            }
            return null;
        });
    }

    /**
     * Unmarks a player as ready during an active ready countdown gate.
     *
     * @param gameId     game identifier
     * @param playerName player name from principal
     */
    @DurableMutation(roomId = "#gameId")
    public void unmarkPlayerReadyForNextHand(String gameId, String playerName) {
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                throw new ResourceNotFoundException("Game not found");
            }

            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            Long inactivityDeadline = room != null ? room.getInactivityDeadlineEpochMs() : null;
            if (inactivityDeadline != null && inactivityDeadline > 0 && clock.millis() >= inactivityDeadline) {
                mutationProxy().abandonGameDueToInactivity(gameId);
                throw new BadRequestException("Game has expired due to inactivity.", "GAME_EXPIRED", "Game has expired due to inactivity");
            }

            Player player = game.findPlayerByName(playerName);
            boolean previousReady = player != null && player.getIsReadyForNextHand();

            game.setPlayerReadyForNextHand(playerName, false);
            if (previousReady) {
                resetGameInactivityUnderLock(gameId, game);
            }
            logger.debug("Player {} unmarked ready for next hand in game {}", playerName, gameId);

            gameStateService.broadcastGameState(gameId, game);
            return null;
        });
    }

    /**
     * Closes a ready gate only for the deadline that created this callback.
     *
     * @param gameId                 game whose ready gate may expire
     * @param expectedDeadlineEpochMs deadline captured when the timer was submitted
     */
    @DurableMutation(roomId = "#gameId")
    public void expireReadyCountdown(String gameId, Long expectedDeadlineEpochMs) {
        if (gameId == null || expectedDeadlineEpochMs == null) {
            return;
        }
        roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null) {
                cancelReadyTimeout(gameId);
                return;
            }

            switch (game.expireReadyCountdown(expectedDeadlineEpochMs)) {
                case IGNORE_STALE -> { }
                case WAITING_FOR_PLAYERS -> {
                    cancelReadyTimeout(gameId);
                    gameStateService.broadcastGameState(gameId, game);
                }
                case FORCED_READY -> completeReadyCountdownAndStartNextHand(gameId, game);
            }
        });
    }

    /**
     * Closes a ready gate only for the deadline that created this callback. Without
     * the comparison, a cancelled old timer could close a newly opened countdown.
     * Backwards-compatible overload.
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
        cancelReadyTimeout(gameId);
        cancelTurnTimeout(gameId);

        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
        game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
        startNewHand(gameId);
    }

    private void cancelReadyTimeout(String gameId) {
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.READY_TIMEOUT);
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
            if (game == null || isGameExpired(game)) {
                throw new ResourceNotFoundException("Game not found");
            }

            Room room = roomService.getRoom(gameId);
            if (room == null) throw new ResourceNotFoundException("Room not found");

            Long inactivityDeadline = room.getInactivityDeadlineEpochMs();
            if (inactivityDeadline != null && inactivityDeadline > 0 && clock.millis() >= inactivityDeadline) {
                mutationProxy().abandonGameDueToInactivity(gameId);
                throw new BadRequestException("Game has expired due to inactivity.", "GAME_EXPIRED", "Game has expired due to inactivity");
            }
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
        return roomCoordinator.coordinate(gameId, () -> {
            Game game = getGame(gameId);
            if (game == null || isGameExpired(game)) {
                throw new ResourceNotFoundException("Game not found");
            }

            if (!game.isGameOver()) {
                throw new ConflictException("Game has not finished");
            }

            Room room = roomService != null ? roomService.getRoom(gameId) : null;
            return terminalSettlement.resolveTerminalResultForParticipant(gameId, game, room, playerName, false);
        });
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
