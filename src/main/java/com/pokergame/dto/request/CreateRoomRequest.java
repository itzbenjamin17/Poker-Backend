package com.pokergame.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pokergame.model.Room;
import com.pokergame.util.RoomPasswordPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Validated request payload for creating a poker room.
 *
 * @param roomName   room display name
 * @param playerName creating player's display name
 * @param maxPlayers maximum room capacity
 * @param smallBlind small blind amount
 * @param bigBlind   big blind amount
 * @param buyIn      starting chips per player
 * @param password   optional room password ({@code null} when blank or omitted)
 */
public record CreateRoomRequest(
        @NotBlank(message = "Room name is required")
        @Size(max = 50, message = "Room name must be 50 characters or less")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Room name cannot contain control characters")
        @Pattern(regexp = "^[^\\p{Cf}]*$", message = "Input contains illegal format characters.")
        @Pattern(regexp = "^((?!<[^>]*>).)*$", message = "Input contains illegal HTML tags.")
        String roomName,

        @NotBlank(message = "Player name is required")
        @Size(max = 30, message = "Player name must be 30 characters or less")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Player name cannot contain control characters")
        @Pattern(regexp = "^[^\\p{Cf}]*$", message = "Input contains illegal format characters.")
        @Pattern(regexp = "^((?!<[^>]*>).)*$", message = "Input contains illegal HTML tags.")
        String playerName,

        @NotNull
        @Min(value = Room.MIN_PLAYERS, message = "Minimum 2 players required")
        @Max(value = Room.MAX_PLAYERS, message = "Maximum 10 players allowed")
        Integer maxPlayers,

        @NotNull
        @Min(value = Room.MIN_SMALL_BLIND, message = "Small blind must be at least 1")
        @Max(value = 10000, message = "Small blind cannot exceed 10,000")
        Integer smallBlind,

        @NotNull
        @Min(value = 2, message = "Big blind must be at least 2")
        @Max(value = 20000, message = "Big blind cannot exceed 20,000")
        Integer bigBlind,

        @NotNull
        @Min(value = 20, message = "Buy-in must be at least 20")
        @Max(value = 1000000, message = "Buy-in cannot exceed 1,000,000")
        Integer buyIn,

        @Size(max = 50, message = "Password must be 50 characters or less")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Password cannot contain control characters")
        String password) {

    public CreateRoomRequest {
        password = (password != null && password.trim().isEmpty()) ? null : password;
    }

    /**
     * Reports whether the entire raw password fits BCrypt's UTF-8 byte limit.
     *
     * @return whether the password can be hashed without truncation
     */
    @JsonIgnore
    @AssertTrue(message = RoomPasswordPolicy.BYTE_LIMIT_MESSAGE)
    public boolean isPasswordWithinByteLimit() {
        return RoomPasswordPolicy.isWithinByteLimit(password);
    }

    /**
     * Reports whether the big blind is at least twice the small blind.
     *
     * @return {@code true} when either blind is missing or {@code bigBlind >= 2 * smallBlind}
     */
    @JsonIgnore
    @AssertTrue(message = "Big blind must be at least twice the small blind.")
    public boolean isBigBlindAtLeastTwiceSmallBlind() {
        return smallBlind == null || bigBlind == null || bigBlind >= (long) Room.BIG_BLIND_MULTIPLE * smallBlind;
    }

    /**
     * Reports whether the buy-in covers at least one big blind.
     *
     * @return {@code true} when either value is missing or {@code buyIn >= bigBlind}
     */
    @JsonIgnore
    @AssertTrue(message = "Buy-in must be at least the big blind amount.")
    public boolean isBuyInAtLeastBigBlind() {
        return bigBlind == null || buyIn == null || buyIn >= bigBlind;
    }

    /**
     * Reports whether the request protects the room with a non-blank password.
     *
     * @return {@code true} when a password was supplied
     */
    @JsonIgnore
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
        return "CreateRoomRequest{"
                + "roomName='" + roomName + '\''
                + ", playerName='" + playerName + '\''
                + ", maxPlayers=" + maxPlayers
                + ", smallBlind=" + smallBlind
                + ", bigBlind=" + bigBlind
                + ", buyIn=" + buyIn
                + ", hasPassword=" + hasPassword()
                + '}';
    }
}
