package com.pokergame.dto.response;

import com.pokergame.dto.internal.PlayerJoinInfo;

import java.time.LocalDateTime;
import java.util.List;

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