package com.pokergame.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.TurnOutcome;
import com.pokergame.model.TurnOutcome.TurnOutcomeType;
import com.pokergame.testutil.TestRoomLocks;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the PlayerActionService class.
 *
 * <p>The game registry and the games are real and every call runs under the room lock, as the
 * durable-mutation boundary does in production; only the collaborators that follow the action
 * (timers, readiness, broadcasts) are mocked. Progression is asserted through the
 * {@link TurnOutcome} handed to {@link GameReadinessCoordinator}.</p>
 */
@Tag("unit")
@DisplayName("Player action service")
@ExtendWith(MockitoExtension.class)
class PlayerActionServiceTest {

    @Mock
    private GameCompletionHandler completionHandler;

    @Mock
    private GameTaskScheduler taskScheduler;

    @Mock
    private GameReadinessCoordinator readinessCoordinator;

    @Mock
    private GameStateService gameStateService;
    private RoomCoordinator roomCoordinator;
    private ActiveGameRegistry activeGames;
    private PlayerActionService playerActionService;

    private Game testGame;
    private List<Player> testPlayers;
    private static final String GAME_ID = "test-game-id";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        playerActionService = new PlayerActionService(
                activeGames, completionHandler, taskScheduler, readinessCoordinator, gameStateService, FIXED_CLOCK);
        testPlayers = new ArrayList<>();
        testPlayers.add(player("Player1", 1000));
        testPlayers.add(player("Player2", 1000));
        testPlayers.add(player("Player3", 1000));

