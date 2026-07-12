package com.pokergame.persistence;

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
 * Versioned, framework-free representation of one room aggregate.
 * <p>
 * The state image is intentionally composed only of records and value types so
 * persistence compatibility is reviewed explicitly instead of inheriting every
 * future field added to mutable domain classes.
 * </p>
 *
 * @param schemaVersion schema used to encode the image
 * @param deleted       whether the image is a lifecycle tombstone
 * @param room          persisted room identity and lobby state
 * @param currentHost   current host, which may differ from the original host
 * @param game          persisted game state, or {@code null} for a lobby
 */
public record AggregateStateImage(int schemaVersion, boolean deleted, RoomState room, String currentHost,
        GameState game) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Stores lobby state that cannot be regenerated safely after restart, including
     * private-room credentials and join order.
     *
     * @param roomId             stable aggregate identity
     * @param roomName           client-visible room name
     * @param originalHost       host recorded when the room was created
     * @param maxPlayers         room capacity
     * @param smallBlind         configured small blind
     * @param bigBlind           configured big blind
     * @param buyIn              configured starting stack
     * @param password           private-room password, protected by WAL encryption
     * @param createdAt          original creation time
     * @param playersWithJoinTime player order and join timestamps
     * @param gameStarted        whether the lobby has entered game play
     */
    public record RoomState(String roomId, String roomName, String originalHost, int maxPlayers, int smallBlind,
            int bigBlind, int buyIn, String password, LocalDateTime createdAt,
            Map<String, LocalDateTime> playersWithJoinTime, boolean gameStarted) {
    }

    /**
     * Stores every authoritative field required to continue the exact hand rather
     * than starting a logically similar replacement hand.
     *
     * @param gameId                       stable game identity
     * @param players                      complete public and private player state
     * @param activePlayerIds               active-player ordering
     * @param remainingDeck                 exact future draw order
     * @param communityCards                cards already exposed on the board
     * @param pot                           undistributed pot
     * @param dealerPosition                dealer index
     * @param smallBlindPosition            small-blind index
     * @param bigBlindPosition              big-blind index
     * @param currentPlayerPosition         current turn index
     * @param currentHighestBet             amount players must match
     * @param currentPhase                  current betting phase
     * @param gameOver                      terminal-state marker
     * @param smallBlind                    game small blind
     * @param bigBlind                      game big blind
     * @param handContributions             contribution ledger used for side pots
     * @param readyCountdownActive          whether the post-hand gate is open
     * @param readyCountdownDeadlineEpochMs absolute ready-gate deadline
     * @param everyoneHasHadInitialTurn     betting-round progress marker
     * @param actedPlayerIds                players that acted in the current round
     * @param scheduledTaskDeadlines        durable runtime-work deadlines
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
     * Stores player state whose loss would change game legality or reveal a
     * different private hand after recovery.
     *
     * @param name                      player display name
     * @param playerId                  stable internal identity
     * @param holeCards                 private cards
     * @param bestHand                  evaluated best hand, when available
     * @param handRank                  evaluated hand rank
     * @param chips                     remaining stack
     * @param currentBet                contribution in the current betting round
     * @param folded                    whether the player folded
     * @param allIn                     whether the player is all-in
     * @param out                       whether the player is out of the game
     * @param disconnected              durable disconnect marker
     * @param disconnectDeadlineEpochMs absolute reconnect deadline
     * @param readyForNextHand          post-hand readiness marker
     */
    public record PlayerState(String name, String playerId, List<CardState> holeCards, List<CardState> bestHand,
            HandRank handRank, int chips, int currentBet, boolean folded, boolean allIn, boolean out,
            boolean disconnected, Long disconnectDeadlineEpochMs, boolean readyForNextHand) {
    }

    /**
     * Persists cards as stable enum values so no mutable deck implementation is
     * serialized into the compatibility boundary.
     *
     * @param rank card rank
     * @param suit card suit
     */
    public record CardState(Rank rank, Suit suit) {
    }
}
