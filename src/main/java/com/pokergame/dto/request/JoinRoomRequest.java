package com.pokergame.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO for joining an existing poker room
 * 
 * @param roomName The name of the room.
 * @param playerName The name of the player joining the room.
 * @param password The password for the room (optional).
 */

public record JoinRoomRequest(
        @NotBlank(message = "Room name is required")
        @Size(max = 50, message = "Room name must be 50 characters or less")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Room name cannot contain control characters")
        String roomName,

        @NotBlank(message = "Player name is required")
        @Size(max = 30, message = "Player name must be 30 characters or less")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Player name cannot contain control characters")
        String playerName,

        @Size(max = 50, message = "Password must be 50 characters or less")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Password cannot contain control characters")
        String password // Optional - null means no password required

) {
}
