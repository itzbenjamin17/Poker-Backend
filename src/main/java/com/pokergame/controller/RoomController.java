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
import com.pokergame.security.PlayerPrincipal;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * REST controller for poker room management.
 * Handles room creation, joining, leaving, and game initialisation.
 */
@RestController
@RequestMapping("/api/room")
public class RoomController {
    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;

    private final GameLifecycleService gameLifecycleService;

    private final JwtService jwtService;


    // Dependency Injection
    public RoomController(RoomService roomService,
            GameLifecycleService gameLifecycleService,
            JwtService jwtService) {
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
    public ResponseEntity<ApiResponse<TokenResponse>> createRoom(
            @Valid @RequestBody CreateRoomRequest createRequest,
            jakarta.servlet.http.HttpServletRequest request) {
        logger.info("Creating new room with request: {}", createRequest);
        
        // Sanitize names first to ensure consistency between token and storage
        String sanitizedPlayerName = com.pokergame.util.InputSanitizer.sanitize(createRequest.getPlayerName());
        
        String roomId = roomService.createRoom(createRequest);

        // Generate JWT token for this player, bound to this room
        String token = jwtService.generateToken(sanitizedPlayerName, roomId);

        logger.info("Room created successfully with ID: {}", roomId);
        TokenResponse tokenResponse = new TokenResponse(token, roomId, sanitizedPlayerName);
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
    public ResponseEntity<ApiResponse<TokenResponse>> joinRoom(
            @Valid @RequestBody JoinRoomRequest joinRequest,
            jakarta.servlet.http.HttpServletRequest request) {
        logger.info("Player {} attempting to join room: {}", joinRequest.playerName(), joinRequest.roomName());
        
        // Sanitize names first to ensure consistency between token and storage
        String sanitizedPlayerName = com.pokergame.util.InputSanitizer.sanitize(joinRequest.playerName());
        
        String roomId = roomService.joinRoom(joinRequest);

        // Generate JWT token for this player, bound to this room
        String token = jwtService.generateToken(sanitizedPlayerName, roomId);

        logger.info("Player {} successfully joined room: {}", sanitizedPlayerName, joinRequest.roomName());
        TokenResponse tokenResponse = new TokenResponse(token, roomId, sanitizedPlayerName);
        return ResponseEntity.ok(ApiResponse.success("Successfully joined room", tokenResponse));
    }

    /**
     * Removes a player from a poker room. Host privileges transfer if needed.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param roomId    room identifier
     * @param authentication authenticated player (from JWT)
     * @return success confirmation
     */
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveRoom(
            @PathVariable String roomId,
            Authentication authentication) {
        PlayerPrincipal playerPrincipal = extractPrincipal(authentication);
        String playerName = playerPrincipal.playerName();
        logger.info("Player {} requesting to leave room {}", playerName, roomId);
        boolean gameActive = gameLifecycleService.gameExists(roomId);

        // Security check: ensure the token is actually for THIS room
        if (!playerPrincipal.roomId().equals(roomId)) {
            throw new UnauthorisedActionException("Token is not valid for this room.");
        }

        // Host only closes room while in lobby phase. During active games, host is
        // transferred.
        roomService.leaveRoom(roomId, playerName, !gameActive);

        if (gameActive && gameLifecycleService.playerExistsInGame(roomId, playerName)) {
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
     * @param authentication authenticated player (must be host)
     * @return created game ID
     * @throws UnauthorisedActionException if the player is not the room host
     */
    @PostMapping("/{roomId}/start-game")
    public ResponseEntity<ApiResponse<String>> startGame(
            @PathVariable String roomId,
            Authentication authentication) {
        PlayerPrincipal playerPrincipal = extractPrincipal(authentication);
        String playerName = playerPrincipal.playerName();
        logger.info("Player {} attempting to start game for room {}", playerName, roomId);

        // Security check: ensure the token is actually for THIS room
        if (!playerPrincipal.roomId().equals(roomId)) {
            throw new UnauthorisedActionException("Token is not valid for this room.");
        }

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

    private PlayerPrincipal extractPrincipal(Authentication authentication) {
        if (authentication.getPrincipal() instanceof PlayerPrincipal principal) {
            return principal;
        }
        throw new UnauthorisedActionException("Invalid authentication principal");
    }
}
