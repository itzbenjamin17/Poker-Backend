package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a poker player with hole cards, chips, and betting state.
 *
 * <p>
 * <b>WARNING:</b> This class performs basic state and chip-accounting validation
 * but does not enforce poker betting rules. Use {@link BettingRound} and {@link Game}
 * for rule-validated actions.
 * </p>
 */
public class Player {
    private final String name;
    private final String playerId;
    private List<Card> holeCards;
    private List<Card> bestHand;
    private HandRank handRank;
    private int chips;
    private int currentBet;
    private boolean hasFolded;
    private boolean isAllIn;
    private boolean isOut;
    private boolean isDisconnected;
    private Long disconnectDeadlineEpochMs;
    private boolean isReadyForNextHand;

    /**
     * Creates a new player with the specified name and starting chip count.
     *
     * @param name     the player's name
     * @param playerId stable player identity
     * @param chips    the starting number of chips for the player
     * @throws IllegalStateException if {@code name}, {@code playerId}, or {@code chips} is invalid
     */
    public Player(String name, String playerId, int chips) {
        validateIdentityAndChips(name, playerId, chips);
        this.name = name;
        this.playerId = playerId;
        this.chips = chips;
        this.holeCards = new ArrayList<>();
        this.bestHand = new ArrayList<>();
        this.handRank = HandRank.NO_HAND;
    }

    private Player(PlayerRestoreBuilder builder) {
        validateIdentityAndChips(builder.name, builder.playerId, builder.chips);
        this.name = builder.name;
        this.playerId = builder.playerId;
        this.chips = builder.chips;
        this.holeCards = builder.holeCards != null ? new ArrayList<>(builder.holeCards) : new ArrayList<>();
        this.bestHand = builder.bestHand != null ? new ArrayList<>(builder.bestHand) : new ArrayList<>();
        this.handRank = builder.handRank != null ? builder.handRank : HandRank.NO_HAND;
        this.currentBet = builder.currentBet;
        this.hasFolded = builder.hasFolded;
        this.isAllIn = builder.allIn;
        this.isOut = builder.out;
        this.isDisconnected = builder.disconnected;
        this.disconnectDeadlineEpochMs = builder.disconnectDeadlineEpochMs;
        this.isReadyForNextHand = builder.readyForNextHand;
    }

