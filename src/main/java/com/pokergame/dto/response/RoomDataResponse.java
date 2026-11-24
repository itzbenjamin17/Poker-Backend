package com.pokergame.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Public lobby snapshot returned by {@code GET /api/room/{roomId}} and broadcast on
 * {@code /room/{roomId}} for all room lifecycle updates, including room closure.
 *
 * @param roomId                    unique room identifier
 * @param roomName                  display name of the room
 * @param maxPlayers                maximum player capacity
 * @param buyIn                     starting chip stack per player
 * @param smallBlind                small blind amount
 * @param bigBlind                  big blind amount
 * @param players                   seated lobby members and host flag
 * @param canStartGame              whether the room meets start requirements
 * @param gameStarted               whether the match has started
 * @param gameFinished              whether the match has finished
 * @param inactivityDeadlineEpochMs lobby or game inactivity deadline in UTC epoch ms, or {@code null}
 * @param serverTimeEpochMs         current server time in UTC epoch ms
 * @param closedReason              non-null closure reason (e.g. {@code "HOST_LEFT"}, {@code "INACTIVITY"})
 *                                  when the room has just closed; {@code null} while open
 */
public record RoomDataResponse(
        String roomId,
        String roomName,
        int maxPlayers,
        int buyIn,
        int smallBlind,
        int bigBlind,
        List<RoomPlayer> players,
        boolean canStartGame,
        boolean gameStarted,
        boolean gameFinished,
        Long inactivityDeadlineEpochMs,
        Long serverTimeEpochMs,
        @JsonInclude(JsonInclude.Include.NON_NULL) String closedReason) {

    public RoomDataResponse {
        players = players == null ? List.of() : List.copyOf(players);
    }

    /**
     * Member entry inside a {@link RoomDataResponse}.
     *
     * @param name   player display name
     * @param isHost whether the player is currently the room host
     */
    public record RoomPlayer(String name, boolean isHost) {
    }
}
