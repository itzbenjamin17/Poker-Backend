package com.pokergame.dto.response;

import com.pokergame.dto.internal.PlayerJoinInfo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents the data of a room.
 * 
 * @param roomId           The ID of the room.
 * @param roomName         The name of the room.
 * @param maxPlayers       The maximum number of players in the room.
 * @param buyIn            The buy-in amount for the room.
 * @param smallBlind       The small blind amount for the room.
 * @param bigBlind         The big blind amount for the room.
 * @param createdAt        The creation timestamp of the room.
 * @param hostName         The name of the host.
 * @param players          The list of players in the room.
 * @param currentPlayers   The current number of players in the room.
 * @param canStartGame     Whether the game can be started.
 * @param gameStarted      Whether the game has started.
 */
public record RoomDataResponse(
        String roomId,
        String roomName,
        int maxPlayers,
        int buyIn,
        int smallBlind,
        int bigBlind,
        LocalDateTime createdAt,
        String hostName,
        List<PlayerJoinInfo> players,
        int currentPlayers,
        boolean canStartGame,
        boolean gameStarted
) {}
