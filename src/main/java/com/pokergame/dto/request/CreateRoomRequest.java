package com.pokergame.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Represents a request to create a new room.
 * 
 * @param roomName     The name of the room.
 * @param playerName   The name of the player creating the room.
 * @param maxPlayers   The maximum number of players allowed in the room.
 * @param smallBlind   The small blind amount.
 * @param bigBlind     The big blind amount.
 * @param buyIn        The buy-in amount.
 * @param password     The password for the room (optional).
 */

public class CreateRoomRequest {

    @NotBlank(message = "Room name is required")
    private final String roomName;

    @NotBlank(message = "Player name is required")
    private String playerName;

    @NotNull
    @Min(value = 2, message = "Minimum 2 players required")
    @Max(value = 10, message = "Maximum 10 players allowed")
    private final Integer maxPlayers;

    @NotNull
    @Min(value = 1, message = "Small blind must be at least 1")
    private final Integer smallBlind;

    @NotNull
    @Min(value = 2, message = "Big blind must be at least 2")
    private final Integer bigBlind;

    @NotNull
    @Min(value = 20, message = "Buy-in must be at least 20")
    private final Integer buyIn;

    private final String password; // Optional - can be null/empty for public rooms


    // Constructor
    public CreateRoomRequest(String roomName, String playerName, Integer maxPlayers,
                             Integer smallBlind, Integer bigBlind, Integer buyIn, String password) {
        this.roomName = roomName;
        this.playerName = playerName;
        this.maxPlayers = maxPlayers;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.buyIn = buyIn;
        this.password = password;
    }

    // Getters and Setters
    public String getRoomName() {
        return roomName;
    }

    public String getPlayerName() {
        return playerName;
    }

    @SuppressWarnings("unused")
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public Integer getMaxPlayers() {
        return maxPlayers;
    }

    public Integer getSmallBlind() {
        return smallBlind;
    }

    public Integer getBigBlind() {
        return bigBlind;
    }

    public Integer getBuyIn() {
        return buyIn;
    }

    public String getPassword() {
        return password;
    }

    // Validation method
    public boolean hasPassword() {
        return password != null && !password.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "CreateRoomRequest{" +
                "roomName='" + roomName + '\'' +
                ", playerName='" + playerName + '\'' +
                ", maxPlayers=" + maxPlayers +
                ", smallBlind=" + smallBlind +
                ", bigBlind=" + bigBlind +
                ", buyIn=" + buyIn +
                ", hasPassword=" + hasPassword() +
                '}';
    }
}