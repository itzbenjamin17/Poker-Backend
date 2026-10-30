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
import com.pokergame.model.TournamentEligibility;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pokergame.util.MdcUtils;
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
import java.util.stream.Collectors;

/**
 * Service class responsible for game lifecycle management.
 * Handles game creation, starting new hands, and game termination.
 */
@Service
public class GameLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(GameLifecycleService.class);
    public static final long FINAL_SHOWDOWN_DISPLAY_DELAY_MS = 6000;
    private static final long AUTO_ADVANCE_STEP_DELAY_MS = 4000;

    @Value("${poker.game-end.display-delay-ms:20000}")
    private long gameEndDisplayDelayMs = 20000;

    @Value("${poker.round-end.display-delay-ms:0}")
    private long roundEndDisplayDelayMs;

    @Value("${poker.ready-countdown-ms:30000}")
    private long configuredReadyCountdownMs = 30000;

    private final RoomService roomService;

    private final HandEvaluatorService handEvaluator;

    private final GameStateService gameStateService;

    private final SimpMessagingTemplate messagingTemplate;


    private final TaskScheduler taskScheduler;
    private GameLifecycleService self;

    /**
     * Creates the lifecycle service with the scheduler that owns runtime timers.
     * Scheduled intent itself lives on {@link Game} so timers can be rebuilt after a
     * restart.
     *
     * @param roomService          authoritative room service
     * @param handEvaluatorService hand evaluator attached to new and restored games
     * @param gameStateService     client state publisher
     * @param messagingTemplate    direct messaging collaborator
     * @param taskScheduler        runtime timer scheduler
     */
    GameLifecycleService(RoomService roomService,
            HandEvaluatorService handEvaluatorService,
            GameStateService gameStateService,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.roomService = roomService;
        this.handEvaluator = handEvaluatorService;
        this.gameStateService = gameStateService;
        this.messagingTemplate = messagingTemplate;
        this.taskScheduler = taskScheduler;
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

    private final Map<String, Game> activeGames = new ConcurrentHashMap<>();
    private final Map<RuntimeTaskKey, RuntimeTask> runtimeTasks = new ConcurrentHashMap<>();

    private record RuntimeTaskKey(String gameId, ScheduledGameTask task) { }
    private record TaskIntent(String gameId, ScheduledGameTask task, long deadline, long handNumber, long generation) { }

    private static final class RuntimeTask {
        private volatile ScheduledFuture<?> future;
        private volatile boolean completed;
        void attach(ScheduledFuture<?> value) {
            future = value;
            if (completed && value != null) value.cancel(false);
        }
        void complete() { completed = true; }
        void cancel() { completed = true; ScheduledFuture<?> value = future; if (value != null) value.cancel(false); }
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

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            Game game = getGame(roomId);
            if (game == null || game.isGameOver()) return;
            synchronized (game) {
                departParticipantsUnderLock(roomId, playerNames, null, game);
            }
            return;
        }

        synchronized (room) {
            if (roomService.getRoom(roomId) != room) return;
            Game game = getGame(roomId);
            synchronized (game == null ? room : game) {
                departParticipantsUnderLock(roomId, playerNames, room, game);
            }
        }
    }

    /**
     * Reconciles host succession following participant departure.
     * Selects:
     * 1. Preserves current host if still present.
     * 2. First remaining connected contender in room join order.
     * 3. First remaining disconnected contender in room join order.
     * 4. First remaining member in room join order as spectator/terminal fallback.
     * 5. Null if no members remain.
     */
    private String determineSurvivingHost(Room room, Game game, Collection<String> departingMembers) {
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

    private void departParticipantsUnderLock(String roomId, Collection<String> playerNames, Room room, Game game) {
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
            List<String> membersToDetach = getTournamentContenderCount(game) == 0
                    ? room.getPlayers() : roomMembersToLeave;
            String survivingHost = determineSurvivingHost(room, game, membersToDetach);
            roomService.leavePlayersBatch(roomId, membersToDetach, survivingHost);
            if (roomService.getRoom(roomId) != null) gameStateService.broadcastGameState(roomId, game);
            cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
            return;
        }

        // Terminal game check: once terminal, roster, winner, balances, results are frozen.
        // Later departures update Room membership only.
        boolean settledTournamentOver = game.getLastSettlementResult() != null
                && game.getLastSettlementResult().settled() && game.isTournamentOver();
        if ((game.isGameOver() || settledTournamentOver) && !game.isForfeitPending()) {
            if (room != null) {
                room.setTerminal(true);
            }
            if (!game.isGameOver() || !game.isTerminalCarryOverSettled()) {
                handleGameEnd(roomId, false);
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
        int contendersBefore = (int) game.getPlayers().stream()
                .filter(p -> TournamentEligibility.isContender(game, p))
                .count();
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
                    cancelRuntimeTasks(roomId, false);
                    List<String> roomMembers = departing.stream().filter(room.getPlayers()::contains).toList();
                    if (!roomMembers.isEmpty() && roomService.getRoom(roomId) != null) {
                        String survivingHost = determineSurvivingHost(room, game, roomMembers);
                        roomService.leavePlayersBatch(roomId, roomMembers, survivingHost);
                    }
                    if (roomService.getRoom(roomId) != null) gameStateService.broadcastGameState(roomId, game);
                    cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
                } else {
                    handleGameEndWithForfeits(roomId, survivor, departingGamePlayers.stream().map(Player::getName).collect(Collectors.toSet()));
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
            cleanupAbandonedGameIfRoomEmpty(roomId, room, game);
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
            return;
        }

        if (game.getPlayers().isEmpty()) {
            activeGames.remove(roomId);
            roomService.destroyRoom(roomId);
            return;
        }

        if (game.getCurrentPhase() == com.pokergame.enums.GamePhase.SHOWDOWN || game.isReadyCountdownActive()) {
            gameStateService.broadcastGameState(roomId, game);
        } else {
            publishProgression(roomId, game, game.reconcileAfterPlayerRemoval());
        }

        if (game.isReadyCountdownActive() && game.areAllReadyEligiblePlayersReady()) {
            completeReadyCountdownAndStartNextHand(roomId, game);
        }
    }

    /**
     * Coordinates every authenticated or grace-expiry departure through the central
     * {@link #departParticipants(String, Collection)} operation.
     *
     * @param roomId room or game identifier
     * @param playerName player leaving
     */
    @DurableMutation(roomId = "#roomId")
    public void leavePlayer(String roomId, String playerName) {
        departParticipants(roomId, List.of(playerName));
    }

    private void cleanupAbandonedGameIfRoomEmpty(String gameId, Room room, Game game) {
        if (getTournamentContenderCount(game) != 0 || !room.getPlayers().isEmpty()) return;
        game.closeReadyCountdown();
        for (ScheduledGameTask task : ScheduledGameTask.values()) game.clearScheduledTask(task);
        cancelRuntimeTasks(gameId, false);
        activeGames.remove(gameId, game);
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
        Room room = roomService.getRoom(roomId);
        if (room == null) {
            logger.warn("Game creation failed: room not found for id {}", roomId);
            throw new ResourceNotFoundException("Room not found");
        }

        synchronized (room) {
            if (roomService.getRoom(roomId) != room) throw new ResourceNotFoundException("Room not found");
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
            RoomDataResponse startPayload = roomService.getRoomData(roomId);

            DurableTransactionContext.afterCommit(() -> messagingTemplate.convertAndSend("/room/" + roomId,
                    new ApiResponse<>(ResponseMessage.GAME_STARTED.getMessage(), startPayload)));
        }

        logger.info("Game created and started for room: {} with {} players", roomId, room.getPlayers().size());

        startNewHand(roomId);

        return roomId;
    }

    /**
     * Starts a new hand of poker for the specified game.
     * Resets game state, deals cards, posts blinds, and broadcasts initial state.
     *
     * @param gameId The unique identifier of the game
     */
    @DurableMutation(roomId = "#gameId")
    public void startNewHand(String gameId) {
        Game game = getGame(gameId);
        if (game == null) {
            logger.warn("Cannot start new hand - game {} not found", gameId);
            return;
        }
        logger.info("Starting new hand for game: {}", gameId);
        synchronized (game) {
            if (game.isForfeitPending()) return;
            if (game.isGameOver()) {
                if (!game.isTerminalCarryOverSettled()) handleGameEnd(gameId, false);
                return;
            }
            if (game.getHandNumber() > 0 && (game.getCurrentPhase() != com.pokergame.enums.GamePhase.SHOWDOWN
                    || game.getLastSettlementResult() == null || !game.getLastSettlementResult().settled())) {
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

            game.beginHand();

            game.dealHoleCards();
            game.postBlinds();
            publishProgression(gameId, game, game.reconcileAfterForcedBlinds());
        }

        synchronized (game) {
            logger.info("New hand started successfully for game: {} | Current player: {} | Phase: {} | Pot: {}",
                    gameId, game.getCurrentPlayer() == null ? "none" : game.getCurrentPlayer().getName(),
                    game.getCurrentPhase(), game.getPot());
        }
    }

    private void publishProgression(String gameId, Game game, TurnOutcome outcome) {
        switch (outcome.type()) {
            case NEXT_PLAYER, PHASE_ADVANCED -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                gameStateService.broadcastGameState(gameId, game);
            }
            case AUTO_ADVANCING -> {
                if (game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE) == null) {
                    scheduleAutoAdvance(gameId);
                }
                gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Auto-advancing...");
            }
            case SHOWDOWN -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                gameStateService.broadcastShowdownResults(gameId, game, outcome.winners(), outcome.winningsPerPlayer());
                if (game.isTournamentOver()) {
                    scheduleGameEnd(gameId, FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
                } else {
                    startReadyCountdown(gameId, configuredReadyCountdownMs);
                }
            }
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
        Game game = getGame(gameId);
        if (game == null) {
            return true;
        }

        synchronized (game) {
            if (game.isForfeitPending()) return true;
            if (game.isGameOver()) return true;
            if ((game.getLastSettlementResult() != null && game.getLastSettlementResult().settled())
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
        }
    }

    /**
     * Removes a player from an active game through the central
     * {@link #departParticipants(String, Collection)} operation.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the name of the player leaving the game
     */
    @DurableMutation(roomId = "#gameId")
    public void leaveGame(String gameId, String playerName) {
        departParticipants(gameId, List.of(playerName));
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
        Game game = getGame(gameId);
        if (game == null)
            return;

        Room room = roomService.getRoom(gameId);
        int maxPlayers = room == null ? 0 : room.getMaxPlayers();
        Player winner;
        com.pokergame.dto.response.GameEndResponse event;
        synchronized (game) {
            if (game.isForfeitPending() && preferredWinner == null) return;
            // Freeze the terminal winner and event once carry-over has been paid.
            // Membership can change during the result display window, so delayed
            // callbacks must not resolve a different winner from mutable players.
            if (game.isGameOver() && game.isTerminalCarryOverSettled()) return;
            // Forfeit participants leave the hand before settlement so they cannot
            // win a pot after abandoning the game. Their already committed chips
            // remain eligible dead money; uncommitted stacks are never transferred.
            for (Player player : game.getPlayers()) {
                if (forfeitedPlayers.contains(player.getName())) player.setIsOut();
            }
            // Resolve any in-flight hand and its carried remainder inside the same
            // game lock before choosing the final game winner or freezing its DTO.
            if (game.getLastSettlementResult() == null || !game.getLastSettlementResult().settled()) {
                game.conductShowdown();
            }
            // Find the winner by chip count, not seat order (activePlayers still
            // contains the busted player at this point since conductShowdown() does
            // not run cleanupAfterHand())
            winner = preferredWinner != null && game.getPlayers().contains(preferredWinner)
                    ? preferredWinner : game.resolveGameWinner();

            if (winner == null) {
                winner = game.getPlayers().stream()
                        .filter(p -> !p.getIsOut() && !p.getIsDisconnected())
                        .findFirst()
                        .orElse(game.getPlayers().stream().findFirst().orElse(null));
            }

            game.completeGameSettlement(winner);
            if (preferredWinner == null) {
                Player resolvedWinner = game.resolveGameWinner();
                if (resolvedWinner != null) winner = resolvedWinner;
            }

            game.markGameOver();
            game.closeReadyCountdown();
            game.invalidateProgressionTasks();
            cancelRuntimeTasks(gameId, true);
            if (game.getRetainedTerminalResult() != null) {
                event = game.getRetainedTerminalResult();
            } else {
                event = gameStateService.captureGameEnd(gameId, game, winner, isForfeit, maxPlayers);
                game.setRetainedTerminalResult(event);
            }
        }

        if (room != null) {
            room.setTerminal(true);
        }

        if (winner == null) {
            logger.warn("Cannot broadcast game end for {} - no winner could be determined", gameId);
            return;
        }

        if (event != null) gameStateService.sendGameEnd(event);

        // Wait a few seconds for players to see the result, then destroy the room and
        // game, on a different thread
        scheduleGameCleanup(gameId, gameEndDisplayDelayMs);
    }

    /**
     * Cleans up the finished game so it no longer uses resources
     *
     * @param gameId id of the game that is now done
     */

    @DurableMutation(roomId = "#gameId")
    public void performGameCleanup(String gameId) {
        cancelRuntimeTasks(gameId, false);
        activeGames.remove(gameId);
        roomService.destroyRoom(gameId);
        logger.info("Game {} and associated room cleaned up", gameId);
    }

    /**
     * Retrieves a game by its unique identifier.
     *
     * @param gameId the unique identifier of the game to retrieve
     * @return the Game object if found, null otherwise
     */
    public Game getGame(String gameId) {
        return activeGames.get(gameId);
    }

    /**
     * Registers an already rehydrated game without executing normal creation logic,
     * which would start a replacement hand.
     *
     * @param game exact game restored from a committed state image
     */
    public void restoreGame(Game game) {
        activeGames.put(game.getGameId(), game);
        if (game.isGameOver()) {
            Room room = roomService.getRoom(game.getGameId());
            if (room != null) {
                room.setTerminal(true);
            }
        }
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
        Game game = getGame(gameId);
        if (game == null) {
            return;
        }
        MdcUtils.runWithRoomId(gameId, () -> {
            List<TaskIntent> intents;
            boolean normalized = false;
            int rebuiltCount;
            synchronized (game) {
                if (game.isReadyCountdownActive() && game.getReadyCountdownDeadlineEpochMs() == null) {
                    if (game.getReadyEligibleCount() < 2) {
                        game.waitForReadyPlayers();
                        game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
                    } else {
                        long freshDeadline = System.currentTimeMillis() + configuredReadyCountdownMs;
                        game.openReadyCountdown(freshDeadline);
                        game.scheduleTask(ScheduledGameTask.READY_TIMEOUT, freshDeadline);
                    }
                    normalized = true;
                }
                if (game.isReadyCountdownActive()) {
                    Long deadline = game.getReadyCountdownDeadlineEpochMs();
                    if (deadline != null && !Objects.equals(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT), deadline))
                        game.scheduleTask(ScheduledGameTask.READY_TIMEOUT, deadline);
                }
                Map<ScheduledGameTask, Long> deadlines = game.getScheduledTaskDeadlinesSnapshot();
                intents = deadlines.entrySet().stream()
                        .map(entry -> taskIntent(gameId, game, entry.getKey(), entry.getValue())).toList();
                rebuiltCount = intents.size();
            }
            DurableTransactionContext.afterCommit(() -> intents.forEach(this::scheduleRuntimeTask));
            if (normalized) gameStateService.broadcastGameState(gameId, game);
            logger.info("Resumed {} recovered timers for game {}", rebuiltCount, gameId);
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
    public void scheduleNewHand(String gameId, long delayMs) {
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
    public void scheduleReadyCountdownOpen(String gameId, long delayMs) {
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
    public void scheduleGameCleanup(String gameId, long delayMs) {
        persistScheduledTask(gameId, ScheduledGameTask.CLEANUP, System.currentTimeMillis() + Math.max(0, delayMs));
    }

    /**
     * Persists the final game-end deadline before notifying clients of victory,
     * allowing a review window of the final board before the game-over overlay.
     *
     * @param gameId  game whose tournament has concluded
     * @param delayMs client review delay before game-over broadcast
     */
    @DurableMutation(roomId = "#gameId")
    public void scheduleGameEnd(String gameId, long delayMs) {
        persistScheduledTask(gameId, ScheduledGameTask.GAME_END, System.currentTimeMillis() + Math.max(0, delayMs));
    }

    /**
     * Persists the first all-in progression deadline before clients observe the
     * auto-advance state.
     *
     * @param gameId game requiring automatic board progression
     */
    @DurableMutation(roomId = "#gameId")
    public void scheduleAutoAdvance(String gameId) {
        persistScheduledTask(gameId, ScheduledGameTask.AUTO_ADVANCE,
                System.currentTimeMillis() + AUTO_ADVANCE_STEP_DELAY_MS);
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
        Game game = getGame(gameId);
        if (game == null) return;
        long generation;
        long handNumber;
        synchronized (game) {
            generation = game.getScheduledTaskGeneration(task);
            handNumber = game.getHandNumber();
        }
        executeScheduledTask(gameId, task, expectedDeadline, handNumber, generation);
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
        Game game = getGame(gameId);
        if (game == null) {
            logger.debug("Cannot execute task {}: game not found for gameId={}", task, gameId);
            return;
        }
        boolean cleanupRequested = false;
        synchronized (game) {
            if (game.isForfeitPending()) return;
            if (game.getHandNumber() != expectedHandNumber || game.getScheduledTaskGeneration(task) != expectedGeneration) return;
            if (game.isGameOver() && task != ScheduledGameTask.CLEANUP) {
                game.clearScheduledTask(task);
                cancelRuntimeTask(gameId, task);
                return;
            }
            Long currentDeadline = game.getScheduledTaskDeadline(task);
            if (currentDeadline == null || currentDeadline != expectedDeadline) {
                logger.debug("Ignoring stale task {} for game {} expected={} current={}", task, gameId, expectedDeadline, currentDeadline);
                return;
            }
            if (task != ScheduledGameTask.CLEANUP) game.clearScheduledTask(task);
            logger.debug("Executing task {} for game {}", task, gameId);
            switch (task) {
                case NEW_HAND -> startNewHand(gameId);
                case READY_OPEN -> startReadyCountdown(gameId, configuredReadyCountdownMs);
                case READY_TIMEOUT -> handleReadyCountdownTimeout(gameId, expectedDeadline);
                case CLEANUP -> cleanupRequested = true;
                case GAME_END -> handleGameEnd(gameId, false);
                case AUTO_ADVANCE -> {
                    if (game.getCurrentPhase() == com.pokergame.enums.GamePhase.SHOWDOWN) {
                        game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                        return;
                    }
                    boolean complete = advanceAutoAdvanceStep(gameId);
                    if (complete) {
                        if (game.isTournamentOver()) {
                            scheduleGameEnd(gameId, FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
                        } else if (roundEndDisplayDelayMs <= 0) {
                            startReadyCountdown(gameId, configuredReadyCountdownMs);
                        } else {
                            persistScheduledTask(gameId, ScheduledGameTask.READY_OPEN,
                                    System.currentTimeMillis() + roundEndDisplayDelayMs);
                        }
                    } else {
                        persistScheduledTask(gameId, ScheduledGameTask.AUTO_ADVANCE,
                                System.currentTimeMillis() + AUTO_ADVANCE_STEP_DELAY_MS);
                    }
                }
            }
        }
        if (cleanupRequested) {
            DurableTransactionContext.afterCommit(() -> mutationProxy().performGameCleanup(gameId));
        }
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
        Game game = getGame(gameId);
        if (game == null) {
            return;
        }
        TaskIntent intent;
        synchronized (game) {
            if (game.isForfeitPending()) return;
            game.scheduleTask(task, deadline);
            intent = taskIntent(gameId, game, task, deadline);
        }
        DurableTransactionContext.afterCommit(() -> scheduleRuntimeTask(intent));
    }

    private TaskIntent taskIntent(String gameId, Game game, ScheduledGameTask task, long deadline) {
        return new TaskIntent(gameId, task, deadline, game.getHandNumber(), game.getScheduledTaskGeneration(task));
    }

    private void scheduleRuntimeTask(TaskIntent intent) {
        String gameId = intent.gameId();
        ScheduledGameTask task = intent.task();
        long deadline = intent.deadline();
        Game game = getGame(gameId);
        if (game == null) return;
        long delay = Math.max(0, deadline - System.currentTimeMillis());
        logger.debug("Scheduling runtime task {} for game {} with delayMs={} deadline={}", task, gameId, delay, deadline);
        RuntimeTaskKey key = new RuntimeTaskKey(gameId, task);
        RuntimeTask registration = new RuntimeTask();
        RuntimeTask replaced;
        synchronized (game) {
            if (game.getHandNumber() != intent.handNumber()
                    || game.getScheduledTaskGeneration(task) != intent.generation()
                    || !Objects.equals(game.getScheduledTaskDeadline(task), deadline)) return;
            replaced = runtimeTasks.put(key, registration);
        }
        if (replaced != null) replaced.cancel();
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                try {
                    mutationProxy().executeScheduledTask(gameId, task, deadline, intent.handNumber(), intent.generation());
                } catch (Exception e) {
                    logger.error("Scheduled task {} failed for game {}", task, gameId, e);
                } finally {
                    registration.complete();
                    runtimeTasks.remove(key, registration);
                }
            }, Instant.now().plusMillis(delay));
            registration.attach(future);
            if (future == null) {
                registration.complete();
                runtimeTasks.remove(key, registration);
            }
        } catch (RuntimeException error) {
            registration.complete();
            runtimeTasks.remove(key, registration);
            throw error;
        }
    }

    private void cancelRuntimeTask(String gameId, ScheduledGameTask task) {
        RuntimeTask registration = runtimeTasks.remove(new RuntimeTaskKey(gameId, task));
        if (registration != null) registration.cancel();
    }

    public void cancelRuntimeTasks(String gameId, boolean keepCleanup) {
        runtimeTasks.forEach((key, registration) -> {
            if (key.gameId().equals(gameId) && (!keepCleanup || key.task() != ScheduledGameTask.CLEANUP)
                    && runtimeTasks.remove(key, registration)) registration.cancel();
        });
    }

    public int getRuntimeTaskCount(String gameId) {
        return (int) runtimeTasks.keySet().stream().filter(k -> k.gameId().equals(gameId)).count();
    }

    /**
     * Checks if a game has reached its scheduled cleanup deadline.
     *
     * @param game game to check
     * @return true if cleanup deadline is set and current time is at or past it
     */
    public boolean isGameExpired(Game game) {
        if (game == null) return true;
        Long cleanupDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP);
        return cleanupDeadline != null && System.currentTimeMillis() >= cleanupDeadline;
    }

    /**
     * Checks if a game with the specified ID exists in the active games and has not expired.
     *
     * @param gameId the unique identifier of the game to check
     * @return true if the game exists in active games and is not expired, false otherwise
     */
    public boolean gameExists(String gameId) {
        Game game = activeGames.get(gameId);
        return game != null && !isGameExpired(game);
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
        Game game = getGame(gameId);
        if (game == null) {
            return false;
        }

        synchronized (game) {
            List<Player> players = game.getPlayers();
            if (players == null) {
                return false;
            }
            return players.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(player -> playerName.equals(player.getName()) && !player.getIsOut());
        }
    }

    /**
     * Checks whether a player currently exists in an active game and matches the expected membership ID.
     *
     * @param gameId       the unique identifier of the game
     * @param playerName   the player name to check
     * @param membershipId the expected membership identity from token
     * @return true if player exists and membership matches, false otherwise
     */
    public boolean playerExistsInGame(String gameId, String playerName, String membershipId) {
        if (!playerExistsInGame(gameId, playerName)) {
            return false;
        }
        if (membershipId == null || membershipId.isBlank()) {
            return true;
        }
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (room == null) {
            return true;
        }
        return Objects.equals(membershipId, room.getMembershipId(playerName));
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
            return;
        }
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (room != null) {
            String activeMembershipId = room.getMembershipId(playerName);
            if (activeMembershipId == null || !activeMembershipId.equals(membershipId)) {
                logger.warn("Rejecting request: stale or mismatched membership for player {} in game {} (token: {}, active: {})",
                        playerName, gameId, membershipId, activeMembershipId);
                throw new AccessDeniedException("Token membership is stale or invalid.");
            }
        }
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
        Game game = getGame(gameId);
        if (game == null) {
            logger.debug("Cannot mark player disconnected: game not found for gameId={}", gameId);
            return;
        }

        synchronized (game) {
            if (game.isForfeitPending()) return;
            if (game.isGameOver()) return;
            Player player = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElse(null);

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
                completeReadyCountdownAndStartNextHand(gameId, game);
            }
        }
    }

    /**
     * Clears disconnect flag for a player that reconnects within grace period.
     *
     * @param gameId     the game identifier
     * @param playerName the reconnecting player name
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReconnected(String gameId, String playerName) {
        Room room = roomService.getRoom(gameId);
        if (room != null) {
            synchronized (room) {
                if (roomService.getRoom(gameId) != room) return;
                Game game = getGame(gameId);
                if (game != null) {
                    synchronized (game) {
                        reconcilePlayerReconnectedUnderLock(gameId, playerName, room, game);
                    }
                } else {
                    reconcilePlayerReconnectedUnderLock(gameId, playerName, room, null);
                }
            }
        } else {
            Game game = getGame(gameId);
            if (game != null) {
                synchronized (game) {
                    reconcilePlayerReconnectedUnderLock(gameId, playerName, null, game);
                }
            }
        }
    }

    private void reconcilePlayerReconnectedUnderLock(String gameId, String playerName, Room room, Game game) {
        if (game != null) {
            if (game.isGameOver()) {
                logger.debug("Skipping reconnect for player {} because game {} is already over", playerName, gameId);
                return;
            }
            Player player = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElse(null);

            if (player != null && player.getIsDisconnected()) {
                player.setDisconnected(false);
                player.setDisconnectDeadlineEpochMs(null);
                logger.info("Player {} marked reconnected in game {}", playerName, gameId);

                if (game.isForfeitPending() && getTournamentContenderCount(game) == 1
                        && isTournamentContender(game, player)) {
                    game.setForfeitPending(false);
                    handleGameEndWithForfeits(gameId, player, Set.of());
                    if (room != null && room.hasPlayer(playerName)) {
                        room.setPlayerConnected(playerName, true);
                        room.setAbsenceDeadline(playerName, null);
                    }
                    return;
                }

                if (room != null) {
                    gameStateService.broadcastGameState(gameId, game);
                }
                if (game.getReadyGateState() == com.pokergame.enums.ReadyGateState.WAITING_FOR_PLAYERS
                        && game.getReadyEligibleCount() >= 2) {
                    startReadyCountdown(gameId, configuredReadyCountdownMs);
                }
            }
        }

        if (room != null && room.hasPlayer(playerName)) {
            room.setPlayerConnected(playerName, true);
            room.setAbsenceDeadline(playerName, null);
        }
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
        Game game = getGame(gameId);
        if (game == null) {
            return;
        }

        long deadlineEpochMs;
        TaskIntent readyIntent;
        synchronized (game) {
            if (game.isForfeitPending()) return;
            if (game.isGameOver()) return;
            if (game.isTournamentOver()) {
                DurableTransactionContext.afterCommit(() -> mutationProxy().handleGameEnd(gameId, false));
                return;
            }
            if (game.getCurrentPhase() != com.pokergame.enums.GamePhase.SHOWDOWN) {
                logger.debug("Skipping ready countdown for game {}: phase is {} (not SHOWDOWN)", gameId, game.getCurrentPhase());
                return;
            }

            if (game.getReadyEligibleCount() < 2) {
                game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
                cancelReadyTimeout(gameId);
                game.waitForReadyPlayers();
                gameStateService.broadcastGameState(gameId, game);
                return;
            }

            deadlineEpochMs = System.currentTimeMillis() + countdownMs;
            game.openReadyCountdown(deadlineEpochMs);
            game.scheduleTask(ScheduledGameTask.READY_TIMEOUT, deadlineEpochMs);
            readyIntent = taskIntent(gameId, game, ScheduledGameTask.READY_TIMEOUT, deadlineEpochMs);
            logger.info("Opened ready countdown for game {} with deadlineEpochMs={}", gameId, deadlineEpochMs);
            gameStateService.broadcastGameState(gameId, game);

            if (game.areAllReadyEligiblePlayersReady()) {
                completeReadyCountdownAndStartNextHand(gameId, game);
                return;
            }
        }

        DurableTransactionContext.afterCommit(() -> scheduleRuntimeTask(readyIntent));
    }

    /**
     * Marks a player as ready during an active ready countdown gate.
     *
     * @param gameId     game identifier
     * @param playerName player name from principal
     */
    @DurableMutation(roomId = "#gameId")
    public void markPlayerReadyForNextHand(String gameId, String playerName) {
        Game game = getGame(gameId);
        if (game == null) {
            throw new ResourceNotFoundException("Game not found");
        }

        synchronized (game) {
            if (!game.isReadyCountdownActive()) {
                throw new UnauthorisedActionException("Ready countdown is not active.");
            }

            Player player = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElseThrow(() -> new UnauthorisedActionException("You are no longer part of this game."));

            if (player.getIsOut()) {
                throw new UnauthorisedActionException("You are out of this game.");
            }

            if (player.getIsDisconnected()) {
                throw new UnauthorisedActionException("Reconnect before marking ready.");
            }

            if (player.getChips() <= 0) throw new UnauthorisedActionException("Players without chips cannot mark ready.");

            player.setReadyForNextHand(true);
            logger.debug("Player {} marked ready for next hand in game {}", playerName, gameId);

            if (game.areAllReadyEligiblePlayersReady()) {
                completeReadyCountdownAndStartNextHand(gameId, game);
            } else {
                gameStateService.broadcastGameState(gameId, game);
            }
        }
    }

    /**
     * Unmarks a player as ready during an active ready countdown gate.
     *
     * @param gameId     game identifier
     * @param playerName player name from principal
     */
    @DurableMutation(roomId = "#gameId")
    public void unmarkPlayerReadyForNextHand(String gameId, String playerName) {
        Game game = getGame(gameId);
        if (game == null) {
            throw new ResourceNotFoundException("Game not found");
        }

        synchronized (game) {
            if (!game.isReadyCountdownActive()) {
                throw new UnauthorisedActionException("Ready countdown is not active.");
            }

            Player player = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElseThrow(() -> new UnauthorisedActionException("You are no longer part of this game."));

            if (player.getIsOut()) {
                throw new UnauthorisedActionException("You are out of this game.");
            }

            if (player.getIsDisconnected() || player.getChips() <= 0) {
                throw new UnauthorisedActionException("You are not eligible to change readiness.");
            }

            player.setReadyForNextHand(false);
            logger.debug("Player {} unmarked ready for next hand in game {}", playerName, gameId);

            gameStateService.broadcastGameState(gameId, game);
        }
    }

    /**
     * Closes a ready gate only for the deadline that created this callback. Without
     * the comparison, a cancelled old timer could close a newly opened countdown.
     *
     * @param gameId                 game whose ready gate may expire
     * @param expectedDeadlineEpochMs deadline captured when the timer was submitted
     */
    @DurableMutation(roomId = "#gameId")
    public void handleReadyCountdownTimeout(String gameId, long expectedDeadlineEpochMs) {
        Game game = getGame(gameId);
        if (game == null) {
            cancelReadyTimeout(gameId);
            return;
        }

        synchronized (game) {
            if (game.isForfeitPending()) return;
            if (!game.isReadyCountdownActive()
                    || !Objects.equals(game.getReadyCountdownDeadlineEpochMs(), expectedDeadlineEpochMs)) {
                logger.debug("Ignoring stale ready countdown timeout for game {} expected={} current={}",
                        gameId, expectedDeadlineEpochMs, game.getReadyCountdownDeadlineEpochMs());
                return;
            }

            logger.info("Ready countdown timed out for game {}: forcing ready for eligible players", gameId);
            if (game.getReadyEligibleCount() < 2) {
                game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
                cancelReadyTimeout(gameId);
                game.waitForReadyPlayers();
                gameStateService.broadcastGameState(gameId, game);
                return;
            }
            game.forceReadyForEligiblePlayers();
            completeReadyCountdownAndStartNextHand(gameId, game);
        }
    }

    /**
     * Cancels the process-local timeout before closing durable ready state and
     * starting the hand, preventing a racing timeout from advancing twice.
     *
     * @param gameId game leaving the ready gate
     * @param game   authoritative game state
     */
    private void completeReadyCountdownAndStartNextHand(String gameId, Game game) {
        cancelReadyTimeout(gameId);

        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
        startNewHand(gameId);
    }

    private void cancelReadyTimeout(String gameId) {
        cancelRuntimeTask(gameId, ScheduledGameTask.READY_TIMEOUT);
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
        Game game = getGame(gameId);
        if (game == null || isGameExpired(game)) {
            throw new ResourceNotFoundException("Game not found");
        }

        Room room = roomService.getRoom(gameId);
        if (room == null) throw new ResourceNotFoundException("Room not found");
        synchronized (room) {
            synchronized (game) {
                if (game.isGameOver()) {
                    GameEndResponse retained = game.getRetainedTerminalResult();
                    if (retained == null) {
                        Player winner = game.resolveGameWinner();
                        if (winner == null && !game.getPlayers().isEmpty()) {
                            winner = game.getPlayers().stream()
                                    .filter(p -> !p.getIsOut() && !p.getIsDisconnected())
                                    .findFirst()
                                    .orElse(game.getPlayers().get(0));
                        }
                        if (winner != null && gameStateService != null) {
                            retained = gameStateService.captureGameEnd(gameId, game, winner, true, room.getMaxPlayers());
                            game.setRetainedTerminalResult(retained);
                        }
                    }
                    if (retained == null) {
                        throw new ResourceNotFoundException("Game not found");
                    }
                    boolean authorized = (retained.finalState() != null
                            && retained.finalState().players() != null
                            && retained.finalState().players().stream().anyMatch(p -> p.name().equals(playerName)))
                            || (game.getPlayers() != null && game.getPlayers().stream().anyMatch(p -> p.getName().equals(playerName)));
                    if (!authorized) {
                        throw new UnauthorisedActionException("You are not part of this game's terminal result.");
                    }
                    return retained;
                }

                if (!canPlayerClaimWin(game, playerName)) throw new UnauthorisedActionException("Cannot claim win right now.");
                List<String> opponents = game.getPlayers().stream()
                        .filter(player -> !player.getName().equals(playerName))
                        .filter(player -> isTournamentContender(game, player) && player.getIsDisconnected())
                        .map(Player::getName)
                        .toList();
                departParticipantsUnderLock(gameId, opponents, room, game);
                GameEndResponse retained = game.getRetainedTerminalResult();
                if (retained == null && game.isGameOver()) {
                    Player claimant = game.getPlayers().stream()
                            .filter(p -> p.getName().equals(playerName))
                            .findFirst()
                            .orElse(null);
                    if (claimant != null && gameStateService != null) {
                        retained = gameStateService.captureGameEnd(gameId, game, claimant, true, room.getMaxPlayers());
                        game.setRetainedTerminalResult(retained);
                    }
                }
                return retained;
            }
        }
    }

    /**
     * Retrieves the frozen terminal result for post-game review.
     *
     * @param gameId     game identifier
     * @param playerName requesting player name
     * @return frozen terminal result
     */
    public GameEndResponse getGameReview(String gameId, String playerName) {
        Game game = getGame(gameId);
        if (game == null || isGameExpired(game)) {
            throw new ResourceNotFoundException("Game not found");
        }

        synchronized (game) {
            if (!game.isGameOver()) {
                throw new ConflictException("Game has not finished");
            }

            GameEndResponse retained = game.getRetainedTerminalResult();
            if (retained == null) {
                Player winner = game.resolveGameWinner();
                if (winner == null && !game.getPlayers().isEmpty()) {
                    winner = game.getPlayers().stream()
                            .filter(p -> !p.getIsOut() && !p.getIsDisconnected())
                            .findFirst()
                            .orElse(game.getPlayers().get(0));
                }
                if (winner != null && gameStateService != null) {
                    Room room = roomService != null ? roomService.getRoom(gameId) : null;
                    int maxPlayers = room != null ? room.getMaxPlayers() : game.getPlayers().size();
                    retained = gameStateService.captureGameEnd(gameId, game, winner, false, maxPlayers);
                    game.setRetainedTerminalResult(retained);
                }
            }
            if (retained == null) {
                throw new ResourceNotFoundException("Game not found");
            }

            boolean authorized = (retained.finalState() != null
                    && retained.finalState().players() != null
                    && retained.finalState().players().stream().anyMatch(p -> p.name().equals(playerName)))
                    || (game.getPlayers() != null && game.getPlayers().stream().anyMatch(p -> p.getName().equals(playerName)));

            if (!authorized) {
                throw new UnauthorisedActionException("You are not part of this game's terminal result.");
            }

            return retained;
        }
    }

    /**
     * Allows a claim only when one connected participant remains against at least one
     * disconnected opponent. This prevents ordinary heads-up games or disconnected
     * claimants from bypassing gameplay.
     *
     * @param game       game being evaluated
     * @param playerName prospective claimant
     * @return whether an immediate disconnect win is legal
     */
    private boolean canPlayerClaimWin(Game game, String playerName) {
        return TournamentEligibility.canClaim(game) && game.getPlayers().stream()
                .anyMatch(player -> player.getName().equals(playerName) && TournamentEligibility.isContender(game, player)
                        && !player.getIsDisconnected());
    }

    private boolean isTournamentContender(Game game, Player player) {
        return TournamentEligibility.isContender(game, player);
    }

    private int getTournamentContenderCount(Game game) {
        return (int) game.getPlayers().stream().filter(player -> isTournamentContender(game, player)).count();
    }

    private int getConnectedContenderCount(Game game) {
        return (int) game.getPlayers().stream()
                .filter(player -> TournamentEligibility.isContender(game, player) && !player.getIsDisconnected()).count();
    }

    private boolean isContender(Game game, String playerName) {
        return game.getPlayers().stream().anyMatch(player -> player.getName().equals(playerName)
                && TournamentEligibility.isContender(game, player));
    }

    /**
     * Removes a grace-expired player from room and game under one room transaction,
     * avoiding a recoverable snapshot where only one registry was updated.
     *
     * @param gameId    room/game identity
     * @param playerName player whose reconnect grace expired
     */
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
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            return;
        }

        synchronized (room) {
            if (!room.hasPlayer(playerName)) {
                return;
            }
            String currentMembershipId = room.getMembershipId(playerName);
            if (!Objects.equals(expectedMembershipId, currentMembershipId)) {
                return;
            }

            Game game = getGame(gameId);
            if (game == null) {
                // Lobby case: guard the lease and membership identity
                if (room.isPlayerConnected(playerName)) {
                    return;
                }
                Long absenceDeadline = room.getAbsenceDeadline(playerName);
                if (absenceDeadline == null || absenceDeadline != expectedDeadlineEpochMs) {
                    return;
                }
                if (System.currentTimeMillis() < expectedDeadlineEpochMs) {
                    return;
                }
                departParticipantsUnderLock(gameId, List.of(playerName), room, null);
                return;
            }

            synchronized (game) {
                boolean isTerminal = (game.isGameOver() || (game.getLastSettlementResult() != null
                        && game.getLastSettlementResult().settled() && game.isTournamentOver()))
                        && !game.isForfeitPending();

                if (isTerminal) {
                    Player gamePlayer = game.getPlayers().stream()
                            .filter(p -> p.getName().equals(playerName))
                            .findFirst()
                            .orElse(null);

                    Long currentDeadline = (gamePlayer != null && gamePlayer.getDisconnectDeadlineEpochMs() != null)
                            ? gamePlayer.getDisconnectDeadlineEpochMs()
                            : room.getAbsenceDeadline(playerName);

                    if (currentDeadline == null || currentDeadline != expectedDeadlineEpochMs) {
                        return;
                    }
                    if (System.currentTimeMillis() < expectedDeadlineEpochMs) {
                        return;
                    }
                    if (gamePlayer != null && !gamePlayer.getIsDisconnected()) {
                        return;
                    }
                    if (gamePlayer == null && room.isPlayerConnected(playerName)) {
                        return;
                    }

                    departParticipantsUnderLock(gameId, List.of(playerName), room, game);
                    return;
                }

                // Active game
                Player gamePlayer = game.getPlayers().stream()
                        .filter(p -> p.getName().equals(playerName))
                        .findFirst()
                        .orElse(null);

                if (gamePlayer == null) {
                    if (room.isPlayerConnected(playerName)) return;
                    Long absenceDeadline = room.getAbsenceDeadline(playerName);
                    if (absenceDeadline == null || absenceDeadline != expectedDeadlineEpochMs) return;
                    if (System.currentTimeMillis() < expectedDeadlineEpochMs) return;
                    departParticipantsUnderLock(gameId, List.of(playerName), room, game);
                    return;
                }

                if (!gamePlayer.getIsDisconnected()) {
                    return;
                }
                Long currentDeadline = gamePlayer.getDisconnectDeadlineEpochMs();
                if (currentDeadline == null || currentDeadline != expectedDeadlineEpochMs) {
                    return;
                }
                if (System.currentTimeMillis() < expectedDeadlineEpochMs) {
                    return;
                }

                departParticipantsUnderLock(gameId, List.of(playerName), room, game);
            }
        }
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
