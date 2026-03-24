package com.pokergame.controller;

import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.TokenResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Principal represents an authenticated player
import java.security.Principal;

/**
 * Main API controller providing unified {@code /api} access point.
 * Delegates to {@link RoomController} and {@link GameController}.
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);

    private final RoomController roomController;

    private final GameController gameController;

    // Dependency Injection
    public ApiController(GameController gameController, RoomController roomController) {
        this.gameController = gameController;
        this.roomController = roomController;
    }

    // ==================== Room Endpoints ====================

    /**
     * Creates a new poker room. Delegates to
     * {@link RoomController#createRoom(CreateRoomRequest)}.
     * PUBLIC ENDPOINT - Issues JWT token.
     */
    @PostMapping("/room/create")
    public ResponseEntity<ApiResponse<TokenResponse>> createRoom(@Valid @RequestBody CreateRoomRequest createRequest) {
        logger.debug("API: Delegating room creation request");
        return roomController.createRoom(createRequest);
    }

    /**
     * Joins an existing poker room. Delegates to
     * {@link RoomController#joinRoom(JoinRoomRequest)}.
     * PUBLIC ENDPOINT - Issues JWT token.
     */
    @PostMapping("/room/join")
    public ResponseEntity<ApiResponse<TokenResponse>> joinRoom(@Valid @RequestBody JoinRoomRequest joinRequest) {
        logger.debug("API: Delegating room join request for room: {}", joinRequest.roomName());
        return roomController.joinRoom(joinRequest);
    }

    /**
     * Removes a player from a room. Delegates to
     * {@link RoomController#leaveRoom(String, Principal)}.
     * SECURED ENDPOINT - Requires JWT token.
     */
    @PostMapping("/room/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveRoom(
            @PathVariable String roomId,
            Principal principal) {
        logger.debug("API: Delegating leave room request for room: {}", roomId);
        return roomController.leaveRoom(roomId, principal);
    }

    /**
     * Retrieves room information. Delegates to
     * {@link RoomController#getRoomInfo(String)}.
     */
    @GetMapping("/room/{roomId}")
    public ResponseEntity<RoomDataResponse> getRoomInfo(@PathVariable String roomId) {
        logger.debug("API: Delegating get room info request for room: {}", roomId);
        return roomController.getRoomInfo(roomId);
    }

    /**
     * Starts a game from a room (host only). Delegates to
     * {@link RoomController#startGame(String, Principal)}.
     * SECURED ENDPOINT - Requires JWT token.
     */
    @PostMapping("/room/{roomId}/start-game")
    public ResponseEntity<ApiResponse<String>> startGame(
            @PathVariable String roomId,
            Principal principal) {
        logger.debug("API: Delegating start game request for room: {}", roomId);
        return roomController.startGame(roomId, principal);
    }

    // ==================== Game Endpoints ====================

    /**
     * Processes a player action. Delegates to
     * {@link GameController#performAction(String, PlayerActionRequest, Principal)}.
     * SECURED ENDPOINT - Requires JWT token.
     */
    @PostMapping("/game/{gameId}/action")
    public ResponseEntity<Void> performAction(
            @PathVariable String gameId,
            @Valid @RequestBody PlayerActionRequest actionRequest,
            Principal principal) {
        logger.debug("API: Delegating player action for game: {}", gameId);
        gameController.performAction(gameId, actionRequest, principal);
        return ResponseEntity.ok().build();
    }

    /**
     * Removes a player from an active game. Delegates to
     * {@link GameController#leaveGame(String, Principal)}.
     * SECURED ENDPOINT - Requires JWT token.
     */
    @PostMapping("/game/{gameId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveGame(
            @PathVariable String gameId,
            Principal principal) {
        logger.debug("API: Delegating leave game request for game: {}", gameId);
        return gameController.leaveGame(gameId, principal);
    }
}
