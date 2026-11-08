package com.pokergame.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Integration scenarios exercising Game and its collaborators. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GameSeatingIntegrationTest extends GameIntegrationTestSupport {

        /**
         * Protects the expected behavior for heads up post blinds dealer posts small blind.
         */
        @Test
        void testHeadsUpPostBlindsDealerPostsSmallBlind() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-sb", headsUpPlayers, 10, 20, mockHandEvaluator);
                headsUpGame.postBlinds();

                assertEquals(0, headsUpGame.getDealerPosition());
                assertEquals(10, headsUpGame.getActivePlayers().getFirst().getCurrentBet());
        }

        /**
         * Protects the expected behavior for heads up post blinds non dealer posts big blind.
         */
        @Test
        void testHeadsUpPostBlindsNonDealerPostsBigBlind() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-bb", headsUpPlayers, 10, 20, mockHandEvaluator);
                headsUpGame.postBlinds();

                assertEquals(20, headsUpGame.getActivePlayers().get(1).getCurrentBet());
        }

        /**
         * Protects the expected behavior for heads up advance positions blinds rotate correctly between hands edge case.
         */
        @Test
        void testHeadsUpAdvancePositionsBlindsRotateCorrectlyBetweenHandsEdgeCase() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-rotate", headsUpPlayers, 10, 20, mockHandEvaluator);

                headsUpGame.postBlinds();
                assertEquals(10, headsUpGame.getActivePlayers().get(0).getCurrentBet());
                assertEquals(20, headsUpGame.getActivePlayers().get(1).getCurrentBet());

                headsUpGame.resetForNewHand();
                headsUpGame.postBlinds();

                assertEquals(1, headsUpGame.getDealerPosition());
                assertEquals(10, headsUpGame.getActivePlayers().get(1).getCurrentBet());
                assertEquals(20, headsUpGame.getActivePlayers().get(0).getCurrentBet());
        }

        /**
         * Protects the expected behavior for advance positions.
         */
        @Test
        void testAdvancePositions() {
                int initialDealer = game.getDealerPosition();

                game.advancePositions();

                assertEquals((initialDealer + 1) % 3, game.getDealerPosition());
        }

        /**
         * Protects the expected behavior for next player.
         */
        @Test
        void testNextPlayer() {
                Player firstPlayer = game.getCurrentPlayer();
                game.nextPlayer();
                Player secondPlayer = game.getCurrentPlayer();

                assertNotEquals(firstPlayer, secondPlayer);
        }

        /**
         * Protects the contract that remove player from game when non current player leaves before current should preserve current player.
         */
        @Test
        void removePlayerFromGame_WhenNonCurrentPlayerLeavesBeforeCurrent_ShouldPreserveCurrentPlayer() {
                game.nextPlayer();
                game.nextPlayer();
                Player currentBeforeRemoval = game.getCurrentPlayer();

                Player playerToRemove = game.getActivePlayers().stream()
                                .filter(p -> !p.equals(currentBeforeRemoval))
                                .findFirst()
                                .orElseThrow();

                game.removePlayerFromGame(playerToRemove);

                assertEquals(currentBeforeRemoval, game.getCurrentPlayer());
                assertEquals(2, game.getActivePlayers().size());
        }

        /**
         * Protects the contract that get big blind player ID when non current player leaves and blind index stale should not throw.
         */
        @Test
        void getBigBlindPlayerId_WhenNonCurrentPlayerLeavesAndBlindIndexStale_ShouldNotThrow() {
                game.postBlinds();

                Player playerToRemove = game.getActivePlayers().get(1);
                assertNotEquals(playerToRemove, game.getCurrentPlayer());

                game.removePlayerFromGame(playerToRemove);

                String bigBlindPlayerId = assertDoesNotThrow(() -> game.getBigBlindPlayerId());

                assertNotNull(bigBlindPlayerId);
                assertTrue(game.getActivePlayers().stream().anyMatch(p -> p.getPlayerId().equals(bigBlindPlayerId)));
        }

}
