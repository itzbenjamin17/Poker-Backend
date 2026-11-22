package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link Player}.
 */
@Tag("unit")
@DisplayName("Player model")
class PlayerTest {

    private Player player;

    @BeforeEach
    void setUp() {
        player = new Player("TestPlayer", "player123", 1000);
    }

    @Test
    void testPlayerCreation() {
        assertNotNull(player);
        assertEquals("TestPlayer", player.getName());
        assertEquals("player123", player.getPlayerId());
        assertEquals(1000, player.getChips());
    }

    @Test
    void testPlayerCreationWithZeroChips() {
        Player poorPlayer = new Player("Poor", "player456", 0);
        assertEquals(0, poorPlayer.getChips());
    }

    @Test
    void testPlayerCreationWithNullOrBlankName() {
        IllegalStateException nullName = assertThrows(
                IllegalStateException.class,
                () -> new Player(null, "player789", 1000));
        assertEquals("Player name required", nullName.getMessage());

        IllegalStateException blankName = assertThrows(
                IllegalStateException.class,
                () -> new Player("   ", "player789", 1000));
        assertEquals("Player name required", blankName.getMessage());
    }

    @Test
    void testPlayerCreationWithNegativeChips() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new Player("Cheater", "player999", -100));
        assertEquals("Chips cannot be negative", exception.getMessage());
    }

    @Test
    void testPlayerCreationWithBlankPlayerId() {
        IllegalStateException nullId = assertThrows(
                IllegalStateException.class,
                () -> new Player("Alice", null, 1000));
        assertEquals("Player ID cannot be empty", nullId.getMessage());

        IllegalStateException blankId = assertThrows(
                IllegalStateException.class,
                () -> new Player("Alice", "   ", 1000));
        assertEquals("Player ID cannot be empty", blankId.getMessage());
    }

    @Test
    void testInitialState() {
        assertEquals(0, player.getCurrentBet());
        assertFalse(player.hasFolded());
        assertFalse(player.isAllIn());
        assertFalse(player.isOut());
        assertEquals(0, player.getHoleCards().size());
        assertEquals(0, player.getBestHand().size());
        assertEquals(HandRank.NO_HAND, player.getHandRank());
    }

    @Test
    void testFoldAndCheckActions() {
        assertEquals(0, player.doAction(PlayerAction.CHECK, 0));
        assertFalse(player.hasFolded());
        assertEquals(1000, player.getChips());

        assertEquals(0, player.doAction(PlayerAction.FOLD, 0));
        assertTrue(player.hasFolded());
        assertEquals(1000, player.getChips());
    }

    @Test
    void testBetCallRaiseAndAllInActions() {
        assertEquals(50, player.doAction(PlayerAction.BET, 50));
        assertEquals(950, player.getChips());
        assertEquals(50, player.getCurrentBet());

        assertEquals(50, player.doAction(PlayerAction.CALL, 50));
        assertEquals(900, player.getChips());
        assertEquals(100, player.getCurrentBet());

        assertEquals(100, player.doAction(PlayerAction.RAISE, 100));
        assertEquals(800, player.getChips());
        assertEquals(200, player.getCurrentBet());

        assertEquals(800, player.doAction(PlayerAction.ALL_IN, 0));
        assertEquals(0, player.getChips());
        assertTrue(player.isAllIn());
        assertEquals(1000, player.getCurrentBet());
    }

    @Test
    void testBetMarksAllInWhenStackReachesZero() {
        Player shortStack = new Player("Short", "short-1", 50);
        int committed = shortStack.doAction(PlayerAction.BET, 50);

        assertEquals(50, committed);
        assertEquals(0, shortStack.getChips());
        assertTrue(shortStack.isAllIn());
        assertEquals(50, shortStack.getCurrentBet());
    }

    @Test
    void testDoActionRejectsNegativeAndInsufficientAmounts() {
        IllegalStateException negative = assertThrows(
                IllegalStateException.class,
                () -> player.doAction(PlayerAction.BET, -1));
        assertEquals("Chip amount cannot be negative", negative.getMessage());

        IllegalStateException insufficient = assertThrows(
                IllegalStateException.class,
                () -> player.doAction(PlayerAction.BET, 1001));
        assertEquals("Insufficient chips for this action", insufficient.getMessage());

        assertThrows(NullPointerException.class, () -> player.doAction(null, 0));
    }

    @Test
    void testAddChips() {
        player.addChips(500);
        assertEquals(1500, player.getChips());

        player.addChips(0);
        assertEquals(1500, player.getChips());
    }

    @Test
    void testResetAttributesClearsPerHandStateAndPreservesMatchAndConnectionState() {
        Player inHand = Player.restoreBuilder()
                .name("Alice")
                .playerId("p1")
                .holeCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)))
                .bestHand(List.of(new Card(Rank.ACE, Suit.SPADES)))
                .handRank(HandRank.FLUSH)
                .chips(400)
                .currentBet(100)
                .hasFolded(true)
                .allIn(true)
                .out(true)
                .disconnected(true)
                .disconnectDeadlineEpochMs(987654321L)
                .readyForNextHand(true)
                .build();

        inHand.resetAttributes();

        assertTrue(inHand.getHoleCards().isEmpty());
        assertTrue(inHand.getBestHand().isEmpty());
        assertEquals(HandRank.NO_HAND, inHand.getHandRank());
        assertFalse(inHand.hasFolded());
        assertFalse(inHand.isAllIn());
        assertEquals(0, inHand.getCurrentBet());
        assertFalse(inHand.isReadyForNextHand());

        assertEquals(400, inHand.getChips());
        assertTrue(inHand.isOut());
        assertTrue(inHand.isDisconnected());
        assertEquals(987654321L, inHand.getDisconnectDeadlineEpochMs());
    }

    @Test
    void testResetCurrentBet() {
        player.doAction(PlayerAction.BET, 100);
        assertEquals(100, player.getCurrentBet());

        player.resetCurrentBet();
        assertEquals(0, player.getCurrentBet());
        assertEquals(900, player.getChips());
    }

    @Test
    void testSetIsOut() {
        assertFalse(player.isOut());
        player.setIsOut();
        assertTrue(player.isOut());
    }

    @Test
    void testEqualsAndHashCodeUseStablePlayerIdAcrossReconstructedInstances() {
        Player reconstructed = new Player("DifferentName", "player123", 50);
        Player differentPlayer = new Player("TestPlayer", "other-player", 1000);

        assertEquals(player, reconstructed);
        assertEquals(reconstructed, player);
        assertEquals(player.hashCode(), reconstructed.hashCode());
        assertNotEquals(player, differentPlayer);

        HashSet<Player> players = new HashSet<>();
        players.add(player);
        assertTrue(players.contains(reconstructed));
    }

    @Test
    void testRestoreBuilder() {
        List<Card> holeCards = List.of(new Card(Rank.ACE, Suit.HEARTS), new Card(Rank.KING, Suit.HEARTS));
        List<Card> bestHand = List.of(
                new Card(Rank.ACE, Suit.HEARTS),
                new Card(Rank.KING, Suit.HEARTS),
                new Card(Rank.QUEEN, Suit.HEARTS),
                new Card(Rank.JACK, Suit.HEARTS),
                new Card(Rank.TEN, Suit.HEARTS));

        Player restored = Player.restoreBuilder()
                .name("Alice")
                .playerId("p1")
                .holeCards(holeCards)
                .bestHand(bestHand)
                .handRank(HandRank.ROYAL_FLUSH)
                .chips(500)
                .currentBet(50)
                .hasFolded(false)
                .allIn(false)
                .out(false)
                .disconnected(true)
                .disconnectDeadlineEpochMs(123456789L)
                .readyForNextHand(true)
                .build();

        assertEquals("Alice", restored.getName());
        assertEquals("p1", restored.getPlayerId());
        assertEquals(holeCards, restored.getHoleCards());
        assertEquals(bestHand, restored.getBestHand());
        assertEquals(HandRank.ROYAL_FLUSH, restored.getHandRank());
        assertEquals(500, restored.getChips());
        assertEquals(50, restored.getCurrentBet());
        assertFalse(restored.hasFolded());
        assertFalse(restored.isAllIn());
        assertFalse(restored.isOut());
        assertTrue(restored.isDisconnected());
        assertEquals(123456789L, restored.getDisconnectDeadlineEpochMs());
        assertTrue(restored.isReadyForNextHand());
    }

    @ParameterizedTest(name = "hasFolded={0}, isOut={1}, isAllIn={2} -> isInHand={3}, canAct={4}")
    @CsvSource({
            "false, false, false, true,  true",
            "false, false, true,  true,  false",
            "false, true,  false, false, false",
            "false, true,  true,  false, false",
            "true,  false, false, false, false",
            "true,  false, true,  false, false",
            "true,  true,  false, false, false",
            "true,  true,  true,  false, false"
    })
    void testIsInHandAndCanActFlagCombinations(
            boolean hasFolded,
            boolean isOut,
            boolean isAllIn,
            boolean expectedInHand,
            boolean expectedCanAct) {
        Player candidate = Player.restoreBuilder()
                .name("Alice")
                .playerId("p1")
                .holeCards(List.of())
                .bestHand(List.of())
                .handRank(HandRank.NO_HAND)
                .chips(isAllIn ? 0 : 500)
                .currentBet(0)
                .hasFolded(hasFolded)
                .allIn(isAllIn)
                .out(isOut)
                .disconnected(false)
                .disconnectDeadlineEpochMs(null)
                .readyForNextHand(false)
                .build();

        assertEquals(expectedInHand, candidate.isInHand());
        assertEquals(expectedCanAct, candidate.canAct());
    }

    @Test
    void testGetHoleCardsAndGetBestHandReturnUnmodifiableLists() {
        List<Card> holeCards = player.getHoleCards();
        List<Card> bestHand = player.getBestHand();
        Card card = new Card(Rank.ACE, Suit.SPADES);

        assertThrows(UnsupportedOperationException.class, () -> holeCards.add(card));
        assertThrows(UnsupportedOperationException.class, () -> bestHand.add(card));
    }

    @Test
    void testSetBestHandDefensivelyCopiesInputAndHandlesNull() {
        List<Card> mutableHand = new ArrayList<>(List.of(
                new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.SPADES),
                new Card(Rank.JACK, Suit.SPADES),
                new Card(Rank.TEN, Suit.SPADES)));

        player.setBestHand(mutableHand);
        mutableHand.clear();

        assertEquals(5, player.getBestHand().size());

        player.setBestHand(null);
        assertTrue(player.getBestHand().isEmpty());
    }

    @Test
    void testRestoreBuilderHandlesNullCardLists() {
        Player restored = Player.restoreBuilder()
                .name("Alice")
                .playerId("p1")
                .chips(500)
                .build();

        assertTrue(restored.getHoleCards().isEmpty());
        assertTrue(restored.getBestHand().isEmpty());
        assertEquals(HandRank.NO_HAND, restored.getHandRank());
    }

    @Test
    void testSetHoleCardsDefensivelyCopiesInputAndRejectsNull() {
        List<Card> mutableCards = new ArrayList<>(List.of(
                new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.SPADES)));

        player.setHoleCards(mutableCards);
        mutableCards.clear();

        assertEquals(2, player.getHoleCards().size());
        assertThrows(NullPointerException.class, () -> player.setHoleCards(null));
    }

    @Test
    void testRestoreBuilderValidatesIdentityAndChips() {
        assertThrows(IllegalStateException.class, () -> Player.restoreBuilder()
                .name(" ")
                .playerId("p1")
                .chips(100)
                .build());
        assertThrows(IllegalStateException.class, () -> Player.restoreBuilder()
                .name("Alice")
                .playerId(" ")
                .chips(100)
                .build());
        assertThrows(IllegalStateException.class, () -> Player.restoreBuilder()
                .name("Alice")
                .playerId("p1")
                .chips(-1)
                .build());
    }
}
