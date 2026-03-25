package com.pokergame.controller;

import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.TokenResponse;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * REST controller for poker room management.
 * Handles room creation, joining, leaving, and game initialisation.
 */
@RestController
@RequestMapping("/room")
public class RoomController {
    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;

    private final GameLifecycleService gameLifecycleService;

    private final JwtService jwtService;

    // Dependency Injection
    public RoomController(RoomService roomService, GameLifecycleService gameLifecycleService, JwtService jwtService) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.jwtService = jwtService;
    }

    /**
     * Creates a new poker room. The creating player becomes the room host.
     * PUBLIC ENDPOINT - Issues JWT token.
     *
     * @param createRequest room configuration (name, max players, etc.)
     * @return room ID and JWT token
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<TokenResponse>> createRoom(@Valid @RequestBody CreateRoomRequest createRequest) {
        logger.info("Creating new room with request: {}", createRequest);
        String roomId = roomService.createRoom(createRequest);

        // Generate JWT token for this player
        String token = jwtService.generateToken(createRequest.getPlayerName());

        logger.info("Room created successfully with ID: {}", roomId);
        TokenResponse tokenResponse = new TokenResponse(token, roomId, createRequest.getPlayerName());
        return ResponseEntity.ok(ApiResponse.success("Room created successfully", tokenResponse));
    }

    /**
     * Joins an existing poker room.
     * PUBLIC ENDPOINT - Issues JWT token.
     *
     * @param joinRequest room name and player information
     * @return room ID and JWT token
     */
    @PostMapping("/join")
    public ResponseEntity<ApiResponse<TokenResponse>> joinRoom(@Valid @RequestBody JoinRoomRequest joinRequest) {
        logger.info("Player {} attempting to join room: {}", joinRequest.playerName(), joinRequest.roomName());
        String roomId = roomService.joinRoom(joinRequest);

        // Generate JWT token for this player
        String token = jwtService.generateToken(joinRequest.playerName());

        logger.info("Player {} successfully joined room: {}", joinRequest.playerName(), joinRequest.roomName());
        TokenResponse tokenResponse = new TokenResponse(token, roomId, joinRequest.playerName());
        return ResponseEntity.ok(ApiResponse.success("Successfully joined room", tokenResponse));
    }

    /**
     * Removes a player from a poker room. Host privileges transfer if needed.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param roomId    room identifier
     * @param principal authenticated player (from JWT)
     * @return success confirmation
     */
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveRoom(
            @PathVariable String roomId,
            Principal principal) {
        String playerName = principal.getName();
        logger.info("Player {} requesting to leave room {}", playerName, roomId);
        roomService.leaveRoom(roomId, playerName);

        if (gameLifecycleService.gameExists(roomId)) {
            gameLifecycleService.leaveGame(roomId, playerName);
        }

        logger.info("Player {} successfully left room {}", playerName, roomId);
        return ResponseEntity.ok(ApiResponse.success("Successfully left room"));
    }

    /**
     * Retrieves room data including players, settings, and status.
     *
     * @param roomId room identifier
     * @return room data, or 404 if not found
     */
    @GetMapping("/{roomId}")
    public ResponseEntity<RoomDataResponse> getRoomInfo(@PathVariable String roomId) {
        logger.debug("Fetching room info for room: {}", roomId);
        RoomDataResponse roomData = roomService.getRoomData(roomId);
        if (roomData == null) {
            logger.warn("Room not found: {}", roomId);
            return ResponseEntity.notFound().build();
        }
        logger.debug("Room info retrieved successfully for room: {}", roomId);
        return ResponseEntity.ok(roomData);
    }

    /**
     * Starts a poker game from a room. Only the room host can start the game.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param roomId    room identifier
     * @param principal authenticated player (must be host)
     * @return created game ID
     * @throws UnauthorisedActionException if the player is not the room host
     */
    @PostMapping("/{roomId}/start-game")
    public ResponseEntity<ApiResponse<String>> startGame(
            @PathVariable String roomId,
            Principal principal) {
        String playerName = principal.getName();
        logger.info("Player {} attempting to start game for room {}", playerName, roomId);

        if (!roomService.isRoomHost(roomId, playerName)) {
            logger.warn("Non-host player {} attempted to start game for room {}", playerName, roomId);
            throw new UnauthorisedActionException(
                    "Only the room host can start the game. Please ask the host to start.");
        }

        logger.info("Host {} authorized to start game for room {}", playerName, roomId);
        String gameId = gameLifecycleService.createGameFromRoom(roomId);
        logger.info("Game {} created successfully from room {}", gameId, roomId);

        return ResponseEntity.ok(ApiResponse.success("Game started successfully", gameId));
    }
}
