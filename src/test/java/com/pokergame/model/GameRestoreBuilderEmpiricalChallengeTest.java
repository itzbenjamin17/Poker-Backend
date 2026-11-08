package com.pokergame.model;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial empirical stress tests for {@link GameRestoreBuilder} and {@link Game#Game(GameRestoreBuilder)}.
 * Stress-tests:
 * 1. Collection mutation before builder.build() vs after builder.build()
 * 2. Corrupted and mismatched active player IDs (not in table, null, duplicates)
 * 3. Boundary conditions (blinds, pot, pre-hand carry over, deck size, deadlines, generations, revision)
 * 4. Position normalization under negative/overflow indices
 * 5. Builder reuse and multi-instance isolation
 * 6. Randomized property-based fuzzing of restore combinations
 */
@Tag("unit")
@DisplayName("GameRestoreBuilder Empirical Adversarial Challenge")
class GameRestoreBuilderEmpiricalChallengeTest {

    private static final String GAME_ID = "challenge-test-game";

    // =========================================================================
    // 1. Collection Mutation Before vs After build()
    // =========================================================================

    @Test
    @DisplayName("mutationBeforeBuild_ReflectedAtBuildTime; mutationAfterBuild_CompletelyIsolated")
    void testCollectionMutation_BeforeVsAfterBuild() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        Player p3 = player("p3", "Charlie", 1000);

        List<Player> mutablePlayers = new ArrayList<>(List.of(p1, p2));
        List<String> mutableActiveIds = new ArrayList<>(List.of("p1", "p2"));
        List<Card> mutableCommunity = new ArrayList<>(List.of(new Card(Rank.ACE, Suit.SPADES)));
        List<Card> mutableDeck = new ArrayList<>(List.of(new Card(Rank.TWO, Suit.HEARTS), new Card(Rank.THREE, Suit.DIAMONDS)));
        Map<String, Integer> mutableContribs = new HashMap<>(Map.of("p1", 50));
        Map<String, Integer> mutableRaises = new HashMap<>(Map.of("PRE_FLOP", 20));
        Map<String, Integer> mutableWagers = new HashMap<>(Map.of("p1", 20));
        List<String> mutableSeatOrder = new ArrayList<>(List.of("p1", "p2"));
        Set<String> mutableActed = new HashSet<>(Set.of("p1"));
        Map<ScheduledGameTask, Long> mutableDeadlines = new EnumMap<>(ScheduledGameTask.class);
        mutableDeadlines.put(ScheduledGameTask.AUTO_ADVANCE, 5000L);
        Map<ScheduledGameTask, Long> mutableGenerations = new EnumMap<>(ScheduledGameTask.class);
        mutableGenerations.put(ScheduledGameTask.AUTO_ADVANCE, 1L);

        GameRestoreBuilder builder = Game.restoreBuilder()
                .gameId(GAME_ID)
                .players(mutablePlayers)
                .activePlayerIds(mutableActiveIds)
                .communityCards(mutableCommunity)
                .remainingDeck(mutableDeck)
                .handContributions(mutableContribs)
                .lastFullRaiseByStreet(mutableRaises)
                .lastActedWagerByPlayer(mutableWagers)
                .handSeatOrder(mutableSeatOrder)
                .actedPlayersInRound(mutableActed)
                .scheduledTaskDeadlines(mutableDeadlines)
                .scheduledTaskGenerations(mutableGenerations)
                .handEvaluator(new HandEvaluatorService());

        // --- Mutate BEFORE build() ---
        mutablePlayers.add(p3);
        mutableActiveIds.add("p3");
        mutableCommunity.add(new Card(Rank.KING, Suit.CLUBS));
        mutableDeck.add(new Card(Rank.FOUR, Suit.SPADES));
        mutableContribs.put("p3", 75);
        mutableRaises.put("FLOP", 40);
        mutableWagers.put("p2", 20);
        mutableSeatOrder.add("p3");
        mutableActed.add("p2");
        mutableDeadlines.put(ScheduledGameTask.CLEANUP, 9000L);
        mutableGenerations.put(ScheduledGameTask.CLEANUP, 2L);

        // Build game
        Game game = builder.build();

        // Verify that mutations before build() WERE captured at build time
        assertEquals(3, game.getPlayers().size());
        assertEquals(3, game.getActivePlayers().size());
        assertEquals(2, game.getCommunityCards().size());
        assertEquals(3, game.getRemainingDeckSnapshot().size());
        assertEquals(75, game.getHandContributionsSnapshot().get("p3"));
        assertEquals(40, game.getLastFullRaiseByStreetSnapshot().get("FLOP"));
        assertEquals(20, game.getLastActedWagerByPlayerSnapshot().get("p2"));
        assertEquals(List.of("p1", "p2", "p3"), game.getHandSeatOrderSnapshot());
        assertTrue(game.getActedPlayerIdsSnapshot().contains("p2"));
        assertEquals(9000L, game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
        assertEquals(2L, game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

        // --- Mutate AFTER build() ---
        mutablePlayers.clear();
        mutableActiveIds.clear();
        mutableCommunity.clear();
        mutableDeck.clear();
        mutableContribs.clear();
        mutableRaises.clear();
        mutableWagers.clear();
        mutableSeatOrder.clear();
        mutableActed.clear();
        mutableDeadlines.clear();
        mutableGenerations.clear();

        // Game internal state MUST remain unchanged (defensive copies verified)
        assertEquals(3, game.getPlayers().size(), "players was affected by post-build mutation");
        assertEquals(3, game.getActivePlayers().size(), "activePlayers was affected by post-build mutation");
        assertEquals(2, game.getCommunityCards().size(), "communityCards was affected by post-build mutation");
        assertEquals(3, game.getRemainingDeckSnapshot().size(), "remainingDeck was affected by post-build mutation");
        assertEquals(2, game.getHandContributionsSnapshot().size(), "handContributions was affected by post-build mutation");
        assertEquals(2, game.getLastFullRaiseByStreetSnapshot().size(), "lastFullRaiseByStreet was affected by post-build mutation");
        assertEquals(2, game.getLastActedWagerByPlayerSnapshot().size(), "lastActedWagerByPlayer was affected by post-build mutation");
        assertEquals(3, game.getHandSeatOrderSnapshot().size(), "handSeatOrder was affected by post-build mutation");
        assertEquals(2, game.getActedPlayerIdsSnapshot().size(), "actedPlayersInRound was affected by post-build mutation");
        assertEquals(2, game.getScheduledTaskDeadlinesSnapshot().size(), "scheduledTaskDeadlines was affected by post-build mutation");
        assertEquals(2, game.getScheduledTaskGenerationsSnapshot().size(), "scheduledTaskGenerations was affected by post-build mutation");
    }

    @Test
    @DisplayName("mutationBeforeBuild_InjectingNullPlayer_FailsAtBuildTime")
    void testMutationBeforeBuild_InjectingNullPlayerFails() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        List<Player> players = new ArrayList<>(List.of(p1, p2));

        GameRestoreBuilder builder = defaultBuilder().players(players);

        // Mutate list before calling build() by injecting a null
        players.add(null);

        BadRequestException ex = assertThrows(BadRequestException.class, builder::build);
        assertEquals("Invalid players list. Please try again.", ex.getMessage());
    }

    @Test
    @DisplayName("builderReuse_BuildingMultipleInstances_EachInstanceIsIndependent")
    void testBuilderReuse_MultiInstanceIsolation() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        GameRestoreBuilder builder = defaultBuilder()
                .players(List.of(p1, p2))
                .activePlayerIds(List.of("p1", "p2"))
                .pot(100)
                .stateRevision(10L);

        Game game1 = builder.build();

        // Mutate builder state
        builder.pot(500)
                .stateRevision(99L)
                .communityCards(List.of(new Card(Rank.ACE, Suit.HEARTS)));

        Game game2 = builder.build();

        // Assert game1 remained unchanged
        assertEquals(100, game1.getPot());
        assertEquals(10L, game1.getStateRevision());
        assertTrue(game1.getCommunityCards().isEmpty());

        // Assert game2 received updated values
        assertEquals(500, game2.getPot());
        assertEquals(99L, game2.getStateRevision());
        assertEquals(1, game2.getCommunityCards().size());
    }

    // =========================================================================
    // 2. Corrupted and Mismatched Active Player Lists
    // =========================================================================

    @Test
    @DisplayName("activePlayerIds_UnknownId_ThrowsBadRequestException")
    void testActivePlayerIds_UnknownIdThrows() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        GameRestoreBuilder builder = defaultBuilder()
                .players(List.of(p1, p2))
                .activePlayerIds(List.of("p1", "p_NON_EXISTENT"));

        BadRequestException ex = assertThrows(BadRequestException.class, builder::build);
        assertEquals("Snapshot references an unknown active player", ex.getMessage());
    }

    @Test
    @DisplayName("activePlayerIds_NullElement_ThrowsBadRequestException")
    void testActivePlayerIds_NullElementThrows() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        List<String> activeWithNull = new ArrayList<>();
        activeWithNull.add("p1");
        activeWithNull.add(null);

        GameRestoreBuilder builder = defaultBuilder()
                .players(List.of(p1, p2))
                .activePlayerIds(activeWithNull);

        BadRequestException ex = assertThrows(BadRequestException.class, builder::build);
        assertEquals("Snapshot references an unknown active player", ex.getMessage());
    }

    @Test
    @DisplayName("activePlayerIds_NullOrEmpty_ResultsInEmptyActivePlayersList")
    void testActivePlayerIds_NullOrEmpty() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        Game gameNull = defaultBuilder()
                .players(List.of(p1, p2))
                .activePlayerIds(null)
                .build();
        assertTrue(gameNull.getActivePlayers().isEmpty());

        Game gameEmpty = defaultBuilder()
                .players(List.of(p1, p2))
                .activePlayerIds(List.of())
                .build();
        assertTrue(gameEmpty.getActivePlayers().isEmpty());
    }

    @Test
    @DisplayName("activePlayerIds_PreservesCustomOrderDifferingFromTableRoster")
    void testActivePlayerIds_PreservesCustomOrder() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        Player p3 = player("p3", "Charlie", 1000);

        Game game = defaultBuilder()
                .players(List.of(p1, p2, p3))
                .activePlayerIds(List.of("p3", "p1"))
                .build();

        assertEquals(2, game.getActivePlayers().size());
        assertEquals("p3", game.getActivePlayers().get(0).getPlayerId());
        assertEquals("p1", game.getActivePlayers().get(1).getPlayerId());
    }

    // =========================================================================
    // 3. Boundary Conditions (Blinds, Pot, Deck Size, Tasks, Revisions)
    // =========================================================================

    @Test
    @DisplayName("boundary_BlindsAndPot_ZeroNegativeAndMaxValues")
    void testBoundary_BlindsAndPot() {
        Game gameZero = defaultBuilder()
                .pot(0)
                .preHandCarryOver(0)
                .smallBlind(0)
                .bigBlind(0)
                .build();
        assertEquals(0, gameZero.getPot());
        assertEquals(0, gameZero.getPreHandCarryOver());
        assertEquals(0, gameZero.getSmallBlind());
        assertEquals(0, gameZero.getBigBlind());

        Game gameMax = defaultBuilder()
                .pot(Integer.MAX_VALUE)
                .preHandCarryOver(Integer.MAX_VALUE)
                .smallBlind(1_000_000)
                .bigBlind(2_000_000)
                .build();
        assertEquals(Integer.MAX_VALUE, gameMax.getPot());
        assertEquals(Integer.MAX_VALUE, gameMax.getPreHandCarryOver());
        assertEquals(1_000_000, gameMax.getSmallBlind());
        assertEquals(2_000_000, gameMax.getBigBlind());
    }

    @Test
    @DisplayName("boundary_DeckSize_EmptyDeckThrowsOnDeal; SingleCardDeckDealsOnce")
    void testBoundary_DeckSize() {
        // Deck with 0 cards
        Game emptyDeckGame = defaultBuilder()
                .remainingDeck(List.of())
                .build();
        assertTrue(emptyDeckGame.getRemainingDeckSnapshot().isEmpty());
        // Attempting to deal card from empty deck must throw BadRequestException
        assertThrows(BadRequestException.class, emptyDeckGame::dealTurn);

        // Deck with 1 card
        Card singleCard = new Card(Rank.SEVEN, Suit.DIAMONDS);
        Game singleCardGame = defaultBuilder()
                .remainingDeck(List.of(singleCard))
                .communityCards(List.of(
                        new Card(Rank.TWO, Suit.CLUBS),
                        new Card(Rank.THREE, Suit.CLUBS),
                        new Card(Rank.FOUR, Suit.CLUBS)))
                .build();

        assertEquals(1, singleCardGame.getRemainingDeckSnapshot().size());
        singleCardGame.dealTurn();
        assertEquals(4, singleCardGame.getCommunityCards().size());
        assertEquals(singleCard, singleCardGame.getCommunityCards().get(3));
        assertTrue(singleCardGame.getRemainingDeckSnapshot().isEmpty());

        // Second deal must fail
        assertThrows(BadRequestException.class, singleCardGame::dealRiver);
    }

    @Test
    @DisplayName("boundary_ScheduledTasks_DeadlinesAndGenerations_ExtremeValues")
    void testBoundary_ScheduledTasksExtremeValues() {
        Map<ScheduledGameTask, Long> extremeDeadlines = Map.of(
                ScheduledGameTask.AUTO_ADVANCE, 0L,
                ScheduledGameTask.READY_TIMEOUT, -1L,
                ScheduledGameTask.CLEANUP, Long.MAX_VALUE);

        Map<ScheduledGameTask, Long> extremeGenerations = Map.of(
                ScheduledGameTask.AUTO_ADVANCE, 0L,
                ScheduledGameTask.READY_TIMEOUT, Long.MAX_VALUE - 1,
                ScheduledGameTask.CLEANUP, Long.MAX_VALUE);

        Game game = defaultBuilder()
                .scheduledTaskDeadlines(extremeDeadlines)
                .scheduledTaskGenerations(extremeGenerations)
                .build();

        assertEquals(0L, game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(-1L, game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertEquals(Long.MAX_VALUE, game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));

        assertEquals(0L, game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(Long.MAX_VALUE - 1, game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT));
        assertEquals(Long.MAX_VALUE, game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));
    }

    @Test
    @DisplayName("boundary_StateRevisionAndHandNumber_ExtremeValues")
    void testBoundary_StateRevisionAndHandNumberExtremeValues() {
        Game game = defaultBuilder()
                .stateRevision(Long.MAX_VALUE - 1)
                .handNumber(Long.MAX_VALUE)
                .build();

        assertEquals(Long.MAX_VALUE - 1, game.getStateRevision());
        assertEquals(Long.MAX_VALUE, game.getHandNumber());

        game.incrementStateRevision();
        assertEquals(Long.MAX_VALUE, game.getStateRevision());
    }

    // =========================================================================
    // 4. Position Normalization Under Out-of-Bounds Indices
    // =========================================================================

    @ParameterizedTest(name = "currentPlayerPosition={0}")
    @ValueSource(ints = {-10, -1, 0, 1, 2, 5, 999})
    @DisplayName("positionNormalization_CurrentPlayerResolvesSafely")
    void testPositionNormalization_CurrentPlayerResolvesSafely(int testPos) {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        Game game = defaultBuilder()
                .players(List.of(p1, p2))
                .activePlayerIds(List.of("p1", "p2"))
                .currentPlayerPosition(testPos)
                .build();

        Player current = game.getCurrentPlayer();
        assertNotNull(current);
        assertTrue(current.getPlayerId().equals("p1") || current.getPlayerId().equals("p2"));
    }

    @Test
    @DisplayName("positionNormalization_EmptyActivePlayers_ThrowsIllegalStateExceptionOnGetCurrentPlayer")
    void testPositionNormalization_EmptyActiveThrows() {
        Game game = defaultBuilder()
                .players(List.of(player("p1", "Alice", 1000), player("p2", "Bob", 1000)))
                .activePlayerIds(List.of())
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, game::getCurrentPlayer);
        assertEquals("No active players available", ex.getMessage());
    }

    // =========================================================================
    // 5. Fallbacks and Default Invariants
    // =========================================================================

    @Test
    @DisplayName("fallbacks_HandSeatOrder_NullOrEmptyDefaultsToRoster")
    void testFallbacks_HandSeatOrder() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        Game gameWithNull = defaultBuilder()
                .players(List.of(p1, p2))
                .handSeatOrder(null)
                .build();
        assertEquals(List.of("p1", "p2"), gameWithNull.getHandSeatOrderSnapshot());

        Game gameWithEmpty = defaultBuilder()
                .players(List.of(p1, p2))
                .handSeatOrder(List.of())
                .build();
        assertEquals(List.of("p1", "p2"), gameWithEmpty.getHandSeatOrderSnapshot());
    }

    @Test
    @DisplayName("fallbacks_ReadyGateState_NullInferredFromCountdownActive")
    void testFallbacks_ReadyGateState() {
        Game gameActive = defaultBuilder()
                .readyCountdownActive(true)
                .readyGateState(null)
                .build();
        assertEquals(ReadyGateState.COUNTING_DOWN, gameActive.getReadyGateState());

        Game gameInactive = defaultBuilder()
                .readyCountdownActive(false)
                .readyGateState(null)
                .build();
        assertEquals(ReadyGateState.CLOSED, gameInactive.getReadyGateState());
    }

    @Test
    @DisplayName("fallbacks_Deck_NullDeckDefaultsToFreshDeck")
    void testFallbacks_NullDeckDefaultsToFreshDeck() {
        Game game = defaultBuilder()
                .remainingDeck(null)
                .build();

        // Fresh deck should have 52 cards
        assertEquals(52, game.getRemainingDeckSnapshot().size());
    }

    // =========================================================================
    // 6. Randomized Property-Based Fuzzing
    // =========================================================================

    @Test
    @DisplayName("fuzzing_100RandomPermutations_AllInvariantsHold")
    void testRandomFuzzing_100Permutations() {
        Random rng = new Random(42);

        for (int i = 0; i < 100; i++) {
            int playerCount = 2 + rng.nextInt(7); // 2 to 8 players
            List<Player> players = new ArrayList<>();
            for (int p = 0; p < playerCount; p++) {
                players.add(player("p" + p, "Player" + p, 100 + rng.nextInt(5000)));
            }

            // Random subset of active players (at least 1)
            int activeCount = 1 + rng.nextInt(playerCount);
            List<String> activeIds = new ArrayList<>();
            List<Integer> indices = new ArrayList<>();
            for (int p = 0; p < playerCount; p++) indices.add(p);
            Collections.shuffle(indices, rng);
            for (int a = 0; a < activeCount; a++) {
                activeIds.add("p" + indices.get(a));
            }

            // Random community cards (0 to 5)
            int commCount = rng.nextInt(6);
            List<Card> community = new ArrayList<>();
            for (int c = 0; c < commCount; c++) {
                community.add(new Card(Rank.values()[rng.nextInt(Rank.values().length)], Suit.values()[rng.nextInt(Suit.values().length)]));
            }

            int pot = rng.nextInt(100_000);
            int sb = 5 * (1 + rng.nextInt(10));
            int bb = sb * 2;
            int carryOver = rng.nextInt(500);
            long revision = rng.nextInt(1000);
            long handNum = 1 + rng.nextInt(50);
            GamePhase phase = GamePhase.values()[rng.nextInt(GamePhase.values().length)];
            boolean gameOver = rng.nextBoolean();

            Game game = Game.restoreBuilder()
                    .gameId("fuzz-game-" + i)
                    .players(players)
                    .activePlayerIds(activeIds)
                    .communityCards(community)
                    .remainingDeck(List.of())
                    .pot(pot)
                    .dealerPosition(rng.nextInt(activeCount))
                    .smallBlindPosition(rng.nextInt(activeCount))
                    .bigBlindPosition(rng.nextInt(activeCount))
                    .currentPlayerPosition(rng.nextInt(activeCount))
                    .currentHighestBet(rng.nextInt(1000))
                    .preHandCarryOver(carryOver)
                    .currentPhase(phase)
                    .gameOver(gameOver)
                    .smallBlind(sb)
                    .bigBlind(bb)
                    .handNumber(handNum)
                    .stateRevision(revision)
                    .handEvaluator(new HandEvaluatorService())
                    .build();

            // Invariant assertions:
            assertNotNull(game);
            assertEquals("fuzz-game-" + i, game.getGameId());
            assertEquals(playerCount, game.getPlayers().size());
            assertEquals(activeCount, game.getActivePlayers().size());
            assertEquals(commCount, game.getCommunityCards().size());
            assertEquals(pot, game.getPot());
            assertEquals(carryOver, game.getPreHandCarryOver());
            assertEquals(sb, game.getSmallBlind());
            assertEquals(bb, game.getBigBlind());
            assertEquals(revision, game.getStateRevision());
            assertEquals(handNum, game.getHandNumber());
            assertEquals(phase, game.getCurrentPhase());
            assertEquals(gameOver, game.isGameOver());

            // Current player resolution must not throw when activePlayers is non-empty
            assertNotNull(game.getCurrentPlayer());
        }
    }

    // =========================================================================
    // Helpers
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
