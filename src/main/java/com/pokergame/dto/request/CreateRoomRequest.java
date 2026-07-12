package com.pokergame.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mutable request model for creating a room. The player name may be replaced by
 * its sanitized form before the request reaches the room service; all other room
 * settings remain immutable after deserialization.
 */

public class CreateRoomRequest {

    @NotBlank(message = "Room name is required")
    @Size(max = 50, message = "Room name must be 50 characters or less")
    @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Room name cannot contain control characters")
    private final String roomName;

    @NotBlank(message = "Player name is required")
    @Size(max = 30, message = "Player name must be 30 characters or less")
    @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Player name cannot contain control characters")
    private String playerName;

    @NotNull
    @Min(value = 2, message = "Minimum 2 players required")
    @Max(value = 10, message = "Maximum 10 players allowed")
    private final Integer maxPlayers;

    @NotNull
    @Min(value = 1, message = "Small blind must be at least 1")
    @Max(value = 10000, message = "Small blind cannot exceed 10,000")
    private final Integer smallBlind;

    @NotNull
    @Min(value = 2, message = "Big blind must be at least 2")
    @Max(value = 20000, message = "Big blind cannot exceed 20,000")
    private final Integer bigBlind;

    @NotNull
    @Min(value = 20, message = "Buy-in must be at least 20")
    @Max(value = 1000000, message = "Buy-in cannot exceed 1,000,000")
    private final Integer buyIn;

    @Size(max = 50, message = "Password must be 50 characters or less")
    @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Password cannot contain control characters")
    private final String password; // Optional - can be null/empty for public rooms


    /**
     * Creates a room request from client-supplied settings.
     *
     * @param roomName room display name
     * @param playerName creating player's display name
     * @param maxPlayers maximum room capacity
     * @param smallBlind small blind amount
     * @param bigBlind big blind amount
     * @param buyIn starting chips per player
     * @param password optional room password
     */
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

    /**
     * Returns the room display name.
     *
     * @return room name
     */
    public String getRoomName() {
        return roomName;
    }

    /**
     * Returns the creating player's display name.
     *
     * @return player name
     */
    public String getPlayerName() {
        return playerName;
    }

    /**
     * Replaces the player name, typically with its sanitized representation.
     *
     * @param playerName player name to retain
     */
    @SuppressWarnings("unused")
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    /**
     * Returns the maximum room capacity.
     *
     * @return maximum player count
     */
    public Integer getMaxPlayers() {
        return maxPlayers;
    }

    /**
     * Returns the small blind amount.
     *
     * @return small blind
     */
    public Integer getSmallBlind() {
        return smallBlind;
    }

    /**
     * Returns the big blind amount.
     *
     * @return big blind
     */
    public Integer getBigBlind() {
        return bigBlind;
    }

    /**
     * Returns each player's starting chip count.
     *
     * @return room buy-in
     */
    public Integer getBuyIn() {
        return buyIn;
    }

    /**
     * Returns the optional room password.
     *
     * @return password, possibly {@code null} or blank
     */
    public String getPassword() {
        return password;
    }

    /**
     * Reports whether the request protects the room with a non-blank password.
     *
     * @return {@code true} when a password was supplied
     */
    public boolean hasPassword() {
        return password != null && !password.trim().isEmpty();
    }

    /**
     * Returns a log-safe description that deliberately omits password contents.
     *
     * @return request settings and password-presence flag
     */
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
