package com.pokergame.model;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Dedicated unit tests for {@link GameRestoreBuilder}.
 * Verifies snapshot reconstruction across all 37 internal fields, validation rules,
 * fallback defaults, deck draw integrity, task scheduling generations, state revisions,
 * and defensive collection isolation.
 */
@Tag("unit")
@DisplayName("GameRestoreBuilder tests")
class GameRestoreBuilderTest {

    private static final String GAME_ID = "restore-test-game";

    // =========================================================================
    // 1. Full 37-Field Exhaustive Verification
    // =========================================================================

    @Test
    @DisplayName("restoreAll37Fields_EveryPropertyFaithfullyRestored: asserts all 37 fields")
    void restoreAll37Fields_EveryPropertyFaithfullyRestored() {
        HandEvaluatorService mockEvaluator = mock(HandEvaluatorService.class);
        when(mockEvaluator.getBestHand(any(), any()))
                .thenReturn(new HandEvaluationResult(
                        List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.TEN, Suit.SPADES)),
                        HandRank.ROYAL_FLUSH));

        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 800);
        Player p3 = player("p3", "Charlie", 600);
        List<Player> allPlayers = List.of(p1, p2, p3);
        List<String> activeIds = List.of("p1", "p2");

        List<Card> deckCards = List.of(
                new Card(Rank.TWO, Suit.CLUBS),
                new Card(Rank.THREE, Suit.DIAMONDS),
                new Card(Rank.FOUR, Suit.HEARTS));
        List<Card> community = List.of(
                new Card(Rank.TEN, Suit.SPADES),
                new Card(Rank.JACK, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.SPADES));

        Map<String, Integer> contributions = Map.of("p1", 200, "p2", 250);
        HandSettlementResult settlement = new HandSettlementResult(
                Map.of("p1", 450), Map.of(), Set.of("p1"), 0, false);
        Map<String, Integer> lastFullRaise = Map.of("PRE_FLOP", 30, "FLOP", 70);
        Map<String, Integer> lastActedWager = Map.of("p1", 100, "p2", 100);
        List<String> seatOrder = List.of("p2", "p3", "p1");
        Set<String> actedPlayers = Set.of("p1", "p2");

        Map<ScheduledGameTask, Long> taskDeadlines = Map.of(
                ScheduledGameTask.AUTO_ADVANCE, 1700000005000L,
                ScheduledGameTask.CLEANUP, 1700000060000L);
        Map<ScheduledGameTask, Long> taskGenerations = Map.of(
                ScheduledGameTask.AUTO_ADVANCE, 3L,
                ScheduledGameTask.CLEANUP, 1L);

        PublicGameStateResponse finalState = new PublicGameStateResponse(
                6, 0, List.of(), 0, GamePhase.SHOWDOWN, 0,
                community, List.of(
                        new PublicPlayerState("p1", "Alice", 1450, 0, "ACTIVE", false, false, false, true, false,
                                HandRank.HIGH_CARD, List.of(), true, null, List.of(), null, false),
                        new PublicPlayerState("p2", "Bob", 550, 0, "ACTIVE", false, false, false, false, false,
                                null, List.of(), false, null, null, null, false)),
                null, null, List.of("Alice"), null, false, null, false, null, false, null,
                true, null, List.of(), null, null, 1L, ReadyGateState.CLOSED, 1700000000000L, 88L);

        GameEndResponse terminalResult = new GameEndResponse(
                "GAME_END", GAME_ID, "Alice", 1450, false, "Alice won", finalState);

        Game game = Game.restoreBuilder()
                .gameId(GAME_ID)                                    // 1
                .players(allPlayers)                                // 2
                .activePlayerIds(activeIds)                         // 3
                .remainingDeck(deckCards)                           // 4
                .communityCards(community)                          // 5
                .pot(475)                                           // 6
                .dealerPosition(1)                                  // 7
                .smallBlindPosition(2)                              // 8
                .bigBlindPosition(0)                                // 9
                .currentPlayerPosition(0)                           // 10
                .currentHighestBet(100)                             // 11
                .preHandCarryOver(25)                               // 12
                .currentPhase(GamePhase.FLOP)                       // 13
                .gameOver(false)                                    // 14
                .smallBlind(15)                                     // 15
                .bigBlind(30)                                       // 16
                .handContributions(contributions)                   // 17
                .lastSettlementResult(settlement)                   // 18
                .lastFullRaiseByStreet(lastFullRaise)               // 19
                .lastActedWagerByPlayer(lastActedWager)             // 20
                .handSeatOrder(seatOrder)                           // 21
                .dealerAnchorPlayerId("p2")                         // 22
                .smallBlindPlayerId("p3")                           // 23
                .bigBlindPlayerId("p1")                             // 24
                .terminalCarryOverSettled(true)                     // 25
                .forfeitPending(true)                               // 26
                .readyCountdownActive(true)                         // 27
                .readyCountdownDeadlineEpochMs(1700000000000L)      // 28
                .readyGateState(ReadyGateState.COUNTING_DOWN)       // 29
                .handNumber(7L)                                     // 30
                .stateRevision(88L)                                 // 31
                .retainedTerminalResult(terminalResult)             // 32
                .everyoneHasHadInitialTurn(true)                    // 33
                .actedPlayersInRound(actedPlayers)                  // 34
                .scheduledTaskDeadlines(taskDeadlines)              // 35
                .scheduledTaskGenerations(taskGenerations)          // 36
                .handEvaluator(mockEvaluator)                       // 37
                .build();

        // Direct assertions on all 37 fields
        assertEquals(GAME_ID, game.getGameId());                                     // 1
        assertEquals(allPlayers, game.getPlayers());                                 // 2
        assertEquals(2, game.getActivePlayers().size());                             // 3
        assertEquals(List.of("p1", "p2"),
                game.getActivePlayers().stream().map(Player::getPlayerId).toList());  // 3
        assertEquals(deckCards, game.getRemainingDeckSnapshot());                    // 4
        assertEquals(community, game.getCommunityCards());                          // 5
        assertEquals(475, game.getPot());                                            // 6
        assertEquals(1, game.getDealerPosition());                                   // 7
        assertEquals(2, game.getSmallBlindPosition());                               // 8
        assertEquals(0, game.getBigBlindPosition());                                 // 9
        assertEquals(0, game.getCurrentPlayerPosition());                            // 10
        assertEquals("p1", game.getCurrentPlayer().getPlayerId());                  // 10
        assertEquals(100, game.getCurrentHighestBet());                              // 11
        assertEquals(25, game.getPreHandCarryOver());                                // 12
        assertEquals(GamePhase.FLOP, game.getCurrentPhase());                        // 13
        assertFalse(game.isGameOver());                                              // 14
        assertEquals(15, game.getSmallBlind());                                      // 15
        assertEquals(30, game.getBigBlind());                                        // 16
        assertEquals(contributions, game.getHandContributionsSnapshot());            // 17
        assertEquals(settlement, game.getLastSettlementResult());                    // 18
        assertEquals(lastFullRaise, game.getLastFullRaiseByStreetSnapshot());        // 19
        assertEquals(lastActedWager, game.getLastActedWagerByPlayerSnapshot());      // 20
        assertEquals(seatOrder, game.getHandSeatOrderSnapshot());                    // 21
        assertEquals("p2", game.getDealerAnchorPlayerId());                          // 22
        assertEquals("p3", game.getSmallBlindPlayerId());                            // 23
        assertEquals("p1", game.getBigBlindPlayerId());                              // 24
        assertTrue(game.isTerminalCarryOverSettled());                                // 25
        assertTrue(game.isForfeitPending());                                         // 26
        assertTrue(game.isReadyCountdownActive());                                   // 27
        assertEquals(1700000000000L, game.getReadyCountdownDeadlineEpochMs());      // 28
        assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());        // 29
        assertEquals(7L, game.getHandNumber());                                      // 30
        assertEquals(88L, game.getStateRevision());                                  // 31
        assertEquals(terminalResult, game.getRetainedTerminalResult());              // 32
        assertTrue(game.hasEveryoneHadInitialTurn());                                // 33
        assertEquals(actedPlayers, game.getActedPlayerIdsSnapshot());                // 34
        assertEquals(1700000005000L,
                game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));      // 35
        assertEquals(1700000060000L,
                game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));           // 35
        assertEquals(taskDeadlines, game.getScheduledTaskDeadlinesSnapshot());        // 35
        assertEquals(3L,
                game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE));    // 36
        assertEquals(1L,
                game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));         // 36
        assertEquals(taskGenerations, game.getScheduledTaskGenerationsSnapshot());    // 36

        // Verify handEvaluator (37) is wired by triggering showdown
        game.conductShowdown();
        verify(mockEvaluator, atLeastOnce()).getBestHand(any(), any());              // 37
    }

    // =========================================================================
    // 2. Validation and Error Handling Tests
    // =========================================================================

    @Test
    @DisplayName("build_ThrowsWhenActivePlayerIdNotInPlayers: rejects unknown active player")
    void build_ThrowsWhenActivePlayerIdNotInPlayers() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        GameRestoreBuilder builder = defaultBuilder()
                .players(List.of(p1, p2))
                .activePlayerIds(List.of("p1", "unknown-player-id"));

        BadRequestException ex = assertThrows(BadRequestException.class, builder::build);
        assertEquals("Snapshot references an unknown active player", ex.getMessage());
    }

    @ParameterizedTest(name = "[{index}] gameId=''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("build_ThrowsWhenGameIdNullOrBlank: rejects null or whitespace gameId")
    void build_ThrowsWhenGameIdNullOrBlank(String invalidId) {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        GameRestoreBuilder builder = defaultBuilder()
                .gameId(invalidId)
                .players(List.of(p1, p2))
                .activePlayerIds(List.of("p1", "p2"));

        BadRequestException ex = assertThrows(BadRequestException.class, builder::build);
        assertEquals("Game ID cannot be null or empty", ex.getMessage());
    }

    @Test
    @DisplayName("build_ThrowsWhenPlayersContainsNull: rejects null elements in player list")
    void build_ThrowsWhenPlayersContainsNull() {
        Player p1 = player("p1", "Alice", 1000);
        List<Player> playersWithNull = new ArrayList<>();
        playersWithNull.add(p1);
        playersWithNull.add(null);

        GameRestoreBuilder builder = defaultBuilder()
                .players(playersWithNull)
                .activePlayerIds(List.of("p1"));

        BadRequestException ex = assertThrows(BadRequestException.class, builder::build);
        assertEquals("Invalid players list. Please try again.", ex.getMessage());
    }

    @Test
    @DisplayName("build_AllowsSinglePlayerOrEmptyActivePlayersWhenSkipValidationIsTrue")
    void build_AllowsSinglePlayerOrEmptyActivePlayersWhenSkipValidationIsTrue() {
        Player p1 = player("p1", "Solo", 1000);

        Game restoredSolo = defaultBuilder()
                .players(List.of(p1))
                .activePlayerIds(List.of("p1"))
                .gameOver(true)
                .build();

        assertNotNull(restoredSolo);
        assertEquals(1, restoredSolo.getPlayers().size());
        assertEquals(1, restoredSolo.getActivePlayers().size());

        Game restoredEmptyActive = defaultBuilder()
                .players(List.of(p1))
                .activePlayerIds(List.of())
                .gameOver(true)
                .build();

        assertNotNull(restoredEmptyActive);
        assertTrue(restoredEmptyActive.getActivePlayers().isEmpty());
    }

    // =========================================================================
    // 3. Deck Restoration and Draw Sequencing Tests
    // =========================================================================

    @Test
    @DisplayName("deckRestoration_PreservesCardListAndDrawOrder: snapshot reflects exact cards")
    void deckRestoration_PreservesCardListAndDrawOrder() {
        List<Card> deckCards = List.of(
                new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.HEARTS),
                new Card(Rank.QUEEN, Suit.DIAMONDS));

        Game game = defaultBuilder()
                .remainingDeck(deckCards)
                .build();

        assertEquals(deckCards, game.getRemainingDeckSnapshot());
    }

    @Test
    @DisplayName("deckRestoration_DealsFromRestoredDeckInExpectedSequence")
    void deckRestoration_DealsFromRestoredDeckInExpectedSequence() {
        // Deck deals from the end of the list (cards.removeLast())
        Card card1 = new Card(Rank.TWO, Suit.CLUBS);
        Card card2 = new Card(Rank.THREE, Suit.DIAMONDS);
        Card card3 = new Card(Rank.ACE, Suit.SPADES);
        List<Card> deckCards = List.of(card1, card2, card3);

        Game game = defaultBuilder()
                .currentPhase(GamePhase.FLOP)
                .communityCards(List.of(
                        new Card(Rank.TEN, Suit.HEARTS),
                        new Card(Rank.JACK, Suit.HEARTS),
                        new Card(Rank.QUEEN, Suit.HEARTS)))
                .remainingDeck(deckCards)
                .build();

        game.dealTurn();

        // The card dealt is the last card in the remaining deck list (card3: ACE of SPADES)
        assertEquals(4, game.getCommunityCards().size());
        assertEquals(card3, game.getCommunityCards().get(3));
        assertEquals(List.of(card1, card2), game.getRemainingDeckSnapshot());
    }

    @Test
    @DisplayName("deckRestoration_EmptyDeckRestoredCleanly")
    void deckRestoration_EmptyDeckRestoredCleanly() {
        Game game = defaultBuilder()
                .remainingDeck(List.of())
                .build();

        assertTrue(game.getRemainingDeckSnapshot().isEmpty());
    }

    // =========================================================================
    // 4. Scheduled Tasks and Generations Restoration Tests
    // =========================================================================

    @Test
    @DisplayName("scheduledTasks_DeadlinesAndGenerationsPreserved: checks queries and snapshots")
    void scheduledTasks_DeadlinesAndGenerationsPreserved() {
        Map<ScheduledGameTask, Long> deadlines = Map.of(
                ScheduledGameTask.AUTO_ADVANCE, 50000L,
                ScheduledGameTask.READY_TIMEOUT, 60000L);
        Map<ScheduledGameTask, Long> generations = Map.of(
                ScheduledGameTask.AUTO_ADVANCE, 2L,
                ScheduledGameTask.READY_TIMEOUT, 4L);

        Game game = defaultBuilder()
                .scheduledTaskDeadlines(deadlines)
                .scheduledTaskGenerations(generations)
                .build();

        assertEquals(50000L, game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(60000L, game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));

        assertEquals(2L, game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(4L, game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT));
        assertEquals(0L, game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

        assertEquals(deadlines, game.getScheduledTaskDeadlinesSnapshot());
        assertEquals(generations, game.getScheduledTaskGenerationsSnapshot());
    }

    @Test
    @DisplayName("scheduledTasks_NullGenerationsMapDefaultsGracefully: leaves generations empty")
    void scheduledTasks_NullGenerationsMapDefaultsGracefully() {
        Game game = defaultBuilder()
                .scheduledTaskDeadlines(Map.of(ScheduledGameTask.CLEANUP, 99999L))
                .scheduledTaskGenerations(null)
                .build();

        assertEquals(99999L, game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
        assertEquals(0L, game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));
        assertTrue(game.getScheduledTaskGenerationsSnapshot().isEmpty());
    }

    // =========================================================================
    // 5. State Revision Counter Restoration Tests
    // =========================================================================

    @Test
    @DisplayName("stateRevision_RestoresExactCounterAndIncrementsFromThere")
    void stateRevision_RestoresExactCounterAndIncrementsFromThere() {
        Game game = defaultBuilder()
                .stateRevision(42L)
                .build();

        assertEquals(42L, game.getStateRevision());

        game.incrementStateRevision();
        assertEquals(43L, game.getStateRevision());

        game.incrementStateRevision();
        assertEquals(44L, game.getStateRevision());
    }

    // =========================================================================
    // 6. Ready Gate and Hand Number Fallbacks Tests
    // =========================================================================

    @Test
    @DisplayName("readyGateState_ExplicitStatePreserved")
    void readyGateState_ExplicitStatePreserved() {
        Game game = defaultBuilder()
                .readyCountdownActive(false)
                .readyGateState(ReadyGateState.WAITING_FOR_PLAYERS)
                .build();

        assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
        assertFalse(game.isReadyCountdownActive());
    }

    @Test
    @DisplayName("readyGateState_NullStateFallsBackToCountingDownWhenActive")
    void readyGateState_NullStateFallsBackToCountingDownWhenActive() {
        Game game = defaultBuilder()
                .readyCountdownActive(true)
                .readyGateState(null)
                .build();

        assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());
        assertTrue(game.isReadyCountdownActive());
    }

    @Test
    @DisplayName("readyGateState_NullStateFallsBackToClosedWhenInactive")
    void readyGateState_NullStateFallsBackToClosedWhenInactive() {
        Game game = defaultBuilder()
                .readyCountdownActive(false)
                .readyGateState(null)
                .build();

        assertEquals(ReadyGateState.CLOSED, game.getReadyGateState());
        assertFalse(game.isReadyCountdownActive());
    }

    // =========================================================================
    // 7. Hand Seat Order and Seating Positions Tests
    // =========================================================================

    @Test
    @DisplayName("handSeatOrder_ExplicitOrderPreserved: custom order maintained")
    void handSeatOrder_ExplicitOrderPreserved() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        Player p3 = player("p3", "Charlie", 1000);
        List<String> customOrder = List.of("p3", "p1", "p2");

        Game game = defaultBuilder()
                .players(List.of(p1, p2, p3))
                .activePlayerIds(List.of("p1", "p2", "p3"))
                .handSeatOrder(customOrder)
                .build();

        assertEquals(customOrder, game.getHandSeatOrderSnapshot());
    }

    @Test
    @DisplayName("handSeatOrder_DefaultsToPlayerRosterOrderWhenEmpty")
    void handSeatOrder_DefaultsToPlayerRosterOrderWhenEmpty() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        Player p3 = player("p3", "Charlie", 1000);

        Game game = defaultBuilder()
                .players(List.of(p1, p2, p3))
                .activePlayerIds(List.of("p1", "p2", "p3"))
                .handSeatOrder(List.of())
                .build();

        assertEquals(List.of("p1", "p2", "p3"), game.getHandSeatOrderSnapshot());
    }

    // =========================================================================
    // 8. Defensive Copying & Builder Delegation Tests
    // =========================================================================

    @Test
    @DisplayName("builderCollections_DefensivelyIsolatedFromInputMutations")
    void builderCollections_DefensivelyIsolatedFromInputMutations() {
        List<Card> mutableCommunity = new ArrayList<>(List.of(new Card(Rank.ACE, Suit.SPADES)));
        Map<String, Integer> mutableContribs = new HashMap<>(Map.of("p1", 100));
        Set<String> mutableActed = new HashSet<>(Set.of("p1"));
        Map<ScheduledGameTask, Long> mutableDeadlines = new EnumMap<>(ScheduledGameTask.class);
        mutableDeadlines.put(ScheduledGameTask.CLEANUP, 9999L);
        Map<String, Integer> mutableRaises = new HashMap<>(Map.of("PRE_FLOP", 20));
        Map<String, Integer> mutableWagers = new HashMap<>(Map.of("p1", 20));

        Game game = defaultBuilder()
                .communityCards(mutableCommunity)
                .handContributions(mutableContribs)
                .actedPlayersInRound(mutableActed)
                .scheduledTaskDeadlines(mutableDeadlines)
                .lastFullRaiseByStreet(mutableRaises)
                .lastActedWagerByPlayer(mutableWagers)
                .build();

        // Mutate inputs after build()
        mutableCommunity.add(new Card(Rank.KING, Suit.HEARTS));
        mutableContribs.put("p2", 200);
        mutableActed.add("p2");
        mutableDeadlines.put(ScheduledGameTask.AUTO_ADVANCE, 1111L);
        mutableRaises.put("FLOP", 40);
        mutableWagers.put("p2", 40);

        // Internal Game state must remain untouched
        assertEquals(1, game.getCommunityCards().size());
        assertEquals(Map.of("p1", 100), game.getHandContributionsSnapshot());
        assertEquals(Set.of("p1"), game.getActedPlayerIdsSnapshot());
        assertEquals(Map.of(ScheduledGameTask.CLEANUP, 9999L), game.getScheduledTaskDeadlinesSnapshot());
        assertEquals(Map.of("PRE_FLOP", 20), game.getLastFullRaiseByStreetSnapshot());
        assertEquals(Map.of("p1", 20), game.getLastActedWagerByPlayerSnapshot());
    }

    @Test
    @DisplayName("restoreBuilderFactoryMethod_DelegatesToGameRestoreBuilder")
    void restoreBuilderFactoryMethod_DelegatesToGameRestoreBuilder() {
        GameRestoreBuilder builder = Game.restoreBuilder();
        assertNotNull(builder);

        Game game = builder
                .gameId("factory-test")
                .players(List.of(player("p1", "Alice", 1000), player("p2", "Bob", 1000)))
                .activePlayerIds(List.of("p1", "p2"))
                .remainingDeck(List.of())
                .communityCards(List.of())
                .handContributions(Map.of())
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .build();

        assertEquals("factory-test", game.getGameId());
        assertEquals(2, game.getActivePlayers().size());
    }

    // =========================================================================
    // Test Helpers
    // =========================================================================

    private static Player player(String id, String name, int chips) {
        return Player.restoreBuilder()
                .playerId(id).name(name).chips(chips).currentBet(0)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false)
                .build();
    }

    private static GameRestoreBuilder defaultBuilder() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        return Game.restoreBuilder()
                .gameId(GAME_ID)
                .players(List.of(p1, p2))
                .activePlayerIds(List.of("p1", "p2"))
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(0)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .preHandCarryOver(0)
                .currentPhase(GamePhase.PRE_FLOP)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .lastSettlementResult(null)
                .lastFullRaiseByStreet(Map.of())
                .lastActedWagerByPlayer(Map.of())
                .handSeatOrder(List.of())
                .dealerAnchorPlayerId("p1")
                .smallBlindPlayerId("p1")
                .bigBlindPlayerId("p2")
                .terminalCarryOverSettled(false)
                .forfeitPending(false)
                .readyCountdownActive(false)
                .readyCountdownDeadlineEpochMs(null)
                .readyGateState(ReadyGateState.CLOSED)
                .handNumber(1L)
                .stateRevision(1L)
                .retainedTerminalResult(null)
                .everyoneHasHadInitialTurn(false)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .scheduledTaskGenerations(Map.of())
                .handEvaluator(new HandEvaluatorService());
    }
}
