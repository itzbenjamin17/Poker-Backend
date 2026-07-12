package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.exception.BadRequestException;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable result of evaluating a hand, pairing the best five cards with their
 * poker rank.
 * 
 * @param bestHand the best 5-card hand found
 * @param handRank the rank of the best hand
 */
public record HandEvaluationResult(List<Card> bestHand, HandRank handRank) {
    private static final Logger logger = LoggerFactory.getLogger(HandEvaluationResult.class);

    /**
     * Validates and defensively copies an evaluated hand result.
     *
     * @throws BadRequestException if the cards or rank are absent
     */
    public HandEvaluationResult {
        if (bestHand == null || handRank == null) {
            logger.error("Null bestHand or handRank: bestHand={}, handRank={}", bestHand, handRank);
            throw new BadRequestException("An error occured evaluating hand. Please try again.");
        }
        bestHand = List.copyOf(bestHand);
    }
}