        testGame = dealtGame(GAME_ID, testPlayers);
    }

    // ==================== Fixtures ====================

    private static Player player(String name, int chips) {
        return new Player(name, UUID.randomUUID().toString(), chips);
    }

    /** Builds a game with blinds 5/10, deals and posts blinds, and registers it under its own room lock. */
    private Game dealtGame(String gameId, List<Player> players) {
        Game game = new Game(gameId, players, 6, 5, 10);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();
        register(game);
        return game;
    }

    private void register(Game game) {
        TestRoomLocks.runWithRoomLock(roomCoordinator, game.getGameId(), () -> activeGames.replace(game));
    }

    /** Runs the service under the room lock, as the durable-mutation boundary does in production. */
    private void process(String gameId, PlayerActionRequest request, String playerName) {
        TestRoomLocks.runWithRoomLock(roomCoordinator, gameId,
                () -> playerActionService.processPlayerAction(gameId, request, playerName));
    }

    private void process(PlayerAction action, int amount, String playerName) {
        process(GAME_ID, new PlayerActionRequest(action, amount), playerName);
    }

    private void act(Game game, PlayerAction action) {
        process(game.getGameId(), new PlayerActionRequest(action, 0), game.getCurrentPlayer().getName());
    }

    /** Returns the outcome types handed to the readiness coordinator for {@code game}, in call order. */
    private List<TurnOutcomeType> outcomeTypes(Game game) {
        ArgumentCaptor<TurnOutcome> captor = ArgumentCaptor.forClass(TurnOutcome.class);
        verify(readinessCoordinator, atLeastOnce())
                .applyTurnOutcomeAndOpenReadyGateIfNeeded(same(game), captor.capture(), eq(true));
        return captor.getAllValues().stream().map(TurnOutcome::type).toList();
    }

    private int totalChipsIncludingPot(Game game) {
        return game.getPlayers().stream().mapToInt(Player::getChips).sum() + game.getPot();
    }

    // ==================== processPlayerAction - Rejections ====================

    /**
     * Protects the contract that an action for an unknown game is rejected as not found.
     */
    @Test
    void processPlayerAction_WhenGameNotFound_ShouldThrowException() {
        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> process("missing-game", new PlayerActionRequest(PlayerAction.CALL, 0), "Player1"));

        assertTrue(exception.getMessage().contains("Game not found"));
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("an action in a finished game is rejected with GAME_FINISHED and changes nothing")
    void processPlayerAction_WhenGameOver_ShouldThrowBadRequestException() {
        testGame.markGameOver();
        String playerName = testGame.getCurrentPlayer().getName();
        int chipsBefore = totalChipsIncludingPot(testGame);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> process(PlayerAction.FOLD, 0, playerName));

        assertEquals("This game has finished.", exception.getMessage());
        assertEquals("GAME_FINISHED", exception.actionErrorCode());
        assertEquals(chipsBefore, totalChipsIncludingPot(testGame));
        assertFalse(testGame.getCurrentPlayer().hasFolded());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("processPlayerAction should throw BadRequestException when game is in SHOWDOWN phase")
    void rejectsActionDuringShowdownPhase() {
        testGame.dealFlop();
        testGame.dealTurn();
        testGame.dealRiver();
        testGame.conductShowdown();
        assertEquals(GamePhase.SHOWDOWN, testGame.getCurrentPhase());

        int initialChips = testPlayers.stream().mapToInt(Player::getChips).sum();
        String playerName = testGame.getCurrentPlayer().getName();

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> process(PlayerAction.CHECK, 0, playerName));

        assertEquals("This hand is already complete.", exception.getMessage());
        assertEquals("HAND_COMPLETE", exception.actionErrorCode());
        int finalChips = testPlayers.stream().mapToInt(Player::getChips).sum();
        assertEquals(initialChips, finalChips);
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("an action out of turn is rejected with NOT_YOUR_TURN and changes nothing")
    void processPlayerAction_WhenNotPlayerTurn_ShouldThrowSecurityException() {
        String currentName = testGame.getCurrentPlayer().getName();
        String otherName = testPlayers.stream().map(Player::getName).filter(n -> !n.equals(currentName))
                .findFirst().orElseThrow();
        int potBefore = testGame.getPot();

        UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class,
                () -> process(PlayerAction.CALL, 0, otherName));

        assertEquals("NOT_YOUR_TURN", exception.actionErrorCode());
        assertTrue(exception.getMessage().contains("not your turn"));
        assertTrue(exception.getMessage().contains(currentName));
        assertEquals(potBefore, testGame.getPot());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("an action by a disconnected player is rejected with DISCONNECTED and changes nothing")
    void processPlayerAction_WhenCurrentPlayerDisconnected_ShouldThrowSecurityException() {
        Player currentPlayer = testGame.getCurrentPlayer();
        currentPlayer.setDisconnected(true);
        int potBefore = testGame.getPot();

        UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class,
                () -> process(PlayerAction.CALL, 0, currentPlayer.getName()));

        assertEquals("DISCONNECTED", exception.actionErrorCode());
        assertTrue(exception.getMessage().toLowerCase().contains("reconnect"));
        assertEquals(potBefore, testGame.getPot());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("an action is rejected while the game is auto-advancing")
    void processPlayerAction_WhenAutoAdvancing_ShouldThrowBadRequestException() {
        testGame.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, FIXED_INSTANT.toEpochMilli() + 5_000);
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBefore = currentPlayer.getChips();

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> process(PlayerAction.CALL, 0, currentPlayer.getName()));

        assertEquals("Actions are not allowed while the game is auto-advancing.", exception.getMessage());
        assertEquals("HAND_ADVANCING", exception.actionErrorCode());
        assertEquals(chipsBefore, currentPlayer.getChips());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("a request with no action is rejected")
    void processPlayerAction_WithNullAction_ShouldThrowBadRequestException() {
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBefore = currentPlayer.getChips();

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> process(GAME_ID, new PlayerActionRequest(null, 0), currentPlayer.getName()));

        assertEquals("No action specified. Please select an action", exception.getMessage());
        assertEquals(chipsBefore, currentPlayer.getChips());
        assertEquals(currentPlayer, testGame.getCurrentPlayer());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("a null request is rejected")
    void processPlayerAction_WithNullRequest_ShouldThrowNullPointerException() {
        String playerName = testGame.getCurrentPlayer().getName();

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> process(GAME_ID, null, playerName));

        assertEquals("actionRequest must not be null", exception.getMessage());
        verifyNoInteractions(completionHandler, readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("an action attempted after game inactivity expiry throws GAME_EXPIRED without abandoning")
    void processPlayerAction_WhenGameInactivityExpired_ThrowsGameExpiredWithoutAbandoning() {
        doThrow(new BadRequestException(
                "Game has expired due to inactivity.",
                "GAME_EXPIRED",
                "Game has expired due to inactivity"))
                .when(completionHandler).rejectExpiredActivity(GAME_ID);
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBefore = currentPlayer.getChips();

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> process(PlayerAction.CHECK, 0, currentPlayer.getName()));

        assertEquals("GAME_EXPIRED", exception.actionErrorCode());
        assertEquals(chipsBefore, currentPlayer.getChips());
        verify(completionHandler, never()).abandonGameDueToInactivity(GAME_ID);
        verifyNoInteractions(taskScheduler, readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("an action attempted at or after the turn deadline rejects with TURN_EXPIRED")
    void processPlayerAction_WhenTurnDeadlineExpired_ThrowsTurnExpired() {
        Game expiredTurnGame = Game.restoreBuilder()
                .gameId(GAME_ID)
                .players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(15)
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(1).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(2).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId())
                .currentHighestBet(10)
                .currentPhase(GamePhase.PRE_FLOP)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .allPlayersHadInitialTurn(false)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of(ScheduledGameTask.TURN_TIMEOUT, FIXED_INSTANT.toEpochMilli()))
                .build();
        register(expiredTurnGame);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> process(PlayerAction.CALL, 0, testPlayers.getFirst().getName()));

        assertEquals("TURN_EXPIRED", exception.actionErrorCode());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @ParameterizedTest
    @EnumSource(value = PlayerAction.class, names = {"BET", "RAISE"})
    @DisplayName("a bet or raise above the player's chips is rejected with INSUFFICIENT_CHIPS")
    void processPlayerAction_WithBetOrRaiseAboveChips_ShouldThrowBadRequestException(PlayerAction action) {
        if (action == PlayerAction.BET) {
            testGame.dealFlop();
        }
        Player currentPlayer = testGame.getCurrentPlayer();
        int chips = currentPlayer.getChips();

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> process(action, chips + 1, currentPlayer.getName()));

        String expectedMessage = action == PlayerAction.BET
                ? "Bet amount cannot exceed available chips"
                : "Raise amount cannot exceed available chips";
        assertEquals(expectedMessage, exception.getMessage());
        assertEquals("INSUFFICIENT_CHIPS", exception.actionErrorCode());
        assertEquals("Available: " + chips + "; yours: " + (chips + 1), exception.actionErrorMessage());
        assertEquals(chips, currentPlayer.getChips());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("a raise below the minimum is rejected with MIN_RAISE")
    void processPlayerAction_WithInvalidRaiseAmount_ShouldThrowException() {
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBefore = currentPlayer.getChips();

        // Raising by 1 when the big blind is 10 cannot reach a legal raise size.
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> process(PlayerAction.RAISE, 1, currentPlayer.getName()));

        assertEquals("MIN_RAISE", ex.actionErrorCode());
        assertEquals(chipsBefore, currentPlayer.getChips());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("an ALL_IN that cannot be contested by any opponent is rejected with RAISE_NOT_AVAILABLE")
    void processPlayerAction_AggressiveAllInCannotBeContested_ShouldReject() {
        List<Player> lowChipPlayers = new ArrayList<>();
        lowChipPlayers.add(player("LowChip", 5));
        lowChipPlayers.add(player("Normal", 1000));
        Game lowChipGame = dealtGame("low-chip-game", lowChipPlayers);
        Player currentPlayer = lowChipGame.getCurrentPlayer();

        UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class,
                () -> process("low-chip-game", new PlayerActionRequest(PlayerAction.ALL_IN, 0),
                        currentPlayer.getName()));

        assertEquals("RAISE_NOT_AVAILABLE", exception.actionErrorCode());
        assertFalse(currentPlayer.isAllIn());
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    @Test
    @DisplayName("an invalid CHECK rejected by the game leaves TURN_TIMEOUT scheduled")
    void processPlayerAction_InvalidCheckWhenCallOwed_ShouldKeepTurnTimeoutScheduled() {
        long deadline = FIXED_INSTANT.toEpochMilli() + 60_000L;
        testGame.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, deadline);
        Player currentPlayer = testGame.getCurrentPlayer();

        UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class,
                () -> process(PlayerAction.CHECK, 0, currentPlayer.getName()));

        assertEquals("CHECK_NOT_AVAILABLE", exception.actionErrorCode());
        assertEquals(deadline, testGame.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT));
        verify(taskScheduler, never()).cancelTask(any(Game.class), eq(ScheduledGameTask.TURN_TIMEOUT));
        verifyNoInteractions(readinessCoordinator, gameStateService);
    }

    // ==================== processPlayerAction - Actions ====================

    @Test
    @DisplayName("a fold marks the player folded, leaves the pot unchanged and passes the turn on")
    void processPlayerAction_WithValidFold_ShouldProcess() {
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBefore = currentPlayer.getChips();
        int potBefore = testGame.getPot();
        assertFalse(currentPlayer.hasFolded());

        assertDoesNotThrow(() -> process(PlayerAction.FOLD, 0, currentPlayer.getName()));

        assertTrue(currentPlayer.hasFolded());
        assertEquals(chipsBefore, currentPlayer.getChips(), "Folding commits no chips");
        assertEquals(potBefore, testGame.getPot(), "Folding does not change the pot");
        assertNotEquals(currentPlayer.getPlayerId(), testGame.getCurrentPlayer().getPlayerId());
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER), outcomeTypes(testGame));
    }

    /**
     * Protects the exact chip accounting of a CALL: the caller commits precisely the amount owed
     * against the current wager, their stack drops by that amount, their wager rises to match the
     * table, and the pot grows by exactly the same amount.
     */
    @Test
    void processPlayerAction_Call_ShouldUpdatePot() {
        int initialPot = testGame.getPot();
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBeforeCall = currentPlayer.getChips();
        int wagerBeforeCall = currentPlayer.getCurrentBet();
        int highestBet = testGame.getCurrentHighestBet();
        int owed = highestBet - wagerBeforeCall;
        assertTrue(owed > 0, "The opening actor must owe the big blind, otherwise CALL is not legal");

        process(PlayerAction.CALL, 0, currentPlayer.getName());

        assertEquals(chipsBeforeCall - owed, currentPlayer.getChips(),
                "A call must decrement the stack by exactly the amount owed");
        assertEquals(highestBet, currentPlayer.getCurrentBet(),
                "After calling, the player's wager matches the current highest bet");
        assertEquals(initialPot + owed, testGame.getPot(),
                "The pot grows by exactly the called amount");
        assertEquals(GamePhase.PRE_FLOP, testGame.getCurrentPhase(),
                "One of three players calling does not complete the pre-flop round");
        assertNotEquals(currentPlayer.getPlayerId(), testGame.getCurrentPlayer().getPlayerId(),
                "Action advances to the next player after a call that does not close the round");
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER), outcomeTypes(testGame));
    }

    /**
     * Protects the contract that a CHECK from a zero-owed position is accepted without moving
     * chips and that it completes the pre-flop round when the big blind exercises their option.
     *
     * <p>The pre-flop limp (SB completes, UTG/others call) leaves the big blind owing nothing, so
     * CHECK is genuinely legal here rather than being a stand-in for CALL. The oracle asserts the
     * big blind's stack and the pot are both unchanged by the check, and that the completed round
     * advances the hand to the flop.</p>
     */
    @Test
    void processPlayerAction_WithValidCheck_WhenAllowed_ShouldProcess() {
        // Everyone other than the big blind limps in, returning the option to the big blind who,
        // having already posted the big blind, owes nothing and may legally CHECK.
        while (!testGame.getCurrentPlayer().getPlayerId().equals(testGame.getBigBlindPlayerId())) {
            act(testGame, PlayerAction.CALL);
        }

        Player bigBlind = testGame.getCurrentPlayer();
        assertEquals(testGame.getBigBlindPlayerId(), bigBlind.getPlayerId(),
                "Action should have returned to the big blind");
        assertEquals(0, testGame.getCurrentHighestBet() - bigBlind.getCurrentBet(),
                "The big blind should owe nothing, so CHECK is the legal action");

        int bigBlindChipsBeforeCheck = bigBlind.getChips();
        int potBeforeCheck = testGame.getPot();

        process(PlayerAction.CHECK, 0, bigBlind.getName());

        // A legal check moves no chips: neither the actor's stack nor the pot changes.
        assertEquals(bigBlindChipsBeforeCheck, bigBlind.getChips(),
                "CHECK must not change the checking player's chips");
        assertEquals(potBeforeCheck, testGame.getPot(), "CHECK must not change the pot");

        // With every actor matched, the completed pre-flop round advances to the flop and hands
        // the action to a new current player.
        assertEquals(GamePhase.FLOP, testGame.getCurrentPhase(),
                "The big blind's check completes the pre-flop round and advances to the flop");
        assertNotNull(testGame.getCurrentPlayer(), "The flop must have a current actor");
        assertEquals(TurnOutcomeType.PHASE_ADVANCED, outcomeTypes(testGame).getLast());
    }

    @Test
    @DisplayName("a valid raise commits the raise amount, raises the table bet and grows the pot")
    void processPlayerAction_WithValidRaise_ShouldProcess() {
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBefore = currentPlayer.getChips();
        int betBefore = currentPlayer.getCurrentBet();
        int potBefore = testGame.getPot();
        int raiseAmount = 30;

        assertDoesNotThrow(() -> process(PlayerAction.RAISE, raiseAmount, currentPlayer.getName()));

        assertEquals(chipsBefore - raiseAmount, currentPlayer.getChips());
        assertEquals(betBefore + raiseAmount, currentPlayer.getCurrentBet());
        assertEquals(currentPlayer.getCurrentBet(), testGame.getCurrentHighestBet());
        assertEquals(potBefore + raiseAmount, testGame.getPot());
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER), outcomeTypes(testGame));
    }

    @Test
    @DisplayName("a valid bet on the flop commits the bet amount and sets the table bet")
    void processPlayerAction_WithValidBet_ShouldProcess() {
        // BET is only valid when nobody has wagered yet, so move the game to the flop first.
        testGame.dealFlop();
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBefore = currentPlayer.getChips();
        int potBefore = testGame.getPot();
        int betAmount = 20;

        assertDoesNotThrow(() -> process(PlayerAction.BET, betAmount, currentPlayer.getName()));

        assertEquals(chipsBefore - betAmount, currentPlayer.getChips());
        assertEquals(betAmount, currentPlayer.getCurrentBet());
        assertEquals(betAmount, testGame.getCurrentHighestBet());
        assertEquals(potBefore + betAmount, testGame.getPot());
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER), outcomeTypes(testGame));
    }

    @Test
    @DisplayName("an ALL_IN commits the whole stack, flags the player all-in and adds the stack to the pot")
    void processPlayerAction_WithAllIn_ShouldProcess() {
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBefore = currentPlayer.getChips();
        int betBefore = currentPlayer.getCurrentBet();
        int potBefore = testGame.getPot();

        assertDoesNotThrow(() -> process(PlayerAction.ALL_IN, 0, currentPlayer.getName()));

        assertTrue(currentPlayer.isAllIn());
        assertEquals(0, currentPlayer.getChips());
        assertEquals(betBefore + chipsBefore, currentPlayer.getCurrentBet());
        assertEquals(potBefore + chipsBefore, testGame.getPot());
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER), outcomeTypes(testGame));
    }

    @Test
    @DisplayName("a CALL larger than the stack becomes an all-in and notifies the player")
    void processPlayerAction_WithCallLargerThanStack_ShouldConvertToAllIn() {
        List<Player> shortStackPlayers = new ArrayList<>();
        shortStackPlayers.add(player("SB", 1000));
        shortStackPlayers.add(player("Short", 5));
        shortStackPlayers.add(player("BB", 1000));
        Game shortStackGame = dealtGame("short-stack-game", shortStackPlayers);
        Player currentPlayer = shortStackGame.getCurrentPlayer();

        assertDoesNotThrow(() -> process("short-stack-game", new PlayerActionRequest(PlayerAction.CALL, 0),
                currentPlayer.getName()));

        assertEquals(0, currentPlayer.getChips());
        assertTrue(currentPlayer.isAllIn());
        verify(gameStateService).sendPrivatePlayerNotification(
                eq("short-stack-game"), eq(currentPlayer.getName()), contains("converted to all-in"));
    }

    @Test
    void processPlayerAction_RaiseWithAllInPlayers_ShouldRemainRaise() {
        // Set up a game where a short stack can go all-in and action continues.
        List<Player> players = new ArrayList<>();
        players.add(player("Normal", 1000));
        players.add(player("AllIn", 50));
        players.add(player("Third", 1000));
        Game gameWithAllIn = dealtGame("all-in-game", players);

        Player shortStack = gameWithAllIn.getCurrentPlayer();
        assertEquals("AllIn", shortStack.getName());
        act(gameWithAllIn, PlayerAction.ALL_IN);

        Player raiser = gameWithAllIn.getCurrentPlayer();
        int previousBet = raiser.getCurrentBet();

        assertDoesNotThrow(() -> process("all-in-game", new PlayerActionRequest(PlayerAction.RAISE, 100),
                raiser.getName()));

        assertTrue(raiser.getCurrentBet() > previousBet);
        assertEquals(raiser.getCurrentBet(), gameWithAllIn.getCurrentHighestBet());
        verify(gameStateService, never()).sendPrivatePlayerNotification(
                eq("all-in-game"), eq(raiser.getName()), contains("converted to a call"));
    }

    // ==================== Game Progression Tests ====================

    @Test
    void processPlayerAction_PreFlop_AllCalls_ShouldReturnActionToBigBlind() {
        act(testGame, PlayerAction.CALL);
        act(testGame, PlayerAction.CALL);

        assertEquals(GamePhase.PRE_FLOP, testGame.getCurrentPhase(),
                "Pre-flop should remain active until big blind takes their option");
        assertEquals(testGame.getBigBlindPlayerId(), testGame.getCurrentPlayer().getPlayerId(),
                "Action should return to the big blind after everyone else calls");
    }

    @Test
    void processPlayerAction_PreFlop_BigBlindShouldBeAbleToRaiseAfterCalls() {
        act(testGame, PlayerAction.CALL);
        act(testGame, PlayerAction.CALL);

        Player bigBlind = testGame.getCurrentPlayer();
        assertEquals(testGame.getBigBlindPlayerId(), bigBlind.getPlayerId());

        int highestBeforeRaise = testGame.getCurrentHighestBet();
        assertDoesNotThrow(() -> process(PlayerAction.RAISE, 25, bigBlind.getName()));

        assertEquals(GamePhase.PRE_FLOP, testGame.getCurrentPhase(),
                "After a big blind raise, action should stay pre-flop");
        assertTrue(testGame.getCurrentHighestBet() > highestBeforeRaise,
                "Big blind raise should increase the highest bet");
    }

    @Test
    void processPlayerAction_PreFlop_HeadsUp_CallShouldStillGiveBigBlindOption() {
        List<Player> players = new ArrayList<>();
        players.add(player("P1", 1000));
        players.add(player("P2", 1000));
        Game game = dealtGame("heads-up-game", players);

        act(game, PlayerAction.CALL);

        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase(),
                "Heads-up pre-flop should stay open for the big blind option");
        assertEquals(game.getBigBlindPlayerId(), game.getCurrentPlayer().getPlayerId(),
                "In heads-up, action should move to big blind after small blind calls");
    }

    @Test
    @DisplayName("a fold followed by a call leaves the folder out and the caller matched to the table")
    void processPlayerAction_MultipleActionsInSequence_ShouldWork() {
        Player player1 = testGame.getCurrentPlayer();
        process(PlayerAction.FOLD, 0, player1.getName());

        Player player2 = testGame.getCurrentPlayer();
        assertNotEquals(player1, player2);

        assertDoesNotThrow(() -> process(PlayerAction.CALL, 0, player2.getName()));

        assertTrue(player1.hasFolded());
        assertFalse(player2.hasFolded());
        assertEquals(testGame.getCurrentHighestBet(), player2.getCurrentBet());
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER, TurnOutcomeType.NEXT_PLAYER), outcomeTypes(testGame));
    }

    @Test
    @DisplayName("a valid action cancels TURN_TIMEOUT and resets game inactivity")
    void processPlayerAction_ValidAction_CancelsTurnTimeoutAndResetsInactivity() {
        testGame.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, FIXED_INSTANT.toEpochMilli() + 60_000L);
        Player currentPlayer = testGame.getCurrentPlayer();

        process(PlayerAction.FOLD, 0, currentPlayer.getName());

        verify(taskScheduler).cancelTask(testGame, ScheduledGameTask.TURN_TIMEOUT);
        verify(taskScheduler).resetGameInactivity(testGame);
    }

    @Test
    @DisplayName("a CHECK with a zero amount keeps the bet flat and advances the turn through real play")
    void processPlayerAction_CheckWithZeroAmount_KeepsBetFlatAndAdvancesTurn() {
        // Reach the flop through real play so there is no wager owed and CHECK is legal.
        playUntilPhaseAdvances(testGame);
        assertEquals(GamePhase.FLOP, testGame.getCurrentPhase());
        clearInvocations(readinessCoordinator);
        Player currentPlayer = testGame.getCurrentPlayer();
        int betBefore = currentPlayer.getCurrentBet();

        process(PlayerAction.CHECK, 0, currentPlayer.getName());

        assertEquals(betBefore, currentPlayer.getCurrentBet(), "A check commits no chips");
        assertNotEquals(currentPlayer.getName(), testGame.getCurrentPlayer().getName(),
                "The turn moves on after a check");
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER), outcomeTypes(testGame));
    }

    /**
     * Plays legal check/call actions through the service for the current actor until the hand
     * leaves its starting phase. Used to reach a PHASE_ADVANCED outcome through real play.
     */
    private void playUntilPhaseAdvances(Game game) {
        GamePhase startPhase = game.getCurrentPhase();
        for (int guard = 0; guard < 20
                && game.getCurrentPhase() == startPhase
                && game.getCurrentPhase() != GamePhase.SHOWDOWN
                && !game.isGameOver(); guard++) {
            checkOrCall(game);
        }
    }

    private void checkOrCall(Game game) {
        List<PlayerAction> legal = game.getLegalActionsFor(game.getCurrentPlayer());
        act(game, legal.contains(PlayerAction.CHECK) ? PlayerAction.CHECK : PlayerAction.CALL);
    }

    // ==================== processPlayerAction - Outcome Arms ====================

    @Test
    @DisplayName("an all-in call that leaves nobody able to act hands AUTO_ADVANCING to the readiness coordinator")
    void processPlayerAction_AllInLeavesNobodyToAct_ShouldReportAutoAdvancing() {
        List<Player> players = new ArrayList<>();
        players.add(player("P1", 100));
        players.add(player("P2", 100));
        Game game = dealtGame("all-in-heads-up", players);

        Player first = game.getCurrentPlayer();
        act(game, PlayerAction.ALL_IN);

        Player second = game.getCurrentPlayer();
        assertNotEquals(first.getName(), second.getName());
        act(game, PlayerAction.ALL_IN);

        assertTrue(first.isAllIn());
        assertTrue(second.isAllIn());
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER, TurnOutcomeType.AUTO_ADVANCING), outcomeTypes(game));
    }

    @Test
    @DisplayName("a real betting round that completes before showdown advances the phase")
    void processPlayerAction_RealBettingRoundCompletes_AdvancesPhase() {
        List<Player> players = new ArrayList<>();
        players.add(player("A", 1000));
        players.add(player("B", 1000));
        Game game = dealtGame("betting-round-game", players);
        int potAfterBlinds = game.getPot();

        playUntilPhaseAdvances(game);

        // The hand moved to the flop without a showdown or auto-advance, and the pot reflects the
        // limped pre-flop round (SB completed to the big blind).
        assertEquals(GamePhase.FLOP, game.getCurrentPhase());
        assertTrue(game.getPot() >= potAfterBlinds, "Completing the pre-flop round cannot shrink the pot");
        assertEquals(2 * game.getBigBlind(), game.getPot(), "Both players matched the big blind pre-flop");
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER, TurnOutcomeType.PHASE_ADVANCED), outcomeTypes(game));
    }

    @Test
    @DisplayName("a real hand played to the river reports one phase advance per street and then SHOWDOWN")
    void processPlayerAction_RealRiverShowdown_ReportsShowdownOutcome() {
        List<Player> players = new ArrayList<>();
        players.add(player("A", 1000));
        players.add(player("B", 1000));
        Game game = dealtGame("river-showdown-game", players);
        // Check/call every street until the river betting round completes and the hand resolves.
        for (int guard = 0; guard < 40 && game.getCurrentPhase() != GamePhase.SHOWDOWN; guard++) {
            checkOrCall(game);
        }

        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase(), "A limped hand reaches a river showdown");
        assertFalse(game.isGameOver(), "Both players still hold chips");
        // Two actions per street: PRE_FLOP, FLOP, TURN and RIVER each close on the second action.
        assertEquals(List.of(
                TurnOutcomeType.NEXT_PLAYER, TurnOutcomeType.PHASE_ADVANCED,
                TurnOutcomeType.NEXT_PLAYER, TurnOutcomeType.PHASE_ADVANCED,
                TurnOutcomeType.NEXT_PLAYER, TurnOutcomeType.PHASE_ADVANCED,
                TurnOutcomeType.NEXT_PLAYER, TurnOutcomeType.SHOWDOWN), outcomeTypes(game));
    }

    // ==================== processPlayerAction - Post-processing Failures ====================

    @Test
    @DisplayName("a real decision whose post-processing fails propagates without a fallback broadcast")
    void processPlayerAction_RealDecisionPostProcessingFails_ShouldPropagateWithoutFallbackBroadcast() {
        doThrow(new IllegalStateException("progression failed")).when(readinessCoordinator)
                .applyTurnOutcomeAndOpenReadyGateIfNeeded(eq(testGame), any(TurnOutcome.class), eq(true));

        Player currentPlayer = testGame.getCurrentPlayer();
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> process(PlayerAction.FOLD, 0, currentPlayer.getName()));

        assertEquals("progression failed", exception.getMessage());
        verifyNoInteractions(gameStateService);
    }

    // ==================== Concurrent Access Tests ====================

    @Test
    void concurrentDuplicateCallsChargeAndAdvanceTheCurrentPlayerOnlyOnce() throws Exception {
        Player actor = testGame.getCurrentPlayer();
        int chipsBefore = actor.getChips();
        int potBefore = testGame.getPot();
        int amountToCall = testGame.getCurrentHighestBet() - actor.getCurrentBet();

        List<Boolean> accepted = raceActions(List.of(actor, actor, actor), PlayerAction.CALL);

        assertEquals(1, accepted.stream().filter(Boolean::booleanValue).count());
        assertEquals(chipsBefore - amountToCall, actor.getChips());
        assertEquals(potBefore + amountToCall, testGame.getPot());
        assertNotSame(actor, testGame.getCurrentPlayer());
        assertEquals(List.of(TurnOutcomeType.NEXT_PLAYER), outcomeTypes(testGame));
    }

    /** Parks the first action inside its room lock while both other requests run. */
    @Test
    void actionsWaitForTheirOwnGameButDoNotBlockAnIndependentGame() throws Exception {
        String independentId = "independent-game";
        Game independent = dealtGame(independentId, new ArrayList<>(List.of(
                new Player("Alice", "a", 1000),
                new Player("Bob", "b", 1000),
                new Player("Charlie", "c", 1000))));

        Player first = testGame.getCurrentPlayer();
        Player independentActor = independent.getCurrentPlayer();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstEntered.countDown();
            assertTrue(releaseFirst.await(15, TimeUnit.SECONDS), "the parked action was never released");
            return null;
        }).doNothing().when(readinessCoordinator)
                .applyTurnOutcomeAndOpenReadyGateIfNeeded(same(testGame), any(TurnOutcome.class), eq(true));

        PlayerActionRequest call = new PlayerActionRequest(PlayerAction.CALL, 0);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> firstAction = executor.submit(() -> process(GAME_ID, call, first.getName()));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Player next = testGame.getCurrentPlayer();

            Future<?> sameGameAction = executor.submit(() -> process(GAME_ID, call, next.getName()));
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> roomCoordinator.lockReservationCount(GAME_ID) >= 2);

            Future<?> independentAction = executor.submit(
                    () -> process(independentId, call, independentActor.getName()));
            independentAction.get(5, TimeUnit.SECONDS);
            assertEquals(independent.getCurrentHighestBet(), independentActor.getCurrentBet());
            assertFalse(sameGameAction.isDone());

            releaseFirst.countDown();
            firstAction.get(5, TimeUnit.SECONDS);
            sameGameAction.get(5, TimeUnit.SECONDS);
            assertEquals(testGame.getCurrentHighestBet(), next.getCurrentBet());
            assertNotSame(next, testGame.getCurrentPlayer());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentAllInsCommitChipsOnlyForPlayersWhoReachTheirTurn() throws Exception {
        List<Player> players = List.copyOf(testGame.getPlayers());
        Player firstActor = testGame.getCurrentPlayer();
        List<Integer> startingChips = players.stream().map(Player::getChips).toList();
        int chipsIncludingPot = totalChipsIncludingPot(testGame);

        List<Boolean> accepted = raceActions(players, PlayerAction.ALL_IN);

        assertTrue(accepted.get(players.indexOf(firstActor)), "The initial actor can always act");
        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            assertEquals(accepted.get(index), player.isAllIn(), player.getName());
            assertEquals(accepted.get(index) ? 0 : startingChips.get(index), player.getChips());
        }
        assertEquals(chipsIncludingPot, totalChipsIncludingPot(testGame));
    }

    /** Releases requests together and observes every worker, including failures from the assertions. */
    private List<Boolean> raceActions(List<Player> actors, PlayerAction action) throws Exception {
        CyclicBarrier startBarrier = new CyclicBarrier(actors.size());
        PlayerActionRequest request = new PlayerActionRequest(action, 0);
        ExecutorService executor = Executors.newFixedThreadPool(actors.size());
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (Player actor : actors) {
                attempts.add(executor.submit(() -> {
                    startBarrier.await(5, TimeUnit.SECONDS);
                    try {
                        process(GAME_ID, request, actor.getName());
                        return true;
                    } catch (UnauthorisedActionException rejection) {
                        assertEquals("NOT_YOUR_TURN", rejection.actionErrorCode());
                        return false;
                    }
                }));
            }
            List<Boolean> accepted = new ArrayList<>();
            for (Future<Boolean> attempt : attempts) {
                accepted.add(attempt.get(5, TimeUnit.SECONDS));
            }
            return accepted;
        } finally {
            executor.shutdownNow();
        }
    }
}
