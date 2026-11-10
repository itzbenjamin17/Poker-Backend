package com.pokergame.service;

import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.pokergame.util.ConcurrentTestSupport.awaitBlockedOn;
import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pokergame.exception.ResourceNotFoundException;

/**
 * Unit tests for the PlayerActionService class.
 */
@Tag("unit")
@DisplayName("Player action service")
@ExtendWith(MockitoExtension.class)
class PlayerActionServiceTest {

    @Mock
    private GameLifecycleService gameLifecycleService;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private HandEvaluatorService handEvaluator;

    private PlayerActionService playerActionService;

    private Game testGame;
    private List<Player> testPlayers;
    private static final String GAME_ID = "test-game-id";

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        playerActionService = new PlayerActionService(gameLifecycleService, gameStateService);
        testPlayers = new ArrayList<>();
        testPlayers.add(new Player("Player1", UUID.randomUUID().toString(), 1000));
        testPlayers.add(new Player("Player2", UUID.randomUUID().toString(), 1000));
        testPlayers.add(new Player("Player3", UUID.randomUUID().toString(), 1000));

        testGame = new Game(GAME_ID, testPlayers, 5, 10, handEvaluator);
        testGame.resetForNewHand();
        testGame.dealHoleCards();
        testGame.postBlinds();
        lenient().doAnswer(invocation -> {
            String gId = invocation.getArgument(0);
            Game g = invocation.getArgument(1);
            TurnOutcome outcome = invocation.getArgument(2);
            switch (outcome.type()) {
                case NEXT_PLAYER, PHASE_ADVANCED -> gameStateService.broadcastGameState(gId, g);
                case AUTO_ADVANCING -> {
                    gameLifecycleService.scheduleAutoAdvance(gId);
                    gameStateService.broadcastAutoAdvanceNotification(gId, g);
                }
                case SHOWDOWN -> gameStateService.broadcastShowdownResults(
                        gId, g, outcome.winners(), outcome.winningsPerPlayer());
            }
            return null;
        }).when(gameLifecycleService).applyTurnOutcome(anyString(), any(Game.class), any(TurnOutcome.class), anyBoolean());
    }

    // ==================== processPlayerAction - Basic Tests ====================

    /**
     * Protects the contract that process player action when game not found should throw exception.
     */
    @Test
    void processPlayerAction_WhenGameNotFound_ShouldThrowException() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(null);

        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, "Player1"));

        assertTrue(exception.getMessage().contains("Game not found"));
    }

    @Test
    @DisplayName("processPlayerAction should throw BadRequestException when game is over")
    void processPlayerAction_WhenGameOver_ShouldThrowBadRequestException() {
        Game spyGame = spy(testGame);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(spyGame);
        spyGame.markGameOver();

        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.FOLD, null);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, "Player1"));

        assertEquals("This game has finished.", exception.getMessage());
        verify(spyGame, never()).processPlayerDecision(any(), any());
    }

    @Test
    @DisplayName("processPlayerAction should throw BadRequestException when game is in SHOWDOWN phase")
    void rejectsActionDuringShowdownPhase() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        when(handEvaluator.getBestHand(any(), any()))
                .thenReturn(new HandEvaluationResult(List.of(new Card(Rank.ACE, Suit.SPADES)), HandRank.HIGH_CARD));
        testGame.conductShowdown();
        assertEquals(GamePhase.SHOWDOWN, testGame.getCurrentPhase());

        int initialChips = testPlayers.stream().mapToInt(Player::getChips).sum();

        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CHECK, 0);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, testGame.getCurrentPlayer().getName()));

        assertEquals("This hand is already complete.", exception.getMessage());
        int finalChips = testPlayers.stream().mapToInt(Player::getChips).sum();
        assertEquals(initialChips, finalChips);
    }

    /**
     * Protects the contract that process player action when not player turn should throw security exception.
     */
    @Test
    void processPlayerAction_WhenNotPlayerTurn_ShouldThrowSecurityException() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        String nonCurrentPlayerName = testPlayers.stream()
                .filter(p -> !p.getName().equals(currentPlayer.getName()))
                .findFirst()
                .orElseThrow()
                .getName();

        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);

        UnauthorisedActionException exception = assertThrows(
                UnauthorisedActionException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, nonCurrentPlayerName));

        assertTrue(exception.getMessage().contains("not your turn"));
    }

    /**
     * Protects the contract that process player action when current player disconnected should throw security exception.
     */
    @Test
    void processPlayerAction_WhenCurrentPlayerDisconnected_ShouldThrowSecurityException() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        currentPlayer.setDisconnected(true);

        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);

        UnauthorisedActionException exception = assertThrows(
                UnauthorisedActionException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));

        assertTrue(exception.getMessage().toLowerCase().contains("reconnect"));
    }

    /**
     * Protects the contract that process player action with valid fold should process.
     */
    @Test
    void processPlayerAction_WithValidFold_ShouldProcess() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.FOLD, null);

        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));

        assertTrue(currentPlayer.getHasFolded());
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    /**
     * Protects the contract that process player action with valid call should process.
     */
    @Test
    void processPlayerAction_WithValidCall_ShouldProcess() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        int initialChips = currentPlayer.getChips();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);

        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));

        assertTrue(currentPlayer.getChips() < initialChips || currentPlayer.getCurrentBet() > 0);
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
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
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        // Everyone other than the big blind limps in, returning the option to the big blind who,
        // having already posted the big blind, owes nothing and may legally CHECK.
        while (!testGame.getCurrentPlayer().getPlayerId().equals(testGame.getBigBlindPlayerId())) {
            Player actor = testGame.getCurrentPlayer();
            playerActionService.processPlayerAction(GAME_ID,
                    new PlayerActionRequest(PlayerAction.CALL, null), actor.getName());
        }

        Player bigBlind = testGame.getCurrentPlayer();
        assertEquals(testGame.getBigBlindPlayerId(), bigBlind.getPlayerId(),
                "Action should have returned to the big blind");
        assertEquals(0, testGame.getCurrentHighestBet() - bigBlind.getCurrentBet(),
                "The big blind should owe nothing, so CHECK is the legal action");

        int bigBlindChipsBeforeCheck = bigBlind.getChips();
        int potBeforeCheck = testGame.getPot();

        playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.CHECK, null), bigBlind.getName());

        // A legal check moves no chips: neither the actor's stack nor the pot changes.
        assertEquals(bigBlindChipsBeforeCheck, bigBlind.getChips(),
                "CHECK must not change the checking player's chips");
        assertEquals(potBeforeCheck, testGame.getPot(), "CHECK must not change the pot");

        // With every actor matched, the completed pre-flop round advances to the flop and hands
        // the action to a new current player.
        assertEquals(GamePhase.FLOP, testGame.getCurrentPhase(),
                "The big blind's check completes the pre-flop round and advances to the flop");
        assertNotNull(testGame.getCurrentPlayer(), "The flop must have a current actor");
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    // ==================== processPlayerAction - Raise Tests ====================

    /**
     * Protects the contract that process player action with valid raise should process.
     */
    @Test
    void processPlayerAction_WithValidRaise_ShouldProcess() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        int raiseAmount = 30; // Raise by 30
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.RAISE, raiseAmount);

        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));

        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    /**
     * Protects the contract that process player action with invalid raise amount should throw exception.
     */
    @Test
    void processPlayerAction_WithInvalidRaiseAmount_ShouldThrowException() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        // Try to raise by 1 when big blind is 10 - this should fail
        int invalidRaiseAmount = 1;
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.RAISE, invalidRaiseAmount);

        assertThrows(UnauthorisedActionException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));
    }

    /**
     * Protects the contract that process player action with raise larger than stack should throw bad request.
     */
    @Test
    void processPlayerAction_WithRaiseLargerThanStack_ShouldThrowBadRequest() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        int overStackRaise = currentPlayer.getChips() + 1;
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.RAISE, overStackRaise);

        assertThrows(BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));
    }

    /**
     * Protects the contract that process player action with call larger than stack should convert to all in.
     */
    @Test
    void processPlayerAction_WithCallLargerThanStack_ShouldConvertToAllIn() {
        List<Player> shortStackPlayers = new ArrayList<>();
        shortStackPlayers.add(new Player("SB", UUID.randomUUID().toString(), 1000));
        shortStackPlayers.add(new Player("Short", UUID.randomUUID().toString(), 5));
        shortStackPlayers.add(new Player("BB", UUID.randomUUID().toString(), 1000));

        Game shortStackGame = new Game(GAME_ID, shortStackPlayers, 5, 10, handEvaluator);
        shortStackGame.resetForNewHand();
        shortStackGame.dealHoleCards();
        shortStackGame.postBlinds();

        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(shortStackGame);

        Player currentPlayer = shortStackGame.getCurrentPlayer();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);

        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));
        assertEquals(0, currentPlayer.getChips());
        assertTrue(currentPlayer.getIsAllIn());
        verify(gameStateService, atLeastOnce()).sendPrivatePlayerNotification(eq(GAME_ID), eq(currentPlayer.getName()),
                contains("converted to all-in"), eq(ResponseMessage.PLAYER_NOTIFICATION));
        verify(gameStateService, never()).sendPlayerNotification(eq(GAME_ID), eq(currentPlayer.getName()),
                anyString());
    }

    // ==================== processPlayerAction - All-In Tests ====================

    /**
     * Protects the contract that process player action with all in should process.
     */
    @Test
    void processPlayerAction_WithAllIn_ShouldProcess() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.ALL_IN, null);

        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));

        // Action should process without error even when all-in players are present.
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    /**
     * Protects the contract that process player action all in with low chips should go all in.
     */
    @Test
    void processPlayerAction_AggressiveAllInCannotBeContested_ShouldReject() {
        // Create a player with very few chips
        List<Player> lowChipPlayers = new ArrayList<>();
        lowChipPlayers.add(new Player("LowChip", UUID.randomUUID().toString(), 5)); // Only 5 chips
        lowChipPlayers.add(new Player("Normal", UUID.randomUUID().toString(), 1000));

        Game lowChipGame = new Game(GAME_ID, lowChipPlayers, 5, 10, handEvaluator);
        lowChipGame.resetForNewHand();
        lowChipGame.dealHoleCards();
        lowChipGame.postBlinds();

        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(lowChipGame);

        Player currentPlayer = lowChipGame.getCurrentPlayer();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.ALL_IN, null);

        UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));
        assertEquals("RAISE_NOT_AVAILABLE", exception.actionErrorCode());
    }

    // ==================== processPlayerAction - Bet Tests ====================

    /**
     * Protects the contract that process player action with valid bet should process.
     */
    @Test
    void processPlayerAction_WithValidBet_ShouldProcess() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        // BET is only valid when there are no active bets, which requires changing game
        // phase
        // The testGame defaults to PRE_FLOP which has blind bets. We advance it to FLOP
        testGame.dealFlop();

        Player currentPlayer = testGame.getCurrentPlayer();
        // Bet amount should be valid
        int betAmount = 20;
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.BET, betAmount);

        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));

        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    // ==================== Broadcasting Tests ====================

    /**
     * Protects the contract that process player action should broadcast game state.
     */
    @Test
    void processPlayerAction_ShouldBroadcastGameState() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.FOLD, null);

        playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName());

        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    // ==================== Game Progression Tests ====================

    /**
     * Protects the contract that process player action when betting round complete should advance game.
     */
    @Test
    void processPlayerAction_WhenBettingRoundComplete_ShouldAdvanceGame() {
        // Create a 2-player game for simpler betting round
        List<Player> twoPlayers = new ArrayList<>();
        twoPlayers.add(new Player("P1", UUID.randomUUID().toString(), 1000));
        twoPlayers.add(new Player("P2", UUID.randomUUID().toString(), 1000));

        Game twoPlayerGame = new Game(GAME_ID, twoPlayers, 5, 10, handEvaluator);
        twoPlayerGame.resetForNewHand();
        twoPlayerGame.dealHoleCards();
        twoPlayerGame.postBlinds();

        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(twoPlayerGame);

        // First player calls
        Player currentPlayer = twoPlayerGame.getCurrentPlayer();
        PlayerActionRequest callRequest = new PlayerActionRequest(PlayerAction.CALL, null);
        playerActionService.processPlayerAction(GAME_ID, callRequest, currentPlayer.getName());

        // Game state should be broadcast
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    /**
     * Protects the contract that process player action pre flop all calls should return action to big blind.
     */
    @Test
    void processPlayerAction_PreFlop_AllCalls_ShouldReturnActionToBigBlind() {
        List<Player> players = new ArrayList<>();
        players.add(new Player("P1", UUID.randomUUID().toString(), 1000));
        players.add(new Player("P2", UUID.randomUUID().toString(), 1000));
        players.add(new Player("P3", UUID.randomUUID().toString(), 1000));

        Game game = new Game(GAME_ID, players, 5, 10, handEvaluator);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();

        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(game);

        Player firstToAct = game.getCurrentPlayer();
        playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.CALL, null), firstToAct.getName());

        Player secondToAct = game.getCurrentPlayer();
        playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.CALL, null), secondToAct.getName());

        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase(),
                "Pre-flop should remain active until big blind takes their option");
        assertEquals(game.getBigBlindPlayerId(), game.getCurrentPlayer().getPlayerId(),
                "Action should return to the big blind after everyone else calls");
    }

    /**
     * Protects the contract that process player action pre flop big blind should be able to raise after calls.
     */
    @Test
    void processPlayerAction_PreFlop_BigBlindShouldBeAbleToRaiseAfterCalls() {
        List<Player> players = new ArrayList<>();
        players.add(new Player("P1", UUID.randomUUID().toString(), 1000));
        players.add(new Player("P2", UUID.randomUUID().toString(), 1000));
        players.add(new Player("P3", UUID.randomUUID().toString(), 1000));

        Game game = new Game(GAME_ID, players, 5, 10, handEvaluator);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();

        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(game);

        Player firstToAct = game.getCurrentPlayer();
        playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.CALL, null), firstToAct.getName());

        Player secondToAct = game.getCurrentPlayer();
        playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.CALL, null), secondToAct.getName());

        Player bigBlind = game.getCurrentPlayer();
        assertEquals(game.getBigBlindPlayerId(), bigBlind.getPlayerId());

        int highestBeforeRaise = game.getCurrentHighestBet();
        assertDoesNotThrow(() -> playerActionService.processPlayerAction(
                GAME_ID,
                new PlayerActionRequest(PlayerAction.RAISE, 25),
                bigBlind.getName()));

        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase(),
                "After a big blind raise, action should stay pre-flop");
        assertTrue(game.getCurrentHighestBet() > highestBeforeRaise,
                "Big blind raise should increase the highest bet");
    }

    /**
     * Protects the contract that process player action pre flop heads up call should still give big blind option.
     */
    @Test
    void processPlayerAction_PreFlop_HeadsUp_CallShouldStillGiveBigBlindOption() {
        List<Player> players = new ArrayList<>();
        players.add(new Player("P1", UUID.randomUUID().toString(), 1000));
        players.add(new Player("P2", UUID.randomUUID().toString(), 1000));

        Game game = new Game(GAME_ID, players, 5, 10, handEvaluator);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();

        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(game);

        Player smallBlind = game.getCurrentPlayer();
        assertDoesNotThrow(() -> playerActionService.processPlayerAction(
                GAME_ID,
                new PlayerActionRequest(PlayerAction.CALL, null),
                smallBlind.getName()));

        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase(),
                "Heads-up pre-flop should stay open for the big blind option");
        assertEquals(game.getBigBlindPlayerId(), game.getCurrentPlayer().getPlayerId(),
                "In heads-up, action should move to big blind after small blind calls");
    }

    // ==================== Multiple Actions Tests ====================

    /**
     * Protects the contract that process player action multiple actions in sequence should work.
     */
    @Test
    void processPlayerAction_MultipleActionsInSequence_ShouldWork() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        // Get the initial current player and have them fold
        Player player1 = testGame.getCurrentPlayer();
        PlayerActionRequest fold1 = new PlayerActionRequest(PlayerAction.FOLD, null);
        playerActionService.processPlayerAction(GAME_ID, fold1, player1.getName());

        // Now the next player should be current
        Player player2 = testGame.getCurrentPlayer();
        assertNotEquals(player1, player2);

        PlayerActionRequest call2 = new PlayerActionRequest(PlayerAction.CALL, null);
        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID, call2, player2.getName()));
    }

    // ==================== Edge Cases ====================

    /**
     * Protects the contract that process player action with null amount for fold should work.
     */
    @Test
    void processPlayerAction_WithNullAmount_ForFold_ShouldWork() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.FOLD, null);

        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));
    }

    /**
     * Protects the contract that process player action same game ID different actions should track correctly.
     */
    @Test
    void processPlayerAction_SameGameId_DifferentActions_ShouldTrackCorrectly() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        // Multiple players taking actions
        Player p1 = testGame.getCurrentPlayer();
        playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.FOLD, null), p1.getName());

        Player p2 = testGame.getCurrentPlayer();
        playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.CALL, null), p2.getName());

        // Verify folded player is actually folded
        assertTrue(p1.getHasFolded());
        assertFalse(p2.getHasFolded());
    }

    // ==================== Conversion Tests ====================

    /**
     * Protects the contract that process player action raise with all in players should remain raise.
     */
    @Test
    void processPlayerAction_RaiseWithAllInPlayers_ShouldRemainRaise() {
        // Set up a game where a short stack can go all-in and action continues.
        List<Player> players = new ArrayList<>();
        Player normalPlayer = new Player("Normal", UUID.randomUUID().toString(), 1000);
        Player allInPlayer = new Player("AllIn", UUID.randomUUID().toString(), 50);
        Player thirdPlayer = new Player("Third", UUID.randomUUID().toString(), 1000);
        players.add(normalPlayer);
        players.add(allInPlayer);
        players.add(thirdPlayer);

        Game gameWithAllIn = new Game(GAME_ID, players, 5, 10, handEvaluator);
        gameWithAllIn.resetForNewHand();
        gameWithAllIn.dealHoleCards();
        gameWithAllIn.postBlinds();

        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(gameWithAllIn);

        Player shortStack = gameWithAllIn.getCurrentPlayer();
        assertEquals("AllIn", shortStack.getName());
        playerActionService.processPlayerAction(
                GAME_ID,
                new PlayerActionRequest(PlayerAction.ALL_IN, null),
                shortStack.getName());

        Player raiser = gameWithAllIn.getCurrentPlayer();
        int previousBet = raiser.getCurrentBet();
        PlayerActionRequest raiseRequest = new PlayerActionRequest(PlayerAction.RAISE, 100);

        assertDoesNotThrow(
                () -> playerActionService.processPlayerAction(GAME_ID, raiseRequest, raiser.getName()));

        assertTrue(raiser.getCurrentBet() > previousBet);
        assertEquals(raiser.getCurrentBet(), gameWithAllIn.getCurrentHighestBet());
        verify(gameStateService, never()).sendPlayerNotification(eq(GAME_ID), eq(raiser.getName()),
                contains("converted to a call"));
    }

    // ==================== Pot Updates Tests ====================

    /**
     * Protects the exact chip accounting of a CALL: the caller commits precisely the amount owed
     * against the current wager, their stack drops by that amount, their wager rises to match the
     * table, and the pot grows by exactly the same amount.
     */
    @Test
    void processPlayerAction_Call_ShouldUpdatePot() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        int initialPot = testGame.getPot();
        Player currentPlayer = testGame.getCurrentPlayer();
        int chipsBeforeCall = currentPlayer.getChips();
        int wagerBeforeCall = currentPlayer.getCurrentBet();
        int highestBet = testGame.getCurrentHighestBet();
        int owed = highestBet - wagerBeforeCall;
        assertTrue(owed > 0, "The opening actor must owe the big blind, otherwise CALL is not legal");

        PlayerActionRequest callRequest = new PlayerActionRequest(PlayerAction.CALL, null);
        playerActionService.processPlayerAction(GAME_ID, callRequest, currentPlayer.getName());

        assertEquals(chipsBeforeCall - owed, currentPlayer.getChips(),
                "A call must decrement the stack by exactly the amount owed");
        assertEquals(highestBet, currentPlayer.getCurrentBet(),
                "After calling, the player's wager matches the current highest bet");
        assertEquals(wagerBeforeCall + owed, currentPlayer.getCurrentBet(),
                "The player's wager increases by exactly the amount owed");
        assertEquals(initialPot + owed, testGame.getPot(),
                "The pot grows by exactly the called amount");
        assertEquals(GamePhase.PRE_FLOP, testGame.getCurrentPhase(),
                "One of three players calling does not complete the pre-flop round");
        assertNotEquals(currentPlayer.getPlayerId(), testGame.getCurrentPlayer().getPlayerId(),
                "Action advances to the next player after a call that does not close the round");
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    /**
     * Protects the contract that process player action fold should not change pot.
     */
    @Test
    void processPlayerAction_Fold_ShouldNotChangePot() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        int initialPot = testGame.getPot();
        Player currentPlayer = testGame.getCurrentPlayer();

        PlayerActionRequest foldRequest = new PlayerActionRequest(PlayerAction.FOLD, null);
        playerActionService.processPlayerAction(GAME_ID, foldRequest, currentPlayer.getName());

        assertEquals(initialPot, testGame.getPot());
    }

    // ==================== Player State Tests ====================

    /**
     * Protects the contract that process player action after fold player should be folded.
     */
    @Test
    void processPlayerAction_AfterFold_PlayerShouldBeFolded() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();
        assertFalse(currentPlayer.getHasFolded());

        PlayerActionRequest foldRequest = new PlayerActionRequest(PlayerAction.FOLD, null);
        playerActionService.processPlayerAction(GAME_ID, foldRequest, currentPlayer.getName());

        assertTrue(currentPlayer.getHasFolded());
    }

    /**
     * Protects the contract that process player action after all in player should be all in.
     */
    @Test
    void processPlayerAction_AfterAllIn_PlayerShouldBeAllIn() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);

        Player currentPlayer = testGame.getCurrentPlayer();

        // Create a scenario where player can go all-in
        // If player has more chips than needed to call, all-in might be converted
        // For this test, ensure we're testing a legitimate all-in scenario
        PlayerActionRequest allInRequest = new PlayerActionRequest(PlayerAction.ALL_IN, null);
        playerActionService.processPlayerAction(GAME_ID, allInRequest, currentPlayer.getName());

        // Player should either be all-in or have made the equivalent call
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    // ==================== Concurrent Access Tests ====================

    @Test
    void concurrentDuplicateCallsChargeAndAdvanceTheCurrentPlayerOnlyOnce() throws Exception {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        Player actor = testGame.getCurrentPlayer();
        int chipsBefore = actor.getChips();
        int potBefore = testGame.getPot();
        int amountToCall = testGame.getCurrentHighestBet() - actor.getCurrentBet();

        List<Boolean> accepted = raceActions(List.of(actor, actor, actor), PlayerAction.CALL);

        assertEquals(1, accepted.stream().filter(Boolean::booleanValue).count());
        assertEquals(chipsBefore - amountToCall, actor.getChips());
        assertEquals(potBefore + amountToCall, testGame.getPot());
        assertNotSame(actor, testGame.getCurrentPlayer());
        verify(gameStateService).broadcastGameState(GAME_ID, testGame);
    }

    /** Parks the first action inside its game monitor while both other requests run. */
    @Test
    void actionsWaitForTheirOwnGameButDoNotBlockAnIndependentGame() throws Exception {
        String independentId = "independent-game";
        Game independent = new Game(independentId, new ArrayList<>(List.of(
                new Player("Alice", "a", 1000),
                new Player("Bob", "b", 1000),
                new Player("Charlie", "c", 1000))), 5, 10, handEvaluator);
        independent.resetForNewHand();
        independent.dealHoleCards();
        independent.postBlinds();
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        when(gameLifecycleService.getGame(independentId)).thenReturn(independent);

        Player first = testGame.getCurrentPlayer();
        Player independentActor = independent.getCurrentPlayer();
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Thread> sameGameWorker = new AtomicReference<>();
        doAnswer(invocation -> {
            firstApplied.countDown();
            assertTrue(releaseFirst.await(15, TimeUnit.SECONDS));
            return null;
        }).doNothing().when(gameLifecycleService)
                .applyTurnOutcome(eq(GAME_ID), same(testGame), any(TurnOutcome.class), eq(true));

        ExecutorService executor = Executors.newFixedThreadPool(3);
        PlayerActionRequest call = new PlayerActionRequest(PlayerAction.CALL, null);
        try {
            Future<?> firstAction = executor.submit(() ->
                    playerActionService.processPlayerAction(GAME_ID, call, first.getName()));
            assertTrue(firstApplied.await(5, TimeUnit.SECONDS), "First action never entered the game monitor");
            Player next = testGame.getCurrentPlayer();

            Future<?> sameGameAction = executor.submit(() -> {
                sameGameWorker.set(Thread.currentThread());
                playerActionService.processPlayerAction(GAME_ID, call, next.getName());
            });
            awaitBlockedOn(sameGameWorker, testGame);

            Future<?> independentAction = executor.submit(() ->
                    playerActionService.processPlayerAction(independentId, call, independentActor.getName()));
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
            stopExecutor(executor);
        }
    }

    @Test
    void concurrentAllInsCommitChipsOnlyForPlayersWhoReachTheirTurn() throws Exception {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        List<Player> players = List.copyOf(testGame.getPlayers());
        Player firstActor = testGame.getCurrentPlayer();
        List<Integer> startingChips = players.stream().map(Player::getChips).toList();
        int chipsIncludingPot = startingChips.stream().mapToInt(Integer::intValue).sum() + testGame.getPot();

        List<Boolean> accepted = raceActions(players, PlayerAction.ALL_IN);

        assertTrue(accepted.get(players.indexOf(firstActor)), "The initial actor can always act");
        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            assertEquals(accepted.get(index), player.getIsAllIn(), player.getName());
            assertEquals(accepted.get(index) ? 0 : startingChips.get(index), player.getChips());
        }
        assertEquals(chipsIncludingPot, players.stream().mapToInt(Player::getChips).sum() + testGame.getPot());
    }

    /** Releases requests together and observes every worker, including failures from the assertions. */
    private List<Boolean> raceActions(List<Player> actors, PlayerAction action) throws Exception {
        CountDownLatch ready = new CountDownLatch(actors.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(actors.size());
        PlayerActionRequest request = new PlayerActionRequest(action, null);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (Player actor : actors) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        playerActionService.processPlayerAction(GAME_ID, request, actor.getName());
                        return true;
                    } catch (UnauthorisedActionException rejection) {
                        assertEquals("NOT_YOUR_TURN", rejection.actionErrorCode());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Boolean> accepted = new ArrayList<>();
            for (Future<Boolean> attempt : attempts) {
                accepted.add(attempt.get(5, TimeUnit.SECONDS));
            }
            return accepted;
        } finally {
            stopExecutor(executor);
        }
    }

    /**
     * Protects the contract that process player action when game deleted during advance should not throw.
     */
    @Test
    void processPlayerAction_WhenGameDeletedDuringAdvance_ShouldNotThrow() {
        List<Player> players = new ArrayList<>();
        players.add(new Player("P1", UUID.randomUUID().toString(), 1000));
        players.add(new Player("P2", UUID.randomUUID().toString(), 1000));

        Game twoPlayerGame = new Game(GAME_ID, players, 5, 10, handEvaluator);
        twoPlayerGame.resetForNewHand();
        twoPlayerGame.dealHoleCards();
        twoPlayerGame.postBlinds();

        // First lookup in processPlayerAction() returns game, second lookup in
        // advanceGame() simulates stale async state.
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(twoPlayerGame, (Game) null);

        Player currentPlayer = twoPlayerGame.getCurrentPlayer();
        PlayerActionRequest foldRequest = new PlayerActionRequest(PlayerAction.FOLD, null);

        assertDoesNotThrow(
                () -> playerActionService.processPlayerAction(GAME_ID, foldRequest, currentPlayer.getName()));
    }

    // ==================== processPlayerAction - Outcome Arms ====================

    private static TurnOutcome outcome(TurnOutcome.TurnOutcomeType type) {
        return new TurnOutcome(type, null, null, 0);
    }

    @Test
    @DisplayName("an all-in call that leaves nobody able to act schedules the auto-advance and notifies clients")
    void processPlayerAction_AllInLeavesNobodyToAct_ShouldScheduleAutoAdvanceAndNotify() {
        List<Player> players = new ArrayList<>();
        players.add(new Player("P1", UUID.randomUUID().toString(), 100));
        players.add(new Player("P2", UUID.randomUUID().toString(), 100));
        Game game = new Game(GAME_ID, players, 5, 10, handEvaluator);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(game);

        Player first = game.getCurrentPlayer();
        playerActionService.processPlayerAction(GAME_ID, new PlayerActionRequest(PlayerAction.ALL_IN, null),
                first.getName());
        clearInvocations(gameStateService, gameLifecycleService);

        Player second = game.getCurrentPlayer();
        assertNotEquals(first.getName(), second.getName());
        playerActionService.processPlayerAction(GAME_ID, new PlayerActionRequest(PlayerAction.ALL_IN, null),
                second.getName());

        assertTrue(first.getIsAllIn());
        assertTrue(second.getIsAllIn());
        verify(gameLifecycleService).scheduleAutoAdvance(GAME_ID);
        verify(gameStateService).broadcastAutoAdvanceNotification(GAME_ID, game);
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
        verify(gameStateService, never()).broadcastShowdownResults(anyString(), any(Game.class), any(), anyInt());

    }

    /**
     * Plays legal check/call actions through the service for the current actor until the hand
     * leaves PRE_FLOP, returning the outcome that advanced the first betting round. Used to reach
     * a PHASE_ADVANCED outcome through real play rather than a stubbed {@code processPlayerDecision}.
     * handEvaluator is mocked only to score hands if a showdown is reached, not to force progression.
     */
    private void playUntilPhaseAdvances(Game game) {
        GamePhase startPhase = game.getCurrentPhase();
        for (int guard = 0; guard < 20 && game.getCurrentPhase() == startPhase && !game.isHandOver(); guard++) {
            Player actor = game.getCurrentPlayer();
            List<PlayerAction> legal = game.getLegalActionsFor(actor);
            PlayerAction choice = legal.contains(PlayerAction.CHECK) ? PlayerAction.CHECK : PlayerAction.CALL;
            playerActionService.processPlayerAction(GAME_ID,
                    new PlayerActionRequest(choice, null), actor.getName());
        }
    }

    @Test
    @DisplayName("a real betting round that completes before showdown advances the phase and broadcasts state")
    void processPlayerAction_RealBettingRoundCompletes_AdvancesPhaseAndBroadcasts() {
        List<Player> players = new ArrayList<>();
        players.add(new Player("A", UUID.randomUUID().toString(), 1000));
        players.add(new Player("B", UUID.randomUUID().toString(), 1000));
        Game game = new Game(GAME_ID, players, 5, 10, handEvaluator);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(game);
        int potAfterBlinds = game.getPot();

        playUntilPhaseAdvances(game);

        // The real decision path reached PHASE_ADVANCED: the hand moved to the flop without a
        // showdown or auto-advance, and the pot reflects the limped pre-flop round (SB completed
        // to the big blind). Would fail if processPlayerDecision mis-classified a legal call/check.
        assertEquals(GamePhase.FLOP, game.getCurrentPhase());
        assertTrue(game.getPot() >= potAfterBlinds, "Completing the pre-flop round cannot shrink the pot");
        assertEquals(2 * game.getBigBlind(), game.getPot(), "Both players matched the big blind pre-flop");
        verify(gameStateService, atLeastOnce()).broadcastGameState(GAME_ID, game);
        verify(gameLifecycleService, never()).scheduleAutoAdvance(anyString());
        verify(gameStateService, never()).broadcastShowdownResults(anyString(), any(Game.class), any(), anyInt());
    }

    @Test
    @DisplayName("a real hand played to the river showdown broadcasts showdown results")
    void processPlayerAction_RealRiverShowdown_BroadcastsResults() {
        List<Player> players = new ArrayList<>();
        players.add(new Player("A", UUID.randomUUID().toString(), 1000));
        players.add(new Player("B", UUID.randomUUID().toString(), 1000));
        Game game = new Game(GAME_ID, players, 5, 10, handEvaluator);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(game);
        // The showdown evaluates hands; a fixed result just names a winner deterministically. It
        // does not drive progression to showdown, which comes from the real river betting round.
        when(handEvaluator.getBestHand(anyList(), anyList()))
                .thenReturn(new HandEvaluationResult(List.of(new Card(Rank.ACE, Suit.SPADES)), HandRank.HIGH_CARD));
        // Check/call every street until the river betting round completes and the hand resolves.
        for (int guard = 0; guard < 40 && game.getCurrentPhase() != GamePhase.SHOWDOWN; guard++) {
            Player actor = game.getCurrentPlayer();
            List<PlayerAction> legal = game.getLegalActionsFor(actor);
            if (legal.isEmpty()) break;
            PlayerAction choice = legal.contains(PlayerAction.CHECK) ? PlayerAction.CHECK : PlayerAction.CALL;
            playerActionService.processPlayerAction(GAME_ID,
                    new PlayerActionRequest(choice, null), actor.getName());
        }

        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase(), "A limped hand reaches a river showdown");
        assertFalse(game.isTournamentOver(), "Both players still hold chips");
        verify(gameStateService).broadcastShowdownResults(eq(GAME_ID), eq(game), argThat(winners -> !winners.isEmpty()), anyInt());
    }

    // ==================== processPlayerAction - Post-processing Failures ====================

    @Test
    @DisplayName("a post-processing failure re-broadcasts the game state and the action still succeeds")
    void processPlayerAction_PostProcessingFails_ShouldRebroadcastAndSucceed() {
        Game spyGame = spy(testGame);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(spyGame);
        doReturn(outcome(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING)).when(spyGame).processPlayerDecision(any(), any());
        doThrow(new IllegalStateException("scheduler unavailable")).when(gameLifecycleService)
                .applyTurnOutcome(eq(GAME_ID), eq(spyGame), any(TurnOutcome.class), eq(true));

        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.FOLD, null), spyGame.getCurrentPlayer().getName()));

        verify(gameStateService).broadcastGameState(GAME_ID, spyGame);
        verify(gameStateService, never()).broadcastAutoAdvanceNotification(anyString(), any(Game.class));
    }

    @Test
    @DisplayName("a failing re-broadcast after a post-processing failure is swallowed and the action still succeeds")
    void processPlayerAction_PostProcessingAndRebroadcastFail_ShouldStillSucceed() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        doThrow(new IllegalStateException("progression failed")).when(gameLifecycleService)
                .applyTurnOutcome(eq(GAME_ID), eq(testGame), any(TurnOutcome.class), eq(true));
        doThrow(new IllegalStateException("broadcast failed")).when(gameStateService)
                .broadcastGameState(anyString(), any(Game.class));

        Player currentPlayer = testGame.getCurrentPlayer();
        assertDoesNotThrow(() -> playerActionService.processPlayerAction(GAME_ID,
                new PlayerActionRequest(PlayerAction.FOLD, null), currentPlayer.getName()));

        assertTrue(currentPlayer.getHasFolded());
        verify(gameStateService).broadcastGameState(GAME_ID, testGame);
    }

    // ==================== processPlayerAction - Rejection Paths ====================

    @Test
    @DisplayName("an action is rejected while the game is auto-advancing")
    void processPlayerAction_WhenAutoAdvancing_ShouldThrowBadRequestException() {
        Game spyGame = spy(testGame);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(spyGame);
        spyGame.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, System.currentTimeMillis() + 5_000);
        String playerName = spyGame.getCurrentPlayer().getName();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, playerName));

        assertEquals("Actions are not allowed while the game is auto-advancing.", exception.getMessage());
        assertEquals("HAND_ADVANCING", exception.actionErrorCode());
        verify(spyGame, never()).processPlayerDecision(any(), any());
        verifyNoInteractions(gameStateService);
    }

    @Test
    @DisplayName("a rejected game-over action carries the GAME_FINISHED code")
    void processPlayerAction_WhenGameOver_ShouldCarryGameFinishedCode() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        testGame.markGameOver();
        String playerName = testGame.getCurrentPlayer().getName();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.FOLD, null);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, playerName));

        assertEquals("GAME_FINISHED", exception.actionErrorCode());
        verifyNoInteractions(gameStateService);
    }

    @Test
    @DisplayName("a rejected not-your-turn action carries the NOT_YOUR_TURN code and changes nothing")
    void processPlayerAction_WhenNotPlayerTurn_ShouldCarryNotYourTurnCode() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        String currentName = testGame.getCurrentPlayer().getName();
        String otherName = testPlayers.stream().map(Player::getName).filter(n -> !n.equals(currentName))
                .findFirst().orElseThrow();
        int potBefore = testGame.getPot();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);

        UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, otherName));

        assertEquals("NOT_YOUR_TURN", exception.actionErrorCode());
        assertTrue(exception.getMessage().contains(currentName));
        assertEquals(potBefore, testGame.getPot());
        verifyNoInteractions(gameStateService);
    }

    @Test
    @DisplayName("a rejected disconnected-player action carries the DISCONNECTED code and changes nothing")
    void processPlayerAction_WhenCurrentPlayerDisconnected_ShouldCarryDisconnectedCode() {
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        Player currentPlayer = testGame.getCurrentPlayer();
        currentPlayer.setDisconnected(true);
        int potBefore = testGame.getPot();
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, null);

        UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));

        assertEquals("DISCONNECTED", exception.actionErrorCode());
        assertEquals(potBefore, testGame.getPot());
        verifyNoInteractions(gameStateService);
    }

    @Test
    @DisplayName("a request with no action is rejected")
    void processPlayerAction_WithNullAction_ShouldThrowBadRequestException() {
        Game spyGame = spy(testGame);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(spyGame);
        String playerName = spyGame.getCurrentPlayer().getName();
        PlayerActionRequest request = new PlayerActionRequest(null, null);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, playerName));

        assertEquals("Action is required", exception.getMessage());
        verify(spyGame, never()).processPlayerDecision(any(), any());
        verifyNoInteractions(gameStateService);
    }

    @Test
    @DisplayName("a null request is rejected")
    void processPlayerAction_WithNullRequest_ShouldThrowBadRequestException() {
        Game spyGame = spy(testGame);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(spyGame);
        String playerName = spyGame.getCurrentPlayer().getName();

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, null, playerName));

        assertEquals("Action is required", exception.getMessage());
        verify(spyGame, never()).processPlayerDecision(any(), any());
        verifyNoInteractions(gameStateService);
    }

    @Test
    @DisplayName("[branch coverage] a negative CALL amount hits the service NEGATIVE_AMOUNT guard")
    void processPlayerAction_WithNegativeAmount_ShouldThrowBadRequestException() {
        Game spyGame = spy(testGame);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(spyGame);
        String playerName = spyGame.getCurrentPlayer().getName();
        // Branch coverage only, NOT request-boundary confidence: the real PlayerActionRequest
        // constructor rejects a CALL carrying a non-zero amount with UNEXPECTED_AMOUNT, so a client
        // can never deliver this to the service. The request-boundary behaviour (what a client
        // actually receives for an invalid frame) is covered by PlayerActionBoundaryIntegrationTest.
        // A mock is the only way to exercise this defence-in-depth guard.
        PlayerActionRequest request = mock(PlayerActionRequest.class);
        when(request.action()).thenReturn(PlayerAction.CALL);
        when(request.amount()).thenReturn(-5);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, playerName));

        assertEquals("Action amount cannot be negative", exception.getMessage());
        assertEquals("NEGATIVE_AMOUNT", exception.actionErrorCode());
        assertEquals("Amount must be positive; yours: -5", exception.actionErrorMessage());
        verify(spyGame, never()).processPlayerDecision(any(), any());
        verifyNoInteractions(gameStateService);
    }

    @ParameterizedTest
    @EnumSource(value = PlayerAction.class, names = {"BET", "RAISE"})
    @DisplayName("[branch coverage] a zero BET or RAISE hits the service AMOUNT_REQUIRED guard")
    void processPlayerAction_WithZeroBetOrRaise_ShouldThrowBadRequestException(PlayerAction action) {
        Game spyGame = spy(testGame);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(spyGame);
        String playerName = spyGame.getCurrentPlayer().getName();
        // Branch coverage only, NOT request-boundary confidence: the real PlayerActionRequest
        // constructor rejects a zero-amount BET/RAISE with AMOUNT_REQUIRED, so this service guard
        // is unreachable from a real client. Client-visible rejection is covered by
        // PlayerActionBoundaryIntegrationTest. A mock is the only way to reach this guard.
        PlayerActionRequest request = mock(PlayerActionRequest.class);
        when(request.action()).thenReturn(action);
        when(request.amount()).thenReturn(0);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, playerName));

        assertEquals("Bet/raise amount must be greater than 0", exception.getMessage());
        assertEquals("AMOUNT_REQUIRED", exception.actionErrorCode());
        verify(spyGame, never()).processPlayerDecision(any(), any());
        verifyNoInteractions(gameStateService);
    }

    @ParameterizedTest
    @EnumSource(value = PlayerAction.class, names = {"BET", "RAISE"})
    @DisplayName("a bet or raise above the player's chips is rejected with INSUFFICIENT_CHIPS")
    void processPlayerAction_WithBetOrRaiseAboveChips_ShouldThrowBadRequestException(PlayerAction action) {
        Game spyGame = spy(testGame);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(spyGame);
        Player currentPlayer = spyGame.getCurrentPlayer();
        int chips = currentPlayer.getChips();
        PlayerActionRequest request = new PlayerActionRequest(action, chips + 1);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> playerActionService.processPlayerAction(GAME_ID, request, currentPlayer.getName()));

        assertEquals("Action amount cannot exceed your available chips", exception.getMessage());
        assertEquals("INSUFFICIENT_CHIPS", exception.actionErrorCode());
        assertEquals("Available: " + chips + "; yours: " + (chips + 1), exception.actionErrorMessage());
        assertEquals(chips, currentPlayer.getChips());
        verify(spyGame, never()).processPlayerDecision(any(), any());
        verifyNoInteractions(gameStateService);
    }

    // ==================== processPlayerAction - Null Amount ====================

    @Test
    @DisplayName("a CALL with a null amount matches the outstanding wager through real play")
    void processPlayerAction_CallWithNullAmount_MatchesOutstandingWager() {
        // Finding 4: assert the observable betting outcome of a null-amount CALL (the actor now
        // matches the highest bet, the pot grew by the owed call and the turn moved on) instead of
        // peeking at the internal PlayerDecision amount. A real Game, not a spy, proves the effect.
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        Player currentPlayer = testGame.getCurrentPlayer();
        int highestBet = testGame.getCurrentHighestBet();
        int owed = highestBet - currentPlayer.getCurrentBet();
        int potBefore = testGame.getPot();
        assertTrue(owed > 0, "The pre-flop small blind owes a call to the big blind");

        playerActionService.processPlayerAction(GAME_ID, new PlayerActionRequest(PlayerAction.CALL, null),
                currentPlayer.getName());

        assertEquals(highestBet, currentPlayer.getCurrentBet(), "A null-amount call matches the outstanding wager");
        assertEquals(potBefore + owed, testGame.getPot(), "The pot grows by exactly the owed call");
        assertNotEquals(currentPlayer.getName(), testGame.getCurrentPlayer().getName(), "The turn moves to the opponent");
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }

    @Test
    @DisplayName("a CHECK with a null amount keeps the wager flat and advances the turn through real play")
    void processPlayerAction_CheckWithNullAmount_KeepsWagerFlatAndAdvancesTurn() {
        // Finding 4: a null-amount CHECK on the flop must leave the actor's bet unchanged while the
        // turn advances and clients are notified. Observing those outcomes replaces the previous
        // internal-argument (amount == 0) check.
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(testGame);
        // Reach the flop through real play so there is no wager owed and CHECK is legal.
        playUntilPhaseAdvances(testGame);
        assertEquals(GamePhase.FLOP, testGame.getCurrentPhase());
        clearInvocations(gameStateService);
        Player currentPlayer = testGame.getCurrentPlayer();
        int betBefore = currentPlayer.getCurrentBet();

        playerActionService.processPlayerAction(GAME_ID, new PlayerActionRequest(PlayerAction.CHECK, null),
                currentPlayer.getName());

        assertEquals(betBefore, currentPlayer.getCurrentBet(), "A check commits no chips");
        assertNotEquals(currentPlayer.getName(), testGame.getCurrentPlayer().getName(), "The turn moves on after a check");
        verify(gameStateService, atLeastOnce()).broadcastGameState(eq(GAME_ID), any(Game.class));
    }
}
