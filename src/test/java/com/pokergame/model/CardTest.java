package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link Card}.
 */
@Tag("unit")
@DisplayName("Card model")
class CardTest {

    @Test
    void rejectsNullRankOrSuit() {
        assertThrows(NullPointerException.class, () -> new Card(null, Suit.SPADES));
        assertThrows(NullPointerException.class, () -> new Card(Rank.ACE, null));
    }

    @ParameterizedTest
    @CsvSource({
            "TWO,CLUBS,2C",
            "TEN,DIAMONDS,10D",
            "JACK,HEARTS,JH",
            "QUEEN,SPADES,QS",
            "KING,HEARTS,KH",
            "ACE,SPADES,AS"
    })
    void toJsonStringUsesCompactRankAndSuitCode(Rank rank, Suit suit, String expected) {
        assertEquals(expected, new Card(rank, suit).toJsonString());
    }

    @Test
    void getValueDelegatesToRank() {
        assertEquals(14, new Card(Rank.ACE, Suit.HEARTS).getValue());
        assertEquals(13, new Card(Rank.KING, Suit.DIAMONDS).getValue());
        assertEquals(2, new Card(Rank.TWO, Suit.CLUBS).getValue());
    }

    @ParameterizedTest
    @CsvSource({
            "ACE,SPADES,Ace of Spades",
            "KING,HEARTS,King of Hearts",
            "TWO,CLUBS,2 of Clubs"
    })
    void rendersReadableLabel(Rank rank, Suit suit, String expected) {
        assertEquals(expected, new Card(rank, suit).toString());
    }
}
