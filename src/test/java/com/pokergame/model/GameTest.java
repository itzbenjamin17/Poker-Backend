package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Game class.
 */
@Tag("unit")
@DisplayName("Game model")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GameTest extends GameIntegrationTestSupport {

        /**
         * Protects the expected behavior for game creation.
         */
        @Test
        void testGameCreation() {
                assertNotNull(game);
                assertEquals("game123", game.getGameId());
                assertEquals(3, game.getPlayers().size());
                assertEquals(3, game.getActivePlayers().size());
                assertEquals(0, game.getPot());
                assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
                assertFalse(game.isGameOver());
        }

        /**
         * Protects the expected behavior for game creation with null game ID.
         */
        @Test
        void testGameCreationWithNullGameId() {
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game(null, players, 10, 20, mockHandEvaluator));
                assertEquals("Game ID cannot be null or empty", exception.getMessage());
        }

        /**
         * Protects the expected behavior for game creation with empty game ID.
         */
        @Test
        void testGameCreationWithEmptyGameId() {
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game("   ", players, 10, 20, mockHandEvaluator));
                assertEquals("Game ID cannot be null or empty", exception.getMessage());
        }

        /**
         * Protects the expected behavior for game creation with insufficient players.
         */
        @Test
        void testGameCreationWithInsufficientPlayers() {
                List<Player> onePlayer = List.of(new Player("Solo", "p1", 1000));
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game("game123", onePlayer, 10, 20, mockHandEvaluator));
                assertEquals("At least 2 players are required to start a game", exception.getMessage());
        }

        /**
         * Protects the expected behavior for game creation with null players list.
         */
        @Test
        void testGameCreationWithNullPlayersList() {
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game("game123", null, 10, 20, mockHandEvaluator));
                assertEquals("At least 2 players are required to start a game", exception.getMessage());
        }

        /**
         * Protects the expected behavior for game creation with null player.
         */
        @Test
        void testGameCreationWithNullPlayer() {
                List<Player> playersWithNull = new ArrayList<>();
                playersWithNull.add(new Player("Player1", "p1", 1000));
                playersWithNull.add(null);

                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game("game123", playersWithNull, 10, 20, mockHandEvaluator));
                assertEquals("Invalid players list. Please try again.", exception.getMessage());
        }

        /**
         * Protects the expected behavior for deal hole cards.
         */
        @Test
        void testDealHoleCards() {
                game.dealHoleCards();

                for (Player player : game.getActivePlayers()) {
                        assertEquals(2, player.getHoleCards().size());
                }
        }

        /**
         * Protects the expected behavior for post blinds.
         */
        @Test
        void testPostBlinds() {
                game.postBlinds();

                Player smallBlindPlayer = game.getActivePlayers().get(1);
                Player bigBlindPlayer = game.getActivePlayers().get(2);

                assertEquals(10, smallBlindPlayer.getCurrentBet());
                assertEquals(20, bigBlindPlayer.getCurrentBet());
                assertEquals(990, smallBlindPlayer.getChips());
                assertEquals(980, bigBlindPlayer.getChips());
                assertEquals(30, game.getPot());
                assertEquals(20, game.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for post blinds with short stack.
         */
        @Test
        void testPostBlindsWithShortStack() {
                // Create player with insufficient chips for blind
                List<Player> shortStackPlayers = new ArrayList<>();
                shortStackPlayers.add(new Player("Player1", "p1", 1000));
                shortStackPlayers.add(new Player("ShortStack", "p2", 5)); // Less than small blind
                shortStackPlayers.add(new Player("Player3", "p3", 1000));

                Game shortGame = new Game("game456", shortStackPlayers, 10, 20, mockHandEvaluator);
                shortGame.postBlinds();

                Player shortStackPlayer = shortGame.getActivePlayers().get(1);
                assertTrue(shortStackPlayer.getIsAllIn());
                assertEquals(0, shortStackPlayer.getChips());
        }

        /**
         * Protects the expected behavior for post blinds small blind short big blind still posts.
         */
        @Test
        void testPostBlindsSmallBlindShortBigBlindStillPosts() {
                List<Player> playersWithShortSb = new ArrayList<>();
                playersWithShortSb.add(new Player("Player1", "p1", 1000));
                playersWithShortSb.add(new Player("ShortSB", "p2", 5));
                playersWithShortSb.add(new Player("BigBlind", "p3", 1000));

                Game shortSbGame = new Game("short-sb-game", playersWithShortSb, 10, 20, mockHandEvaluator);
                shortSbGame.postBlinds();

                Player smallBlindPlayer = shortSbGame.getActivePlayers().get(1);
                Player bigBlindPlayer = shortSbGame.getActivePlayers().get(2);

                assertTrue(smallBlindPlayer.getIsAllIn());
                assertEquals(0, smallBlindPlayer.getChips());
                assertEquals(5, smallBlindPlayer.getCurrentBet());
                assertEquals(20, bigBlindPlayer.getCurrentBet());
                assertEquals(25, shortSbGame.getPot());
        }

        /**
         * Protects the expected behavior for post blinds big blind short small blind still posts.
         */
        @Test
        void testPostBlindsBigBlindShortSmallBlindStillPosts() {
                List<Player> playersWithShortBb = new ArrayList<>();
                playersWithShortBb.add(new Player("Player1", "p1", 1000));
                playersWithShortBb.add(new Player("SmallBlind", "p2", 1000));
                playersWithShortBb.add(new Player("ShortBB", "p3", 15));

                Game shortBbGame = new Game("short-bb-game", playersWithShortBb, 10, 20, mockHandEvaluator);
                shortBbGame.postBlinds();

                Player smallBlindPlayer = shortBbGame.getActivePlayers().get(1);
                Player bigBlindPlayer = shortBbGame.getActivePlayers().get(2);

                assertEquals(10, smallBlindPlayer.getCurrentBet());
                assertEquals(990, smallBlindPlayer.getChips());
                assertTrue(bigBlindPlayer.getIsAllIn());
                assertEquals(15, bigBlindPlayer.getCurrentBet());
                assertEquals(0, bigBlindPlayer.getChips());
                assertEquals(25, shortBbGame.getPot());
        }

        /**
         * Protects the expected behavior for post blinds both short includes both contributions edge case.
         */
        @Test
        void testPostBlindsBothShortIncludesBothContributionsEdgeCase() {
                List<Player> playersWithBothShort = new ArrayList<>();
                playersWithBothShort.add(new Player("Player1", "p1", 1000));
                playersWithBothShort.add(new Player("ShortSB", "p2", 7));
                playersWithBothShort.add(new Player("ShortBB", "p3", 15));

                Game bothShortGame = new Game("both-short-game", playersWithBothShort, 10, 20, mockHandEvaluator);
                bothShortGame.postBlinds();

                Player smallBlindPlayer = bothShortGame.getActivePlayers().get(1);
                Player bigBlindPlayer = bothShortGame.getActivePlayers().get(2);

                assertTrue(smallBlindPlayer.getIsAllIn());
                assertTrue(bigBlindPlayer.getIsAllIn());
                assertEquals(7, smallBlindPlayer.getCurrentBet());
                assertEquals(15, bigBlindPlayer.getCurrentBet());
                assertEquals(22, bothShortGame.getPot());
        }

        /**
         * Protects the expected behavior for post blinds current highest bet uses actual big blind when covered.
         */
        @Test
        void testPostBlindsCurrentHighestBetUsesActualBigBlindWhenCovered() {
                game.postBlinds();

                assertEquals(20, game.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for post blinds current highest bet uses actual all in when big blind short.
         */
        @Test
        void testPostBlindsCurrentHighestBetUsesActualAllInWhenBigBlindShort() {
                List<Player> playersWithShortBb = new ArrayList<>();
                playersWithShortBb.add(new Player("Player1", "p1", 1000));
                playersWithShortBb.add(new Player("SmallBlind", "p2", 1000));
                playersWithShortBb.add(new Player("ShortBB", "p3", 15));

                Game shortBbGame = new Game("short-bb-highest-bet", playersWithShortBb, 10, 20, mockHandEvaluator);
                shortBbGame.postBlinds();

                assertEquals(15, shortBbGame.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for post blinds current highest bet both short uses max posted contribution edge case.
         */
        @Test
        void testPostBlindsCurrentHighestBetBothShortUsesMaxPostedContributionEdgeCase() {
                List<Player> playersWithBothShort = new ArrayList<>();
                playersWithBothShort.add(new Player("Player1", "p1", 1000));
                playersWithBothShort.add(new Player("ShortSB", "p2", 7));
                playersWithBothShort.add(new Player("ShortBB", "p3", 15));

                Game bothShortGame = new Game("both-short-highest-bet", playersWithBothShort, 10, 20,
                                mockHandEvaluator);
                bothShortGame.postBlinds();

                assertEquals(15, bothShortGame.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for deal flop.
         */
        @Test
        void testDealFlop() {
                game.dealFlop();

                assertEquals(3, game.getCommunityCards().size());
                assertEquals(GamePhase.FLOP, game.getCurrentPhase());
                assertEquals(0, game.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for deal turn.
         */
        @Test
        void testDealTurn() {
                game.dealFlop();
                game.dealTurn();

                assertEquals(4, game.getCommunityCards().size());
                assertEquals(GamePhase.TURN, game.getCurrentPhase());
        }

        /**
         * Protects the expected behavior for deal river.
         */
        @Test
        void testDealRiver() {
                game.dealFlop();
                game.dealTurn();
                game.dealRiver();

                assertEquals(5, game.getCommunityCards().size());
                assertEquals(GamePhase.RIVER, game.getCurrentPhase());
        }

        /**
         * Protects the expected behavior for is betting round complete.
         */
        @Test
        void testIsBettingRoundComplete() {
                // Initially not complete because no one has had an initial turn yet.
                assertFalse(game.isBettingRoundComplete());

                // Set everyone has had initial turn
                game.setEveryoneHasHadInitialTurn(true);

                // Still not complete because of different bets
                game.postBlinds();
                assertFalse(game.isBettingRoundComplete());

                // Make everyone match the bet
                for (Player player : game.getActivePlayers()) {
                        if (player.getCurrentBet() < game.getCurrentHighestBet()) {
                                int callAmount = game.getCurrentHighestBet() - player.getCurrentBet();
                                player.payChips(0, callAmount);
                        }
                }

                // Now it should be complete
                assertTrue(game.isBettingRoundComplete());
        }

        /**
         * Protects the expected behavior for set everyone has had initial turn.
         */
        @Test
        void testSetEveryoneHasHadInitialTurn() {
                assertFalse(game.isBettingRoundComplete());

                game.setEveryoneHasHadInitialTurn(true);

                // All players have 0 bet initially, so with everyone having had a turn, round
                // is complete
                assertTrue(game.isBettingRoundComplete());
        }

        /**
         * Protects the expected behavior for reset bets for round.
         */
        @Test
        void testResetBetsForRound() {
                game.postBlinds();
                assertEquals(20, game.getCurrentHighestBet());

                game.setEveryoneHasHadInitialTurn(true);
                game.resetBetsForRound();

                assertEquals(0, game.getCurrentHighestBet());
                for (Player player : game.getActivePlayers()) {
                        assertEquals(0, player.getCurrentBet());
                }
        }

        /**
         * Protects the expected behavior for is hand over.
         */
        @Test
        void testIsHandOver() {
                assertFalse(game.isHandOver());

                // Make all but one player fold
                game.getActivePlayers().get(0).doAction(PlayerAction.FOLD, 0, 0);
                game.getActivePlayers().get(1).doAction(PlayerAction.FOLD, 0, 0);

                assertTrue(game.isHandOver());
        }

        /**
         * Protects the expected behavior for reset for new hand clears hand state and advances dealer.
         */
        @Test
        void testResetForNewHandClearsHandStateAndAdvancesDealer() {
                game.dealHoleCards();
                game.postBlinds();
                game.dealFlop();

                Player playerOne = game.getPlayers().get(0);
                Player playerTwo = game.getPlayers().get(1);
                Player playerThree = game.getPlayers().get(2);

                playerOne.doAction(PlayerAction.FOLD, 0, game.getPot());
                playerTwo.setBestHand(List.of(new Card(Rank.ACE, Suit.SPADES)));
                playerTwo.setHandRank(HandRank.ONE_PAIR);
                playerThree.doAction(PlayerAction.BET, 100, 0);

                int dealerPositionBeforeReset = game.getDealerPosition();
                int potBeforeReset = game.getPot();
                List<Integer> chipCountsBeforeReset = game.getPlayers().stream()
                                .map(Player::getChips)
                                .toList();

                boolean gameEnded = game.resetForNewHand();

                assertFalse(gameEnded);
                assertEquals(0, game.getCommunityCards().size());
                assertEquals(potBeforeReset, game.getPot(),
                                "Hand reset should preserve any configured carry-over pot");
                assertEquals(0, game.getCurrentHighestBet());
                assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
                assertEquals(players.size(), game.getActivePlayers().size());
                assertEquals((dealerPositionBeforeReset + 1) % players.size(), game.getDealerPosition());
                assertEquals(chipCountsBeforeReset,
                                game.getPlayers().stream().map(Player::getChips).toList(),
                                "Hand reset should not change chip stacks");

                for (Player player : game.getPlayers()) {
                        assertEquals(0, player.getHoleCards().size());
                        assertEquals(0, player.getBestHand().size());
                        assertEquals(HandRank.NO_HAND, player.getHandRank());
                        assertEquals(0, player.getCurrentBet());
                        assertFalse(player.getHasFolded());
                        assertFalse(player.getIsAllIn());
                }
        }

        /**
         * Protects the expected behavior for cleanup after hand.
         */
        @Test
        void testCleanupAfterHand() {
                // Make a player lose all chips
                Player player = game.getActivePlayers().getFirst();
                player.doAction(PlayerAction.ALL_IN, 0, 0);

                assertEquals(3, game.getActivePlayers().size());
                assertFalse(game.isGameOver());

                game.cleanupAfterHand();

                assertTrue(player.getIsOut());
                assertEquals(2, game.getActivePlayers().size());
                assertFalse(game.isGameOver()); // Still 2 players
        }

        /**
         * Protects the expected behavior for cleanup after hand ends game.
         */
        @Test
        void testCleanupAfterHandEndsGame() {
                // Make all but one player lose all chips
                game.getActivePlayers().get(0).doAction(PlayerAction.ALL_IN, 0, 0);
                game.getActivePlayers().get(1).doAction(PlayerAction.ALL_IN, 0, 0);

                game.cleanupAfterHand();

                assertEquals(1, game.getActivePlayers().size());
                assertTrue(game.isGameOver());
        }

        /**
         * Protects the expected behavior for getters.
         */
        @Test
        void testGetters() {
                assertEquals("game123", game.getGameId());
                assertEquals(3, game.getPlayers().size());
                assertEquals(3, game.getActivePlayers().size());
                assertNotNull(game.getCurrentPlayer());
                assertNotNull(game.getCommunityCards());
                assertEquals(0, game.getPot());
                assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
                assertFalse(game.isGameOver());
                assertEquals(0, game.getCurrentHighestBet());
                assertEquals(0, game.getDealerPosition());
        }

        /**
         * Protects the expected behavior for reset for new hand with eliminated players.
         */
        @Test
        void testResetForNewHandWithEliminatedPlayers() {
                // Eliminate a player
                Player eliminated = game.getActivePlayers().getFirst();
                eliminated.doAction(PlayerAction.ALL_IN, 0, 0);
                eliminated.setIsOut();

                game.resetForNewHand();

                assertEquals(2, game.getActivePlayers().size());
                assertFalse(game.getActivePlayers().contains(eliminated));
        }

        @Test
        void testIsTournamentOverWhenOnePlayerHasChips() {
                assertFalse(game.isTournamentOver());

                // Set 2 players out of 3 to 0 chips
                game.getPlayers().get(1).doAction(PlayerAction.ALL_IN, 0, 0); // chips = 0
                game.getPlayers().get(2).doAction(PlayerAction.ALL_IN, 0, 0); // chips = 0

                assertTrue(game.isTournamentOver());
        }

        @Test
        void testAreAllReadyEligiblePlayersReadyExcludesZeroChipPlayers() {
                game.openReadyCountdown(System.currentTimeMillis() + 10_000);

                // Set player 1 to 0 chips
                game.getPlayers().get(1).doAction(PlayerAction.ALL_IN, 0, 0);

                // Only player 0 and player 2 have chips > 0
                game.getPlayers().get(0).setReadyForNextHand(true);
                assertFalse(game.areAllReadyEligiblePlayersReady());

                game.getPlayers().get(2).setReadyForNextHand(true);
                // Player 1 has 0 chips, so with player 0 and 2 ready, all eligible should be ready
                assertTrue(game.areAllReadyEligiblePlayersReady());
        }

        /**
         * Protects the expected behavior for dealing full hand progression.
         */
        @Test
        void testDealingFullHandProgression() {
                // PRE_FLOP
                assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
                assertEquals(0, game.getCommunityCards().size());

                // FLOP
                game.dealFlop();
                assertEquals(GamePhase.FLOP, game.getCurrentPhase());
                assertEquals(3, game.getCommunityCards().size());

                // TURN
                game.dealTurn();
                assertEquals(GamePhase.TURN, game.getCurrentPhase());
                assertEquals(4, game.getCommunityCards().size());

                // RIVER
                game.dealRiver();
                assertEquals(GamePhase.RIVER, game.getCurrentPhase());
                assertEquals(5, game.getCommunityCards().size());

                // SHOWDOWN
                List<Card> hand = List.of(new Card(Rank.ACE, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(hand, HandRank.HIGH_CARD));

                game.conductShowdown();
                assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        }

        @Test
        @DisplayName("should mark game as over")
        void testMarkGameOver() {
                assertFalse(game.isGameOver());
                game.markGameOver();
                assertTrue(game.isGameOver());
        }

        @Test
        @DisplayName("should resolve game winner by chip count and index tiebreak")
        void testResolveGameWinner() {
                // Setup: Player 1 (index 0) has 0 chips and is out
                // Player 2 (index 1) has 1000 chips
                // Player 3 (index 2) has 1000 chips
                players.get(0).setChips(0);
                players.get(0).setIsOut();
                players.get(1).setChips(1000);
                players.get(2).setChips(1000);

                Player winner = game.resolveGameWinner();

                // Should pick Player 2 (index 1) over Player 3 (index 2) due to lower index tiebreak
                assertNotNull(winner);
                assertEquals("Player2", winner.getName());

                // Now give Player 3 more chips
                players.get(2).setChips(2000);
                winner = game.resolveGameWinner();
                assertEquals("Player3", winner.getName());
        }

        @Test
        @DisplayName("restoreBuilder fully reconstructs the game state from persistence boundaries")
        void testRestoreBuilder() {
                Player p1 = new Player("P1", "p1-id", 500);
                Player p2 = new Player("P2", "p2-id", 300);
                List<Player> restoredPlayers = List.of(p1, p2);

                List<Card> deckCards = List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.HEARTS));
                List<Card> community = List.of(new Card(Rank.TWO, Suit.DIAMONDS));

                Map<String, Integer> contribs = Map.of("p1-id", 100);
                Set<String> acted = Set.of("p1-id");
                Map<ScheduledGameTask, Long> tasks = Map.of(ScheduledGameTask.AUTO_ADVANCE, 12345L);

                Game restored = Game.restoreBuilder()
                                .gameId("restored-game")
                                .players(restoredPlayers)
                                .activePlayerIds(List.of("p1-id", "p2-id"))
                                .remainingDeck(deckCards)
                                .communityCards(community)
                                .pot(100)
                                .dealerPosition(1)
                                .smallBlindPosition(0)
                                .bigBlindPosition(1)
                                .currentPlayerPosition(0)
                                .currentHighestBet(50)
                                .currentPhase(GamePhase.FLOP)
                                .gameOver(false)
                                .smallBlind(10)
                                .bigBlind(20)
                                .handContributions(contribs)
                                .readyCountdownActive(true)
                                .readyCountdownDeadlineEpochMs(9999L)
                                .everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(acted)
                                .scheduledTaskDeadlines(tasks)
                                .handEvaluator(mockHandEvaluator)
                                .build();

                assertEquals("restored-game", restored.getGameId());
                assertEquals(100, restored.getPot());
                assertEquals(GamePhase.FLOP, restored.getCurrentPhase());
                assertTrue(restored.getCommunityCards().contains(new Card(Rank.TWO, Suit.DIAMONDS)));
                assertEquals(2, restored.getActivePlayers().size());
                assertEquals(10, restored.getSmallBlind());
                assertEquals(20, restored.getBigBlind());
        }

        @Test
        @DisplayName("resetForNewHand clears actedPlayersInRound")
        void resetForNewHand_ClearsActedPlayersInRound() {
                game.postBlinds();
                Player actor = game.getCurrentPlayer();
                game.processPlayerDecision(actor, new PlayerDecision(PlayerAction.CALL, 0, actor.getPlayerId()));
                assertFalse(game.getActedPlayerIdsSnapshot().isEmpty(),
                                "actedPlayersInRound should contain the player who acted before reset");

                boolean gameEnded = game.resetForNewHand();

                assertFalse(gameEnded);
                assertTrue(game.getActedPlayerIdsSnapshot().isEmpty(),
                                "resetForNewHand must clear actedPlayersInRound");
        }

}
