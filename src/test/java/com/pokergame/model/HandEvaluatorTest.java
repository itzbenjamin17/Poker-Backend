package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HandEvaluator}.
 */
@Tag("unit")
@DisplayName("Hand evaluator")
class HandEvaluatorTest {

    private HandEvaluator service;

    @BeforeEach
    void setUp() {
        service = new HandEvaluator();
    }

    @Test
    void getBestHandRejectsNullOrFewerThanFiveCards() {
        List<Card> twoCards = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.FOUR, Suit.HEARTS));

        assertThrows(NullPointerException.class, () -> service.getBestHand(null, twoCards));
        assertThrows(NullPointerException.class, () -> service.getBestHand(twoCards, null));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getBestHand(twoCards, List.of(card(Rank.KING, Suit.CLUBS), card(Rank.NINE, Suit.DIAMONDS))));
        assertTrue(exception.getMessage().contains("5"));
    }

    @Test
    void compareSameRankRejectsInvalidHandSizeMismatchedRankAndReturnsZeroForNoHand() {
        List<Card> five = cards("2S 5H 7D 9C KS");
        assertThrows(IllegalStateException.class,
                () -> service.compareSameRank(List.of(), five, HandRank.HIGH_CARD));
        assertThrows(IllegalStateException.class,
                () -> service.compareSameRank(five, cards("2S 5H 7D 9C"), HandRank.HIGH_CARD));

        List<Card> onePair = cards("2S 2H 7D 9C KS");
        assertThrows(IllegalStateException.class,
                () -> service.compareSameRank(five, onePair, HandRank.HIGH_CARD));
        assertThrows(IllegalStateException.class,
                () -> service.compareSameRank(onePair, five, HandRank.HIGH_CARD));

        assertEquals(0, service.compareSameRank(five, onePair, HandRank.NO_HAND));
        assertEquals(0, service.compareSameRank(onePair, five, HandRank.NO_HAND));
    }

    @Test
    void getBestHandWithExactlyFiveCardsEvaluatesOnlyCombination() {
        List<Card> communityCards = List.of(
                card(Rank.TEN, Suit.HEARTS),
                card(Rank.JACK, Suit.HEARTS),
                card(Rank.QUEEN, Suit.HEARTS));
        List<Card> holeCards = List.of(
                card(Rank.KING, Suit.HEARTS),
                card(Rank.ACE, Suit.HEARTS));

        HandEvaluationResult result = service.getBestHand(communityCards, holeCards);

        assertEquals(HandRank.ROYAL_FLUSH, result.handRank());
        assertEquals(5, result.bestHand().size());
    }

    @Test
    void highCardComparisonUsesLowToHighBestHandsWithoutMutatingInputs() {
        List<Card> board = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.FOUR, Suit.HEARTS),
                card(Rank.SEVEN, Suit.DIAMONDS), card(Rank.NINE, Suit.CLUBS),
                card(Rank.JACK, Suit.SPADES));
        List<Card> aceHole = List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.THREE, Suit.DIAMONDS));
        List<Card> kingHole = List.of(card(Rank.KING, Suit.HEARTS), card(Rank.EIGHT, Suit.DIAMONDS));
        List<Card> aceBefore = List.copyOf(aceHole);
        List<Card> kingBefore = List.copyOf(kingHole);
        HandEvaluationResult ace = service.getBestHand(board, aceHole);
        HandEvaluationResult king = service.getBestHand(board, kingHole);

        assertEquals(List.of(Rank.FOUR, Rank.SEVEN, Rank.NINE, Rank.JACK, Rank.ACE),
                ranksOf(ace.bestHand()));
        assertEquals(List.of(Rank.SEVEN, Rank.EIGHT, Rank.NINE, Rank.JACK, Rank.KING),
                ranksOf(king.bestHand()));
        assertTrue(service.compareSameRank(ace.bestHand(), king.bestHand(), HandRank.HIGH_CARD) > 0);
        assertTrue(service.compareSameRank(king.bestHand(), ace.bestHand(), HandRank.HIGH_CARD) < 0);
        assertEquals(aceBefore, aceHole);
        assertEquals(kingBefore, kingHole);
    }

    @Test
    void highCardComparisonHandlesTiesAndKickersInEitherInputOrder() {
        List<Card> aceHigh = List.of(card(Rank.ACE, Suit.SPADES), card(Rank.JACK, Suit.SPADES),
                card(Rank.NINE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES), card(Rank.FOUR, Suit.DIAMONDS));
        List<Card> sameRanksOtherSuits = List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.JACK, Suit.HEARTS),
                card(Rank.NINE, Suit.HEARTS), card(Rank.SEVEN, Suit.HEARTS), card(Rank.FOUR, Suit.CLUBS));
        List<Card> lowerKicker = List.of(card(Rank.ACE, Suit.CLUBS), card(Rank.JACK, Suit.CLUBS),
                card(Rank.NINE, Suit.CLUBS), card(Rank.SEVEN, Suit.CLUBS), card(Rank.THREE, Suit.DIAMONDS));
        List<Card> mutableDescending = new ArrayList<>(aceHigh);
        List<Card> before = List.copyOf(mutableDescending);

        assertEquals(0, service.compareSameRank(mutableDescending, sameRanksOtherSuits, HandRank.HIGH_CARD));
        assertEquals(0, service.compareSameRank(sameRanksOtherSuits, mutableDescending, HandRank.HIGH_CARD));
        assertTrue(service.compareSameRank(mutableDescending, lowerKicker, HandRank.HIGH_CARD) > 0);
        assertTrue(service.compareSameRank(lowerKicker, mutableDescending, HandRank.HIGH_CARD) < 0);
        assertEquals(before, mutableDescending);
    }

    @Test
    @DisplayName("High-card best hands compare ties and kickers accurately from seven cards")
    void highCardBestHandsCompareTiesAndKickersFromSevenCards() {
        List<Card> board = List.of(
                card(Rank.TWO, Suit.SPADES),
                card(Rank.FIVE, Suit.HEARTS),
                card(Rank.SEVEN, Suit.DIAMONDS),
                card(Rank.JACK, Suit.CLUBS),
                card(Rank.KING, Suit.SPADES));

        HandEvaluationResult aceQueen = service.getBestHand(board,
                List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.QUEEN, Suit.DIAMONDS)));
        HandEvaluationResult aceTen = service.getBestHand(board,
                List.of(card(Rank.ACE, Suit.CLUBS), card(Rank.TEN, Suit.DIAMONDS)));
        HandEvaluationResult aceQueenTie = service.getBestHand(board,
                List.of(card(Rank.ACE, Suit.DIAMONDS), card(Rank.QUEEN, Suit.CLUBS)));

        assertEquals(HandRank.HIGH_CARD, aceQueen.handRank());
        assertEquals(List.of(Rank.SEVEN, Rank.JACK, Rank.QUEEN, Rank.KING, Rank.ACE),
                ranksOf(aceQueen.bestHand()));
        assertEquals(List.of(Rank.SEVEN, Rank.TEN, Rank.JACK, Rank.KING, Rank.ACE),
                ranksOf(aceTen.bestHand()));
        assertTrue(service.compareSameRank(aceQueen.bestHand(), aceTen.bestHand(), HandRank.HIGH_CARD) > 0);
        assertTrue(service.compareSameRank(aceTen.bestHand(), aceQueen.bestHand(), HandRank.HIGH_CARD) < 0);
        assertEquals(0, service.compareSameRank(aceQueen.bestHand(), aceQueenTie.bestHand(),
                HandRank.HIGH_CARD));
        assertEquals(0, service.compareSameRank(aceQueenTie.bestHand(), aceQueen.bestHand(),
                HandRank.HIGH_CARD));
    }

    @Test
    @DisplayName("Royal flushes of different suits or on a shared board tie")
    void royalFlushesTieAcrossSuitsAndSharedBoard() {
        List<Card> spades = hand(card(Rank.TEN, Suit.SPADES), card(Rank.JACK, Suit.SPADES),
                card(Rank.QUEEN, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                card(Rank.ACE, Suit.SPADES));
        List<Card> hearts = hand(card(Rank.TEN, Suit.HEARTS), card(Rank.JACK, Suit.HEARTS),
                card(Rank.QUEEN, Suit.HEARTS), card(Rank.KING, Suit.HEARTS),
                card(Rank.ACE, Suit.HEARTS));

        assertEquals(HandRank.ROYAL_FLUSH, rankOf(spades));
        assertEquals(HandRank.ROYAL_FLUSH, rankOf(hearts));
        assertEquals(0, service.compareSameRank(spades, hearts, HandRank.ROYAL_FLUSH));
        assertEquals(0, service.compareSameRank(hearts, spades, HandRank.ROYAL_FLUSH));

        HandEvaluationResult first = service.getBestHand(spades,
                List.of(card(Rank.TWO, Suit.HEARTS), card(Rank.THREE, Suit.DIAMONDS)));
        HandEvaluationResult second = service.getBestHand(spades,
                List.of(card(Rank.FOUR, Suit.CLUBS), card(Rank.NINE, Suit.DIAMONDS)));

        assertEquals(HandRank.ROYAL_FLUSH, first.handRank());
        assertEquals(HandRank.ROYAL_FLUSH, second.handRank());
        assertEquals(first.bestHand(), second.bestHand());
        assertEquals(0, service.compareSameRank(first.bestHand(), second.bestHand(), first.handRank()));
        assertEquals(0, service.compareSameRank(second.bestHand(), first.bestHand(), second.handRank()));
    }

    @Test
    @DisplayName("The wheel (A-2-3-4-5) is evaluated as a straight ordered with the ace last, while 6-high is preferred")
    void wheelAndSixHighStraightSelectionFromSevenCards() {
        List<Card> wheelBoard = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.THREE, Suit.HEARTS),
                card(Rank.FOUR, Suit.DIAMONDS), card(Rank.FIVE, Suit.CLUBS),
                card(Rank.KING, Suit.SPADES));
        HandEvaluationResult wheelResult = service.getBestHand(wheelBoard,
                List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.NINE, Suit.DIAMONDS)));
        assertEquals(HandRank.STRAIGHT, wheelResult.handRank());
        assertEquals(List.of(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.ACE),
                ranksOf(wheelResult.bestHand()));

        List<Card> sixHighBoard = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.THREE, Suit.HEARTS),
                card(Rank.FOUR, Suit.DIAMONDS), card(Rank.FIVE, Suit.CLUBS),
                card(Rank.SIX, Suit.SPADES));
        HandEvaluationResult sixHighResult = service.getBestHand(sixHighBoard,
                List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.KING, Suit.DIAMONDS)));
        assertEquals(HandRank.STRAIGHT, sixHighResult.handRank());
        assertEquals(List.of(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX),
                ranksOf(sixHighResult.bestHand()));
    }

    @Test
    @DisplayName("Straights and straight flushes are ranked by top card with ace low only in the wheel")
    void straightsAndStraightFlushesRankedByTopCard() {
        List<Card> broadway = cards("TS JH QD KC AS");
        List<Card> broadwayOtherSuits = cards("TH JD QC KS AH");
        List<Card> nineHigh = cards("5S 6H 7D 8C 9S");
        List<Card> sixHigh = cards("2D 3C 4S 5H 6D");
        List<Card> wheel = cards("2H 3S 4D 5C AH");
        List<Card> wheelOtherSuits = cards("2S 3H 4C 5D AS");

        assertTrue(compare(broadway, nineHigh, HandRank.STRAIGHT) > 0);
        assertTrue(compare(nineHigh, broadway, HandRank.STRAIGHT) < 0);
        assertTrue(compare(broadway, wheel, HandRank.STRAIGHT) > 0);
        assertTrue(compare(wheel, broadway, HandRank.STRAIGHT) < 0);
        assertTrue(compare(sixHigh, wheel, HandRank.STRAIGHT) > 0);
        assertTrue(compare(wheel, sixHigh, HandRank.STRAIGHT) < 0);
        assertEquals(0, compare(broadway, broadwayOtherSuits, HandRank.STRAIGHT));
        assertEquals(0, compare(wheel, wheelOtherSuits, HandRank.STRAIGHT));
        assertEquals(HandRank.HIGH_CARD, best("QS KH AD 2C 3S", "7C 9D").handRank());

        List<Card> nineHighFlush = cards("5S 6S 7S 8S 9S");
        List<Card> nineHighFlushOtherSuit = cards("5H 6H 7H 8H 9H");
        List<Card> sixHighFlush = cards("2S 3S 4S 5S 6S");
        List<Card> wheelFlush = cards("2D 3D 4D 5D AD");
        assertTrue(compare(nineHighFlush, wheelFlush, HandRank.STRAIGHT_FLUSH) > 0);
        assertTrue(compare(wheelFlush, nineHighFlush, HandRank.STRAIGHT_FLUSH) < 0);
        assertTrue(compare(sixHighFlush, wheelFlush, HandRank.STRAIGHT_FLUSH) > 0);
        assertTrue(compare(wheelFlush, sixHighFlush, HandRank.STRAIGHT_FLUSH) < 0);
        assertEquals(0, compare(nineHighFlush, nineHighFlushOtherSuit, HandRank.STRAIGHT_FLUSH));
    }

    @Test
    @DisplayName("Two pair comparison checks top pair, bottom pair, and kicker in both directions")
    void twoPairComparisonChecksTopPairBottomPairAndKicker() {
        List<Card> acesAndKingsQueen = cards("AS AH KD KC QS");
        List<Card> acesAndKingsJack = cards("AD AC KS KH JH");
        List<Card> acesAndKingsQueenTie = cards("AD AC KS KH QH");
        List<Card> acesAndQueens = cards("AD AC QH QD JS");
        List<Card> kingsAndQueens = cards("KS KH QD QC JS");

        assertTrue(service.compareSameRank(acesAndKingsQueen, kingsAndQueens, HandRank.TWO_PAIR) > 0);
        assertTrue(service.compareSameRank(kingsAndQueens, acesAndKingsQueen, HandRank.TWO_PAIR) < 0);
        assertTrue(service.compareSameRank(acesAndKingsQueen, acesAndQueens, HandRank.TWO_PAIR) > 0);
        assertTrue(service.compareSameRank(acesAndQueens, acesAndKingsQueen, HandRank.TWO_PAIR) < 0);
        assertTrue(service.compareSameRank(acesAndKingsQueen, acesAndKingsJack, HandRank.TWO_PAIR) > 0);
        assertTrue(service.compareSameRank(acesAndKingsJack, acesAndKingsQueen, HandRank.TWO_PAIR) < 0);
        assertEquals(0, service.compareSameRank(acesAndKingsQueen, acesAndKingsQueenTie, HandRank.TWO_PAIR));
        assertEquals(0, service.compareSameRank(acesAndKingsQueenTie, acesAndKingsQueen, HandRank.TWO_PAIR));
    }

    @Test
    @DisplayName("With three pairs the highest remaining card is used as the two pair kicker")
    void threePairsUseHighestRemainingCardAsKicker() {
        List<Card> board = List.of(card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                card(Rank.QUEEN, Suit.DIAMONDS), card(Rank.QUEEN, Suit.CLUBS),
                card(Rank.JACK, Suit.SPADES));

        HandEvaluationResult aceKicker = service.getBestHand(board,
                List.of(card(Rank.JACK, Suit.HEARTS), card(Rank.ACE, Suit.DIAMONDS)));
        HandEvaluationResult jackKicker = service.getBestHand(board,
                List.of(card(Rank.JACK, Suit.DIAMONDS), card(Rank.TWO, Suit.CLUBS)));

        assertEquals(HandRank.TWO_PAIR, aceKicker.handRank());
        assertTrue(ranksOf(aceKicker.bestHand()).contains(Rank.ACE));
        assertFalse(ranksOf(aceKicker.bestHand()).contains(Rank.JACK));
        assertEquals(HandRank.TWO_PAIR, jackKicker.handRank());
        assertEquals(1, ranksOf(jackKicker.bestHand()).stream().filter(Rank.JACK::equals).count());
        assertTrue(service.compareSameRank(aceKicker.bestHand(), jackKicker.bestHand(), HandRank.TWO_PAIR) > 0);
        assertTrue(service.compareSameRank(jackKicker.bestHand(), aceKicker.bestHand(), HandRank.TWO_PAIR) < 0);
    }

    @Test
    @DisplayName("Flushes are decided by the first differing card and tie when all five ranks match")
    void flushesAreDecidedByFirstDifferingCardAndTieOnIdenticalRanks() {
        List<Card> aceKingNine = hand(card(Rank.THREE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                card(Rank.NINE, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                card(Rank.ACE, Suit.SPADES));
        List<Card> aceKingNineHearts = hand(card(Rank.THREE, Suit.HEARTS), card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.NINE, Suit.HEARTS), card(Rank.KING, Suit.HEARTS),
                card(Rank.ACE, Suit.HEARTS));
        List<Card> aceKingEight = hand(card(Rank.THREE, Suit.HEARTS), card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.EIGHT, Suit.HEARTS), card(Rank.KING, Suit.HEARTS),
                card(Rank.ACE, Suit.HEARTS));
        List<Card> aceQueen = hand(card(Rank.THREE, Suit.CLUBS), card(Rank.SEVEN, Suit.CLUBS),
                card(Rank.NINE, Suit.CLUBS), card(Rank.QUEEN, Suit.CLUBS),
                card(Rank.ACE, Suit.CLUBS));
        List<Card> lowestCardDiffers = hand(card(Rank.TWO, Suit.DIAMONDS), card(Rank.SEVEN, Suit.DIAMONDS),
                card(Rank.NINE, Suit.DIAMONDS), card(Rank.KING, Suit.DIAMONDS),
                card(Rank.ACE, Suit.DIAMONDS));

        assertEquals(0, service.compareSameRank(aceKingNine, aceKingNineHearts, HandRank.FLUSH));
        assertEquals(0, service.compareSameRank(aceKingNineHearts, aceKingNine, HandRank.FLUSH));
        assertTrue(service.compareSameRank(aceKingNine, aceKingEight, HandRank.FLUSH) > 0);
        assertTrue(service.compareSameRank(aceKingEight, aceKingNine, HandRank.FLUSH) < 0);
        assertTrue(service.compareSameRank(aceKingNine, aceQueen, HandRank.FLUSH) > 0);
        assertTrue(service.compareSameRank(aceQueen, aceKingNine, HandRank.FLUSH) < 0);
        assertTrue(service.compareSameRank(aceKingNine, lowestCardDiffers, HandRank.FLUSH) > 0);
        assertTrue(service.compareSameRank(lowestCardDiffers, aceKingNine, HandRank.FLUSH) < 0);
    }

    @Test
    @DisplayName("With six suited cards the best five form the flush")
    void bestFlushUsesTheFiveHighestSuitedCards() {
        List<Card> board = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                card(Rank.NINE, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                card(Rank.THREE, Suit.HEARTS));

        HandEvaluationResult result = service.getBestHand(board,
                List.of(card(Rank.ACE, Suit.SPADES), card(Rank.FOUR, Suit.SPADES)));

        assertEquals(HandRank.FLUSH, result.handRank());
        assertEquals(List.of(Rank.FOUR, Rank.SEVEN, Rank.NINE, Rank.KING, Rank.ACE),
                ranksOf(result.bestHand()));
    }

    @Test
    @DisplayName("Four of a kind compares quad rank first and kicker when quads are shared")
    void fourOfAKindComparesQuadRankAndSharedBoardKicker() {
        List<Card> quadAcesKing = cards("AS AH AD AC KS");
        List<Card> quadKingsAce = cards("KS KH KD KC AH");
        assertTrue(service.compareSameRank(quadAcesKing, quadKingsAce, HandRank.FOUR_OF_A_KIND) > 0);
        assertTrue(service.compareSameRank(quadKingsAce, quadAcesKing, HandRank.FOUR_OF_A_KIND) < 0);

        List<Card> board = List.of(card(Rank.ACE, Suit.SPADES), card(Rank.ACE, Suit.HEARTS),
                card(Rank.ACE, Suit.DIAMONDS), card(Rank.ACE, Suit.CLUBS),
                card(Rank.TWO, Suit.SPADES));

        HandEvaluationResult kingKicker = service.getBestHand(board,
                List.of(card(Rank.KING, Suit.HEARTS), card(Rank.QUEEN, Suit.DIAMONDS)));
        HandEvaluationResult fourKicker = service.getBestHand(board,
                List.of(card(Rank.THREE, Suit.DIAMONDS), card(Rank.FOUR, Suit.DIAMONDS)));
        HandEvaluationResult alsoKingKicker = service.getBestHand(board,
                List.of(card(Rank.KING, Suit.CLUBS), card(Rank.NINE, Suit.CLUBS)));

        assertEquals(HandRank.FOUR_OF_A_KIND, kingKicker.handRank());
        assertEquals(HandRank.FOUR_OF_A_KIND, fourKicker.handRank());
        assertTrue(service.compareSameRank(kingKicker.bestHand(), fourKicker.bestHand(),
                HandRank.FOUR_OF_A_KIND) > 0);
        assertTrue(service.compareSameRank(fourKicker.bestHand(), kingKicker.bestHand(),
                HandRank.FOUR_OF_A_KIND) < 0);
        assertEquals(0, service.compareSameRank(kingKicker.bestHand(), alsoKingKicker.bestHand(),
                HandRank.FOUR_OF_A_KIND));
        assertEquals(0, service.compareSameRank(alsoKingKicker.bestHand(), kingKicker.bestHand(),
                HandRank.FOUR_OF_A_KIND));
    }

    @Test
    @DisplayName("Full houses compare triplet first, then pair, and select higher triplet when two triplets exist")
    void fullHousesCompareTripletThenPairAndSelectHigherTriplet() {
        List<Card> acesOverKings = cards("AS AH AD KC KS");
        List<Card> kingsOverAces = cards("KD KH KC AC AS");
        assertTrue(service.compareSameRank(acesOverKings, kingsOverAces, HandRank.FULL_HOUSE) > 0);
        assertTrue(service.compareSameRank(kingsOverAces, acesOverKings, HandRank.FULL_HOUSE) < 0);

        List<Card> kingsOverQueens = hand(card(Rank.QUEEN, Suit.SPADES), card(Rank.QUEEN, Suit.HEARTS),
                card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                card(Rank.KING, Suit.DIAMONDS));
        List<Card> kingsOverJacks = hand(card(Rank.JACK, Suit.SPADES), card(Rank.JACK, Suit.HEARTS),
                card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                card(Rank.KING, Suit.DIAMONDS));
        List<Card> kingsOverQueensOtherSuits = hand(card(Rank.QUEEN, Suit.DIAMONDS),
                card(Rank.QUEEN, Suit.CLUBS), card(Rank.KING, Suit.SPADES),
                card(Rank.KING, Suit.HEARTS), card(Rank.KING, Suit.CLUBS));

        assertTrue(service.compareSameRank(kingsOverQueens, kingsOverJacks, HandRank.FULL_HOUSE) > 0);
        assertTrue(service.compareSameRank(kingsOverJacks, kingsOverQueens, HandRank.FULL_HOUSE) < 0);
        assertEquals(0, service.compareSameRank(kingsOverQueens, kingsOverQueensOtherSuits,
                HandRank.FULL_HOUSE));
        assertEquals(0, service.compareSameRank(kingsOverQueensOtherSuits, kingsOverQueens,
                HandRank.FULL_HOUSE));

        List<Card> twoTripletBoard = List.of(card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                card(Rank.KING, Suit.DIAMONDS), card(Rank.QUEEN, Suit.CLUBS),
                card(Rank.QUEEN, Suit.SPADES));
        HandEvaluationResult result = service.getBestHand(twoTripletBoard,
                List.of(card(Rank.QUEEN, Suit.HEARTS), card(Rank.TWO, Suit.DIAMONDS)));
        assertEquals(HandRank.FULL_HOUSE, result.handRank());
        assertEquals(3, ranksOf(result.bestHand()).stream().filter(Rank.KING::equals).count());
        assertEquals(2, ranksOf(result.bestHand()).stream().filter(Rank.QUEEN::equals).count());
    }

    @Test
    @DisplayName("One pair compares pair rank first, then kickers, and ties when all match")
    void onePairComparesPairRankThenKickersAndTiesWhenEqual() {
        List<Card> acesPair = cards("AS AH KD QC JS");
        List<Card> kingsPair = cards("KS KH QD JC TS");
        List<Card> acesPairTie = cards("AD AC KS QH JD");
        assertTrue(service.compareSameRank(acesPair, kingsPair, HandRank.ONE_PAIR) > 0);
        assertTrue(service.compareSameRank(kingsPair, acesPair, HandRank.ONE_PAIR) < 0);
        assertEquals(0, service.compareSameRank(acesPair, acesPairTie, HandRank.ONE_PAIR));
        assertEquals(0, service.compareSameRank(acesPairTie, acesPair, HandRank.ONE_PAIR));

        List<Card> aceKingFive = hand(card(Rank.FIVE, Suit.SPADES), card(Rank.NINE, Suit.SPADES),
                card(Rank.NINE, Suit.HEARTS), card(Rank.KING, Suit.DIAMONDS),
                card(Rank.ACE, Suit.CLUBS));
        List<Card> aceKingFour = hand(card(Rank.FOUR, Suit.HEARTS), card(Rank.NINE, Suit.DIAMONDS),
                card(Rank.NINE, Suit.CLUBS), card(Rank.KING, Suit.SPADES),
                card(Rank.ACE, Suit.HEARTS));
        List<Card> aceQueenFive = hand(card(Rank.FIVE, Suit.HEARTS), card(Rank.NINE, Suit.SPADES),
                card(Rank.NINE, Suit.CLUBS), card(Rank.QUEEN, Suit.DIAMONDS),
                card(Rank.ACE, Suit.SPADES));
        List<Card> kingQueenJack = hand(card(Rank.NINE, Suit.SPADES), card(Rank.NINE, Suit.DIAMONDS),
                card(Rank.JACK, Suit.HEARTS), card(Rank.QUEEN, Suit.CLUBS),
                card(Rank.KING, Suit.HEARTS));

        assertTrue(service.compareSameRank(aceKingFive, aceKingFour, HandRank.ONE_PAIR) > 0);
        assertTrue(service.compareSameRank(aceKingFour, aceKingFive, HandRank.ONE_PAIR) < 0);
        assertTrue(service.compareSameRank(aceKingFive, aceQueenFive, HandRank.ONE_PAIR) > 0);
        assertTrue(service.compareSameRank(aceQueenFive, aceKingFive, HandRank.ONE_PAIR) < 0);
        assertTrue(service.compareSameRank(aceQueenFive, kingQueenJack, HandRank.ONE_PAIR) > 0);
        assertTrue(service.compareSameRank(kingQueenJack, aceQueenFive, HandRank.ONE_PAIR) < 0);
    }

    @Test
    @DisplayName("Three of a kind compares triplet rank first, then kickers, and ties when equal")
    void threeOfAKindComparesTripletThenKickersAndTiesWhenEqual() {
        List<Card> tripAces = cards("AS AH AD KC QS");
        List<Card> tripKings = cards("KS KH KD AC QH");
        assertTrue(service.compareSameRank(tripAces, tripKings, HandRank.THREE_OF_A_KIND) > 0);
        assertTrue(service.compareSameRank(tripKings, tripAces, HandRank.THREE_OF_A_KIND) < 0);

        List<Card> aceThree = hand(card(Rank.THREE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.DIAMONDS),
                card(Rank.ACE, Suit.CLUBS));
        List<Card> kingQueen = hand(card(Rank.SEVEN, Suit.SPADES), card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.SEVEN, Suit.CLUBS), card(Rank.QUEEN, Suit.DIAMONDS),
                card(Rank.KING, Suit.HEARTS));
        List<Card> kingFive = hand(card(Rank.FIVE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.DIAMONDS),
                card(Rank.KING, Suit.CLUBS));
        List<Card> kingFiveTie = hand(card(Rank.FIVE, Suit.HEARTS), card(Rank.SEVEN, Suit.SPADES),
                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.CLUBS),
                card(Rank.KING, Suit.DIAMONDS));
        List<Card> kingFour = hand(card(Rank.FOUR, Suit.HEARTS), card(Rank.SEVEN, Suit.SPADES),
                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.CLUBS),
                card(Rank.KING, Suit.DIAMONDS));

        assertTrue(service.compareSameRank(aceThree, kingQueen, HandRank.THREE_OF_A_KIND) > 0);
        assertTrue(service.compareSameRank(kingQueen, aceThree, HandRank.THREE_OF_A_KIND) < 0);
        assertTrue(service.compareSameRank(kingFive, kingFour, HandRank.THREE_OF_A_KIND) > 0);
        assertTrue(service.compareSameRank(kingFour, kingFive, HandRank.THREE_OF_A_KIND) < 0);
        assertEquals(0, service.compareSameRank(kingFive, kingFiveTie, HandRank.THREE_OF_A_KIND));
        assertEquals(0, service.compareSameRank(kingFiveTie, kingFive, HandRank.THREE_OF_A_KIND));
    }

    @Test
    @DisplayName("Every hand rank is found in seven cards and outranks every rank below it")
    void everyHandRankIsRecognisedAndOutranksTheRanksBelowIt() {
        String[][] sevenCardHands = {
                {"HIGH_CARD", "AS KD 9C 7H 5S", "3D 2C"},
                {"ONE_PAIR", "AS AD 9C 7H 5S", "3D 2C"},
                {"TWO_PAIR", "AS AD 9C 9H 5S", "3D 2C"},
                {"THREE_OF_A_KIND", "AS AD AC 9H 5S", "3D 2C"},
                {"STRAIGHT", "9S 8D 7C 6H 5S", "5D 2C"},
                {"FLUSH", "AS 9S 7S 5S 3S", "KD KH"},
                {"FULL_HOUSE", "KS KD KC 5H 5S", "5D 2C", "5H 5S KS KD KC"},
                {"FOUR_OF_A_KIND", "9S 9D 9C 9H 5S", "5D 5C", "5S 9S 9D 9C 9H"},
                {"STRAIGHT_FLUSH", "9H 8H 7H 6H 5H", "5S 5D"},
                {"ROYAL_FLUSH", "AS KS QS JS TS", "9S 2D"}};
        HandRank previous = null;
        for (String[] row : sevenCardHands) {
            HandRank expected = HandRank.valueOf(row[0]);
            HandEvaluationResult result = best(row[1], row[2]);
            assertEquals(expected, result.handRank(), row[0]);
            if (row.length > 3) {
                assertEquals(ranksOf(cards(row[3])), ranksOf(result.bestHand()), row[0]);
            }
            if (previous != null) {
                assertTrue(expected.beats(previous), row[0] + " should beat " + previous);
            }
            previous = expected;
        }
    }

    @Test
    @DisplayName("When the board is each player's best hand, they tie for every kind of board")
    void playersWhoBothPlayTheBoardTie() {
        String[][] boardsAndHoles = {
                {"STRAIGHT", "5S 6H 7D 8C 9S", "2H 2D", "KC 3C"},
                {"FLUSH", "KS QS 9S 6S 3S", "2H 4D", "5C 7D"},
                {"FULL_HOUSE", "KS KD KC QH QS", "2H 3D", "4C 5D"},
                {"HIGH_CARD", "AS KD JC 9H 7S", "2H 3D", "4C 3S"}};
        for (String[] row : boardsAndHoles) {
            HandEvaluationResult first = best(row[1], row[2]);
            HandEvaluationResult second = best(row[1], row[3]);
            assertEquals(HandRank.valueOf(row[0]), first.handRank(), row[0]);
            assertEquals(first.handRank(), second.handRank(), row[0]);
            assertEquals(0, compare(first, second), row[0]);
            assertEquals(0, compare(second, first), row[0]);
        }
    }

    private static Card card(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }

    private static List<Card> hand(Card... cards) {
        return List.of(cards);
    }

    private HandRank rankOf(List<Card> fiveCards) {
        return service.getBestHand(List.of(), fiveCards).handRank();
    }

    private static List<Rank> ranksOf(List<Card> cards) {
        return cards.stream().map(Card::rank).toList();
    }

    private int compare(List<Card> first, List<Card> second, HandRank rank) {
        return Integer.signum(service.compareSameRank(first, second, rank));
    }

    private int compare(HandEvaluationResult first, HandEvaluationResult second) {
        if (first.handRank() != second.handRank()) {
            return Integer.signum(first.handRank().getRank() - second.handRank().getRank());
        }
        return compare(first.bestHand(), second.bestHand(), first.handRank());
    }

    private static List<Card> cards(String spec) {
        List<Card> result = new ArrayList<>();
        for (String token : spec.split(" ")) {
            int value = "23456789TJQKA".indexOf(token.charAt(0)) + 2;
            Rank rank = Arrays.stream(Rank.values())
                    .filter(r -> r.getValue() == value).findFirst().orElseThrow();
            Suit suit = Arrays.stream(Suit.values())
                    .filter(s -> s.name().charAt(0) == token.charAt(1)).findFirst().orElseThrow();
            result.add(new Card(rank, suit));
        }
        return result;
    }

    private HandEvaluationResult best(String board, String hole) {
        return service.getBestHand(cards(board), cards(hole));
    }
}
