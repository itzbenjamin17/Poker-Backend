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

public record AggregateStateImage(int schemaVersion, boolean deleted, RoomState room, String currentHost,
        GameState game) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public record RoomState(String roomId, String roomName, String originalHost, int maxPlayers, int smallBlind,
            int bigBlind, int buyIn, String password, LocalDateTime createdAt,
            Map<String, LocalDateTime> playersWithJoinTime, boolean gameStarted) {
    }

    public record GameState(String gameId, List<PlayerState> players, List<String> activePlayerIds,
            List<CardState> remainingDeck, List<CardState> communityCards, int pot, int dealerPosition,
            int smallBlindPosition, int bigBlindPosition, int currentPlayerPosition, int currentHighestBet,
            GamePhase currentPhase, boolean gameOver, int smallBlind, int bigBlind,
            Map<String, Integer> handContributions, boolean readyCountdownActive,
            Long readyCountdownDeadlineEpochMs, boolean everyoneHasHadInitialTurn, Set<String> actedPlayerIds,
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines) {
    }

    public record PlayerState(String name, String playerId, List<CardState> holeCards, List<CardState> bestHand,
            HandRank handRank, int chips, int currentBet, boolean folded, boolean allIn, boolean out,
            boolean disconnected, Long disconnectDeadlineEpochMs, boolean readyForNextHand) {
    }

    public record CardState(Rank rank, Suit suit) {
    }
}
