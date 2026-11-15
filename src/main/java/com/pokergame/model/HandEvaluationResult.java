package com.pokergame.model;

import com.pokergame.enums.HandRank;

import java.util.List;

/**
 * Immutable result of evaluating a hand, pairing the best five cards with their
 * poker rank.
 * 
 * @param bestHand the best 5-card hand found
 * @param handRank the rank of the best hand
 */
public record HandEvaluationResult(List<Card> bestHand, HandRank handRank) {

    /**
     * Validates and defensively copies an evaluated hand result.
     *
     * @throws IllegalStateException if the cards or rank are absent
     */
    public HandEvaluationResult {
        if (bestHand == null || handRank == null) {
            throw new IllegalStateException("An error occurred evaluating hand. Please try again.");
        }
        bestHand = List.copyOf(bestHand);
    }
}
