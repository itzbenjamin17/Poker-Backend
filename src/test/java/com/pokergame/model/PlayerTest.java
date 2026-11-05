package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.pokergame.exception.BadRequestException;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Player class.
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
    void testPlayerCreationWithNullName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Player(null, "player789", 1000));
        assertEquals("Player name required", exception.getMessage());
    }

    @Test
    void testPlayerCreationWithEmptyName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Player("   ", "player789", 1000));
        assertEquals("Player name required", exception.getMessage());
    }

    @Test
    void testPlayerCreationWithNegativeChips() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Player("Cheater", "player999", -100));
        assertEquals("Chips cannot be negative", exception.getMessage());
    }

    @Test
    void testInitialState() {
        assertEquals(0, player.getCurrentBet());
        assertFalse(player.getHasFolded());
        assertFalse(player.getIsAllIn());
        assertFalse(player.getIsOut());
        assertEquals(0, player.getHoleCards().size());
        assertEquals(0, player.getBestHand().size());
        assertEquals(HandRank.NO_HAND, player.getHandRank());
    }

    @Test
    void testFoldAction() {
        int pot = 100;
        int newPot = player.doAction(PlayerAction.FOLD, 0, pot);

        assertEquals(100, newPot, "Pot should not change on fold");
        assertTrue(player.getHasFolded());
        assertEquals(1000, player.getChips(), "Chips should not change on fold");
    }

    @Test
    void testCheckAction() {
        int pot = 100;
        int newPot = player.doAction(PlayerAction.CHECK, 0, pot);

        assertEquals(100, newPot, "Pot should not change on check");
        assertEquals(1000, player.getChips(), "Chips should not change on check");
        assertEquals(0, player.getCurrentBet());
    }

    @Test
    void testBetAction() {
        int pot = 100;
        int betAmount = 50;
        int newPot = player.doAction(PlayerAction.BET, betAmount, pot);

        assertEquals(150, newPot, "Pot should increase by bet amount");
        assertEquals(950, player.getChips(), "Chips should decrease by bet amount");
        assertEquals(50, player.getCurrentBet());
    }

    @Test
    void testCallAction() {
        int pot = 100;
        int callAmount = 50;
        int newPot = player.doAction(PlayerAction.CALL, callAmount, pot);

        assertEquals(150, newPot);
        assertEquals(950, player.getChips());
        assertEquals(50, player.getCurrentBet());
    }

    @Test
    void testRaiseAction() {
        int pot = 100;
        int raiseAmount = 100;
        int newPot = player.doAction(PlayerAction.RAISE, raiseAmount, pot);

        assertEquals(200, newPot);
        assertEquals(900, player.getChips());
        assertEquals(100, player.getCurrentBet());
    }

    @Test
    void testAllInAction() {
        int pot = 100;
        int newPot = player.doAction(PlayerAction.ALL_IN, 0, pot);

        assertEquals(1100, newPot, "All chips should go to pot");
        assertEquals(0, player.getChips(), "Player should have no chips left");
        assertTrue(player.getIsAllIn());
        assertEquals(1000, player.getCurrentBet());
    }

    @Test
    void testPayChips() {
        int pot = 100;
        int newPot = player.payChips(pot, 250);

        assertEquals(350, newPot);
        assertEquals(750, player.getChips());
        assertEquals(250, player.getCurrentBet());
    }

    @Test
    void testPayChipsMarksAllInWhenStackReachesZero() {
        Player shortStack = new Player("Short", "short-1", 50);
        int newPot = shortStack.payChips(100, 50);

        assertEquals(150, newPot);
        assertEquals(0, shortStack.getChips());
        assertTrue(shortStack.getIsAllIn());
        assertEquals(50, shortStack.getCurrentBet());
    }

    @Test
    void testAddChips() {
        player.addChips(500);
        assertEquals(1500, player.getChips());

        player.addChips(0);
        assertEquals(1500, player.getChips());
    }

    @Test
    void testResetAttributes() {
        player.doAction(PlayerAction.BET, 100, 0);
        player.doAction(PlayerAction.FOLD, 0, 0);
        player.setHandRank(HandRank.FLUSH);
        player.setBestHand(List.of(new Card(Rank.ACE, Suit.SPADES)));

        player.resetAttributes();

        assertEquals(0, player.getHoleCards().size());
        assertEquals(0, player.getBestHand().size());
        assertEquals(HandRank.NO_HAND, player.getHandRank());
        assertFalse(player.getHasFolded());
        assertFalse(player.getIsAllIn());
        assertEquals(0, player.getCurrentBet());
    }

    @Test
    void testResetCurrentBet() {
        player.doAction(PlayerAction.BET, 100, 0);
        assertEquals(100, player.getCurrentBet());

        player.resetCurrentBet();
        assertEquals(0, player.getCurrentBet());
        assertEquals(900, player.getChips(), "Chips should not be affected");
    }

    @Test
    void testSetIsOut() {
        assertFalse(player.getIsOut());
        player.setIsOut();
        assertTrue(player.getIsOut());
    }

    @Test
    void testSetBestHand() {
        List<Card> hand = List.of(
                new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.SPADES),
                new Card(Rank.JACK, Suit.SPADES),
                new Card(Rank.TEN, Suit.SPADES));

        player.setBestHand(hand);
        assertEquals(5, player.getBestHand().size());
        assertEquals(hand, player.getBestHand());
    }

    @Test
    void testSetHandRank() {
        player.setHandRank(HandRank.ROYAL_FLUSH);
        assertEquals(HandRank.ROYAL_FLUSH, player.getHandRank());
    }

    @Test
    void testMultipleBetsIncrementCurrentBet() {
        player.doAction(PlayerAction.BET, 100, 0);
        assertEquals(100, player.getCurrentBet());

        player.doAction(PlayerAction.RAISE, 200, 0);
        assertEquals(300, player.getCurrentBet());
        assertEquals(700, player.getChips());
    }

    @Test
    void testPlayerStatePersistsAcrossActions() {
        int pot = 0;

        pot = player.doAction(PlayerAction.BET, 100, pot);
        assertEquals(100, pot);
        assertEquals(900, player.getChips());

        pot = player.doAction(PlayerAction.RAISE, 200, pot);
        assertEquals(300, pot);
        assertEquals(700, player.getChips());
        assertEquals(300, player.getCurrentBet());
    }

    @Test
    void testAllInWithPartialChips() {
        Player shortStack = new Player("ShortStack", "player999", 50);
        int pot = 100;

        int newPot = shortStack.doAction(PlayerAction.ALL_IN, 0, pot);

        assertEquals(150, newPot);
        assertEquals(0, shortStack.getChips());
        assertTrue(shortStack.getIsAllIn());
        assertEquals(50, shortStack.getCurrentBet());
    }

    @Test
    void testHoleCardsInitiallyEmpty() {
        List<Card> holeCards = player.getHoleCards();
        assertNotNull(holeCards);
        assertEquals(0, holeCards.size());
    }

    /**
     * Identity is keyed on the stable player ID, not the display name, so a reconstructed player
     * with the same ID but a different name is treated as the same participant.
     */
    @Test
    void testEqualsUsesStablePlayerIdAcrossReconstructedInstances() {
        Player reconstructed = new Player("DifferentName", "player123", 50);

        assertEquals(player, reconstructed);
        assertEquals(reconstructed, player);
    }

    @Test
    void testHashCodeMatchesForSamePlayerId() {
        Player reconstructed = new Player("DifferentName", "player123", 50);

        assertEquals(player.hashCode(), reconstructed.hashCode());
    }

    @Test
    void testEqualsReturnsFalseForDifferentPlayerIds() {
        Player differentPlayer = new Player("TestPlayer", "other-player", 1000);

        assertNotEquals(player, differentPlayer);
    }

    /**
     * Player identity supports hash-based collection membership, so a reconstructed instance with
     * the same ID resolves to the same entry.
     */
    @Test
    void testEqualsSupportsCollectionLookupsForReconstructedInstances() {
        HashSet<Player> players = new HashSet<>();
        players.add(player);

        Player reconstructed = new Player("TestPlayer", "player123", 1000);

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
        assertFalse(restored.getHasFolded());
        assertFalse(restored.getIsAllIn());
        assertFalse(restored.getIsOut());
        assertTrue(restored.getIsDisconnected());
        assertEquals(123456789L, restored.getDisconnectDeadlineEpochMs());
        assertTrue(restored.getIsReadyForNextHand());
    }
}
