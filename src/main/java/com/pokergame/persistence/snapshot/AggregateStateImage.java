package com.pokergame.persistence.snapshot;

import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.enums.ScheduledGameTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A clean, simple snapshot of a poker room and its game.
 * <p>
 * This class exists so that if we change how the live Game or Room works in the future,
 * older saved games won't automatically break. We only save plain data here.
 * </p>
 *
 * @param schemaVersion the version number of this save format
 * @param deleted       true if this room was deleted
 * @param room          the saved room details (like its name and rules)
 * @param currentHost   the current host of the room
 * @param game          the saved game progress, or {@code null} if the game hasn't started
 */
public record AggregateStateImage(int schemaVersion, boolean deleted, RoomState room, String currentHost,
        GameState game) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Details about the room itself (the lobby) that we need to restore, like passwords and who joined when.
     *
     * @param roomId             the unique ID for the room
     * @param roomName           the name of the room players see
     * @param originalHost       the player who created the room
     * @param maxPlayers         how many people can join
     * @param smallBlind         the room's small blind amount
     * @param bigBlind           the room's big blind amount
     * @param buyIn              how many chips players start with
     * @param password           the room's password
     * @param createdAt          when the room was created
     * @param playersWithJoinTime when each player joined
     * @param gameStarted        true if they have started playing
     */
    public record RoomState(String roomId, String roomName, String originalHost, int maxPlayers, int smallBlind,
            int bigBlind, int buyIn, String password, LocalDateTime createdAt,
            Map<String, LocalDateTime> playersWithJoinTime, boolean gameStarted) {
    }

    /**
     * Everything we need to pick up a hand of poker exactly where we left off.
     *
     * @param gameId                       the unique ID for the game
     * @param players                      details about all the players
     * @param activePlayerIds               who is still playing in this hand
     * @param remainingDeck                 the cards still in the deck, in exact order
     * @param communityCards                the cards on the table
     * @param pot                           total chips in the pot right now
     * @param dealerPosition                who has the dealer button
     * @param smallBlindPosition            who posted the small blind
     * @param bigBlindPosition              who posted the big blind
     * @param currentPlayerPosition         whose turn it is to act
     * @param currentHighestBet             the amount a player has to match to stay in
     * @param currentPhase                  what part of the hand we're in (like the flop or river)
     * @param gameOver                      true if the game is finished
     * @param smallBlind                    the small blind amount
     * @param bigBlind                      the big blind amount
     * @param handContributions             how much each player put in (used to split side pots)
     * @param readyCountdownActive          true if we're waiting for the next hand to start
     * @param readyCountdownDeadlineEpochMs when the wait for the next hand ends
     * @param everyoneHasHadInitialTurn     true if everyone got a chance to bet this round
     * @param actedPlayerIds                who has already made a move this round
     * @param scheduledTaskDeadlines        when upcoming automatic actions are supposed to happen
     */
    public record GameState(String gameId, List<PlayerState> players, List<String> activePlayerIds,
            List<CardState> remainingDeck, List<CardState> communityCards, int pot, int dealerPosition,
            int smallBlindPosition, int bigBlindPosition, int currentPlayerPosition, int currentHighestBet,
            GamePhase currentPhase, boolean gameOver, int smallBlind, int bigBlind,
            Map<String, Integer> handContributions, boolean readyCountdownActive,
            Long readyCountdownDeadlineEpochMs, boolean everyoneHasHadInitialTurn, Set<String> actedPlayerIds,
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines) {
    }

    /**
     * Everything we need to know about a specific player during a hand.
     *
     * @param name                      the player's name
     * @param playerId                  their unique ID
     * @param holeCards                 their hidden cards
     * @param bestHand                  their best 5-card hand (if evaluated)
     * @param handRank                  the rank of their best hand
     * @param chips                     how many chips they have left
     * @param currentBet                how much they've bet in this round
     * @param folded                    true if they folded
     * @param allIn                     true if they pushed all their chips in
     * @param out                       true if they lost all their chips and are out
     * @param disconnected              true if they lost their connection
     * @param disconnectDeadlineEpochMs when their time to reconnect runs out
     * @param readyForNextHand          true if they are ready to start the next hand
     */
    public record PlayerState(String name, String playerId, List<CardState> holeCards, List<CardState> bestHand,
            HandRank handRank, int chips, int currentBet, boolean folded, boolean allIn, boolean out,
            boolean disconnected, Long disconnectDeadlineEpochMs, boolean readyForNextHand) {
    }

    /**
     * A simple representation of a playing card for saving.
     *
     * @param rank the value of the card (like King or Two)
     * @param suit the suit of the card (like Hearts or Spades)
     */
    public record CardState(Rank rank, Suit suit) {
    }
}
