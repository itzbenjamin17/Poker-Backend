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

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        player = new Player("TestPlayer", "player123", 1000);
    }

    /**
     * Protects the expected behavior for player creation.
     */
    @Test
    void testPlayerCreation() {
        assertNotNull(player);
        assertEquals("TestPlayer", player.getName());
        assertEquals("player123", player.getPlayerId());
        assertEquals(1000, player.getChips());
    }

    /**
     * Protects the expected behavior for player creation with zero chips.
     */
    @Test
    void testPlayerCreationWithZeroChips() {
        Player poorPlayer = new Player("Poor", "player456", 0);
        assertEquals(0, poorPlayer.getChips());
    }

    /**
     * Protects the expected behavior for player creation with null name.
     */
    @Test
    void testPlayerCreationWithNullName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Player(null, "player789", 1000));
        assertEquals("Player name required", exception.getMessage());
    }

    /**
     * Protects the expected behavior for player creation with empty name.
     */
    @Test
    void testPlayerCreationWithEmptyName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Player("   ", "player789", 1000));
        assertEquals("Player name required", exception.getMessage());
    }

    /**
     * Protects the expected behavior for player creation with negative chips.
     */
    @Test
    void testPlayerCreationWithNegativeChips() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Player("Cheater", "player999", -100));
        assertEquals("Chips cannot be negative", exception.getMessage());
    }

    /**
     * Protects the expected behavior for initial state.
     */
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

    /**
     * Protects the expected behavior for fold action.
     */
    @Test
    void testFoldAction() {
        int pot = 100;
        int newPot = player.doAction(PlayerAction.FOLD, 0, pot);

        assertEquals(100, newPot, "Pot should not change on fold");
        assertTrue(player.getHasFolded());
        assertEquals(1000, player.getChips(), "Chips should not change on fold");
    }

    /**
     * Protects the expected behavior for check action.
     */
    @Test
    void testCheckAction() {
        int pot = 100;
        int newPot = player.doAction(PlayerAction.CHECK, 0, pot);

        assertEquals(100, newPot, "Pot should not change on check");
        assertEquals(1000, player.getChips(), "Chips should not change on check");
        assertEquals(0, player.getCurrentBet());
    }

    /**
     * Protects the expected behavior for bet action.
     */
    @Test
    void testBetAction() {
        int pot = 100;
        int betAmount = 50;
        int newPot = player.doAction(PlayerAction.BET, betAmount, pot);

        assertEquals(150, newPot, "Pot should increase by bet amount");
        assertEquals(950, player.getChips(), "Chips should decrease by bet amount");
        assertEquals(50, player.getCurrentBet());
    }

    /**
     * Protects the expected behavior for call action.
     */
    @Test
    void testCallAction() {
        int pot = 100;
        int callAmount = 50;
        int newPot = player.doAction(PlayerAction.CALL, callAmount, pot);

        assertEquals(150, newPot);
        assertEquals(950, player.getChips());
        assertEquals(50, player.getCurrentBet());
    }

    /**
     * Protects the expected behavior for raise action.
     */
    @Test
    void testRaiseAction() {
        int pot = 100;
        int raiseAmount = 100;
        int newPot = player.doAction(PlayerAction.RAISE, raiseAmount, pot);

        assertEquals(200, newPot);
        assertEquals(900, player.getChips());
        assertEquals(100, player.getCurrentBet());
    }

    /**
     * Protects the expected behavior for all in action.
     */
    @Test
    void testAllInAction() {
        int pot = 100;
        int newPot = player.doAction(PlayerAction.ALL_IN, 0, pot);

        assertEquals(1100, newPot, "All chips should go to pot");
        assertEquals(0, player.getChips(), "Player should have no chips left");
        assertTrue(player.getIsAllIn());
        assertEquals(1000, player.getCurrentBet());
    }

    /**
     * Protects the expected behavior for pay chips.
     */
    @Test
    void testPayChips() {
        int pot = 100;
        int newPot = player.payChips(pot, 250);

        assertEquals(350, newPot);
        assertEquals(750, player.getChips());
        assertEquals(250, player.getCurrentBet());
    }

    /**
     * Protects the expected behavior for pay chips marks all in when stack reaches zero.
     */
    @Test
    void testPayChipsMarksAllInWhenStackReachesZero() {
        Player shortStack = new Player("Short", "short-1", 50);
        int newPot = shortStack.payChips(100, 50);

        assertEquals(150, newPot);
        assertEquals(0, shortStack.getChips());
        assertTrue(shortStack.getIsAllIn());
        assertEquals(50, shortStack.getCurrentBet());
    }

    /**
     * Protects the expected behavior for add chips.
     */
    @Test
    void testAddChips() {
        player.addChips(500);
        assertEquals(1500, player.getChips());

        player.addChips(0);
        assertEquals(1500, player.getChips());
    }

    /**
     * Protects the expected behavior for reset attributes.
     */
    @Test
    void testResetAttributes() {
        // Set up some state
        player.doAction(PlayerAction.BET, 100, 0);
        player.doAction(PlayerAction.FOLD, 0, 0);
        player.setHandRank(HandRank.FLUSH);
        player.setBestHand(List.of(new Card(Rank.ACE, Suit.SPADES)));

        // Reset
        player.resetAttributes();

        assertEquals(0, player.getHoleCards().size());
        assertEquals(0, player.getBestHand().size());
        assertEquals(HandRank.NO_HAND, player.getHandRank());
        assertFalse(player.getHasFolded());
        assertFalse(player.getIsAllIn());
        assertEquals(0, player.getCurrentBet());
    }

    /**
     * Protects the expected behavior for reset current bet.
     */
    @Test
    void testResetCurrentBet() {
        player.doAction(PlayerAction.BET, 100, 0);
        assertEquals(100, player.getCurrentBet());

        player.resetCurrentBet();
        assertEquals(0, player.getCurrentBet());
        assertEquals(900, player.getChips(), "Chips should not be affected");
    }

    /**
     * Protects the expected behavior for set is out.
     */
    @Test
    void testSetIsOut() {
        assertFalse(player.getIsOut());
        player.setIsOut();
        assertTrue(player.getIsOut());
    }

    /**
     * Protects the expected behavior for set best hand.
     */
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

    /**
     * Protects the expected behavior for set hand rank.
     */
    @Test
    void testSetHandRank() {
        player.setHandRank(HandRank.ROYAL_FLUSH);
        assertEquals(HandRank.ROYAL_FLUSH, player.getHandRank());
    }

    /**
     * Protects the expected behavior for multiple bets increment current bet.
     */
    @Test
    void testMultipleBetsIncrementCurrentBet() {
        player.doAction(PlayerAction.BET, 100, 0);
        assertEquals(100, player.getCurrentBet());

        player.doAction(PlayerAction.RAISE, 200, 0);
        assertEquals(300, player.getCurrentBet());
        assertEquals(700, player.getChips());
    }

    /**
     * Protects the expected behavior for player state persists across actions.
     */
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

    /**
     * Protects the expected behavior for all in with partial chips.
     */
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

    /**
     * Protects the expected behavior for hole cards initially empty.
     */
    @Test
    void testHoleCardsInitiallyEmpty() {
        List<Card> holeCards = player.getHoleCards();
        assertNotNull(holeCards);
        assertEquals(0, holeCards.size());
    }

    /**
     * Protects the expected behavior for get player ID.
     */
    @Test
    void testGetPlayerId() {
        assertEquals("player123", player.getPlayerId());
    }

    /**
     * Protects the expected behavior for get name.
     */
    @Test
    void testGetName() {
        assertEquals("TestPlayer", player.getName());
    }

    /**
     * Protects the expected behavior for equals uses stable player ID across reconstructed instances.
     */
    @Test
    void testEqualsUsesStablePlayerIdAcrossReconstructedInstances() {
        Player reconstructed = new Player("DifferentName", "player123", 50);

        assertEquals(player, reconstructed);
        assertEquals(reconstructed, player);
    }

    /**
     * Protects the expected behavior for hash code matches for same player ID.
     */
    @Test
    void testHashCodeMatchesForSamePlayerId() {
        Player reconstructed = new Player("DifferentName", "player123", 50);

        assertEquals(player.hashCode(), reconstructed.hashCode());
    }

    /**
     * Protects the expected behavior for equals returns false for different player IDs.
     */
    @Test
    void testEqualsReturnsFalseForDifferentPlayerIds() {
        Player differentPlayer = new Player("TestPlayer", "other-player", 1000);

        assertNotEquals(player, differentPlayer);
    }

    /**
     * Protects the expected behavior for equals supports collection lookups for reconstructed instances.
     */
    @Test
    void testEqualsSupportsCollectionLookupsForReconstructedInstances() {
        HashSet<Player> players = new HashSet<>();
        players.add(player);

        Player reconstructed = new Player("TestPlayer", "player123", 1000);

        assertTrue(players.contains(reconstructed));
    }
}
