package com.pokergame.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pokergame.util.RoomPasswordPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Validated request payload for joining an existing poker room.
 *
 * @param roomName   the name of the room
 * @param playerName the name of the player joining the room
 * @param password   the optional room password ({@code null} when blank or omitted)
 */
public record JoinRoomRequest(
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

        @Size(max = 50, message = "Password must be 50 characters or less")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Password cannot contain control characters")
        String password) {

    public JoinRoomRequest {
        password = (password != null && password.trim().isEmpty()) ? null : password;
    }

    /**
     * Reports whether verifying the password would preserve all of its UTF-8 bytes.
     *
     * @return whether the password fits BCrypt's byte limit
     */
    @JsonIgnore
    @AssertTrue(message = RoomPasswordPolicy.BYTE_LIMIT_MESSAGE)
    public boolean isPasswordWithinByteLimit() {
        return RoomPasswordPolicy.isWithinByteLimit(password);
    }

    /**
     * Returns a log-safe description that deliberately omits password contents.
     *
     * @return room name, player name, and password-presence flag
     */
    @Override
    public String toString() {
        return "JoinRoomRequest{"
                + "roomName='" + roomName + '\''
                + ", playerName='" + playerName + '\''
                + ", hasPassword=" + (password != null)
                + '}';
    }
}
