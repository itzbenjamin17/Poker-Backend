package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ReadyCountdownStartOutcome;
import com.pokergame.enums.ReadyCountdownTimeoutOutcome;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the {@link Game} aggregate root across lifecycle, betting, showdown settlement,
 * seating reconciliation, ready-gate, and scheduling behaviors.
 */
@Tag("unit")
@DisplayName("Game model")
class GameTest {

    private static final List<Card> ROYAL_FLUSH_BOARD = List.of(
            new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
            new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.KING, Suit.SPADES),
            new Card(Rank.ACE, Suit.SPADES));

    private List<Player> players;
    private Game game;

    @BeforeEach
    void setUp() {
        players = new ArrayList<>();
        players.add(new Player("Player1", "p1", 1000));
        players.add(new Player("Player2", "p2", 1000));
        players.add(new Player("Player3", "p3", 1000));

        game = createGame("game123", players, 10, 20);
    }

    private static Game createGame(
            String gameId,
            List<Player> tablePlayers,
            int smallBlind,
            int bigBlind) {
        int maxPlayers = tablePlayers == null ? 2 : Math.max(2, tablePlayers.size());
        return new Game(gameId, tablePlayers, maxPlayers, smallBlind, bigBlind);
    }

    private static Game createStandardGame(int numPlayers, int chips) {
        List<Player> list = new ArrayList<>();
        for (int i = 0; i < numPlayers; i++) {
            list.add(new Player("Player_" + i, "p" + i, chips));
        }
        return createGame("game-" + UUID.randomUUID(), list, 10, 20);
    }

    private static Player restoredPlayer(String name, String id, int chips, int currentBet, boolean allIn) {
        return Player.restoreBuilder()
                .name(name)
                .playerId(id)
                .chips(chips)
                .currentBet(currentBet)
                .holeCards(List.of())
                .bestHand(List.of())
                .handRank(HandRank.NO_HAND)
                .hasFolded(false)
                .allIn(allIn)
                .out(false)
                .disconnected(false)
                .readyForNextHand(false)
                .build();
    }

    private static Player restoredPlayer(
            String name, String id, int chips, boolean isOut, boolean hasFolded, boolean isAllIn) {
        return Player.restoreBuilder()
                .playerId(id)
                .name(name)
                .chips(chips)
                .currentBet(0)
                .holeCards(List.of())
                .bestHand(List.of())
                .handRank(HandRank.NO_HAND)
                .hasFolded(hasFolded)
                .allIn(isAllIn)
                .out(isOut)
                .disconnected(false)
                .readyForNextHand(false)
                .build();
    }

    private Game restoredBettingGame(
            String id,
            List<Player> handPlayers,
            int pot,
            int highestBet,
            int currentPlayerPosition,
            Map<String, Integer> contributions,
            int lastFullRaise,
            Map<String, Integer> previousBets) {
        return Game.restoreBuilder()
                .gameId(id)
                .players(handPlayers)
                .activePlayerIds(handPlayers.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(pot)
                .dealerPlayerId(handPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(handPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(handPlayers.get(Math.min(1, handPlayers.size() - 1)).getPlayerId())
                .currentPlayerId(handPlayers.get(currentPlayerPosition).getPlayerId())
                .currentHighestBet(highestBet)
                .currentPhase(GamePhase.PRE_FLOP)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(contributions)
                .carryOverPot(0)
                .lastFullRaise(lastFullRaise)
                .previousBets(previousBets)
                .allPlayersHadInitialTurn(false)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
                .build();
    }

    private Game livePotWithFoldedBlindAndCarryOver(int pot, int carryOver) {
        Player shortStack = restoredPlayer("Short", "short-" + pot, 0, 50, true);
        Player bettor = restoredPlayer("Bettor", "bettor-" + pot, 800, 250, false);
        Player owing = restoredPlayer("Owing", "owing-" + pot, 950, 50, false);
        Player foldedBlind = Player.restoreBuilder()
                .name("Folded BB")
                .playerId("folded-" + pot)
                .chips(980)
                .holeCards(List.of())
                .bestHand(List.of())
                .handRank(HandRank.NO_HAND)
                .currentBet(20)
                .hasFolded(true)
                .allIn(false)
                .out(false)
                .disconnected(false)
                .readyForNextHand(false)
                .build();
        String shortId = shortStack.getPlayerId();
        String bettorId = bettor.getPlayerId();
        String owingId = owing.getPlayerId();
        String foldedId = foldedBlind.getPlayerId();
        return Game.restoreBuilder()
                .gameId("live-folded-cap-" + pot)
                .players(List.of(shortStack, bettor, owing, foldedBlind))
                .activePlayerIds(List.of(shortId, bettorId, owingId, foldedId))
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(pot)
                .dealerPlayerId(shortId)
                .smallBlindPlayerId(shortId)
                .bigBlindPlayerId(bettorId)
                .currentPlayerId(owingId)
                .currentHighestBet(250)
                .currentPhase(GamePhase.FLOP)
                .gameOver(false)
                .smallBlind(10)
                .bigBlind(20)
                .handContributions(Map.of(shortId, 50, bettorId, 250, owingId, 50, foldedId, 20))
                .carryOverPot(carryOver)
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
                .build();
    }

    @Nested
    class CreationAndLifecycleTests {

        @Test
        void testGameCreation() {
            assertNotNull(game);
            assertEquals("game123", game.getGameId());
            assertEquals(3, game.getPlayers().size());
            assertEquals(3, game.getActivePlayers().size());
            assertNotNull(game.getCurrentPlayer());
            assertNotNull(game.getCommunityCards());
            assertEquals(0, game.getPot());
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertFalse(game.isGameOver());
            assertEquals(0, game.getCurrentHighestBet());
            assertEquals("p1", game.toSnapshot().dealerPlayerId());
        }

        @Test
        void testGameCreationValidationRejectsInvalidInputs() {
            IllegalStateException nullId = assertThrows(IllegalStateException.class,
                    () -> new Game(null, players, 3, 10, 20));
            assertEquals("Game ID cannot be null or empty", nullId.getMessage());

            IllegalStateException blankId = assertThrows(IllegalStateException.class,
                    () -> new Game("   ", players, 3, 10, 20));
            assertEquals("Game ID cannot be null or empty", blankId.getMessage());

            List<Player> onePlayer = List.of(new Player("Solo", "p1", 1000));
            IllegalStateException tooFew = assertThrows(IllegalStateException.class,
                    () -> new Game("game123", onePlayer, 2, 10, 20));
            assertEquals("At least 2 players are required to start a game", tooFew.getMessage());

            IllegalStateException nullList = assertThrows(IllegalStateException.class,
                    () -> new Game("game123", null, 2, 10, 20));
            assertEquals("At least 2 players are required to start a game", nullList.getMessage());

            List<Player> playersWithNull = new ArrayList<>();
            playersWithNull.add(new Player("Player1", "p1", 1000));
            playersWithNull.add(null);
            IllegalStateException nullElement = assertThrows(IllegalStateException.class,
                    () -> new Game("game123", playersWithNull, 2, 10, 20));
            assertEquals("Invalid players list. Please try again.", nullElement.getMessage());

            IllegalStateException lowMax = assertThrows(IllegalStateException.class,
                    () -> new Game("game123", players, 1, 10, 20));
            assertEquals("Max players must be between 2 and 10.", lowMax.getMessage());

            IllegalStateException highMax = assertThrows(IllegalStateException.class,
                    () -> new Game("game123", players, 11, 10, 20));
            assertEquals("Max players must be between 2 and 10.", highMax.getMessage());
        }

        @Test
        void processPlayerDecisionRejectsNullActionWithBadRequestException() {
            game.postBlinds();
            Player player = game.getActivePlayers().getFirst();

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> game.processPlayerDecision(player, null, 0));
            assertEquals("No action specified. Please select an action", ex.getMessage());
        }

        @Test
        void testDealHoleCards() {
            game.dealHoleCards();

            for (Player player : game.getActivePlayers()) {
                assertEquals(2, player.getHoleCards().size());
            }
        }

        @Test
        void testPostBlindsStandardAndShortStacks() {
            game.postBlinds();
            Player sb = game.getActivePlayers().get(1);
            Player bb = game.getActivePlayers().get(2);
            assertEquals(10, sb.getCurrentBet());
            assertEquals(20, bb.getCurrentBet());
            assertEquals(990, sb.getChips());
            assertEquals(980, bb.getChips());
            assertEquals(30, game.getPot());
            assertEquals(20, game.getCurrentHighestBet());

            Game shortSbGame = createGame("short-sb-game", List.of(
                    new Player("Player1", "p1", 1000),
                    new Player("ShortSB", "p2", 5),
                    new Player("BigBlind", "p3", 1000)), 10, 20);
            shortSbGame.postBlinds();
            assertTrue(shortSbGame.getActivePlayers().get(1).isAllIn());
            assertEquals(0, shortSbGame.getActivePlayers().get(1).getChips());
            assertEquals(5, shortSbGame.getActivePlayers().get(1).getCurrentBet());
            assertEquals(20, shortSbGame.getActivePlayers().get(2).getCurrentBet());
            assertEquals(25, shortSbGame.getPot());
            assertEquals(20, shortSbGame.getCurrentHighestBet());

            Game shortBbGame = createGame("short-bb-game", List.of(
                    new Player("Player1", "p1", 1000),
                    new Player("SmallBlind", "p2", 1000),
                    new Player("ShortBB", "p3", 15)), 10, 20);
            shortBbGame.postBlinds();
            assertEquals(10, shortBbGame.getActivePlayers().get(1).getCurrentBet());
            assertEquals(990, shortBbGame.getActivePlayers().get(1).getChips());
            assertTrue(shortBbGame.getActivePlayers().get(2).isAllIn());
            assertEquals(15, shortBbGame.getActivePlayers().get(2).getCurrentBet());
            assertEquals(0, shortBbGame.getActivePlayers().get(2).getChips());
            assertEquals(25, shortBbGame.getPot());
            assertEquals(15, shortBbGame.getCurrentHighestBet());

            Game bothShortGame = createGame("both-short-game", List.of(
                    new Player("Player1", "p1", 1000),
                    new Player("ShortSB", "p2", 7),
                    new Player("ShortBB", "p3", 15)), 10, 20);
            bothShortGame.postBlinds();
            assertTrue(bothShortGame.getActivePlayers().get(1).isAllIn());
            assertTrue(bothShortGame.getActivePlayers().get(2).isAllIn());
            assertEquals(7, bothShortGame.getActivePlayers().get(1).getCurrentBet());
            assertEquals(15, bothShortGame.getActivePlayers().get(2).getCurrentBet());
            assertEquals(22, bothShortGame.getPot());
            assertEquals(15, bothShortGame.getCurrentHighestBet());
        }

        @Test
        void postBlindsThrowsWhenFewerThanTwoActivePlayers() {
            Game singleActive = Game.restoreBuilder()
                    .gameId("single-active-blinds")
                    .players(List.of(new Player("Alice", "p1", 1000), new Player("Bob", "p2", 0)))
                    .activePlayerIds(List.of("p1"))
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, singleActive::postBlinds);
            assertEquals("Trying to post blinds for a game single-active-blinds which has less than 2 players",
                    ex.getMessage());
        }

        @Test
        void testDealStreetsAndResetBetsAcrossRounds() {
            game.postBlinds();
            assertEquals(20, game.getCurrentHighestBet());

            game.dealFlop();
            assertEquals(3, game.getCommunityCards().size());
            assertEquals(GamePhase.FLOP, game.getCurrentPhase());
            assertEquals(0, game.getCurrentHighestBet());
            for (Player player : game.getActivePlayers()) {
                assertEquals(0, player.getCurrentBet());
            }

            game.dealTurn();
            assertEquals(4, game.getCommunityCards().size());
            assertEquals(GamePhase.TURN, game.getCurrentPhase());

            game.dealRiver();
            assertEquals(5, game.getCommunityCards().size());
            assertEquals(GamePhase.RIVER, game.getCurrentPhase());

            game.conductShowdown();
            assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        }

        @Test
        void testFoldEndingHandSettlesSingleContender() {
            game.postBlinds();
            Player utg = game.getCurrentPlayer();
            TurnOutcome firstFold = game.processPlayerDecision(
                    utg, PlayerAction.FOLD, 0);
            assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, firstFold.type());

            Player sb = game.getCurrentPlayer();
            TurnOutcome secondFold = game.processPlayerDecision(
                    sb, PlayerAction.FOLD, 0);
            assertEquals(TurnOutcome.TurnOutcomeType.SHOWDOWN, secondFold.type());
            assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
            assertEquals(1010, game.getPlayers().get(2).getChips());
        }

        @Test
        void testResetForNewHandClearsHandStateAndAdvancesDealer() {
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();

            Player playerOne = game.getPlayers().get(0);
            Player playerTwo = game.getPlayers().get(1);
            Player playerThree = game.getPlayers().get(2);

            playerOne.doAction(PlayerAction.FOLD, 0);
            playerTwo.setBestHand(List.of(new Card(Rank.ACE, Suit.SPADES)));
            playerTwo.setHandRank(HandRank.ONE_PAIR);
            playerThree.doAction(PlayerAction.BET, 100);

            String dealerBeforeReset = game.toSnapshot().dealerPlayerId();
            int dealerIndexBeforeReset = game.getActivePlayers().stream()
                    .map(Player::getPlayerId).toList().indexOf(dealerBeforeReset);
            int potBeforeReset = game.getPot();
            List<Integer> chipCountsBeforeReset = game.getPlayers().stream()
                    .map(Player::getChips)
                    .toList();

            boolean gameEnded = game.resetForNewHand();

            assertFalse(gameEnded);
            assertEquals(0, game.getCommunityCards().size());
            assertEquals(potBeforeReset, game.getPot());
            assertEquals(0, game.getCurrentHighestBet());
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertEquals(players.size(), game.getActivePlayers().size());
            assertEquals(players.get((dealerIndexBeforeReset + 1) % players.size()).getPlayerId(),
                    game.toSnapshot().dealerPlayerId());
            assertEquals(chipCountsBeforeReset, game.getPlayers().stream().map(Player::getChips).toList());

            for (Player player : game.getPlayers()) {
                assertEquals(0, player.getHoleCards().size());
                assertEquals(0, player.getBestHand().size());
                assertEquals(HandRank.NO_HAND, player.getHandRank());
                assertEquals(0, player.getCurrentBet());
                assertFalse(player.hasFolded());
                assertFalse(player.isAllIn());
            }
        }

        @Test
        void testResetForNewHandEliminatesZeroChipPlayersAndEndsGameWhenOneRemains() {
            Player first = game.getActivePlayers().getFirst();
            first.doAction(PlayerAction.ALL_IN, 0);
            assertFalse(game.resetForNewHand());
            assertTrue(first.isOut());
            assertEquals(2, game.getActivePlayers().size());
            assertFalse(game.getActivePlayers().contains(first));
            assertFalse(game.isGameOver());

            Player second = game.getActivePlayers().getFirst();
            second.doAction(PlayerAction.ALL_IN, 0);
            assertTrue(game.resetForNewHand());
            assertEquals(1, game.getActivePlayers().size());
            assertTrue(game.isGameOver());
        }

        @Test
        void testMarkGameOverAndResolveWinnerByChipCountAndIndexTiebreak() {
            assertFalse(game.isGameOver());
            game.markGameOver();
            assertTrue(game.isGameOver());

            players.get(0).doAction(PlayerAction.BET, 1000);
            players.get(0).setIsOut();

            Player winner = game.resolveWinner(null);
            assertNotNull(winner);
            assertEquals("Player2", winner.getName());

            players.get(2).addChips(1000);
            winner = game.resolveWinner(null);
            assertEquals("Player3", winner.getName());
        }

        @Test
        void resetForNewHand_ClearsActedPlayersInRound() {
            game.postBlinds();
            Player actor = game.getCurrentPlayer();
            game.processPlayerDecision(actor, PlayerAction.CALL, 0);
            assertFalse(game.toSnapshot().actedPlayerIds().isEmpty());

            assertFalse(game.resetForNewHand());
            assertTrue(game.toSnapshot().actedPlayerIds().isEmpty());
        }

        @Test
        void resetForNewHand_ClearsPerHandAttributesOnBustedPlayers() {
            Player busted = game.getActivePlayers().getFirst();
            busted.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
            busted.doAction(PlayerAction.ALL_IN, 0);
            busted.setHandRank(HandRank.ONE_PAIR);
            busted.setBestHand(List.of(new Card(Rank.ACE, Suit.SPADES)));

            assertFalse(game.resetForNewHand());
            assertTrue(busted.isOut());
            assertEquals(0, busted.getChips());
            assertTrue(busted.getHoleCards().isEmpty());
            assertEquals(0, busted.getCurrentBet());
            assertFalse(busted.isAllIn());
            assertEquals(HandRank.NO_HAND, busted.getHandRank());
            assertTrue(busted.getBestHand().isEmpty());
        }
    }

    @Nested
    class BettingAndTurnProgressionTests {

        @Test
        void testProcessPlayerDecisionFold() {
            Player player = game.getCurrentPlayer();
            game.processPlayerDecision(player, PlayerAction.FOLD, 0);
            assertTrue(player.hasFolded());
            assertEquals(0, game.getPot());
        }

        @Test
        void testProcessPlayerDecisionCheckAndCheckWithActiveBet() {
            Player player = game.getCurrentPlayer();
            game.processPlayerDecision(player, PlayerAction.CHECK, 0);
            assertFalse(player.hasFolded());
            assertEquals(0, game.getPot());
            assertEquals(0, player.getCurrentBet());

            Game activeBetGame = createStandardGame(3, 1000);
            activeBetGame.postBlinds();
            Player actor = activeBetGame.getCurrentPlayer();
            UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class, () ->
                    activeBetGame.processPlayerDecision(actor, PlayerAction.CHECK, 0));
            assertTrue(exception.getMessage().contains("Cannot check when there is an active bet"));
        }

        @Test
        void testProcessPlayerDecisionBetAndBetWithActiveBet() {
            Player player = game.getCurrentPlayer();
            game.processPlayerDecision(player, PlayerAction.BET, 50);
            assertEquals(50, game.getPot());
            assertEquals(50, player.getCurrentBet());
            assertEquals(950, player.getChips());
            assertEquals(50, game.getCurrentHighestBet());

            Game activeBetGame = createStandardGame(3, 1000);
            activeBetGame.postBlinds();
            Player actor = activeBetGame.getCurrentPlayer();
            UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class, () ->
                    activeBetGame.processPlayerDecision(actor, PlayerAction.BET, 50));
            assertTrue(exception.getMessage().contains("Cannot bet when there is an active bet"));
        }

        @Test
        void testProcessPlayerDecisionCallRaiseAllInAndInvalidRaise() {
            game.postBlinds();
            Player caller = game.getActivePlayers().getFirst();
            game.processPlayerDecision(caller, PlayerAction.CALL, 0);
            assertEquals(50, game.getPot());
            assertEquals(20, caller.getCurrentBet());
            assertEquals(980, caller.getChips());

            Game raiseGame = createStandardGame(3, 1000);
            raiseGame.postBlinds();
            Player raiser = raiseGame.getActivePlayers().getFirst();
            raiseGame.processPlayerDecision(raiser, PlayerAction.RAISE, 50);
            assertEquals(80, raiseGame.getPot());
            assertEquals(50, raiser.getCurrentBet());
            assertEquals(950, raiser.getChips());
            assertEquals(50, raiseGame.getCurrentHighestBet());

            Game allInGame = createStandardGame(3, 1000);
            Player shover = allInGame.getCurrentPlayer();
            allInGame.processPlayerDecision(shover, PlayerAction.ALL_IN, 0);
            assertEquals(1000, allInGame.getPot());
            assertEquals(1000, shover.getCurrentBet());
            assertEquals(0, shover.getChips());
            assertTrue(shover.isAllIn());

            Game invalidRaiseGame = createStandardGame(3, 1000);
            invalidRaiseGame.postBlinds();
            Player badRaiser = invalidRaiseGame.getActivePlayers().getFirst();
            badRaiser.doAction(PlayerAction.BET, 10);
            BadRequestException nonIncreasing = assertThrows(BadRequestException.class, () ->
                    invalidRaiseGame.processPlayerDecision(
                            badRaiser, PlayerAction.RAISE, 5));
            assertEquals("MIN_RAISE", nonIncreasing.actionErrorCode());
        }

        @Test
        void nonIncreasingRaiseReportsFullIncrementalMinimum() {
            game.postBlinds();
            Player player = game.getActivePlayers().getFirst();
            BadRequestException exception = assertThrows(BadRequestException.class, () ->
                    game.processPlayerDecision(player, PlayerAction.RAISE, 1));
            assertEquals("MIN_RAISE", exception.actionErrorCode());
            assertEquals("The minimum raise is 40 chips (you entered 1).", exception.actionErrorMessage());
        }

        @Test
        void fullRaiseIncrementSetsNextMinimumAtTenToFiftyToNinety() {
            Player a = restoredPlayer("A", "a", 900, 10, false);
            Player b = restoredPlayer("B", "b", 900, 10, false);
            Player c = restoredPlayer("C", "c", 900, 10, false);
            Game hand = restoredBettingGame("raise-increment", List.of(a, b, c), 30, 10, 0,
                    Map.of("a", 10, "b", 10, "c", 10), 0, Map.of());

            hand.processPlayerDecision(a, PlayerAction.RAISE, 40);
            assertEquals(50, a.getCurrentBet());
            assertEquals(40, hand.toSnapshot().lastFullRaise());
            assertEquals(80, hand.getMinimumRaiseAmount(b));

            int bChipsBefore = b.getChips();
            BadRequestException tooSmall = assertThrows(BadRequestException.class, () ->
                    hand.processPlayerDecision(b, PlayerAction.RAISE, 10));
            assertEquals("MIN_RAISE", tooSmall.actionErrorCode());
            assertEquals(bChipsBefore, b.getChips());
            assertEquals(50, hand.getCurrentHighestBet());

            hand.processPlayerDecision(b, PlayerAction.RAISE, 80);
            assertEquals(90, b.getCurrentBet());
            assertEquals(90, hand.getCurrentHighestBet());
        }

        @Test
        void minimumRaiseAtShowdownFallsBackToTheBigBlindEvenAfterARaise() {
            Player a = restoredPlayer("A", "a", 900, 10, false);
            Player b = restoredPlayer("B", "b", 900, 50, false);
            Game hand = restoredBettingGame("showdown-min-raise", List.of(a, b), 60, 50, 0,
                    Map.of("a", 10, "b", 50), 40, Map.of("b", 50));

            hand.processPlayerDecision(a, PlayerAction.FOLD, 0);

            assertEquals(GamePhase.SHOWDOWN, hand.getCurrentPhase());
            int owed = Math.max(0, hand.getCurrentHighestBet() - b.getCurrentBet());
            assertEquals(owed + 10, hand.getMinimumRaiseAmount(b));
        }

        @Test
        void shortAllInDoesNotReopenAlreadyActedPlayerButCumulativeShortRaisesDo() {
            Player a = restoredPlayer("A", "a", 900, 100, false);
            Player b = restoredPlayer("B", "b", 900, 100, false);
            Player shortOne = restoredPlayer("Short1", "s1", 0, 150, true);
            Player shortTwo = restoredPlayer("Short2", "s2", 0, 200, true);
            Game hand = restoredBettingGame("short-raise-reopening", List.of(a, b, shortOne, shortTwo), 550, 200, 0,
                    Map.of("a", 100, "b", 100, "s1", 150, "s2", 200), 100, Map.of("a", 100));

            assertTrue(hand.getLegalActionsFor(a).contains(PlayerAction.RAISE));
            assertTrue(hand.getLegalActionsFor(a).contains(PlayerAction.ALL_IN));
        }

        @Test
        void oneShortAllInLeavesRaiseClosedButAllowsCallOrCallingAllIn() {
            Player a = restoredPlayer("A", "a", 900, 100, false);
            Player shortOne = restoredPlayer("Short", "s", 0, 150, true);
            Player b = restoredPlayer("B", "b", 900, 100, false);
            Game hand = restoredBettingGame("short-raise-closed", List.of(a, shortOne, b), 350, 150, 0,
                    Map.of("a", 100, "s", 150, "b", 100), 100, Map.of("a", 100));

            assertFalse(hand.getLegalActionsFor(a).contains(PlayerAction.RAISE));
            assertFalse(hand.getLegalActionsFor(a).contains(PlayerAction.ALL_IN));
            assertTrue(hand.getLegalActionsFor(a).contains(PlayerAction.CALL));

            UnauthorisedActionException closed = assertThrows(UnauthorisedActionException.class, () ->
                    hand.processPlayerDecision(a, PlayerAction.ALL_IN, 0));
            assertEquals("RAISE_NOT_REOPENED", closed.actionErrorCode());
            assertEquals(100, a.getCurrentBet());
            assertEquals(900, a.getChips());

            Player shortA = restoredPlayer("A", "a", 50, 100, false);
            Game shortHand = restoredBettingGame("short-call-allin",
                    List.of(shortA, restoredPlayer("Short", "s", 0, 150, true), restoredPlayer("B", "b", 900, 100, false)),
                    350, 150, 0, Map.of("a", 100, "s", 150, "b", 100), 100, Map.of("a", 100));
            assertTrue(shortHand.getLegalActionsFor(shortA).contains(PlayerAction.ALL_IN));
            shortHand.processPlayerDecision(shortA, PlayerAction.ALL_IN, 0);
            assertEquals(150, shortA.getCurrentBet());
            assertTrue(shortA.isAllIn());
        }

        @Test
        void legalActionsOmitRaiseWhenOnlyCallFitsButAllowShortWholeStackRaise() {
            Player exactCaller = restoredPlayer("Exact", "exact", 80, 20, false);
            Player wagerLeader = restoredPlayer("Leader", "leader", 900, 100, false);
            Player other = restoredPlayer("Other", "other", 900, 100, false);
            Game exactCall = restoredBettingGame("exact-call-actions", List.of(exactCaller, wagerLeader, other), 220, 100, 0,
                    Map.of("exact", 20, "leader", 100, "other", 100), 100, Map.of());

            List<PlayerAction> exactActions = exactCall.getLegalActionsFor(exactCaller);
            assertFalse(exactActions.contains(PlayerAction.CALL));
            assertTrue(exactActions.contains(PlayerAction.ALL_IN));
            assertFalse(exactActions.contains(PlayerAction.RAISE));
            exactCall.processPlayerDecision(exactCaller, PlayerAction.ALL_IN, 0);
            assertEquals(100, exactCaller.getCurrentBet());

            Player shortRaiser = restoredPlayer("ShortRaise", "shortRaise", 90, 20, false);
            Player leader2 = restoredPlayer("Leader2", "leader2", 900, 100, false);
            Player other2 = restoredPlayer("Other2", "other2", 900, 100, false);
            Game shortRaise = restoredBettingGame("short-all-in-raise-actions", List.of(shortRaiser, leader2, other2), 220, 100, 0,
                    Map.of("shortRaise", 20, "leader2", 100, "other2", 100), 100, Map.of());

            assertTrue(shortRaise.getLegalActionsFor(shortRaiser).contains(PlayerAction.RAISE));
            assertTrue(shortRaise.getLegalActionsFor(shortRaiser).contains(PlayerAction.ALL_IN));
            shortRaise.processPlayerDecision(shortRaiser, PlayerAction.ALL_IN, 0);
            assertEquals(110, shortRaiser.getCurrentBet());
            assertTrue(shortRaiser.isAllIn());
        }

        @Test
        void callWithoutOwedChipsAndRaiseWithoutWagerRejectBeforeMutation() {
            Player actor = game.getCurrentPlayer();
            int startingChips = actor.getChips();
            assertThrows(UnauthorisedActionException.class, () ->
                    game.processPlayerDecision(actor, PlayerAction.CALL, 0));
            assertThrows(UnauthorisedActionException.class, () ->
                    game.processPlayerDecision(actor, PlayerAction.RAISE, 10));
            assertEquals(startingChips, actor.getChips());
            assertEquals(0, game.getPot());
            assertEquals(0, game.getCurrentHighestBet());
        }

        @Test
        void forcedAllInRunoutDistinguishesMatchedStateFromGenuineOwedCall() {
            Player allIn = restoredPlayer("AllIn", "ai", 0, 10, true);
            Player matched = restoredPlayer("Matched", "matched", 100, 10, false);
            Game matchedGame = Game.restoreBuilder()
                    .gameId("runout-matched")
                    .players(List.of(allIn, matched))
                    .activePlayerIds(List.of("ai", "matched"))
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(20)
                    .dealerPlayerId("ai")
                    .smallBlindPlayerId("ai")
                    .bigBlindPlayerId("matched")
                    .currentPlayerId("matched")
                    .currentHighestBet(10)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .gameOver(false)
                    .smallBlind(5)
                    .bigBlind(10)
                    .handContributions(Map.of("ai", 10, "matched", 10))
                    .carryOverPot(0)
                    .allPlayersHadInitialTurn(false)
                    .actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of())
                    
                    .build();
            assertEquals(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, matchedGame.advanceAfterBlinds().type());

            Player owing = restoredPlayer("Owing", "owing", 100, 0, false);
            Game owingGame = Game.restoreBuilder()
                    .gameId("runout-owed-call")
                    .players(List.of(allIn, owing))
                    .activePlayerIds(List.of("ai", "owing"))
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(10)
                    .dealerPlayerId("ai")
                    .smallBlindPlayerId("ai")
                    .bigBlindPlayerId("owing")
                    .currentPlayerId("owing")
                    .currentHighestBet(10)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .gameOver(false)
                    .smallBlind(5)
                    .bigBlind(10)
                    .handContributions(Map.of("ai", 10))
                    .carryOverPot(0)
                    .allPlayersHadInitialTurn(false)
                    .actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of())
                    .build();
            assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, owingGame.advanceAfterBlinds().type());
        }

        @Test
        void advanceAfterBlindsSkipsCurrentPlayerWhoWentAllInPostingBlinds() {
            Player dealerAllIn = restoredPlayer("DealerSB", "p1", 0, 5, true);
            Player deepBb = restoredPlayer("BigBlind", "p2", 990, 10, false);
            Player utg = restoredPlayer("UTG", "p3", 1000, 0, false);
            Game blindAllInGame = Game.restoreBuilder()
                    .gameId("blind-allin-turn-skip")
                    .players(List.of(dealerAllIn, deepBb, utg))
                    .activePlayerIds(List.of("p1", "p2", "p3"))
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(15)
                    .dealerPlayerId("p1")
                    .smallBlindPlayerId("p1")
                    .bigBlindPlayerId("p2")
                    .currentPlayerId("p1")
                    .currentHighestBet(10)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .gameOver(false)
                    .smallBlind(5)
                    .bigBlind(10)
                    .handContributions(Map.of("p1", 5, "p2", 10))
                    .carryOverPot(0)
                    .allPlayersHadInitialTurn(false)
                    .actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of())
                    .build();

            TurnOutcome outcome = blindAllInGame.advanceAfterBlinds();

            assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, outcome.type());
            assertEquals("p2", blindAllInGame.getCurrentPlayer().getPlayerId());
        }

        @Test
        void departureLeavingOneContenderSettlesHandWithoutAnotherAction() {
            Player a = restoredPlayer("A", "a", 950, 50, false);
            Player folded = restoredPlayer("Folded", "f", 950, false, true, false);
            Player leaving = restoredPlayer("Leaving", "leaving", 1000, 0, false);
            Game hand = Game.restoreBuilder()
                    .gameId("leave-finishes-hand")
                    .players(List.of(a, folded, leaving))
                    .activePlayerIds(List.of("a", "f", "leaving"))
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(100)
                    .smallBlindPlayerId("f")
                    .bigBlindPlayerId("leaving")
                    .currentPlayerId("leaving")
                    .currentHighestBet(0)
                    .currentPhase(GamePhase.FLOP)
                    .gameOver(false)
                    .smallBlind(5)
                    .bigBlind(10)
                    .handContributions(Map.of("a", 50, "f", 50))
                    .carryOverPot(0)
                    .handSeatOrder(List.of("a", "f", "leaving"))
                    .dealerPlayerId("a")
                    .allPlayersHadInitialTurn(false)
                    .actedPlayersInRound(Set.of("a", "f"))
                    .scheduledTimes(Map.of())
                    
                    .build();

            hand.removePlayer(leaving);
            TurnOutcome outcome = hand.advanceAfterPlayerRemoval();

            assertEquals(TurnOutcome.TurnOutcomeType.SHOWDOWN, outcome.type());
            assertEquals(Set.of("a"), hand.getLastHandPayout().winnerIds());
            assertEquals(1050, a.getChips());
            assertEquals(950, folded.getChips());
            assertEquals(0, hand.getPot());
            assertTrue(hand.isHandSettled());
        }

        @Test
        void riverCallThatCompletesBettingReturnsShowdownImmediately() {
            Player allIn = restoredPlayer("AllIn", "allIn", 0, 100, true);
            Player caller = restoredPlayer("Caller", "caller", 100, 0, false);
            Game hand = Game.restoreBuilder()
                    .gameId("river-call-showdown")
                    .players(List.of(allIn, caller))
                    .activePlayerIds(List.of("allIn", "caller"))
                    .remainingDeck(List.of())
                    .communityCards(List.of(
                            new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.THREE, Suit.DIAMONDS),
                            new Card(Rank.FOUR, Suit.HEARTS), new Card(Rank.FIVE, Suit.SPADES),
                            new Card(Rank.SIX, Suit.CLUBS)))
                    .pot(100)
                    .dealerPlayerId("allIn")
                    .smallBlindPlayerId("allIn")
                    .bigBlindPlayerId("caller")
                    .currentPlayerId("caller")
                    .currentHighestBet(100)
                    .currentPhase(GamePhase.RIVER)
                    .gameOver(false)
                    .smallBlind(5)
                    .bigBlind(10)
                    .handContributions(Map.of("allIn", 100))
                    .carryOverPot(0)
                    .allPlayersHadInitialTurn(false)
                    .actedPlayersInRound(Set.of("allIn"))
                    .scheduledTimes(Map.of())
                    .build();

            TurnOutcome outcome = hand.processPlayerDecision(caller, PlayerAction.CALL, 0);

            assertEquals(TurnOutcome.TurnOutcomeType.SHOWDOWN, outcome.type());
            assertEquals(GamePhase.SHOWDOWN, hand.getCurrentPhase());
            assertTrue(hand.isHandSettled());
        }

        @Test
        void testMinBetRaiseAndAllInShortExceptionsAndConversions() {
            Player current = game.getCurrentPlayer();
            BadRequestException smallBet = assertThrows(BadRequestException.class, () ->
                    game.processPlayerDecision(current, PlayerAction.BET, 10));
            assertTrue(smallBet.getMessage().contains("Bet amount must be at least the big blind"));

            Game smallRaiseGame = createStandardGame(3, 1000);
            smallRaiseGame.postBlinds();
            Player raiser = smallRaiseGame.getActivePlayers().getFirst();
            BadRequestException smallRaise = assertThrows(BadRequestException.class, () ->
                    smallRaiseGame.processPlayerDecision(raiser, PlayerAction.RAISE, 30));
            assertTrue(smallRaise.getMessage().contains("Raise increment must be at least 20"));
            assertEquals("MIN_RAISE", smallRaise.actionErrorCode());
            assertEquals("The minimum raise is 40 chips (you entered 30).", smallRaise.actionErrorMessage());

            Game shortBetGame = createGame("short-bet-allin", List.of(
                    new Player("P1", "p1", 15),
                    new Player("P2", "p2", 1000),
                    new Player("P3", "p3", 1000)), 10, 20);
            Player shortBetActor = shortBetGame.getCurrentPlayer();
            assertDoesNotThrow(() -> shortBetGame.processPlayerDecision(
                    shortBetActor, PlayerAction.BET, 15));
            assertEquals(15, shortBetActor.getCurrentBet());
            assertTrue(shortBetActor.isAllIn());

            Game shortRaiseGame = createGame("short-raise-allin", List.of(
                    new Player("P1", "p1", 25),
                    new Player("P2", "p2", 1000),
                    new Player("P3", "p3", 1000)), 10, 20);
            shortRaiseGame.postBlinds();
            Player shortRaiser = shortRaiseGame.getActivePlayers().getFirst();
            assertDoesNotThrow(() -> shortRaiseGame.processPlayerDecision(
                    shortRaiser, PlayerAction.RAISE, 25));
            assertEquals(25, shortRaiser.getCurrentBet());
            assertTrue(shortRaiser.isAllIn());

            Game sidePotGame = createGame("all-in-raise-game", List.of(
                    new Player("Deep1", "deep1", 1000),
                    new Player("Short", "short", 50),
                    new Player("Deep2", "deep2", 1000)), 5, 10);
            sidePotGame.resetForNewHand();
            sidePotGame.dealHoleCards();
            sidePotGame.postBlinds();
            Player allInPlayer = sidePotGame.getCurrentPlayer();
            assertEquals("Short", allInPlayer.getName());
            sidePotGame.processPlayerDecision(allInPlayer, PlayerAction.ALL_IN, 0);
            Player deep1 = sidePotGame.getPlayers().getFirst();
            int prevBet = deep1.getCurrentBet();
            TurnOutcome raiseOutcome = sidePotGame.processPlayerDecision(
                    deep1, PlayerAction.RAISE, 100);
            assertNull(raiseOutcome.playerActionNotice());
            assertTrue(deep1.getCurrentBet() > prevBet);
            assertEquals(deep1.getCurrentBet(), sidePotGame.getCurrentHighestBet());

            Game shortCallGame = createGame("short-call-game", List.of(
                    new Player("P1", "p1", 1000),
                    new Player("Short", "p2", 5),
                    new Player("P3", "p3", 1000)), 5, 10);
            shortCallGame.resetForNewHand();
            shortCallGame.dealHoleCards();
            shortCallGame.postBlinds();
            Player shortCaller = shortCallGame.getCurrentPlayer();
            TurnOutcome callOutcome = shortCallGame.processPlayerDecision(
                    shortCaller, PlayerAction.CALL, 0);
            assertEquals("Not enough chips to call — you're all in.", callOutcome.playerActionNotice());
            assertEquals(0, shortCaller.getChips());
            assertTrue(shortCaller.isAllIn());
        }
    }

    @Nested
    class ShowdownAndSidePotSettlementTests {

        @Test
        void challenge_GameShowdown_OddChipCarryOver_LifecycleContinuity() {
            Player p1 = restoredPlayer("P1", "p1", 0, 33, true);
            Player p2 = restoredPlayer("P2", "p2", 0, 33, true);
            Player p3 = restoredPlayer("P3", "p3", 0, 34, true);

            Game gameUncalled = Game.restoreBuilder().gameId("odd-chip-game")
                    .players(List.of(p1, p2, p3)).activePlayerIds(List.of("p1", "p2", "p3"))
                    .remainingDeck(List.of()).communityCards(List.of()).pot(100)
                    .dealerPlayerId("p1").smallBlindPlayerId("p1").bigBlindPlayerId("p2").currentPlayerId("p3")
                    .currentHighestBet(34).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                    .smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("p1", 33, "p2", 33, "p3", 34))
                    .carryOverPot(0).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();

            assertEquals(1, gameUncalled.getUncalledAmount());
            assertEquals(List.of(99), gameUncalled.getPotBreakdown());

            Game oddGame = Game.restoreBuilder().gameId("odd-game-split")
                    .players(List.of(p1, p2, p3)).activePlayerIds(List.of("p1", "p2", "p3"))
                    .remainingDeck(List.of()).communityCards(List.of()).pot(101)
                    .dealerPlayerId("p1").smallBlindPlayerId("p1").bigBlindPlayerId("p2").currentPlayerId("p3")
                    .currentHighestBet(34).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                    .smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("p1", 33, "p2", 34, "p3", 34))
                    .carryOverPot(0).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();

            assertEquals(0, oddGame.getUncalledAmount());
            assertEquals(List.of(99, 2), oddGame.getPotBreakdown());
        }

        @Test
        void foldedContributionBoundariesDoNotCreateOddChipsAcrossSameEligiblePot() {
            Player a = restoredPlayer("A", "a", 900, 100, false);
            Player b = restoredPlayer("B", "b", 900, 100, false);
            Player f1 = Player.restoreBuilder().name("F1").playerId("f1").chips(995).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(5).hasFolded(true)
                    .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
            Player f2 = Player.restoreBuilder().name("F2").playerId("f2").chips(990).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(10).hasFolded(true)
                    .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
            Player f3 = Player.restoreBuilder().name("F3").playerId("f3").chips(985).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(15).hasFolded(true)
                    .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();

            Game ordinary = Game.restoreBuilder().gameId("coalesce-ordinary-pots")
                    .players(List.of(a, b, f1, f2, f3)).activePlayerIds(List.of("a", "b", "f1", "f2", "f3"))
                    .remainingDeck(List.of()).communityCards(ROYAL_FLUSH_BOARD).pot(230).dealerPlayerId("a")
                    .smallBlindPlayerId("a").bigBlindPlayerId("b").currentPlayerId("a").currentHighestBet(100)
                    .currentPhase(GamePhase.RIVER).gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("a", 100, "b", 100, "f1", 5, "f2", 10, "f3", 15))
                    .carryOverPot(0).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();

            ordinary.conductShowdown();

            assertEquals(1015, a.getChips());
            assertEquals(1015, b.getChips());
            assertEquals(0, ordinary.getPot());
        }

        @Test
        void deadUpperLayersJoinNearestEligibleLowerAllocation() {
            Player a = restoredPlayer("A", "a", 0, 0, false);
            a.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
            Player b = restoredPlayer("B", "b", 0, 0, false);
            b.setHoleCards(List.of(new Card(Rank.TWO, Suit.DIAMONDS), new Card(Rank.SEVEN, Suit.HEARTS)));
            Player c = restoredPlayer("C", "c", 0, false, true, false);
            Player d = restoredPlayer("D", "d", 0, false, true, false);

            Game deadUpper = Game.restoreBuilder().gameId("dead-upper-layer").players(List.of(a, b, c, d))
                    .activePlayerIds(List.of("a", "b", "c", "d")).remainingDeck(List.of())
                    .communityCards(List.of(
                            new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                            new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.TWO, Suit.CLUBS),
                            new Card(Rank.THREE, Suit.DIAMONDS)))
                    .pot(550).dealerPlayerId("a").smallBlindPlayerId("b")
                    .bigBlindPlayerId("c").currentPlayerId("a").currentHighestBet(200)
                    .currentPhase(GamePhase.RIVER).gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("a", 50, "b", 100, "c", 200, "d", 200))
                    .carryOverPot(0).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();

            deadUpper.conductShowdown();
            assertEquals(Set.of("a", "b"), deadUpper.getLastHandPayout().winnerIds());
            assertEquals(200, a.getChips());
            assertEquals(350, b.getChips());
            assertEquals(0, deadUpper.getPot());
            assertEquals(Map.of("a", 200, "b", 350), deadUpper.getLastHandPayout().potWinnings());
            assertTrue(deadUpper.getLastHandPayout().refunds().isEmpty());
        }

        @Test
        void departedContributionStillDefinesSidePotAndCannotReceiveRefund() {
            Player shortPlayer = new Player("Short", "s", 0);
            shortPlayer.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
            Player midPlayer = new Player("Mid", "m", 150);
            midPlayer.setHoleCards(List.of(new Card(Rank.TWO, Suit.DIAMONDS), new Card(Rank.SEVEN, Suit.HEARTS)));
            Game departedGame = Game.restoreBuilder().gameId("departed-contributor").players(List.of(shortPlayer, midPlayer))
                    .activePlayerIds(List.of("s", "m")).remainingDeck(List.of()).communityCards(List.of(
                            new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                            new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.TWO, Suit.CLUBS),
                            new Card(Rank.THREE, Suit.DIAMONDS)))
                    .pot(350).dealerPlayerId("s").smallBlindPlayerId("s").bigBlindPlayerId("m")
                    .currentPlayerId("s").currentHighestBet(150).currentPhase(GamePhase.RIVER)
                    .gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("s", 50, "m", 150, "departed", 150))
                    .carryOverPot(0).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();

            departedGame.conductShowdown();

            assertEquals(150, shortPlayer.getChips());
            assertEquals(350, midPlayer.getChips());
            assertEquals(0, departedGame.getPot());
            assertEquals(Map.of("s", 150, "m", 200), departedGame.getLastHandPayout().potWinnings());
            assertTrue(departedGame.getLastHandPayout().refunds().isEmpty());
        }

        @Test
        void deadBlindLayersPayRetainedZeroContributionContender() {
            Player contender = new Player("Contender", "a", 100);
            Player foldedSb = Player.restoreBuilder().name("Folded SB").playerId("b").chips(90)
                    .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(10)
                    .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
            Player foldedBb = Player.restoreBuilder().name("Folded BB").playerId("c").chips(80)
                    .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(20)
                    .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();

            Game deadBlindGame = Game.restoreBuilder().gameId("zero-contribution-contender")
                    .players(List.of(contender, foldedSb, foldedBb))
                    .activePlayerIds(List.of("a", "b", "c")).remainingDeck(List.of()).communityCards(List.of())
                    .pot(30).dealerPlayerId("a").smallBlindPlayerId("b").bigBlindPlayerId("c")
                    .currentPlayerId("a").currentHighestBet(20).currentPhase(GamePhase.PRE_FLOP)
                    .gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("b", 10, "c", 20)).carryOverPot(0)
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();

            deadBlindGame.conductShowdown();

            assertEquals(130, contender.getChips());
            assertEquals(0, deadBlindGame.getPot());
            assertEquals(Map.of("a", 30), deadBlindGame.getLastHandPayout().potWinnings());
        }

        @Test
        void livePotBreakdownsAndUncalledAmounts() {
            Game shoveGame = createGame("uncalled-breakdown", List.of(
                    new Player("AllIn", "a1", 480),
                    new Player("SmallBlind", "s1", 1000),
                    new Player("BigBlind", "b1", 1000)), 10, 20);
            shoveGame.postBlinds();
            Player allInPlayer = shoveGame.getCurrentPlayer();
            shoveGame.processPlayerDecision(allInPlayer, PlayerAction.ALL_IN, 0);
            assertEquals(List.of(510), shoveGame.getPotBreakdown());
            assertEquals(0, shoveGame.getUncalledAmount());

            Player shortStack = restoredPlayer("Short", "short", 0, 50, true);
            Player bettor = restoredPlayer("Bettor", "bettor", 800, 250, false);
            Player owing = restoredPlayer("Owing", "owing", 950, 50, false);
            Game live = Game.restoreBuilder().gameId("live-cap-pending-bet").players(List.of(shortStack, bettor, owing))
                    .activePlayerIds(List.of("short", "bettor", "owing")).remainingDeck(List.of())
                    .communityCards(List.of()).pot(350).dealerPlayerId("short").smallBlindPlayerId("short")
                    .bigBlindPlayerId("bettor").currentPlayerId("owing").currentHighestBet(250).currentPhase(GamePhase.FLOP)
                    .gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("short", 50, "bettor", 250, "owing", 50))
                    .carryOverPot(0).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
            assertEquals(List.of(150, 200), live.getPotBreakdown());
            assertEquals(0, live.getUncalledAmount());

            Game liveFolded = livePotWithFoldedBlindAndCarryOver(370, 0);
            assertEquals(List.of(170, 200), liveFolded.getPotBreakdown());
            assertEquals(0, liveFolded.getUncalledAmount());

            Game carried = livePotWithFoldedBlindAndCarryOver(380, 10);
            assertEquals(List.of(180, 200), carried.getPotBreakdown());
            assertEquals(0, carried.getUncalledAmount());
        }

        private static final List<Card> SIDE_POT_BOARD = List.of(
                new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.SEVEN, Suit.DIAMONDS),
                new Card(Rank.NINE, Suit.HEARTS), new Card(Rank.JACK, Suit.SPADES),
                new Card(Rank.KING, Suit.CLUBS));

        /** Hole cards from strongest to weakest against the side-pot board: aces, queens, tens, high card. */
        private static final List<List<Card>> HOLE_BY_STRENGTH = List.of(
                List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS)),
                List.of(new Card(Rank.QUEEN, Suit.DIAMONDS), new Card(Rank.QUEEN, Suit.CLUBS)),
                List.of(new Card(Rank.TEN, Suit.SPADES), new Card(Rank.TEN, Suit.DIAMONDS)),
                List.of(new Card(Rank.THREE, Suit.HEARTS), new Card(Rank.FOUR, Suit.DIAMONDS)));

        private Game riverGame(String id, List<Player> table, List<Player> active, int pot, int carryOver,
                               int highestBet, Map<String, Integer> contributions, List<Card> board) {
            return Game.restoreBuilder().gameId(id)
                    .players(table).activePlayerIds(active.stream().map(Player::getPlayerId).toList())
                    .remainingDeck(List.of()).communityCards(board).pot(pot)
                    .dealerPlayerId(table.getFirst().getPlayerId())
                    .smallBlindPlayerId(table.getFirst().getPlayerId())
                    .bigBlindPlayerId(table.getLast().getPlayerId())
                    .currentPlayerId(active.getFirst().getPlayerId())
                    .currentHighestBet(highestBet).currentPhase(GamePhase.RIVER).gameOver(false)
                    .smallBlind(10).bigBlind(20).handContributions(new HashMap<>(contributions))
                    .carryOverPot(carryOver).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
        }

        private static void deal(Player player, int strength) {
            player.setHoleCards(HOLE_BY_STRENGTH.get(strength));
        }

        @Test
        void fourWayAllInShowsThreeContestedPotsAndPaysEachToItsBestEligibleHand() {
            Player a = restoredPlayer("A", "a", 0, 50, true);
            Player b = restoredPlayer("B", "b", 0, 100, true);
            Player c = restoredPlayer("C", "c", 0, 200, true);
            Player d = restoredPlayer("D", "d", 0, 300, true);
            Game hand = riverGame("four-way-all-in", List.of(a, b, c, d), List.of(a, b, c, d), 650, 0, 300,
                    Map.of("a", 50, "b", 100, "c", 200, "d", 300), SIDE_POT_BOARD);

            assertEquals(List.of(200, 150, 200), hand.getPotBreakdown());
            assertEquals(100, hand.getUncalledAmount());

            deal(a, 0);
            deal(b, 1);
            deal(c, 2);
            deal(d, 3);
            hand.conductShowdown();

            assertEquals(200, a.getChips());
            assertEquals(150, b.getChips());
            assertEquals(200, c.getChips());
            assertEquals(100, d.getChips());
            assertEquals(0, hand.getPot());
        }

        @Test
        void uncalledShoveIsExcludedFromBreakdownAndRefundedToTheShover() {
            Player shover = restoredPlayer("Shover", "a1", 0, 480, true);
            Player sb = restoredPlayer("SB", "s1", 0, 10, true);
            Player bb = restoredPlayer("BB", "b1", 980, 20, false);
            Game hand = riverGame("uncalled-shove-payout", List.of(shover, sb, bb), List.of(shover, sb, bb),
                    510, 0, 20, Map.of("a1", 480, "s1", 10, "b1", 20), SIDE_POT_BOARD);

            assertEquals(List.of(30, 20), hand.getPotBreakdown());
            assertEquals(460, hand.getUncalledAmount());

            deal(shover, 3);
            deal(sb, 0);
            deal(bb, 1);
            hand.conductShowdown();

            assertEquals(460, shover.getChips());
            assertEquals(30, sb.getChips());
            assertEquals(1000, bb.getChips());
            assertEquals(0, hand.getPot());
        }

        @Test
        void foldedContributorsDeadMoneyJoinsTheContestedPotAndOnlyTheCoveringExcessIsRefunded() {
            Player allIn = restoredPlayer("AllIn", "a", 0, 50, true);
            Player caller = restoredPlayer("Caller", "b", 850, 150, false);
            Player folded = restoredPlayer("Folded", "c", 980, false, true, false);
            Game hand = riverGame("folded-dead-money", List.of(allIn, caller, folded), List.of(allIn, caller, folded),
                    220, 0, 150, Map.of("a", 50, "b", 150, "c", 20), SIDE_POT_BOARD);

            assertEquals(List.of(120), hand.getPotBreakdown());
            assertEquals(100, hand.getUncalledAmount());

            deal(allIn, 0);
            deal(caller, 1);
            hand.conductShowdown();

            assertEquals(120, allIn.getChips());
            assertEquals(950, caller.getChips());
            assertEquals(980, folded.getChips());
            assertEquals(0, hand.getPot());
        }

        @Test
        void randomizedHandsBreakdownPlusUncalledEqualsPotAndShowdownConservesChips() {
            Random rng = new Random(20_260_101L);
            for (int run = 0; run < 400; run++) {
                int carryOver = rng.nextInt(4) == 0 ? 1 + rng.nextInt(60) : 0;
                List<Player> table = new ArrayList<>();
                List<Player> active = new ArrayList<>();
                Map<String, Integer> contributions = new LinkedHashMap<>();
                int highestBet = 0;
                int pot = carryOver;
                boolean anyInHand = false;

                for (int i = 0, count = 2 + rng.nextInt(7); i < count; i++) {
                    String id = "p" + i;
                    int contribution = 10 + rng.nextInt(300);
                    int currentBet = rng.nextBoolean() ? contribution : rng.nextInt(contribution + 1);
                    int role = rng.nextInt(10);
                    boolean folded = role <= 1;
                    boolean out = role == 2;
                    boolean departed = role == 3;
                    boolean allIn = role == 4 || role == 5;

                    contributions.put(id, contribution);
                    pot += contribution;
                    if (departed) {
                        continue;
                    }
                    Player player = Player.restoreBuilder()
                            .playerId(id).name("P" + i).chips(allIn ? 0 : rng.nextInt(500)).currentBet(currentBet)
                            .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                            .hasFolded(folded).allIn(allIn).out(out).disconnected(false).readyForNextHand(false)
                            .build();
                    table.add(player);
                    if (!out || rng.nextBoolean()) {
                        active.add(player);
                    }
                    if (!folded && !out) {
                        anyInHand = true;
                        highestBet = Math.max(highestBet, currentBet);
                    }
                }
                if (!anyInHand) {
                    Player survivor = restoredPlayer("Survivor", "survivor", 100, 10, false);
                    table.add(survivor);
                    active.add(survivor);
                    contributions.put("survivor", 10);
                    pot += 10;
                    highestBet = Math.max(highestBet, 10);
                }

                List<Card> deck = new ArrayList<>();
                for (Suit suit : Suit.values()) {
                    for (Rank rank : Rank.values()) {
                        deck.add(new Card(rank, suit));
                    }
                }
                Collections.shuffle(deck, rng);
                int nextCard = 5;
                for (Player player : table) {
                    player.setHoleCards(deck.subList(nextCard, nextCard + 2));
                    nextCard += 2;
                }
                int chipsBefore = table.stream().mapToInt(Player::getChips).sum();
                Game hand = riverGame("random-pot-" + run, table, active, pot, carryOver, highestBet,
                        contributions, List.copyOf(deck.subList(0, 5)));
                String context = "run " + run + " contributions=" + contributions + " pot=" + pot;

                List<Integer> breakdown = hand.getPotBreakdown();
                assertTrue(breakdown.stream().allMatch(layer -> layer > 0), context);
                assertEquals(pot, breakdown.stream().mapToInt(Integer::intValue).sum() + hand.getUncalledAmount(),
                        "breakdown plus uncalled must equal the pot, " + context);

                hand.conductShowdown();
                int chipsAfter = table.stream().mapToInt(Player::getChips).sum();
                assertEquals(chipsBefore + pot, chipsAfter + hand.getPot(),
                        "showdown must pay out every chip in the pot, " + context);
            }
        }

        @Test
        void testConductShowdownWithOnePlayerRefundOnlyAndMultiplePlayers() {
            game.processPlayerDecision(game.getActivePlayers().get(2), PlayerAction.BET, 100);
            game.getActivePlayers().get(0).doAction(PlayerAction.FOLD, 0);
            game.getActivePlayers().get(1).doAction(PlayerAction.FOLD, 0);

            game.conductShowdown();
            assertTrue(game.getLastHandPayout().winnerIds().isEmpty());
            assertEquals(1000, game.getPlayers().get(2).getChips());
            assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());

            Game multiGame = createStandardGame(3, 1000);
            multiGame.getActivePlayers().get(0).setHoleCards(List.of(
                    new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
            multiGame.getActivePlayers().get(1).setHoleCards(List.of(
                    new Card(Rank.TWO, Suit.DIAMONDS), new Card(Rank.SEVEN, Suit.HEARTS)));
            multiGame.getActivePlayers().get(2).setHoleCards(List.of(
                    new Card(Rank.THREE, Suit.HEARTS), new Card(Rank.EIGHT, Suit.CLUBS)));

            multiGame.processPlayerDecision(multiGame.getActivePlayers().getFirst(), PlayerAction.BET, 100);
            multiGame.processPlayerDecision(multiGame.getActivePlayers().get(1), PlayerAction.CALL, 100);
            multiGame.processPlayerDecision(multiGame.getActivePlayers().get(2), PlayerAction.CALL, 100);
            multiGame.getCommunityCards().clear();
            multiGame.getCommunityCards().addAll(List.of(
                    new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                    new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.TWO, Suit.CLUBS),
                    new Card(Rank.THREE, Suit.DIAMONDS)));

            multiGame.conductShowdown();
            assertEquals(Set.of("p0"), multiGame.getLastHandPayout().winnerIds());
            assertEquals(1200, multiGame.getActivePlayers().getFirst().getChips());
            assertEquals(HandRank.ROYAL_FLUSH, multiGame.getActivePlayers().getFirst().getHandRank());
            assertEquals(HandRank.ONE_PAIR, multiGame.getActivePlayers().get(1).getHandRank());
            assertEquals(HandRank.ONE_PAIR, multiGame.getActivePlayers().get(2).getHandRank());
        }

        @Test
        void testConductShowdownRegressionJoshVsBenjaminWithRealEvaluation() {
            Game headsUpGame = createGame("regression-josh-benjamin", List.of(
                    new Player("josh", "j1", 1000),
                    new Player("Benjamin", "b1", 1000)), 10, 20);
            Player josh = headsUpGame.getActivePlayers().getFirst();
            Player benjamin = headsUpGame.getActivePlayers().get(1);

            josh.setHoleCards(List.of(new Card(Rank.THREE, Suit.SPADES), new Card(Rank.KING, Suit.DIAMONDS)));
            benjamin.setHoleCards(List.of(new Card(Rank.ACE, Suit.HEARTS), new Card(Rank.TEN, Suit.CLUBS)));
            headsUpGame.getCommunityCards().addAll(List.of(
                    new Card(Rank.THREE, Suit.DIAMONDS), new Card(Rank.JACK, Suit.CLUBS),
                    new Card(Rank.ACE, Suit.SPADES), new Card(Rank.EIGHT, Suit.HEARTS),
                    new Card(Rank.TWO, Suit.CLUBS)));
            headsUpGame.postBlinds();

            headsUpGame.conductShowdown();
            assertEquals(HandRank.ONE_PAIR, josh.getHandRank());
            assertEquals(HandRank.ONE_PAIR, benjamin.getHandRank());
            assertEquals(Set.of("b1"), headsUpGame.getLastHandPayout().winnerIds());
            assertEquals(1010, benjamin.getChips());
            assertEquals(990, josh.getChips());
        }

        @Test
        void testOddChipsAwardedClockwiseAcrossRotatedDealerHands() {
            // Hand 1: dealer is p1; p2 is clockwise left of p1 and receives the odd chip.
            game.processPlayerDecision(game.getActivePlayers().get(0), PlayerAction.BET, 51);
            game.processPlayerDecision(game.getActivePlayers().get(1), PlayerAction.CALL, 51);
            game.processPlayerDecision(game.getActivePlayers().get(2), PlayerAction.CALL, 51);
            game.getActivePlayers().get(2).doAction(PlayerAction.FOLD, 0);
            game.getCommunityCards().addAll(ROYAL_FLUSH_BOARD);
            game.conductShowdown();

            assertEquals(1025, game.getPlayers().get(0).getChips());
            assertEquals(1026, game.getPlayers().get(1).getChips());
            assertEquals(949, game.getPlayers().get(2).getChips());
            assertEquals(0, game.getPot());
            assertEquals(List.of(), game.getPotBreakdown());
            assertEquals(0, game.getUncalledAmount());

            game.resetForNewHand();
            assertEquals(0, game.getPot());
            assertEquals(0, game.toSnapshot().carryOverPot());

            // Hand 2: dealer rotated to p2; p1 is now clockwise left of dealer (after folded p3) and receives the odd chip.
            game.processPlayerDecision(game.getActivePlayers().get(0), PlayerAction.BET, 51);
            game.processPlayerDecision(game.getActivePlayers().get(1), PlayerAction.CALL, 51);
            game.processPlayerDecision(game.getActivePlayers().get(2), PlayerAction.CALL, 51);
            game.getActivePlayers().get(2).doAction(PlayerAction.FOLD, 0);
            game.getCommunityCards().addAll(ROYAL_FLUSH_BOARD);
            game.conductShowdown();

            assertEquals(1051, game.getPlayers().get(0).getChips());
            assertEquals(1051, game.getPlayers().get(1).getChips());
            assertEquals(898, game.getPlayers().get(2).getChips());
            assertEquals(0, game.getPot());
            assertEquals(3000, game.getPlayers().stream().mapToInt(Player::getChips).sum() + game.getPot());
        }

        @Test
        void testConductShowdown_SidePotsSplitRemainderAndRefunds() {
            List<Card> flushStraightBoard = List.of(
                    new Card(Rank.FOUR, Suit.SPADES), new Card(Rank.FIVE, Suit.SPADES),
                    new Card(Rank.SIX, Suit.SPADES), new Card(Rank.SEVEN, Suit.DIAMONDS),
                    new Card(Rank.TWO, Suit.CLUBS));

            Game basic = createGame("side-pot-basic", List.of(
                    new Player("Short", "s1", 50), new Player("Mid", "m1", 300), new Player("Deep", "d1", 300)),
                    10, 20);
            Player s = basic.getPlayers().get(0);
            Player m = basic.getPlayers().get(1);
            Player d = basic.getPlayers().get(2);
            basic.processPlayerDecision(m, PlayerAction.BET, 150);
            basic.processPlayerDecision(s, PlayerAction.CALL, 0);
            basic.processPlayerDecision(d, PlayerAction.CALL, 0);
            basic.getCommunityCards().clear();
            basic.getCommunityCards().addAll(flushStraightBoard);
            s.setHoleCards(List.of(new Card(Rank.NINE, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES)));
            m.setHoleCards(List.of(new Card(Rank.EIGHT, Suit.HEARTS), new Card(Rank.NINE, Suit.CLUBS)));
            d.setHoleCards(List.of(new Card(Rank.SEVEN, Suit.HEARTS), new Card(Rank.KING, Suit.CLUBS)));
            basic.conductShowdown();
            assertEquals(150, s.getChips());
            assertEquals(350, m.getChips());
            assertEquals(150, d.getChips());
            assertEquals(0, basic.getPot());

            Game splitSide = createGame("side-pot-split", List.of(
                    new Player("Short", "s1", 50), new Player("Mid", "m1", 300), new Player("Deep", "d1", 300)),
                    10, 20);
            splitSide.processPlayerDecision(splitSide.getPlayers().get(1), PlayerAction.BET, 150);
            splitSide.processPlayerDecision(splitSide.getPlayers().get(0), PlayerAction.CALL, 0);
            splitSide.processPlayerDecision(splitSide.getPlayers().get(2), PlayerAction.CALL, 0);
            splitSide.getCommunityCards().clear();
            splitSide.getCommunityCards().addAll(flushStraightBoard);
            splitSide.getPlayers().get(0).setHoleCards(List.of(new Card(Rank.NINE, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES)));
            splitSide.getPlayers().get(1).setHoleCards(List.of(new Card(Rank.EIGHT, Suit.HEARTS), new Card(Rank.NINE, Suit.CLUBS)));
            splitSide.getPlayers().get(2).setHoleCards(List.of(new Card(Rank.EIGHT, Suit.CLUBS), new Card(Rank.NINE, Suit.DIAMONDS)));
            splitSide.conductShowdown();
            assertEquals(150, splitSide.getPlayers().get(0).getChips());
            assertEquals(250, splitSide.getPlayers().get(1).getChips());
            assertEquals(250, splitSide.getPlayers().get(2).getChips());

            Game multi = createGame("side-pot-multi", List.of(
                    new Player("A", "a1", 50), new Player("B", "b1", 100),
                    new Player("C", "c1", 300), new Player("D", "d1", 300)), 10, 20);
            multi.processPlayerDecision(multi.getPlayers().get(2), PlayerAction.BET, 200);
            multi.processPlayerDecision(multi.getPlayers().get(3), PlayerAction.CALL, 0);
            multi.processPlayerDecision(multi.getPlayers().get(1), PlayerAction.CALL, 0);
            multi.processPlayerDecision(multi.getPlayers().get(0), PlayerAction.CALL, 0);
            multi.getCommunityCards().clear();
            multi.getCommunityCards().addAll(flushStraightBoard);
            multi.getPlayers().get(0).setHoleCards(List.of(new Card(Rank.NINE, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES)));
            multi.getPlayers().get(1).setHoleCards(List.of(new Card(Rank.EIGHT, Suit.HEARTS), new Card(Rank.NINE, Suit.CLUBS)));
            multi.getPlayers().get(2).setHoleCards(List.of(new Card(Rank.SEVEN, Suit.HEARTS), new Card(Rank.SEVEN, Suit.CLUBS)));
            multi.getPlayers().get(3).setHoleCards(List.of(new Card(Rank.SIX, Suit.HEARTS), new Card(Rank.KING, Suit.CLUBS)));
            multi.conductShowdown();
            assertEquals(200, multi.getPlayers().get(0).getChips());
            assertEquals(150, multi.getPlayers().get(1).getChips());
            assertEquals(300, multi.getPlayers().get(2).getChips());
            assertEquals(100, multi.getPlayers().get(3).getChips());

            Game foldedEdge = createGame("side-pot-folded-edge", List.of(
                    new Player("Short", "s1", 50), new Player("Mid", "m1", 300), new Player("Folder", "f1", 300)),
                    10, 20);
            foldedEdge.processPlayerDecision(foldedEdge.getPlayers().get(1), PlayerAction.BET, 150);
            foldedEdge.processPlayerDecision(foldedEdge.getPlayers().get(0), PlayerAction.CALL, 0);
            foldedEdge.processPlayerDecision(foldedEdge.getPlayers().get(2), PlayerAction.CALL, 0);
            foldedEdge.getPlayers().get(2).doAction(PlayerAction.FOLD, 0);
            foldedEdge.getCommunityCards().clear();
            foldedEdge.getCommunityCards().addAll(flushStraightBoard);
            foldedEdge.getPlayers().get(0).setHoleCards(List.of(new Card(Rank.NINE, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES)));
            foldedEdge.getPlayers().get(1).setHoleCards(List.of(new Card(Rank.SEVEN, Suit.HEARTS), new Card(Rank.KING, Suit.CLUBS)));
            foldedEdge.conductShowdown();
            assertEquals(150, foldedEdge.getPlayers().get(0).getChips());
            assertEquals(350, foldedEdge.getPlayers().get(1).getChips());
            assertEquals(150, foldedEdge.getPlayers().get(2).getChips());

            Game splitRemainder = createGame("side-pot-remainder-edge", List.of(
                    new Player("A", "a1", 50), new Player("B", "b1", 101),
                    new Player("C", "c1", 101), new Player("D", "d1", 101)), 10, 20);
            splitRemainder.processPlayerDecision(splitRemainder.getPlayers().get(1), PlayerAction.BET, 101);
            splitRemainder.processPlayerDecision(splitRemainder.getPlayers().get(2), PlayerAction.CALL, 0);
            splitRemainder.processPlayerDecision(splitRemainder.getPlayers().get(3), PlayerAction.CALL, 0);
            splitRemainder.processPlayerDecision(splitRemainder.getPlayers().get(0), PlayerAction.CALL, 0);
            splitRemainder.getCommunityCards().clear();
            splitRemainder.getCommunityCards().addAll(flushStraightBoard);
            splitRemainder.getPlayers().get(0).setHoleCards(List.of(new Card(Rank.NINE, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES)));
            splitRemainder.getPlayers().get(1).setHoleCards(List.of(new Card(Rank.EIGHT, Suit.HEARTS), new Card(Rank.NINE, Suit.CLUBS)));
            splitRemainder.getPlayers().get(2).setHoleCards(List.of(new Card(Rank.EIGHT, Suit.CLUBS), new Card(Rank.NINE, Suit.DIAMONDS)));
            splitRemainder.getPlayers().get(3).setHoleCards(List.of(new Card(Rank.SEVEN, Suit.HEARTS), new Card(Rank.KING, Suit.CLUBS)));
            splitRemainder.conductShowdown();
            assertEquals(200, splitRemainder.getPlayers().get(0).getChips());
            assertEquals(77, splitRemainder.getPlayers().get(1).getChips());
            assertEquals(76, splitRemainder.getPlayers().get(2).getChips());
            assertEquals(0, splitRemainder.getPlayers().get(3).getChips());
            assertEquals(0, splitRemainder.getPot());

            Game uncalledRefund = createGame("uncalled-refund", List.of(
                    new Player("AllIn", "a1", 480),
                    new Player("SmallBlind", "s1", 1000),
                    new Player("BigBlind", "b1", 1000)), 10, 20);
            uncalledRefund.postBlinds();
            Player shover = uncalledRefund.getCurrentPlayer();
            uncalledRefund.processPlayerDecision(shover, PlayerAction.ALL_IN, 0);
            uncalledRefund.getCommunityCards().clear();
            uncalledRefund.getCommunityCards().addAll(List.of(
                    new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                    new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.TWO, Suit.DIAMONDS),
                    new Card(Rank.THREE, Suit.CLUBS)));
            shover.setHoleCards(List.of(new Card(Rank.FOUR, Suit.HEARTS), new Card(Rank.NINE, Suit.DIAMONDS)));
            uncalledRefund.getActivePlayers().get(1).setHoleCards(List.of(new Card(Rank.KING, Suit.SPADES), new Card(Rank.ACE, Suit.SPADES)));
            uncalledRefund.getActivePlayers().get(2).setHoleCards(List.of(new Card(Rank.THREE, Suit.HEARTS), new Card(Rank.KING, Suit.HEARTS)));
            uncalledRefund.conductShowdown();
            assertEquals(460, shover.getChips());
            assertEquals(1020, uncalledRefund.getActivePlayers().get(1).getChips());
            assertEquals(1000, uncalledRefund.getActivePlayers().get(2).getChips());
            assertEquals(0, uncalledRefund.getPot());
        }

        @Test
        void departureCapsUncallableAndGenuineAllInCallsAndAggressionRestrictions() {
            Player shortStack = restoredPlayer("Short", "s", 0, 50, true);
            Player retained = restoredPlayer("A", "a", 500, 100, false);
            Player departing = restoredPlayer("B", "b", 300, 200, false);
            Game capUncallable = restoredBettingGame("departure-uncallable-top", List.of(shortStack, retained, departing),
                    350, 200, 1, Map.of("s", 50, "a", 100, "b", 200), 100, Map.of());
            capUncallable.removePlayer(departing);
            TurnOutcome runout = capUncallable.advanceAfterPlayerRemoval();
            assertEquals(100, capUncallable.getCurrentHighestBet());
            assertEquals(0, capUncallable.getCurrentHighestBet() - retained.getCurrentBet());
            assertEquals(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, runout.type());
            assertEquals(Map.of("s", 50, "a", 100, "b", 200), capUncallable.toSnapshot().handContributions());

            Player shortCallAi = restoredPlayer("Short", "s", 0, 200, true);
            Player retained2 = restoredPlayer("A", "a", 100, 100, false);
            Player departing2 = restoredPlayer("B", "b", 700, 300, false);
            Game capGenuine = restoredBettingGame("departure-genuine-call", List.of(shortCallAi, retained2, departing2),
                    600, 300, 1, Map.of("s", 200, "a", 100, "b", 300), 100, Map.of());
            capGenuine.removePlayer(departing2);
            TurnOutcome nextTurn = capGenuine.advanceAfterPlayerRemoval();
            assertEquals(200, capGenuine.getCurrentHighestBet());
            assertEquals(100, capGenuine.getCurrentHighestBet() - retained2.getCurrentBet());
            assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, nextTurn.type());
            assertEquals(List.of(PlayerAction.FOLD, PlayerAction.ALL_IN), capGenuine.getLegalActionsFor(retained2));
            capGenuine.processPlayerDecision(retained2, PlayerAction.ALL_IN, 0);
            assertEquals(200, retained2.getCurrentBet());
            assertEquals(Map.of("s", 200, "a", 200, "b", 300), capGenuine.toSnapshot().handContributions());

            Player actorDeep = restoredPlayer("Actor", "a", 500, 0, false);
            Player allInOpp = restoredPlayer("All-in", "b", 0, 100, true);
            Game aggressionBlocked = restoredBettingGame("uncallable-aggression", List.of(actorDeep, allInOpp),
                    100, 100, 0, Map.of("b", 100), 100, Map.of());
            assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CALL), aggressionBlocked.getLegalActionsFor(actorDeep));
            assertThrows(UnauthorisedActionException.class, () ->
                    aggressionBlocked.processPlayerDecision(actorDeep, PlayerAction.RAISE, 100));
            assertThrows(UnauthorisedActionException.class, () ->
                    aggressionBlocked.processPlayerDecision(actorDeep, PlayerAction.ALL_IN, 0));

            Player actorExact = restoredPlayer("Actor", "a", 100, 0, false);
            Game callingAllIn = restoredBettingGame("calling-allin", List.of(actorExact, restoredPlayer("All-in", "b", 0, 100, true)),
                    100, 100, 0, Map.of("b", 100), 100, Map.of());
            assertTrue(callingAllIn.getLegalActionsFor(actorExact).contains(PlayerAction.ALL_IN));
            callingAllIn.processPlayerDecision(actorExact, PlayerAction.ALL_IN, 0);
            assertEquals(100, actorExact.getCurrentBet());
        }

        @Test
        void realEvaluatorAndCarryOverAtShowdown() {
            List<Card> board = List.of(
                    new Card(Rank.TWO, Suit.SPADES), new Card(Rank.FOUR, Suit.HEARTS),
                    new Card(Rank.SEVEN, Suit.DIAMONDS), new Card(Rank.NINE, Suit.CLUBS),
                    new Card(Rank.JACK, Suit.SPADES));
            Player ace = Player.restoreBuilder().name("Ace").playerId("ace").chips(950)
                    .holeCards(List.of(new Card(Rank.ACE, Suit.HEARTS), new Card(Rank.THREE, Suit.DIAMONDS)))
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                    .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
            Player king = Player.restoreBuilder().name("King").playerId("king").chips(950)
                    .holeCards(List.of(new Card(Rank.KING, Suit.HEARTS), new Card(Rank.EIGHT, Suit.DIAMONDS)))
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                    .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
            Game realGame = Game.restoreBuilder().gameId("real-high-card-showdown").players(List.of(ace, king))
                    .activePlayerIds(List.of("ace", "king")).remainingDeck(List.of()).communityCards(board)
                    .pot(100).dealerPlayerId("ace").smallBlindPlayerId("ace").bigBlindPlayerId("king")
                    .currentPlayerId("ace").currentHighestBet(50).currentPhase(GamePhase.RIVER)
                    .gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("ace", 50, "king", 50)).carryOverPot(0)
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
            realGame.conductShowdown();
            assertEquals(Set.of("ace"), realGame.getLastHandPayout().winnerIds());
            assertEquals(1050, ace.getChips());
            assertEquals(950, king.getChips());
            assertEquals(Map.of("ace", 100), realGame.getLastHandPayout().potWinnings());

            Player coA = restoredPlayer("A", "a", 50, 0, true);
            Player coB = restoredPlayer("B", "b", 50, 0, true);
            Game carryGame = Game.restoreBuilder().gameId("carry-over-remainder").players(List.of(coA, coB))
                    .activePlayerIds(List.of("a", "b")).remainingDeck(List.of()).communityCards(ROYAL_FLUSH_BOARD)
                    .pot(101).dealerPlayerId("a").smallBlindPlayerId("a").bigBlindPlayerId("b")
                    .currentPlayerId("a").currentHighestBet(50).currentPhase(GamePhase.RIVER)
                    .gameOver(false).smallBlind(10).bigBlind(20).handContributions(Map.of("a", 50, "b", 50))
                    .carryOverPot(1).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
            carryGame.conductShowdown();
            assertEquals(100, coA.getChips());
            assertEquals(101, coB.getChips());
            assertEquals(0, carryGame.getPot());
            assertEquals(List.of(), carryGame.getPotBreakdown());
            assertEquals(0, carryGame.getUncalledAmount());
            assertEquals(0, carryGame.getLastHandPayout().remainder());
        }

        @Test
        void conductShowdownIsIdempotent() {
            Player p1 = restoredPlayer("Pieter", "p1", 0, 1000, true);
            p1.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
            Player p2 = restoredPlayer("BoshMan", "p2", 0, 1000, true);
            p2.setHoleCards(List.of(new Card(Rank.FOUR, Suit.HEARTS), new Card(Rank.NINE, Suit.DIAMONDS)));
            Game idemp = riverGame("double-showdown-game", List.of(p1, p2), List.of(p1, p2), 2000, 0, 1000,
                    Map.of("p1", 1000, "p2", 1000),
                    List.of(
                            new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                            new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.TWO, Suit.CLUBS),
                            new Card(Rank.THREE, Suit.DIAMONDS)));

            idemp.conductShowdown();
            Set<String> firstWinnerIds = idemp.getLastHandPayout().winnerIds();
            assertEquals(Set.of("p1"), firstWinnerIds);
            assertEquals(2000, p1.getChips());
            assertEquals(0, p2.getChips());
            assertEquals(0, idemp.getPot());

            idemp.conductShowdown();
            assertEquals(firstWinnerIds, idemp.getLastHandPayout().winnerIds());
            assertEquals(2000, p1.getChips());
        }

        @Test
        void conductShowdownWithZeroContendersPreservesCarryOverAndGameOverResetPreservesPot() {
            Game voidGame = createStandardGame(2, 100);
            voidGame.processPlayerDecision(voidGame.getPlayers().get(0), PlayerAction.BET, 50);
            voidGame.processPlayerDecision(voidGame.getPlayers().get(1), PlayerAction.CALL, 50);
            voidGame.getPlayers().forEach(pl -> pl.doAction(PlayerAction.FOLD, 0));
            voidGame.conductShowdown();
            assertTrue(voidGame.getLastHandPayout().winnerIds().isEmpty());
            assertEquals(100, voidGame.getLastHandPayout().remainder());
            assertFalse(voidGame.resetForNewHand());
            assertEquals(100, voidGame.toSnapshot().carryOverPot());

            Player fundedA = restoredPlayer("A", "a", 50, 50, false);
            Player fundedB = restoredPlayer("B", "b", 50, 0, false);
            Game alreadyGameOver = Game.restoreBuilder()
                    .gameId("finished-game-reset-ledger")
                    .players(List.of(fundedA, fundedB))
                    .activePlayerIds(List.of("a", "b"))
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(50)
                    .dealerPlayerId("a")
                    .smallBlindPlayerId("a")
                    .bigBlindPlayerId("b")
                    .currentPlayerId("a")
                    .currentHighestBet(50)
                    .currentPhase(GamePhase.RIVER)
                    .gameOver(true)
                    .smallBlind(10)
                    .bigBlind(20)
                    .handContributions(Map.of("a", 50))
                    .carryOverPot(0)
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of())
                    .build();
            assertTrue(alreadyGameOver.resetForNewHand());
            assertEquals(2, alreadyGameOver.getActivePlayers().size());
            assertEquals(0, alreadyGameOver.toSnapshot().carryOverPot());
            assertEquals(Map.of("a", 50), alreadyGameOver.toSnapshot().handContributions());
            assertEquals(50, alreadyGameOver.getPot());
        }

        @Test
        void settleGameEndAwardsUnsettledPotAndCarryOverIdempotently() {
            Game finishedBlind = createGame("finished-blind-pot", List.of(
                    new Player("Small", "small", 1000), new Player("Big", "big", 1000)),
                    10, 20);
            finishedBlind.postBlinds();
            Player small = finishedBlind.getPlayers().get(0);
            Player big = finishedBlind.getPlayers().get(1);
            finishedBlind.removePlayer(small);
            Player winner = finishedBlind.settleGameEnd(null, Set.of());
            assertSame(big, winner);
            assertEquals(1010, big.getChips());
            assertEquals(0, finishedBlind.getPot());
            assertTrue(finishedBlind.isFinalPotSettled());
            assertSame(big, finishedBlind.settleGameEnd(null, Set.of()));
            assertEquals(1010, big.getChips());

            Player oa = new Player("A", "a", 100);
            Player ob = new Player("B", "b", 100);
            Game oddCarry = Game.restoreBuilder().gameId("finished-odd-carry").players(List.of(oa, ob))
                    .activePlayerIds(List.of("a", "b")).remainingDeck(List.of()).communityCards(List.of())
                    .pot(1).dealerPlayerId("a").smallBlindPlayerId("a").bigBlindPlayerId("b")
                    .currentPlayerId("a").currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                    .gameOver(false).smallBlind(10).bigBlind(20).handContributions(Map.of())
                    .carryOverPot(1).lastHandPayout(new HandPayout(Map.of(), Map.of(), Set.of(), 1, false))
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
            Player oddWinner = oddCarry.settleGameEnd(null, Set.of());
            oddCarry.settleGameEnd(oddWinner, Set.of());
            assertEquals(101, oddWinner.getChips());
            assertEquals(0, oddCarry.getPot());
            assertEquals(1, oddCarry.getLastHandPayout().remainder());
        }

        @Test
        void conductShowdownConservesTotalChipsAcrossRepeatedCalls() {
            Player p1 = restoredPlayer("P1", "p1", 0, 1000, true);
            p1.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS)));
            Player p2 = restoredPlayer("P2", "p2", 0, 1000, true);
            p2.setHoleCards(List.of(new Card(Rank.KING, Suit.SPADES), new Card(Rank.KING, Suit.HEARTS)));
            Player p3 = restoredPlayer("P3", "p3", 980, false, true, false);
            Game conserve = riverGame("conserve-chips", List.of(p1, p2, p3), List.of(p1, p2, p3), 2020, 0, 1000,
                    Map.of("p1", 1000, "p2", 1000, "p3", 20), SIDE_POT_BOARD);

            conserve.conductShowdown();
            assertEquals(3000, conserve.getPlayers().stream().mapToInt(Player::getChips).sum() + conserve.getPot());
            conserve.conductShowdown();
            assertEquals(3000, conserve.getPlayers().stream().mapToInt(Player::getChips).sum() + conserve.getPot());
        }

        @Test
        void oddChipsGoClockwiseFromDealerWithZeroCarryOver() {
            // Three-way split of 101 chips (2 odd chips): seat order [p0, p1, p2, p3], dealer = p1.
            // Clockwise from dealer p1: p2, p3 (folded), p0, p1 (dealer last) -> p2 and p0 get +1 each.
            Player p0 = restoredPlayer("P0", "p0", 70, 0, true);
            Player p1 = restoredPlayer("P1", "p1", 70, 0, true);
            Player p2 = restoredPlayer("P2", "p2", 70, 0, true);
            Player p3 = restoredPlayer("P3", "p3", 89, false, true, false);
            Game threeWay = Game.restoreBuilder().gameId("three-way-odd-chips")
                    .players(List.of(p0, p1, p2, p3)).activePlayerIds(List.of("p0", "p1", "p2", "p3"))
                    .remainingDeck(List.of()).communityCards(ROYAL_FLUSH_BOARD).pot(101)
                    .dealerPlayerId("p1").smallBlindPlayerId("p2").bigBlindPlayerId("p3")
                    .currentPlayerId("p0").currentHighestBet(30).currentPhase(GamePhase.RIVER)
                    .gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("p0", 30, "p1", 30, "p2", 30, "p3", 11))
                    .carryOverPot(0).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
            int totalBeforeThreeWay = threeWay.getPlayers().stream().mapToInt(Player::getChips).sum() + threeWay.getPot();

            threeWay.conductShowdown();

            assertEquals(104, p0.getChips());
            assertEquals(103, p1.getChips());
            assertEquals(104, p2.getChips());
            assertEquals(89, p3.getChips());
            assertEquals(0, threeWay.getPot());
            assertEquals(0, threeWay.getLastHandPayout().remainder());
            assertEquals(totalBeforeThreeWay,
                    threeWay.getPlayers().stream().mapToInt(Player::getChips).sum() + threeWay.getPot());
            assertFalse(threeWay.resetForNewHand());
            assertEquals(0, threeWay.toSnapshot().carryOverPot());

            // Heads-up split of 101 chips (1 odd chip): dealer is small blind 'a', so 'b' (left of dealer) gets +1.
            Player huA = restoredPlayer("A", "a", 50, 0, true);
            Player huB = restoredPlayer("B", "b", 50, 0, true);
            Game headsUp = Game.restoreBuilder().gameId("heads-up-odd-chip")
                    .players(List.of(huA, huB)).activePlayerIds(List.of("a", "b"))
                    .remainingDeck(List.of()).communityCards(ROYAL_FLUSH_BOARD).pot(101)
                    .dealerPlayerId("a").smallBlindPlayerId("a").bigBlindPlayerId("b")
                    .currentPlayerId("a").currentHighestBet(50).currentPhase(GamePhase.RIVER)
                    .gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("a", 50, "b", 50)).carryOverPot(1)
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
            int totalBeforeHeadsUp = headsUp.getPlayers().stream().mapToInt(Player::getChips).sum() + headsUp.getPot();

            headsUp.conductShowdown();

            assertEquals(100, huA.getChips());
            assertEquals(101, huB.getChips());
            assertEquals(0, headsUp.getPot());
            assertEquals(0, headsUp.getLastHandPayout().remainder());
            assertEquals(totalBeforeHeadsUp,
                    headsUp.getPlayers().stream().mapToInt(Player::getChips).sum() + headsUp.getPot());
            assertFalse(headsUp.resetForNewHand());
            assertEquals(0, headsUp.toSnapshot().carryOverPot());

            // Side pot with its own leftover chip (101) and rotated dealer 's2' -> 's1' is clockwise left of 's2' and gets +1.
            Player s0 = restoredPlayer("S0", "s0", 69, false, true, false);
            Player s1 = restoredPlayer("S1", "s1", 50, 0, true);
            s1.setHoleCards(List.of(new Card(Rank.ACE, Suit.DIAMONDS), new Card(Rank.FOUR, Suit.DIAMONDS)));
            Player s2 = restoredPlayer("S2", "s2", 50, 0, true);
            s2.setHoleCards(List.of(new Card(Rank.ACE, Suit.HEARTS), new Card(Rank.FOUR, Suit.HEARTS)));
            Player s3 = restoredPlayer("S3", "s3", 0, 0, true);
            s3.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
            Game sidePotOdd = Game.restoreBuilder().gameId("side-pot-odd-chip")
                    .players(List.of(s0, s1, s2, s3)).activePlayerIds(List.of("s0", "s1", "s2", "s3"))
                    .remainingDeck(List.of()).communityCards(List.of(
                            new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                            new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.KING, Suit.CLUBS),
                            new Card(Rank.TWO, Suit.CLUBS)))
                    .pot(141)
                    .dealerPlayerId("s2").smallBlindPlayerId("s3").bigBlindPlayerId("s0")
                    .currentPlayerId("s1").currentHighestBet(50).currentPhase(GamePhase.RIVER)
                    .gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("s0", 31, "s1", 50, "s2", 50, "s3", 10))
                    .carryOverPot(0).allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
            int totalBeforeSidePot = sidePotOdd.getPlayers().stream().mapToInt(Player::getChips).sum() + sidePotOdd.getPot();

            sidePotOdd.conductShowdown();

            assertEquals(40, s3.getChips());
            assertEquals(101, s1.getChips());
            assertEquals(100, s2.getChips());
            assertEquals(69, s0.getChips());
            assertEquals(0, sidePotOdd.getPot());
            assertEquals(0, sidePotOdd.getLastHandPayout().remainder());
            assertEquals(totalBeforeSidePot,
                    sidePotOdd.getPlayers().stream().mapToInt(Player::getChips).sum() + sidePotOdd.getPot());
            assertFalse(sidePotOdd.resetForNewHand());
            assertEquals(0, sidePotOdd.toSnapshot().carryOverPot());

            // Departed dealer still anchored in handSeatOrder: [d0, d1, d2], d1 was dealer and removed mid-hand.
            // Clockwise from d1 is d2, then d0 -> d2 gets the odd chip.
            Player d0 = restoredPlayer("D0", "d0", 50, 0, true);
            Player d1 = restoredPlayer("D1", "d1", 50, 0, true);
            Player d2 = restoredPlayer("D2", "d2", 50, 0, true);
            Game departedDealer = Game.restoreBuilder().gameId("departed-dealer-odd-chip")
                    .players(List.of(d0, d1, d2)).activePlayerIds(List.of("d0", "d1", "d2"))
                    .handSeatOrder(List.of("d0", "d1", "d2"))
                    .remainingDeck(List.of()).communityCards(ROYAL_FLUSH_BOARD).pot(101)
                    .dealerPlayerId("d1").smallBlindPlayerId("d2").bigBlindPlayerId("d0")
                    .currentPlayerId("d2").currentHighestBet(50).currentPhase(GamePhase.RIVER)
                    .gameOver(false).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("d0", 50, "d2", 50)).carryOverPot(1)
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                    .build();
            departedDealer.removePlayer(d1);
            int totalBeforeDeparted = departedDealer.getPlayers().stream().mapToInt(Player::getChips).sum() + departedDealer.getPot();

            departedDealer.conductShowdown();

            assertEquals(100, d0.getChips());
            assertEquals(101, d2.getChips());
            assertEquals(0, departedDealer.getPot());
            assertEquals(0, departedDealer.getLastHandPayout().remainder());
            assertEquals(totalBeforeDeparted,
                    departedDealer.getPlayers().stream().mapToInt(Player::getChips).sum() + departedDealer.getPot());
            assertFalse(departedDealer.resetForNewHand());
            assertEquals(0, departedDealer.toSnapshot().carryOverPot());
        }

        @Test
        void wasContestedShowdownReflectsSettledEvaluatedWinnersOnly() {
            Game unsettled = createStandardGame(2, 100);
            unsettled.dealHoleCards();
            unsettled.postBlinds();
            assertFalse(unsettled.wasContestedShowdown());

            Player foldingPlayer = unsettled.getCurrentPlayer();
            unsettled.processPlayerDecision(foldingPlayer,
                    PlayerAction.FOLD, 0);
            assertTrue(unsettled.isHandSettled());
            assertFalse(unsettled.wasContestedShowdown());

            Player c1 = restoredPlayer("P1", "p0", 80, 20, false);
            deal(c1, 0);
            Player c2 = restoredPlayer("P2", "p1", 80, 20, false);
            deal(c2, 1);
            Game contested = riverGame("contested-showdown", List.of(c1, c2), List.of(c1, c2), 40, 0, 20,
                    Map.of("p0", 20, "p1", 20), SIDE_POT_BOARD);
            contested.conductShowdown();
            assertTrue(contested.wasContestedShowdown());

            Set<String> winnerIds = contested.getLastHandPayout().winnerIds();
            assertEquals(Set.of("p0"), winnerIds);
            contested.settleGameEnd(c1, Set.of(c2.getName()));
            assertTrue(contested.wasContestedShowdown());

            Game voidGame = createStandardGame(2, 100);
            voidGame.getPlayers().getFirst().setHandRank(HandRank.ROYAL_FLUSH);
            voidGame.getPlayers().forEach(p -> p.doAction(PlayerAction.FOLD, 0));
            voidGame.conductShowdown();
            assertTrue(voidGame.isHandSettled());
            assertFalse(voidGame.wasContestedShowdown());
        }
    }

    @Nested
    class SeatingAndPlayerRemovalTests {

        @Test
        void keyPositionRemovalsPreserveOrAdvanceTurnAccurately() {
            Game dealerRemoval = createStandardGame(5, 1000);
            Player dealer = dealerRemoval.getActivePlayers().get(0);
            assertEquals("p3", dealerRemoval.getCurrentPlayer().getPlayerId());
            dealerRemoval.removePlayer(dealer);
            assertEquals(4, dealerRemoval.getActivePlayers().size());
            assertEquals("p3", dealerRemoval.getCurrentPlayer().getPlayerId());
            dealerRemoval.resetForNewHand();
            assertEquals("p1", dealerRemoval.toSnapshot().dealerPlayerId());

            Game sbRemoval = createStandardGame(4, 1000);
            Player sbPlayer = sbRemoval.getActivePlayers().stream()
                    .filter(p -> p.getPlayerId().equals(sbRemoval.getSmallBlindPlayerId()))
                    .findFirst().orElseThrow();
            sbRemoval.removePlayer(sbPlayer);
            assertEquals("p3", sbRemoval.getCurrentPlayer().getPlayerId());

            Game bbRemoval = createStandardGame(4, 1000);
            Player bbPlayer = bbRemoval.getActivePlayers().stream()
                    .filter(p -> p.getPlayerId().equals(bbRemoval.getBigBlindPlayerId()))
                    .findFirst().orElseThrow();
            bbRemoval.removePlayer(bbPlayer);
            assertEquals("p3", bbRemoval.getCurrentPlayer().getPlayerId());

            Game curRemoval = createStandardGame(4, 1000);
            Player curPlayer = curRemoval.getCurrentPlayer();
            curRemoval.removePlayer(curPlayer);
            assertEquals("p0", curRemoval.getCurrentPlayer().getPlayerId());
        }

        @Test
        void midHandBlindDeparturesPreserveBlindIdsAndTailDepartureSkipsWrappedOutPlayer() {
            Game sbGame = createStandardGame(4, 1000);
            sbGame.postBlinds();
            String sbId = sbGame.getSmallBlindPlayerId();
            String bbId = sbGame.getBigBlindPlayerId();
            String actorId = sbGame.getCurrentPlayer().getPlayerId();
            sbGame.removePlayer(sbGame.getActivePlayers().get(1));
            assertEquals(sbId, sbGame.getSmallBlindPlayerId());
            assertEquals(bbId, sbGame.getBigBlindPlayerId());
            assertEquals(actorId, sbGame.getCurrentPlayer().getPlayerId());

            Game bbGame = createStandardGame(4, 1000);
            bbGame.postBlinds();
            String bbGameSbId = bbGame.getSmallBlindPlayerId();
            String bbGameBbId = bbGame.getBigBlindPlayerId();
            String bbGameActorId = bbGame.getCurrentPlayer().getPlayerId();
            bbGame.removePlayer(bbGame.getActivePlayers().get(2));
            assertEquals(bbGameSbId, bbGame.getSmallBlindPlayerId());
            assertEquals(bbGameBbId, bbGame.getBigBlindPlayerId());
            assertEquals(bbGameActorId, bbGame.getCurrentPlayer().getPlayerId());

            Game wrappedOut = createStandardGame(4, 1000);
            wrappedOut.postBlinds();
            wrappedOut.getActivePlayers().get(0).setIsOut();
            Player tailActor = wrappedOut.getCurrentPlayer();
            assertEquals("p3", tailActor.getPlayerId());
            wrappedOut.removePlayer(tailActor);
            assertEquals("p1", wrappedOut.getCurrentPlayer().getPlayerId());
        }

        @Test
        void removalWhenSuccessorFoldedAllInAtTailOrNonActionable() {
            Player p0 = restoredPlayer("Alice", "p0", 1000, false, false, false);
            Player p1 = restoredPlayer("Bob", "p1", 1000, false, false, false);
            Player p2Folded = restoredPlayer("Charlie", "p2", 1000, false, true, false);
            Player p3 = restoredPlayer("Dave", "p3", 1000, false, false, false);
            Game foldedSuccessor = Game.restoreBuilder().gameId("succ-fold")
                    .players(List.of(p0, p1, p2Folded, p3)).activePlayerIds(List.of("p0", "p1", "p2", "p3"))
                    .remainingDeck(List.of())
                    .currentPlayerId("p1").smallBlind(10).bigBlind(20).build();
            foldedSuccessor.removePlayer(p1);
            assertEquals("p3", foldedSuccessor.getCurrentPlayer().getPlayerId());

            Player p2AllIn = restoredPlayer("Charlie", "p2", 0, false, false, true);
            Game allInSuccessor = Game.restoreBuilder().gameId("succ-allin")
                    .players(List.of(p0, p1, p2AllIn, p3)).activePlayerIds(List.of("p0", "p1", "p2", "p3"))
                    .remainingDeck(List.of())
                    .currentPlayerId("p1").smallBlind(10).bigBlind(20).build();
            allInSuccessor.removePlayer(p1);
            assertEquals("p3", allInSuccessor.getCurrentPlayer().getPlayerId());

            Game tailWrap = Game.restoreBuilder().gameId("tail-wrap")
                    .players(List.of(p0, p1, p3)).activePlayerIds(List.of("p0", "p1", "p3"))
                    .remainingDeck(List.of())
                    .currentPlayerId("p3").smallBlind(10).bigBlind(20).build();
            tailWrap.removePlayer(p3);
            assertEquals("p0", tailWrap.getCurrentPlayer().getPlayerId());

            Player ai = restoredPlayer("Alice", "p0", 0, false, false, true);
            Player cur = restoredPlayer("Bob", "p1", 1000, false, false, false);
            Player fld = restoredPlayer("Charlie", "p2", 1000, false, true, false);
            Game allNonActionable = Game.restoreBuilder().gameId("non-actionable")
                    .players(List.of(ai, cur, fld)).activePlayerIds(List.of("p0", "p1", "p2"))
                    .remainingDeck(List.of())
                    .currentPlayerId("p1").smallBlind(10).bigBlind(20).build();
            allNonActionable.removePlayer(cur);
            assertEquals(2, allNonActionable.getActivePlayers().size());
            assertNotNull(allNonActionable.getCurrentPlayer());

            Game singleAllIn = Game.restoreBuilder().gameId("single-allin")
                    .players(List.of(ai, cur)).activePlayerIds(List.of("p0", "p1"))
                    .remainingDeck(List.of())
                    .currentPlayerId("p1").smallBlind(10).bigBlind(20).build();
            singleAllIn.removePlayer(cur);
            assertEquals(1, singleAllIn.getActivePlayers().size());
            assertEquals("p0", singleAllIn.getCurrentPlayer().getPlayerId());
        }

        @Test
        void rapidSequentialRemovalsForwardReverseAndCurrentMaintainBounds() {
            Game forward = createStandardGame(6, 1000);
            while (!forward.getActivePlayers().isEmpty()) {
                forward.removePlayer(forward.getActivePlayers().getFirst());
                if (!forward.getActivePlayers().isEmpty()) {
                    assertNotNull(forward.getCurrentPlayer());
                } else {
                    assertThrows(IllegalStateException.class, forward::getCurrentPlayer);
                }
            }

            Game reverse = createStandardGame(6, 1000);
            while (!reverse.getActivePlayers().isEmpty()) {
                reverse.removePlayer(reverse.getActivePlayers().getLast());
                if (!reverse.getActivePlayers().isEmpty()) {
                    assertNotNull(reverse.getCurrentPlayer());
                } else {
                    assertThrows(IllegalStateException.class, reverse::getCurrentPlayer);
                }
            }

            Game curEveryStep = createStandardGame(6, 1000);
            while (curEveryStep.getActivePlayers().size() > 1) {
                curEveryStep.removePlayer(curEveryStep.getCurrentPlayer());
                assertTrue(curEveryStep.toSnapshot().handSeatOrder().contains(curEveryStep.toSnapshot().dealerPlayerId()));
                assertNotNull(curEveryStep.getCurrentPlayer());
            }
            assertEquals(1, curEveryStep.getActivePlayers().size());
        }

        @ParameterizedTest
        @ValueSource(ints = {11, 23, 42, 77, 99, 137, 256, 512, 1024, 2048})
        void rapidRemoval_FuzzRandomPermutations(int seed) {
            Game fuzz = createStandardGame(6, 1000);
            List<Player> queue = new ArrayList<>(fuzz.getActivePlayers());
            Collections.shuffle(queue, new Random(seed));

            for (Player target : queue) {
                fuzz.removePlayer(target);
                int remaining = fuzz.getActivePlayers().size();
                if (remaining > 0) {
                    assertTrue(fuzz.toSnapshot().handSeatOrder().contains(fuzz.toSnapshot().dealerPlayerId()));
                    assertTrue(fuzz.getActivePlayers().contains(fuzz.getCurrentPlayer()));
                }
            }
            assertEquals(0, fuzz.getActivePlayers().size());
            assertThrows(IllegalStateException.class, fuzz::getCurrentPlayer);
        }

        @Test
        void threeToTwoTransitionAndSingleSurvivorResetToGameOver() {
            Game threeToTwo = createStandardGame(3, 1000);
            threeToTwo.removePlayer(threeToTwo.getActivePlayers().get(2));
            assertEquals(2, threeToTwo.getActivePlayers().size());
            assertFalse(threeToTwo.resetForNewHand());

            assertEquals("p1", threeToTwo.toSnapshot().dealerPlayerId());
            assertEquals("p1", threeToTwo.getSmallBlindPlayerId());
            assertEquals("p0", threeToTwo.getBigBlindPlayerId());
            assertEquals("p1", threeToTwo.toSnapshot().currentPlayerId());
            assertEquals("p1", threeToTwo.getCurrentPlayer().getPlayerId());

            threeToTwo.removePlayer(threeToTwo.getActivePlayers().get(1));
            assertEquals(1, threeToTwo.getActivePlayers().size());
            assertTrue(threeToTwo.resetForNewHand());
            assertTrue(threeToTwo.isGameOver());
        }

        @Test
        void departedAnchorsAndRemovalProgressionThroughStreets() {
            Player d = new Player("D", "d", 100);
            Player a = new Player("A", "a", 100);
            Player b = new Player("B", "b", 100);
            Player c = new Player("C", "c", 100);
            Game anchored = Game.restoreBuilder().gameId("position-anchor")
                    .players(List.of(d, a, b, c)).activePlayerIds(List.of("d", "a", "b", "c"))
                    .remainingDeck(List.of())
                    .pot(0).currentPlayerId("c")
                    .currentPhase(GamePhase.FLOP).smallBlind(5).bigBlind(10)
                    .handSeatOrder(List.of("d", "a", "b", "c")).dealerPlayerId("d")
                    .smallBlindPlayerId("a").bigBlindPlayerId("b").allPlayersHadInitialTurn(true)
                    .build();
            anchored.removePlayer(d);
            anchored.removePlayer(b);
            assertEquals("a", anchored.getSmallBlindPlayerId());
            assertEquals("b", anchored.getBigBlindPlayerId());
            anchored.resetForNewHand();
            assertEquals("a", anchored.toSnapshot().dealerPlayerId());
            assertEquals("a", anchored.getSmallBlindPlayerId());
            assertEquals("c", anchored.getBigBlindPlayerId());

            Player a2 = restoredPlayer("A", "a", 990, 10, false);
            Player b2 = restoredPlayer("B", "b", 980, 20, false);
            Player c2 = restoredPlayer("C", "c", 1000, 0, false);
            Player d2 = restoredPlayer("D", "d", 1000, 0, false);
            Game sbLeft = Game.restoreBuilder().gameId("post-flop-seat-anchor")
                    .players(List.of(a2, b2, c2, d2)).activePlayerIds(List.of("a", "b", "c", "d"))
                    .remainingDeck(List.of(
                            new Card(Rank.TWO, Suit.SPADES), new Card(Rank.THREE, Suit.SPADES),
                            new Card(Rank.FOUR, Suit.SPADES), new Card(Rank.FIVE, Suit.SPADES),
                            new Card(Rank.SIX, Suit.SPADES)))
                    .pot(30).currentPlayerId("c")
                    .currentHighestBet(20).currentPhase(GamePhase.PRE_FLOP).smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("a", 10, "b", 20)).handSeatOrder(List.of("a", "b", "c", "d"))
                    .dealerPlayerId("d").smallBlindPlayerId("a").bigBlindPlayerId("b")
                    .build();
            sbLeft.removePlayer(a2);
            assertEquals("c", sbLeft.getCurrentPlayer().getPlayerId());
            sbLeft.processPlayerDecision(c2, PlayerAction.CALL, 0);
            sbLeft.processPlayerDecision(d2, PlayerAction.CALL, 0);
            sbLeft.processPlayerDecision(b2, PlayerAction.CHECK, 0);
            assertEquals(GamePhase.FLOP, sbLeft.getCurrentPhase());
            assertEquals("b", sbLeft.getCurrentPlayer().getPlayerId());
            assertEquals("d", sbLeft.toSnapshot().dealerPlayerId());
            assertEquals("a", sbLeft.getSmallBlindPlayerId());

            Player da = restoredPlayer("A2", "a2", 1000, 0, false);
            Player db = restoredPlayer("B2", "b2", 1000, 0, false);
            Player dc = restoredPlayer("C2", "c2", 1000, 0, false);
            Player dd = restoredPlayer("D2", "d2", 1000, 0, false);
            Game dealerLeft = Game.restoreBuilder().gameId("departed-dealer-post-flop")
                    .players(List.of(da, db, dc, dd)).activePlayerIds(List.of("a2", "b2", "c2", "d2"))
                    .remainingDeck(List.of(new Card(Rank.SEVEN, Suit.CLUBS)))
                    .pot(0).currentPlayerId("c2")
                    .currentHighestBet(0).currentPhase(GamePhase.FLOP).smallBlind(10).bigBlind(20)
                    .handSeatOrder(List.of("a2", "b2", "c2", "d2")).dealerPlayerId("d2")
                    .build();
            dealerLeft.removePlayer(dd);
            dealerLeft.dealTurn();
            assertEquals("a2", dealerLeft.getCurrentPlayer().getPlayerId());

            Game succ = Game.restoreBuilder().gameId("leave-successor")
                    .players(List.of(
                            restoredPlayer("A", "a", 1000, 0, false),
                            restoredPlayer("B", "b", 1000, 0, false),
                            restoredPlayer("C", "c", 1000, 0, false),
                            restoredPlayer("D", "d", 1000, 0, false)))
                    .activePlayerIds(List.of("a", "b", "c", "d")).remainingDeck(List.of()).currentPlayerId("b")
                    .currentPhase(GamePhase.FLOP).smallBlind(5).bigBlind(10)
                    .handSeatOrder(List.of("a", "b", "c", "d")).dealerPlayerId("a")
                    .build();
            succ.removePlayer(succ.getPlayers().get(1));
            TurnOutcome succOutcome = succ.advanceAfterPlayerRemoval();
            assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, succOutcome.type());
            assertEquals("c", succ.getCurrentPlayer().getPlayerId());

            Game flopComplete = Game.restoreBuilder().gameId("leave-completes-flop")
                    .players(List.of(
                            restoredPlayer("A", "a", 1000, 0, false),
                            restoredPlayer("B", "b", 1000, 0, false),
                            restoredPlayer("C", "c", 1000, 0, false)))
                    .activePlayerIds(List.of("a", "b", "c"))
                    .remainingDeck(List.of(new Card(Rank.SIX, Suit.CLUBS)))
                    .communityCards(List.of(
                            new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.THREE, Suit.DIAMONDS),
                            new Card(Rank.FOUR, Suit.HEARTS)))
                    .currentPlayerId("c").currentPhase(GamePhase.FLOP).smallBlind(5).bigBlind(10)
                    .handSeatOrder(List.of("a", "b", "c")).dealerPlayerId("a")
                    .actedPlayersInRound(Set.of("a", "b")).build();
            flopComplete.removePlayer(flopComplete.getPlayers().get(2));
            TurnOutcome turnOutcome = flopComplete.advanceAfterPlayerRemoval();
            assertEquals(TurnOutcome.TurnOutcomeType.PHASE_ADVANCED, turnOutcome.type());
            assertEquals(GamePhase.TURN, flopComplete.getCurrentPhase());
            assertEquals(4, flopComplete.getCommunityCards().size());
        }

        @Test
        void headsUpBlindsRotationTurnProgressionAndBlindIdSafety() {
            Game headsUpGame = createGame("heads-up-rotate", List.of(
                    new Player("Dealer", "d1", 1000), new Player("Opponent", "o1", 1000)),
                    10, 20);
            headsUpGame.postBlinds();
            assertEquals("d1", headsUpGame.toSnapshot().dealerPlayerId());
            assertEquals(10, headsUpGame.getActivePlayers().get(0).getCurrentBet());
            assertEquals(20, headsUpGame.getActivePlayers().get(1).getCurrentBet());

            headsUpGame.resetForNewHand();
            headsUpGame.postBlinds();
            assertEquals("o1", headsUpGame.toSnapshot().dealerPlayerId());
            assertEquals(10, headsUpGame.getActivePlayers().get(1).getCurrentBet());
            assertEquals(20, headsUpGame.getActivePlayers().get(0).getCurrentBet());

            Player firstPlayer = game.getCurrentPlayer();
            game.processPlayerDecision(firstPlayer, PlayerAction.CHECK, 0);
            Player secondPlayer = game.getCurrentPlayer();
            assertNotEquals(firstPlayer, secondPlayer);
            game.processPlayerDecision(secondPlayer, PlayerAction.CHECK, 0);
            Player currentBeforeRemoval = game.getCurrentPlayer();
            Player nonCurrent = game.getActivePlayers().stream()
                    .filter(p -> !p.equals(currentBeforeRemoval)).findFirst().orElseThrow();
            game.removePlayer(nonCurrent);
            assertEquals(currentBeforeRemoval, game.getCurrentPlayer());
            assertEquals(2, game.getActivePlayers().size());

            Game blindSafety = createStandardGame(3, 1000);
            blindSafety.postBlinds();
            Player nonCurrentSb = blindSafety.getActivePlayers().get(1);
            assertNotEquals(nonCurrentSb, blindSafety.getCurrentPlayer());
            blindSafety.removePlayer(nonCurrentSb);
            String bigBlindId = assertDoesNotThrow(blindSafety::getBigBlindPlayerId);
            assertNotNull(bigBlindId);
            assertTrue(blindSafety.getActivePlayers().stream().anyMatch(p -> p.getPlayerId().equals(bigBlindId)));
        }

        @Test
        void turnRotationSkipsOpponentMarkedOutMidHand() {
            Game midHandOut = Game.restoreBuilder().gameId("mid-hand-out")
                    .players(List.of(
                            restoredPlayer("A", "a", 1000, 0, false),
                            restoredPlayer("B", "b", 1000, 0, false),
                            restoredPlayer("C", "c", 1000, 0, false)))
                    .activePlayerIds(List.of("a", "b", "c"))
                    .remainingDeck(List.of(new Card(Rank.SIX, Suit.CLUBS)))
                    .communityCards(List.of(
                            new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.THREE, Suit.DIAMONDS),
                            new Card(Rank.FOUR, Suit.HEARTS)))
                    .currentPlayerId("a").currentPhase(GamePhase.FLOP).smallBlind(5).bigBlind(10)
                    .handSeatOrder(List.of("a", "b", "c")).dealerPlayerId("a")
                    .build();

            Player actorA = midHandOut.getPlayers().get(0);
            Player outOpponentB = midHandOut.getPlayers().get(1);
            outOpponentB.setIsOut();

            TurnOutcome outcome = midHandOut.processPlayerDecision(
                    actorA, PlayerAction.CHECK, 0);

            assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, outcome.type());
            assertEquals("c", midHandOut.getCurrentPlayer().getPlayerId());
        }

        @Test
        void advanceAfterPlayerRemovalThrowsWhenNoActivePlayersRemain() {
            Game emptying = createStandardGame(2, 1000);
            emptying.removePlayer(emptying.getPlayers().get(1));
            emptying.removePlayer(emptying.getPlayers().get(0));

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    emptying::advanceAfterPlayerRemoval);
            assertEquals("No active players remain after removal", ex.getMessage());
        }
    }

    @Nested
    class ReadyGateAndSchedulingDelegationTests {

        @Test
        void stateRevisionIncrementsAndResetsAcrossBeginHandAndRestore() {
            Game revGame = createStandardGame(2, 1000);
            assertEquals(0L, revGame.getStateRevision());
            assertEquals(0L, revGame.getHandNumber());

            revGame.beginHand();
            assertEquals(1L, revGame.getHandNumber());
            assertEquals(1L, revGame.getStateRevision());

            for (int i = 0; i < 25; i++) {
                revGame.incrementStateRevision();
            }
            assertEquals(26L, revGame.getStateRevision());

            revGame.beginHand();
            assertEquals(2L, revGame.getHandNumber());
            assertEquals(1L, revGame.getStateRevision());

            Game restored = Game.restoreBuilder().gameId("game-restore-rev")
                    .players(List.of(new Player("Alice", "p1", 1000), new Player("Bob", "p2", 1000)))
                    .activePlayerIds(List.of("p1", "p2")).remainingDeck(List.of()).smallBlind(10).bigBlind(20)
                    .handNumber(42L).stateRevision(199L)
                    .scheduledTimes(Map.of()).timerVersions(Map.of()).build();
            assertEquals(42L, restored.getHandNumber());
            assertEquals(199L, restored.getStateRevision());
            restored.incrementStateRevision();
            assertEquals(200L, restored.getStateRevision());
            restored.beginHand();
            assertEquals(43L, restored.getHandNumber());
            assertEquals(1L, restored.getStateRevision());
        }

        private static void dealToRiverAndShowdown(Game target) {
            target.dealHoleCards();
            target.dealFlop();
            target.dealTurn();
            target.dealRiver();
            target.conductShowdown();
        }

        @Test
        void readyGateAndSchedulingDelegationOnGameAggregateRoot() {
            Player p1 = new Player("Alice", "p1", 1000);
            Player p2 = new Player("Bob", "p2", 1000);
            Game aggGame = createGame("game-adversarial-1", List.of(p1, p2), 10, 20);

            assertEquals(ReadyGateState.CLOSED, aggGame.getReadyGateState());
            assertFalse(aggGame.isReadyCountdownActive());
            assertNull(aggGame.getReadyCountdownDeadlineEpochMs());
            assertNull(aggGame.getGameEndResult());

            dealToRiverAndShowdown(aggGame);
            long deadline = 50_000L;
            assertEquals(ReadyCountdownStartOutcome.OPENED, aggGame.openOrWaitReadyGate(deadline));
            assertEquals(ReadyGateState.COUNTING_DOWN, aggGame.getReadyGateState());
            assertTrue(aggGame.isReadyCountdownActive());
            assertEquals(deadline, aggGame.getReadyCountdownDeadlineEpochMs());
            assertEquals(deadline, aggGame.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));

            assertEquals(ReadyCountdownTimeoutOutcome.FORCED_READY, aggGame.expireReadyCountdown(deadline));
            assertTrue(p1.isReadyForNextHand());
            assertTrue(p2.isReadyForNextHand());
            assertTrue(aggGame.areAllEligiblePlayersReady());

            aggGame.closeReadyCountdown();
            assertEquals(ReadyGateState.CLOSED, aggGame.getReadyGateState());
            assertTrue(p1.isReadyForNextHand());

            aggGame.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 1500L);
            aggGame.scheduleTask(ScheduledGameTask.CLEANUP, 60000L);
            assertEquals(1500L, aggGame.getScheduledTaskTime(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, aggGame.getTimerVersion(ScheduledGameTask.AUTO_ADVANCE));
            aggGame.clearScheduledTask(ScheduledGameTask.CLEANUP);
            assertNull(aggGame.getScheduledTaskTime(ScheduledGameTask.CLEANUP));

            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                aggGame.scheduleTask(task, 70_000L + task.ordinal());
            }
            Map<ScheduledGameTask, Long> versionsBeforeClearAll = aggGame.toSnapshot().timerVersions();
            aggGame.clearReadyCountdownAndScheduledTasks();
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                assertNull(aggGame.getScheduledTaskTime(task));
                assertEquals(versionsBeforeClearAll.get(task) + 1, aggGame.getTimerVersion(task));
            }

            PublicGameStateResponse endResponse = new PublicGameStateResponse(
                    2, 0, List.of(), 0, GamePhase.SHOWDOWN, 0, List.of(), List.of(), null, null,
                    List.of("Alice"), null, false, null, false, null, false, null, true, 0,
                    List.of(), null, null, 1L, ReadyGateState.CLOSED, 1_700_000_000_000L, 1L,
                    null, null, PublicGameStateResponse.GameEnd.won("Alice", 2000, false, "Game Over"));
            aggGame.setGameEndResult(endResponse);
            assertSame(endResponse, aggGame.getGameEndResult());
        }

        @Test
        void abandonDueToInactivity_MarksGameOverClosesReadyGateAndClearsScheduledTasks() {
            Game inactiveGame = createStandardGame(2, 1000);
            dealToRiverAndShowdown(inactiveGame);
            inactiveGame.openOrWaitReadyGate(50_000L);
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                inactiveGame.scheduleTask(task, 80_000L + task.ordinal());
            }

            inactiveGame.abandonDueToInactivity();

            assertTrue(inactiveGame.isGameOver());
            assertTrue(inactiveGame.isMatchConcluded());
            assertEquals(ReadyGateState.CLOSED, inactiveGame.getReadyGateState());
            assertFalse(inactiveGame.isReadyCountdownActive());
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                assertNull(inactiveGame.getScheduledTaskTime(task));
            }
        }

        @Test
        void pauseForDisconnectedSurvivor_MarksLeaversOutHoldsGameAndKeepsCleanup() {
            Game pauseGame = createStandardGame(3, 1000);
            dealToRiverAndShowdown(pauseGame);
            pauseGame.openOrWaitReadyGate(50_000L);
            pauseGame.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 1500L);
            pauseGame.scheduleTask(ScheduledGameTask.CLEANUP, 60000L);
            Player survivor = pauseGame.getPlayers().get(2);
            survivor.setDisconnected(true);
            List<Player> leaving = List.of(pauseGame.getPlayers().get(0), pauseGame.getPlayers().get(1));

            pauseGame.pauseForDisconnectedSurvivor(leaving);

            assertTrue(leaving.stream().allMatch(Player::isOut));
            assertFalse(survivor.isOut());
            assertTrue(pauseGame.isPausedForDisconnectedSurvivor());
            assertFalse(pauseGame.isGameOver());
            assertFalse(pauseGame.isMatchConcluded());
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, pauseGame.getReadyGateState());
            assertNull(pauseGame.getScheduledTaskTime(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(60000L, pauseGame.getScheduledTaskTime(ScheduledGameTask.CLEANUP));

            pauseGame.clearPausedForDisconnectedSurvivor();
            assertFalse(pauseGame.isPausedForDisconnectedSurvivor());
            assertTrue(pauseGame.isMatchConcluded());
        }

        @Test
        void readyGateExcludesZeroChipPlayersAndHandlesRepairAndTransitions() {
            Game readyGame = createStandardGame(3, 1000);
            dealToRiverAndShowdown(readyGame);
            readyGame.openOrWaitReadyGate(50_000L);
            readyGame.getPlayers().get(1).doAction(PlayerAction.ALL_IN, 0);
            readyGame.getPlayers().get(0).setReadyForNextHand(true);
            assertFalse(readyGame.areAllEligiblePlayersReady());
            readyGame.getPlayers().get(2).setReadyForNextHand(true);
            assertTrue(readyGame.areAllEligiblePlayersReady());

            Game fewerOpen = createStandardGame(2, 1000);
            dealToRiverAndShowdown(fewerOpen);
            fewerOpen.getPlayers().get(1).setDisconnected(true);
            fewerOpen.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 99_999L);
            assertEquals(ReadyCountdownStartOutcome.WAITING_FOR_PLAYERS, fewerOpen.openOrWaitReadyGate(50_000L));
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, fewerOpen.getReadyGateState());
            assertNull(fewerOpen.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));

            Game fewerExpire = createStandardGame(2, 1000);
            dealToRiverAndShowdown(fewerExpire);
            fewerExpire.openOrWaitReadyGate(50_000L);
            fewerExpire.getPlayers().get(1).setDisconnected(true);
            assertEquals(ReadyCountdownTimeoutOutcome.WAITING_FOR_PLAYERS, fewerExpire.expireReadyCountdown(50_000L));
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, fewerExpire.getReadyGateState());
            assertNull(fewerExpire.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));

            Player r1 = new Player("Alice", "p1", 1000);
            Player r2 = new Player("Bob", "p2", 1000);
            Game repairMissing = Game.restoreBuilder().gameId("repair-missing").players(List.of(r1, r2))
                    .activePlayerIds(List.of("p1", "p2")).remainingDeck(List.of()).smallBlind(10).bigBlind(20)
                    .currentPhase(GamePhase.SHOWDOWN).readyGateState(ReadyGateState.COUNTING_DOWN)
                    .readyCountdownDeadlineEpochMs(null).build();
            assertTrue(repairMissing.repairRecoveredReadyGate(60_000L));
            assertTrue(repairMissing.isReadyCountdownActive());
            assertEquals(60_000L, repairMissing.getReadyCountdownDeadlineEpochMs());
            assertEquals(60_000L, repairMissing.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));

            Game repairReschedule = Game.restoreBuilder().gameId("repair-resched").players(List.of(r1, r2))
                    .activePlayerIds(List.of("p1", "p2")).remainingDeck(List.of()).smallBlind(10).bigBlind(20)
                    .currentPhase(GamePhase.SHOWDOWN).readyGateState(ReadyGateState.COUNTING_DOWN)
                    .readyCountdownDeadlineEpochMs(70_000L).build();
            assertFalse(repairReschedule.repairRecoveredReadyGate(60_000L));
            assertEquals(70_000L, repairReschedule.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        void openOrWaitReadyGate_IgnoresPausedForDisconnectedSurvivorGameOverNonShowdownAndStopsWhenDecided() {
            Player p1 = restoredPlayer("Alice", "p1", 1000, 0, false);
            Player p2 = restoredPlayer("Bob", "p2", 1000, 0, false);

            Game pausedGame = Game.restoreBuilder().gameId("rg-forfeit")
                    .players(List.of(p1, p2)).activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of()).smallBlind(10).bigBlind(20)
                    .currentPhase(GamePhase.SHOWDOWN).pausedForDisconnectedSurvivor(true).build();
            assertEquals(ReadyCountdownStartOutcome.IGNORE, pausedGame.openOrWaitReadyGate(50_000L));
            assertEquals(ReadyGateState.CLOSED, pausedGame.getReadyGateState());
            assertNull(pausedGame.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));

            Game gameOverGame = Game.restoreBuilder().gameId("rg-over")
                    .players(List.of(p1, p2)).activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of()).smallBlind(10).bigBlind(20)
                    .currentPhase(GamePhase.SHOWDOWN).gameOver(true).build();
            assertEquals(ReadyCountdownStartOutcome.IGNORE, gameOverGame.openOrWaitReadyGate(50_000L));
            assertEquals(ReadyGateState.CLOSED, gameOverGame.getReadyGateState());
            assertNull(gameOverGame.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));

            Player broke = restoredPlayer("Bob", "p2", 0, 0, false);
            Game decidedGame = Game.restoreBuilder().gameId("rg-decided")
                    .players(List.of(p1, broke)).activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of()).smallBlind(10).bigBlind(20)
                    .currentPhase(GamePhase.SHOWDOWN).build();
            assertEquals(ReadyCountdownStartOutcome.GAME_DECIDED, decidedGame.openOrWaitReadyGate(50_000L));
            assertEquals(ReadyGateState.CLOSED, decidedGame.getReadyGateState());
            assertNull(decidedGame.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));

            Game turnGame = Game.restoreBuilder().gameId("rg-turn")
                    .players(List.of(p1, p2)).activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of()).smallBlind(10).bigBlind(20)
                    .currentPhase(GamePhase.TURN).build();
            assertEquals(ReadyCountdownStartOutcome.IGNORE, turnGame.openOrWaitReadyGate(50_000L));
            assertEquals(ReadyGateState.CLOSED, turnGame.getReadyGateState());
            assertNull(turnGame.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));
        }
    }

    @Nested
    class StateRevisionTests {

        @Test
        void incrementStateRevision_MonotonicAcrossSequence() {
            Game revGame = createStandardGame(2, 1000);
            revGame.incrementStateRevision();
            assertEquals(1L, revGame.getStateRevision());
            revGame.incrementStateRevision();
            assertEquals(2L, revGame.getStateRevision());
            revGame.incrementStateRevision();
            assertEquals(3L, revGame.getStateRevision());

            Game nearMax = Game.restoreBuilder()
                    .gameId("near-max")
                    .players(List.of(new Player("Alice", "p1", 1000), new Player("Bob", "p2", 1000)))
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .stateRevision(Long.MAX_VALUE - 1)
                    .build();
            nearMax.incrementStateRevision();
            assertEquals(Long.MAX_VALUE, nearMax.getStateRevision());
        }

        @Test
        void resetStateRevisionForNewHand_ResetsToInitialHandRevision() {
            Game revGame = createStandardGame(2, 1000);
            revGame.incrementStateRevision();
            revGame.incrementStateRevision();

            revGame.resetStateRevisionForNewHand();

            assertEquals(1L, Game.INITIAL_HAND_REVISION);
            assertEquals(1L, revGame.getStateRevision());
        }
    }

    @Nested
    @DisplayName("Eligibility and winner resolution rules")
    class EligibilityAndWinnerResolutionTests {

        private static Player eligibilityPlayer(
                String id, String name, int chips, boolean out, boolean disconnected) {
            return Player.restoreBuilder()
                    .playerId(id)
                    .name(name)
                    .chips(chips)
                    .currentBet(0)
                    .holeCards(List.of())
                    .bestHand(List.of())
                    .handRank(HandRank.NO_HAND)
                    .hasFolded(false)
                    .allIn(false)
                    .out(out)
                    .disconnected(disconnected)
                    .readyForNextHand(false)
                    .build();
        }

        private static Game eligibilityGameWith(
                List<Player> players, GamePhase phase, boolean settled, boolean gameOver) {
            return Game.restoreBuilder()
                    .gameId("elig-game")
                    .players(players)
                    .activePlayerIds(players.stream().map(Player::getPlayerId).toList())
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(0)
                    .dealerPlayerId(players.isEmpty() ? null : players.getFirst().getPlayerId())
                    .smallBlindPlayerId(players.isEmpty() ? null : players.getFirst().getPlayerId())
                    .bigBlindPlayerId(players.size() > 1 ? players.get(1).getPlayerId() : players.isEmpty() ? null : players.getFirst().getPlayerId())
                    .currentPlayerId(players.isEmpty() ? null : players.getFirst().getPlayerId())
                    .currentHighestBet(0)
                    .carryOverPot(0)
                    .currentPhase(phase)
                    .gameOver(gameOver)
                    .smallBlind(10)
                    .bigBlind(20)
                    .handContributions(Map.of())
                    .lastHandPayout(settled ? new HandPayout(Map.of("p1", 100), Map.of(), Set.of("p1"), 0, false) : null)
                    .actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of())
                    .build();
        }

        @Test
        @DisplayName("isContender excludes eliminated players and zero-chip players after showdown settles")
        void isContenderRespectsOutStatusAndSettledZeroChipExclusion() {
            Player outPlayer = eligibilityPlayer("p1", "Out", 500, true, false);
            Player bustedConnected = eligibilityPlayer("p2", "Busted", 0, false, false);
            Player alive = eligibilityPlayer("p3", "Alive", 1000, false, false);

            Game midHand = eligibilityGameWith(List.of(outPlayer, bustedConnected, alive), GamePhase.TURN, false, false);
            assertFalse(midHand.isContender(outPlayer));
            assertTrue(midHand.isContender(bustedConnected));
            assertTrue(midHand.isContender(alive));
            assertEquals(2, midHand.countContenders());

            Game settledShowdown = eligibilityGameWith(List.of(outPlayer, bustedConnected, alive), GamePhase.SHOWDOWN, true, false);
            assertFalse(settledShowdown.isContender(bustedConnected));
            assertTrue(settledShowdown.isContender(alive));
            assertEquals(1, settledShowdown.countContenders());
        }

        @Test
        @DisplayName("findClaimWinPlayer and canClaimWin permit only the sole connected contender")
        void disconnectClaimRequiresSoleConnectedContenderWithDisconnectedOpponent() {
            Player alice = eligibilityPlayer("p1", "Alice", 1000, false, false);
            Player bob = eligibilityPlayer("p2", "Bob", 1000, false, true);
            Player carol = eligibilityPlayer("p3", "Carol", 1000, false, true);

            Game claimable = eligibilityGameWith(List.of(alice, bob, carol), GamePhase.FLOP, false, false);
            assertEquals(Optional.of(alice), claimable.findClaimWinPlayer());
            assertTrue(claimable.canClaimWin("Alice"));
            assertFalse(claimable.canClaimWin("Bob"));
            assertFalse(claimable.canClaimWin("Unknown"));
            assertEquals(List.of("Bob", "Carol"), claimable.disconnectedOpponentsOf("Alice"));

            Player dan = eligibilityPlayer("p4", "Dan", 1000, false, false);
            Game twoConnected = eligibilityGameWith(List.of(alice, bob, dan), GamePhase.FLOP, false, false);
            assertEquals(Optional.empty(), twoConnected.findClaimWinPlayer());
            assertFalse(twoConnected.canClaimWin("Alice"));

            Game allConnected = eligibilityGameWith(List.of(alice, dan), GamePhase.PRE_FLOP, false, false);
            assertEquals(Optional.empty(), allConnected.findClaimWinPlayer());

            Game allDisconnected = eligibilityGameWith(List.of(bob, carol), GamePhase.FLOP, false, false);
            assertEquals(Optional.empty(), allDisconnected.findClaimWinPlayer());
        }

        @Test
        @DisplayName("findClaimWinPlayer returns empty once the game is over or settled and decided")
        void findClaimWinPlayerStopsWhenGameOverOrSettledAndDecided() {
            Player alice = eligibilityPlayer("p1", "Alice", 1000, false, false);
            Player bob = eligibilityPlayer("p2", "Bob", 0, false, true);

            Game gameOver = eligibilityGameWith(List.of(alice, bob), GamePhase.TURN, false, true);
            assertEquals(Optional.empty(), gameOver.findClaimWinPlayer());

            // Unsettled showdown with a disconnected 0-chip all-in player still counts 2 contenders
            Game unsettledShowdown = eligibilityGameWith(List.of(alice, bob), GamePhase.SHOWDOWN, false, false);
            assertEquals(Optional.of(alice), unsettledShowdown.findClaimWinPlayer());

            // Once settled, 0-chip Bob is no longer a contender and game is decided
            Game settledDecided = eligibilityGameWith(List.of(alice, bob), GamePhase.SHOWDOWN, true, false);
            settledDecided.setGameEndResult(new PublicGameStateResponse(
                    2, 0, List.of(), 0, GamePhase.SHOWDOWN, 0, List.of(), List.of(), null, null,
                    List.of("Alice"), null, false, null, false, null, false, null, true, 0,
                    List.of(), null, null, 1L, ReadyGateState.CLOSED, 1_700_000_000_000L, 1L,
                    null, null, PublicGameStateResponse.GameEnd.won("Alice", 1000, false, "Complete")));
            assertEquals(Optional.empty(), settledDecided.findClaimWinPlayer());
        }

        @Test
        @DisplayName("isGameDecided handles empty roster, single chip holder, and multiple chip holders")
        void isGameDecidedEvaluatesRemainingChipHolders() {
            Game emptyGame = Game.restoreBuilder().gameId("empty").players(List.of()).activePlayerIds(List.of())
                    .remainingDeck(List.of())
                    .build();
            assertTrue(emptyGame.isGameDecided());

            Player p1 = eligibilityPlayer("p1", "Alice", 1000, false, false);
            Player p2OutWithChips = eligibilityPlayer("p2", "Bob", 500, true, false);
            Player p3Broke = eligibilityPlayer("p3", "Carol", 0, false, false);
            Game singleChipHolder = eligibilityGameWith(List.of(p1, p2OutWithChips, p3Broke), GamePhase.SHOWDOWN, true, false);
            assertTrue(singleChipHolder.isGameDecided());

            Player p4 = eligibilityPlayer("p4", "Dan", 10, false, false);
            Game multipleChipHolders = eligibilityGameWith(List.of(p1, p4), GamePhase.SHOWDOWN, true, false);
            assertFalse(multipleChipHolders.isGameDecided());
        }

        @Test
        @DisplayName("resolveWinner returns eligible preferred winner or chip leader with earliest-seat tiebreak")
        void resolveWinnerSelectsEligiblePreferredWinnerOrChipLeader() {
            Player alice = eligibilityPlayer("p1", "Alice", 1000, true, false);
            Player bob = eligibilityPlayer("p2", "Bob", 1000, false, false);
            Player carol = eligibilityPlayer("p3", "Carol", 1000, false, false);
            Game tiedGame = eligibilityGameWith(List.of(alice, bob, carol), GamePhase.SHOWDOWN, true, true);

            assertSame(bob, tiedGame.resolveWinner(null));
            assertSame(carol, tiedGame.resolveWinner(carol));

            Player richCarol = eligibilityPlayer("p3", "Carol", 2000, false, false);
            Game chipLeadGame = eligibilityGameWith(List.of(alice, bob, richCarol), GamePhase.SHOWDOWN, true, true);
            assertSame(richCarol, chipLeadGame.resolveWinner(null));
        }

        @Test
        @DisplayName("resolveWinner throws IllegalStateException when every player is out or roster is empty")
        void resolveWinnerThrowsWhenAllOutOrEmptyRoster() {
            Player allOutDc = eligibilityPlayer("p1", "Alice", 0, true, true);
            Player allOutConnected = eligibilityPlayer("p2", "Bob", 0, true, false);
            Game allOutGame = eligibilityGameWith(List.of(allOutDc, allOutConnected), GamePhase.SHOWDOWN, true, true);
            assertThrows(IllegalStateException.class, () -> allOutGame.resolveWinner(null));

            Game emptyGame = Game.restoreBuilder().gameId("empty").players(List.of()).activePlayerIds(List.of())
                    .remainingDeck(List.of())
                    .build();
            assertThrows(IllegalStateException.class, () -> emptyGame.resolveWinner(null));
        }

        @Test
        @DisplayName("resolveWinner throws IllegalStateException when preferred winner is out, off-roster, or roster is empty")
        void resolveWinnerThrowsWhenPreferredWinnerIsOutOrOffRoster() {
            Player aliceOut = eligibilityPlayer("p1", "Alice", 1000, true, false);
            Player bob = eligibilityPlayer("p2", "Bob", 1000, false, false);
            Game game = eligibilityGameWith(List.of(aliceOut, bob), GamePhase.SHOWDOWN, true, true);

            assertThrows(IllegalStateException.class, () -> game.resolveWinner(aliceOut));

            Player outsider = eligibilityPlayer("p9", "Outsider", 5000, false, false);
            assertThrows(IllegalStateException.class, () -> game.resolveWinner(outsider));

            Game emptyGame = Game.restoreBuilder().gameId("empty").players(List.of()).activePlayerIds(List.of())
                    .remainingDeck(List.of())
                    .build();
            assertThrows(IllegalStateException.class, () -> emptyGame.resolveWinner(bob));
        }
    }

    @Nested
    @DisplayName("Snapshot restore builder")
    class SnapshotRestoreTests {

        private static final String RESTORE_GAME_ID = "restore-test-game";

        private static Player restorePlayer(String id, String name, int chips) {
            return Player.restoreBuilder()
                    .playerId(id)
                    .name(name)
                    .chips(chips)
                    .currentBet(0)
                    .holeCards(List.of())
                    .bestHand(List.of())
                    .handRank(HandRank.NO_HAND)
                    .hasFolded(false)
                    .allIn(false)
                    .out(false)
                    .disconnected(false)
                    .readyForNextHand(false)
                    .build();
        }

        private static Game.GameRestoreBuilder validRestoreBuilder() {
            Player p1 = restorePlayer("p1", "Alice", 1000);
            Player p2 = restorePlayer("p2", "Bob", 1000);
            return Game.restoreBuilder()
                    .gameId(RESTORE_GAME_ID)
                    .players(List.of(p1, p2))
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(0)
                    .currentPlayerId("p1")
                    .currentHighestBet(0)
                    .carryOverPot(0)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .gameOver(false)
                    .smallBlind(5)
                    .bigBlind(10)
                    .handContributions(Map.of())
                    .lastHandPayout(null)
                    .lastFullRaise(0)
                    .previousBets(Map.of())
                    .handSeatOrder(List.of())
                    .dealerPlayerId("p1")
                    .smallBlindPlayerId("p1")
                    .bigBlindPlayerId("p2")
                    .finalPotSettled(false)
                    .pausedForDisconnectedSurvivor(false)
                    .readyCountdownDeadlineEpochMs(null)
                    .readyGateState(ReadyGateState.CLOSED)
                    .handNumber(1L)
                    .stateRevision(1L)
                    .gameEndResult(null)
                    .allPlayersHadInitialTurn(false)
                    .actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of())
                    .timerVersions(Map.of());
        }

        @Test
        @DisplayName("Restores full snapshot state and respects whether showdown settlement already ran")
        void restoresFullSnapshotAndShowdownSettlementState() {
            Player p1 = restorePlayer("p1", "Alice", 1000);
            p1.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
            Player p2 = restorePlayer("p2", "Bob", 800);
            p2.setHoleCards(List.of(new Card(Rank.TWO, Suit.HEARTS), new Card(Rank.THREE, Suit.CLUBS)));
            Player p3 = restorePlayer("p3", "Charlie", 600);
            List<Card> deckCards = List.of(
                    new Card(Rank.TWO, Suit.CLUBS),
                    new Card(Rank.THREE, Suit.DIAMONDS),
                    new Card(Rank.FOUR, Suit.HEARTS));
            List<Card> community = List.of(
                    new Card(Rank.TEN, Suit.SPADES),
                    new Card(Rank.JACK, Suit.SPADES),
                    new Card(Rank.QUEEN, Suit.SPADES));
            Map<String, Integer> contributions = Map.of("p1", 200, "p2", 250);
            HandPayout payout = new HandPayout(Map.of("p1", 450), Map.of(), Set.of("p1"), 0, true);
            Map<String, Integer> previousBets = Map.of("p1", 100, "p2", 100);
            List<String> seatOrder = List.of("p2", "p3", "p1");
            Set<String> actedPlayers = Set.of("p1", "p2");
            Map<ScheduledGameTask, Long> scheduledTimes = Map.of(
                    ScheduledGameTask.AUTO_ADVANCE, 1700000005000L,
                    ScheduledGameTask.CLEANUP, 1700000060000L);
            Map<ScheduledGameTask, Long> timerVersions = Map.of(
                    ScheduledGameTask.AUTO_ADVANCE, 3L,
                    ScheduledGameTask.CLEANUP, 1L);
            PublicGameStateResponse finishedResult = new PublicGameStateResponse(
                    3, 0, List.of(), 0, GamePhase.SHOWDOWN, 0, List.of(), List.of(), null, null,
                    List.of("Alice"), null, false, null, false, null, false, null, true, 0,
                    List.of(), null, null, 1L, ReadyGateState.CLOSED, 1_700_000_000_000L, 1L,
                    null, null, PublicGameStateResponse.GameEnd.won("Alice", 1450, false, "Alice won"));

            Game settledGame = Game.restoreBuilder()
                    .gameId(RESTORE_GAME_ID)
                    .maxPlayers(6)
                    .players(List.of(p1, p2, p3))
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(deckCards)
                    .communityCards(community)
                    .pot(475)
                    .currentPlayerId("p1")
                    .currentHighestBet(100)
                    .carryOverPot(25)
                    .currentPhase(GamePhase.FLOP)
                    .gameOver(false)
                    .smallBlind(15)
                    .bigBlind(30)
                    .handContributions(contributions)
                    .lastHandPayout(payout)
                    .lastFullRaise(70)
                    .previousBets(previousBets)
                    .handSeatOrder(seatOrder)
                    .dealerPlayerId("p2")
                    .smallBlindPlayerId("p3")
                    .bigBlindPlayerId("p1")
                    .finalPotSettled(true)
                    .pausedForDisconnectedSurvivor(true)
                    .readyCountdownDeadlineEpochMs(1700000000000L)
                    .readyGateState(ReadyGateState.COUNTING_DOWN)
                    .handNumber(7L)
                    .stateRevision(88L)
                    .gameEndResult(finishedResult)
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(actedPlayers)
                    .scheduledTimes(scheduledTimes)
                    .timerVersions(timerVersions)
                    .build();

            assertEquals(RESTORE_GAME_ID, settledGame.getGameId());
            assertEquals(6, settledGame.getMaxPlayers());
            assertEquals(List.of(p1, p2, p3), settledGame.getPlayers());
            assertEquals(List.of(p1, p2), settledGame.getActivePlayers());
            assertEquals(community, settledGame.getCommunityCards());
            assertEquals(475, settledGame.getPot());
            assertEquals(100, settledGame.getCurrentHighestBet());
            assertEquals(GamePhase.FLOP, settledGame.getCurrentPhase());
            assertFalse(settledGame.isGameOver());
            assertEquals(15, settledGame.getSmallBlind());
            assertEquals(30, settledGame.getBigBlind());
            assertEquals("p3", settledGame.getSmallBlindPlayerId());
            assertEquals("p1", settledGame.getBigBlindPlayerId());
            assertEquals(ReadyGateState.COUNTING_DOWN, settledGame.getReadyGateState());
            assertEquals(1700000000000L, settledGame.getReadyCountdownDeadlineEpochMs());
            Game.Snapshot snapshot = settledGame.toSnapshot();
            assertEquals(deckCards, snapshot.remainingDeck());
            assertEquals(25, snapshot.carryOverPot());
            assertEquals(contributions, snapshot.handContributions());
            assertTrue(snapshot.allPlayersHadInitialTurn());
            assertEquals(actedPlayers, snapshot.actedPlayerIds());
            assertEquals(scheduledTimes, snapshot.scheduledTimes());
            assertEquals(timerVersions, snapshot.timerVersions());
            assertEquals(70, snapshot.lastFullRaise());
            assertEquals(previousBets, snapshot.previousBets());
            assertEquals(seatOrder, snapshot.handSeatOrder());
            assertEquals("p2", snapshot.dealerPlayerId());
            assertEquals("p1", snapshot.currentPlayerId());
            assertEquals(payout, settledGame.getLastHandPayout());
            assertTrue(settledGame.isFinalPotSettled());
            assertTrue(settledGame.isPausedForDisconnectedSurvivor());
            assertEquals(7L, settledGame.getHandNumber());
            assertEquals(88L, settledGame.getStateRevision());
            assertEquals(finishedResult, settledGame.getGameEndResult());
            assertEquals(3L, settledGame.getTimerVersion(ScheduledGameTask.AUTO_ADVANCE));

            // Settled hand stays idempotent on showdown
            settledGame.conductShowdown();
            assertEquals(1000, p1.getChips());
            assertEquals(800, p2.getChips());

            // Unsettled restored hand evaluates and awards the pot at showdown
            Player u1 = restorePlayer("p1", "Alice", 1000);
            u1.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
            Player u2 = restorePlayer("p2", "Bob", 800);
            u2.setHoleCards(List.of(new Card(Rank.TWO, Suit.HEARTS), new Card(Rank.THREE, Suit.CLUBS)));
            Game unsettledGame = Game.restoreBuilder()
                    .gameId(RESTORE_GAME_ID)
                    .players(List.of(u1, u2))
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .communityCards(community)
                    .pot(200)
                    .currentPlayerId("p1")
                    .currentHighestBet(100)
                    .currentPhase(GamePhase.FLOP)
                    .smallBlind(15)
                    .bigBlind(30)
                    .handContributions(Map.of("p1", 100, "p2", 100))
                    .dealerPlayerId("p2")
                    .smallBlindPlayerId("p2")
                    .bigBlindPlayerId("p1")
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of())
                    .build();

            unsettledGame.conductShowdown();
            assertEquals(Set.of("p1"), unsettledGame.getLastHandPayout().winnerIds());
            assertEquals(1200, u1.getChips());
            assertEquals(HandRank.ROYAL_FLUSH, u1.getHandRank());
        }

        @Test
        @DisplayName("Rejects corrupt snapshot inputs instead of silently inventing defaults")
        void rejectsCorruptSnapshotInputs() {
            for (String invalidId : Arrays.asList(null, "", "   ", "\t\n")) {
                IllegalStateException ex = assertThrows(IllegalStateException.class,
                        () -> validRestoreBuilder().gameId(invalidId).build());
                assertEquals("Game ID cannot be null or empty", ex.getMessage());
            }

            IllegalStateException nullPlayersEx = assertThrows(IllegalStateException.class,
                    () -> validRestoreBuilder().players(null).build());
            assertEquals("Invalid players list. Please try again.", nullPlayersEx.getMessage());

            List<Player> playersWithNull = new ArrayList<>();
            playersWithNull.add(restorePlayer("p1", "Alice", 1000));
            playersWithNull.add(null);
            IllegalStateException nullElementEx = assertThrows(IllegalStateException.class,
                    () -> validRestoreBuilder().players(playersWithNull).activePlayerIds(List.of("p1")).build());
            assertEquals("Invalid players list. Please try again.", nullElementEx.getMessage());

            for (int invalidMax : new int[]{1, 11}) {
                IllegalStateException maxEx = assertThrows(IllegalStateException.class,
                        () -> validRestoreBuilder().maxPlayers(invalidMax).build());
                assertEquals("Max players must be between 2 and 10.", maxEx.getMessage());
            }

            IllegalStateException unknownActiveEx = assertThrows(IllegalStateException.class,
                    () -> validRestoreBuilder().activePlayerIds(List.of("p1", "unknown-player")).build());
            assertEquals("Snapshot references an unknown active player", unknownActiveEx.getMessage());

            List<String> activeWithNull = new ArrayList<>();
            activeWithNull.add("p1");
            activeWithNull.add(null);
            assertThrows(IllegalStateException.class,
                    () -> validRestoreBuilder().activePlayerIds(activeWithNull).build());

            // Null remainingDeck must fail rather than inventing a fresh 52-card deck
            assertThrows(IllegalStateException.class,
                    () -> validRestoreBuilder().remainingDeck(null).build());
            assertThrows(NullPointerException.class,
                    () -> validRestoreBuilder().readyGateState(null).build());
        }

        @Test
        @DisplayName("Preserves restored deck draw sequence and fails cleanly when exhausted")
        void preservesRestoredDeckDrawOrderAndEmptyGuards() {
            Card card1 = new Card(Rank.TWO, Suit.CLUBS);
            Card card2 = new Card(Rank.THREE, Suit.DIAMONDS);
            Card card3 = new Card(Rank.ACE, Suit.SPADES);
            Game game = validRestoreBuilder()
                    .currentPhase(GamePhase.FLOP)
                    .communityCards(List.of(
                            new Card(Rank.TEN, Suit.HEARTS),
                            new Card(Rank.JACK, Suit.HEARTS),
                            new Card(Rank.QUEEN, Suit.HEARTS)))
                    .remainingDeck(List.of(card1, card2, card3))
                    .build();

            game.dealTurn();
            assertEquals(4, game.getCommunityCards().size());
            assertEquals(card3, game.getCommunityCards().get(3));
            assertEquals(List.of(card1, card2), game.toSnapshot().remainingDeck());

            Game oneCardGame = validRestoreBuilder()
                    .communityCards(List.of(
                            new Card(Rank.TWO, Suit.CLUBS),
                            new Card(Rank.THREE, Suit.CLUBS),
                            new Card(Rank.FOUR, Suit.CLUBS)))
                    .remainingDeck(List.of(new Card(Rank.SEVEN, Suit.DIAMONDS)))
                    .build();
            oneCardGame.dealTurn();
            assertTrue(oneCardGame.toSnapshot().remainingDeck().isEmpty());
            assertThrows(IllegalStateException.class, oneCardGame::dealRiver);
        }

        @Test
        @DisplayName("Isolates restored collections from caller mutations before and after build")
        void isolatesCollectionsBeforeAndAfterBuild() {
            Player p1 = restorePlayer("p1", "Alice", 1000);
            Player p2 = restorePlayer("p2", "Bob", 1000);
            Player p3 = restorePlayer("p3", "Charlie", 1000);

            List<Player> mutablePlayers = new ArrayList<>(List.of(p1, p2));
            List<String> mutableActiveIds = new ArrayList<>(List.of("p1", "p2"));
            List<Card> mutableCommunity = new ArrayList<>(List.of(new Card(Rank.ACE, Suit.SPADES)));
            List<Card> mutableDeck = new ArrayList<>(List.of(new Card(Rank.TWO, Suit.HEARTS)));
            Map<String, Integer> mutableContribs = new HashMap<>(Map.of("p1", 50));
            Map<String, Integer> mutableWagers = new HashMap<>(Map.of("p1", 20));
            List<String> mutableSeatOrder = new ArrayList<>(List.of("p1", "p2"));
            Set<String> mutableActed = new HashSet<>(Set.of("p1"));
            Map<ScheduledGameTask, Long> mutableDeadlines = new EnumMap<>(ScheduledGameTask.class);
            mutableDeadlines.put(ScheduledGameTask.AUTO_ADVANCE, 5000L);
            Map<ScheduledGameTask, Long> mutableVersions = new EnumMap<>(ScheduledGameTask.class);
            mutableVersions.put(ScheduledGameTask.AUTO_ADVANCE, 1L);

            Game.GameRestoreBuilder builder = validRestoreBuilder()
                    .players(mutablePlayers)
                    .activePlayerIds(mutableActiveIds)
                    .communityCards(mutableCommunity)
                    .remainingDeck(mutableDeck)
                    .handContributions(mutableContribs)
                    .previousBets(mutableWagers)
                    .handSeatOrder(mutableSeatOrder)
                    .actedPlayersInRound(mutableActed)
                    .scheduledTimes(mutableDeadlines)
                    .timerVersions(mutableVersions);

            // Mutations before build() are captured
            mutablePlayers.add(p3);
            mutableActiveIds.add("p3");
            mutableCommunity.add(new Card(Rank.KING, Suit.CLUBS));
            mutableDeck.add(new Card(Rank.FOUR, Suit.SPADES));
            mutableContribs.put("p3", 75);
            mutableWagers.put("p2", 20);
            mutableSeatOrder.add("p3");
            mutableActed.add("p2");
            mutableDeadlines.put(ScheduledGameTask.CLEANUP, 9000L);
            mutableVersions.put(ScheduledGameTask.CLEANUP, 2L);

            Game game = builder.build();

            // Mutations after build() are isolated
            mutablePlayers.clear();
            mutableActiveIds.clear();
            mutableCommunity.clear();
            mutableDeck.clear();
            mutableContribs.clear();
            mutableWagers.clear();
            mutableSeatOrder.clear();
            mutableActed.clear();
            mutableDeadlines.clear();
            mutableVersions.clear();

            assertEquals(3, game.getPlayers().size());
            assertEquals(3, game.getActivePlayers().size());
            assertEquals(2, game.getCommunityCards().size());
            assertEquals(2, game.toSnapshot().remainingDeck().size());
            assertEquals(2, game.toSnapshot().handContributions().size());
            assertEquals(2, game.toSnapshot().previousBets().size());
            assertEquals(3, game.toSnapshot().handSeatOrder().size());
            assertEquals(2, game.toSnapshot().actedPlayerIds().size());
            assertEquals(2, game.toSnapshot().scheduledTimes().size());
            assertEquals(2, game.toSnapshot().timerVersions().size());
        }

        @Test
        @DisplayName("Applies safe fallbacks for seat order, timer versions, and current player")
        void appliesSafeFallbacksForSeatOrderTimerVersionsAndCurrentPlayer() {
            Player p1 = restorePlayer("p1", "Alice", 1000);
            Player p2 = restorePlayer("p2", "Bob", 1000);
            Player p3 = restorePlayer("p3", "Charlie", 1000);

            Game defaultOrderAndTimers = validRestoreBuilder()
                    .players(List.of(p1, p2, p3))
                    .activePlayerIds(List.of("p3", "p1"))
                    .handSeatOrder(null)
                    .scheduledTimes(Map.of(ScheduledGameTask.CLEANUP, 99999L))
                    .timerVersions(null)
                    .currentPlayerId("missing")
                    .build();

            assertEquals(List.of("p1", "p2", "p3"), defaultOrderAndTimers.toSnapshot().handSeatOrder());
            assertEquals(List.of("p3", "p1"),
                    defaultOrderAndTimers.getActivePlayers().stream().map(Player::getPlayerId).toList());
            assertEquals(0L, defaultOrderAndTimers.getTimerVersion(ScheduledGameTask.CLEANUP));
            assertTrue(defaultOrderAndTimers.toSnapshot().timerVersions().isEmpty());
            assertNotNull(defaultOrderAndTimers.getCurrentPlayer());

            Game emptyActive = validRestoreBuilder()
                    .players(List.of(p1, p2))
                    .activePlayerIds(List.of())
                    .gameOver(true)
                    .build();
            IllegalStateException ex = assertThrows(IllegalStateException.class, emptyActive::getCurrentPlayer);
            assertEquals("No active players available", ex.getMessage());
        }
    }
}
