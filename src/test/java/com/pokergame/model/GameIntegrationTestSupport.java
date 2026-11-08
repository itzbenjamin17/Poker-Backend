package com.pokergame.model;

import com.pokergame.enums.*;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import java.util.ArrayList;
import java.util.*;

/** Shared aggregate fixtures for Game integration scenarios. */
abstract class GameIntegrationTestSupport {

        @Mock
        protected HandEvaluatorService mockHandEvaluator;

        protected List<Player> players;
        protected Game game;

        /**
         * Initializes the test fixtures before each scenario.
         */
        @BeforeEach
        void setUp() {
                players = new ArrayList<>();
                players.add(new Player("Player1", "p1", 1000));
                players.add(new Player("Player2", "p2", 1000));
                players.add(new Player("Player3", "p3", 1000));

                game = new Game("game123", players, 10, 20, mockHandEvaluator);
        }

        protected Player restoredPlayer(String name, String id, int chips, int currentBet, boolean allIn) {
                return Player.restoreBuilder().name(name).playerId(id).chips(chips).currentBet(currentBet)
                                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                                .hasFolded(false).allIn(allIn).out(false).disconnected(false)
                                .readyForNextHand(false).build();
        }

        protected Game restoredBettingGame(String id, List<Player> handPlayers, int pot, int highestBet,
                        int currentPlayerPosition, Map<String, Integer> contributions,
                        Map<String, Integer> lastFullRaiseByStreet,
                        Map<String, Integer> lastActedWagerByPlayer) {
                return Game.restoreBuilder().gameId(id).players(handPlayers)
                                .activePlayerIds(handPlayers.stream().map(Player::getPlayerId).toList())
                                .remainingDeck(List.of()).communityCards(List.of()).pot(pot).dealerPosition(0)
                                .smallBlindPosition(0).bigBlindPosition(Math.min(1, handPlayers.size() - 1))
                                .currentPlayerPosition(currentPlayerPosition).currentHighestBet(highestBet)
                                .currentPhase(GamePhase.PRE_FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                                .handContributions(contributions).preHandCarryOver(0)
                                .lastFullRaiseByStreet(lastFullRaiseByStreet)
                                .lastActedWagerByPlayer(lastActedWagerByPlayer)
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
        }

        protected Game livePotWithFoldedBlindAndCarryOver(int pot, int carryOver) {
                Player shortStack = restoredPlayer("Short", "short-" + pot, 0, 50, true);
                Player bettor = restoredPlayer("Bettor", "bettor-" + pot, 800, 250, false);
                Player owing = restoredPlayer("Owing", "owing-" + pot, 950, 50, false);
                Player foldedBlind = Player.restoreBuilder().name("Folded BB").playerId("folded-" + pot).chips(980)
                                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(20)
                                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                String shortId = shortStack.getPlayerId();
                String bettorId = bettor.getPlayerId();
                String owingId = owing.getPlayerId();
                String foldedId = foldedBlind.getPlayerId();
                return Game.restoreBuilder().gameId("live-folded-cap-" + pot)
                                .players(List.of(shortStack, bettor, owing, foldedBlind))
                                .activePlayerIds(List.of(shortId, bettorId, owingId, foldedId)).remainingDeck(List.of())
                                .communityCards(List.of()).pot(pot).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(2).currentHighestBet(250).currentPhase(GamePhase.FLOP).gameOver(false)
                                .smallBlind(10).bigBlind(20).handContributions(Map.of(shortId, 50, bettorId, 250,
                                                owingId, 50, foldedId, 20)).preHandCarryOver(carryOver)
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                                .scheduledTaskDeadlines(Map.of()).handEvaluator(mockHandEvaluator).build();
        }
}