    private static void validateIdentityAndChips(String name, String playerId, int chips) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalStateException("Player name required");
        }
        if (chips < 0) {
            throw new IllegalStateException("Chips cannot be negative");
        }
        if (playerId == null || playerId.trim().isEmpty()) {
            throw new IllegalStateException("Player ID cannot be empty");
        }
    }

    /**
     * Creates a builder for restoring a player instance from snapshot state.
     *
     * @return a new PlayerRestoreBuilder
     */
    public static PlayerRestoreBuilder restoreBuilder() {
        return new PlayerRestoreBuilder();
    }

    /**
     * Builder for reconstructing a player instance from snapshot state.
     */
    public static class PlayerRestoreBuilder {
        private String name;
        private String playerId;
        private List<Card> holeCards;
        private List<Card> bestHand;
        private HandRank handRank;
        private int chips;
        private int currentBet;
        private boolean hasFolded;
        private boolean allIn;
        private boolean out;
        private boolean disconnected;
        private Long disconnectDeadlineEpochMs;
        private boolean readyForNextHand;

        public PlayerRestoreBuilder name(String name) {
            this.name = name;
            return this;
        }

        public PlayerRestoreBuilder playerId(String playerId) {
            this.playerId = playerId;
            return this;
        }

        public PlayerRestoreBuilder holeCards(List<Card> holeCards) {
            this.holeCards = holeCards;
            return this;
        }

        public PlayerRestoreBuilder bestHand(List<Card> bestHand) {
            this.bestHand = bestHand;
            return this;
        }

        public PlayerRestoreBuilder handRank(HandRank handRank) {
            this.handRank = handRank;
            return this;
        }

        public PlayerRestoreBuilder chips(int chips) {
            this.chips = chips;
            return this;
        }

        public PlayerRestoreBuilder currentBet(int currentBet) {
            this.currentBet = currentBet;
            return this;
        }

        public PlayerRestoreBuilder hasFolded(boolean hasFolded) {
            this.hasFolded = hasFolded;
            return this;
        }

        public PlayerRestoreBuilder allIn(boolean allIn) {
            this.allIn = allIn;
            return this;
        }

        public PlayerRestoreBuilder out(boolean out) {
            this.out = out;
            return this;
        }

        public PlayerRestoreBuilder disconnected(boolean disconnected) {
            this.disconnected = disconnected;
            return this;
        }

        public PlayerRestoreBuilder disconnectDeadlineEpochMs(Long disconnectDeadlineEpochMs) {
            this.disconnectDeadlineEpochMs = disconnectDeadlineEpochMs;
            return this;
        }

        public PlayerRestoreBuilder readyForNextHand(boolean readyForNextHand) {
            this.readyForNextHand = readyForNextHand;
            return this;
        }

        public Player build() {
            return new Player(this);
        }
    }

    /**
     * Resets all player attributes for a new hand.
     * Clears hole cards, best hand, and resets betting status.
     */
    public void resetAttributes() {
        this.holeCards = new ArrayList<>();
        this.bestHand = new ArrayList<>();
        this.handRank = HandRank.NO_HAND;
        this.hasFolded = false;
        this.isAllIn = false;
        this.currentBet = 0;
        this.isReadyForNextHand = false;
    }

    /**
     * Resets the current bet to zero for a new betting round.
     */
    public void resetCurrentBet() {
        this.currentBet = 0;
    }

    private int goAllIn() {
        int committed = chips;
        isAllIn = true;
        currentBet += committed;
        chips = 0;
        return committed;
    }

    /**
     * Executes a player action and returns the chips committed by this action.
     *
     * <p>
     * <b>IMPORTANT:</b> The caller is responsible for calculating the correct
     * amount needed for betting actions (BET, RAISE, CALL). This method does NOT
     * validate or calculate bet amounts - it uses the provided amount directly.
     * </p>
     *
     * @param action the poker action to perform (FOLD, CHECK, CALL, BET, RAISE, ALL_IN)
     * @param amount the chip amount for betting actions (ignored for FOLD, CHECK, ALL_IN)
     * @return the number of chips committed to the pot
     * @throws IllegalStateException if a betting amount is negative or exceeds available chips
     */
    public int doAction(PlayerAction action, int amount) {
        Objects.requireNonNull(action, "action must not be null");
        return switch (action) {
            case RAISE, BET, CALL -> payChips(amount);
            case ALL_IN -> goAllIn();
            case FOLD -> {
                hasFolded = true;
                yield 0;
            }
            case CHECK -> 0;
        };
    }

    private int payChips(int amount) {
        if (amount < 0) {
            throw new IllegalStateException("Chip amount cannot be negative");
        }

        if (amount > this.chips) {
            throw new IllegalStateException("Insufficient chips for this action");
        }

        this.chips -= amount;
        if (this.chips == 0) {
            this.isAllIn = true;
        }
        this.currentBet += amount;
        return amount;
    }

    /**
     * Adds {@code amount} chips to this player's stack.
     *
     * @param amount the chips to add
     */
    public void addChips(int amount) {
        this.chips += amount;
    }

    public List<Card> getHoleCards() {
        return List.copyOf(holeCards);
    }

    /**
     * Replaces the player's hole cards with a defensive copy of {@code cards}.
     *
     * @param cards the dealt hole cards
     */
    public void setHoleCards(List<Card> cards) {
        Objects.requireNonNull(cards, "cards must not be null");
        this.holeCards = new ArrayList<>(cards);
    }

    public int getCurrentBet() {
        return currentBet;
    }

    public int getChips() {
        return chips;
    }

    public String getName() {
        return name;
    }

    public boolean getHasFolded() {
        return hasFolded;
    }

    public boolean getIsAllIn() {
        return isAllIn;
    }

    public boolean getIsOut() {
        return isOut;
    }

    /**
     * Returns whether the player is still contesting the current hand (has not folded and is not out).
     *
     * @return {@code true} if the player is still in the hand
     */
    public boolean isInHand() {
        return !hasFolded && !isOut;
    }

    /**
     * Returns whether the player can still take betting actions in the current hand (in the hand and not all-in).
     *
     * @return {@code true} if the player can still act
     */
    public boolean canAct() {
        return isInHand() && !isAllIn;
    }

    public boolean getIsDisconnected() {
        return isDisconnected;
    }

    public Long getDisconnectDeadlineEpochMs() {
        return disconnectDeadlineEpochMs;
    }

    public boolean getIsReadyForNextHand() {
        return isReadyForNextHand;
    }

    /**
     * Marks the player as out of the game, such as when eliminated with zero chips
     * or after forfeiting on departure.
     */
    public void setIsOut() {
        this.isOut = true;
    }

    public void setDisconnected(boolean disconnected) {
        this.isDisconnected = disconnected;
    }

    public void setDisconnectDeadlineEpochMs(Long disconnectDeadlineEpochMs) {
        this.disconnectDeadlineEpochMs = disconnectDeadlineEpochMs;
    }

    public void setReadyForNextHand(boolean readyForNextHand) {
        this.isReadyForNextHand = readyForNextHand;
    }

    public List<Card> getBestHand() {
        return List.copyOf(bestHand);
    }

    public String getPlayerId() {
        return playerId;
    }

    public HandRank getHandRank() {
        return handRank;
    }

    /**
     * Stores evaluated cards separately from hole cards so showdown results can be
     * rendered without recomputing against a later board state.
     *
     * @param cards evaluated best five-card hand, or {@code null} for empty
     */
    public void setBestHand(List<Card> cards) {
        this.bestHand = cards != null ? new ArrayList<>(cards) : new ArrayList<>();
    }

    /**
     * Stores the rank paired with {@link #setBestHand(List)} for stable comparison
     * and recovery.
     *
     * @param handRank evaluated rank
     */
    public void setHandRank(HandRank handRank) {
        this.handRank = handRank;
    }

    /**
     * Compares stable player identity rather than mutable display or game state.
     *
     * @param o object to compare
     * @return whether both values represent the same player identity
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Player player)) {
            return false;
        }
        return Objects.equals(playerId, player.playerId);
    }

    /**
     * Hashes the same stable identity used by {@link #equals(Object)} so players can
     * safely participate in sets while their chips and status change.
     *
     * @return identity-based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(playerId);
    }
}
