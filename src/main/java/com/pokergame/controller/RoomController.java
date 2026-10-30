package com.pokergame.controller;

import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.TokenResponse;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.util.InputSanitizer;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.pokergame.model.Room;
import com.pokergame.util.LockLease;
import java.util.List;


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

    @Autowired(required = false)
    private DurableMutationAspect durableMutationAspect;

    /**
     * Creates the room controller with its room, game, and token services.
     *
     * @param roomService owns lobby state and membership
     * @param gameLifecycleService transitions rooms into active games
     * @param jwtService issues room-bound player tokens
     */
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
     * @param request current servlet request
     * @return room ID and JWT token
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<TokenResponse>> createRoom(
            @Valid @RequestBody CreateRoomRequest createRequest,
            jakarta.servlet.http.HttpServletRequest request) {
        logger.info("Creating new room with request: {}", createRequest);
        
        // Sanitize names first to ensure consistency between token and storage
        InputSanitizer.sanitize(createRequest.getRoomName());
        String sanitizedPlayerName = InputSanitizer.sanitize(createRequest.getPlayerName());
        
        String roomId = roomService.createRoom(createRequest);

        Room room = roomService.getRoom(roomId);
        String membershipId = room != null ? room.getMembershipId(sanitizedPlayerName) : null;

        // Generate JWT token for this player, bound to this room and membership
        String token = membershipId != null
                ? jwtService.generateToken(sanitizedPlayerName, roomId, membershipId)
                : jwtService.generateToken(sanitizedPlayerName, roomId);

        logger.info("Room created successfully with ID: {}", roomId);
        TokenResponse tokenResponse = new TokenResponse(token, roomId, sanitizedPlayerName);
        return ResponseEntity.ok(ApiResponse.success("Room created successfully", tokenResponse));
    }

    /**
     * Joins an existing poker room.
     * PUBLIC ENDPOINT - Issues JWT token.
     *
     * @param joinRequest room name and player information
     * @param request current servlet request
     * @return room ID and JWT token
     */
    @PostMapping("/join")
    public ResponseEntity<ApiResponse<TokenResponse>> joinRoom(
            @Valid @RequestBody JoinRoomRequest joinRequest,
            jakarta.servlet.http.HttpServletRequest request) {
        logger.info("Player {} attempting to join room: {}", joinRequest.playerName(), joinRequest.roomName());
        
        // Sanitize names first to ensure consistency between token and storage
        InputSanitizer.sanitize(joinRequest.roomName());
        String sanitizedPlayerName = InputSanitizer.sanitize(joinRequest.playerName());
        
        String roomId = roomService.joinRoom(joinRequest);

        Room room = roomService.getRoom(roomId);
        String membershipId = room != null ? room.getMembershipId(sanitizedPlayerName) : null;

        // Generate JWT token for this player, bound to this room and membership
        String token = membershipId != null
                ? jwtService.generateToken(sanitizedPlayerName, roomId, membershipId)
                : jwtService.generateToken(sanitizedPlayerName, roomId);

        logger.info("Player {} successfully joined room: {}", sanitizedPlayerName, joinRequest.roomName());
        TokenResponse tokenResponse = new TokenResponse(token, roomId, sanitizedPlayerName);
        return ResponseEntity.ok(ApiResponse.success("Successfully joined room", tokenResponse));
    }

    /**
     * Removes a player from a poker room. Host privileges transfer if needed.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param roomId    room identifier
     * @param playerPrincipal authenticated player principal
     * @return success confirmation
     */
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveRoom(
            @PathVariable String roomId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Player {} requesting to leave room {}", playerName, roomId);
        // Security check: ensure the token is actually for THIS room
        if (!playerPrincipal.roomId().equals(roomId)) {
            throw new UnauthorisedActionException("Token is not valid for this room.");
        }

        gameLifecycleService.validatePlayerMembership(roomId, playerName, playerPrincipal.membershipId());

        gameLifecycleService.departParticipants(roomId, List.of(playerName));

        logger.info("Player {} successfully left room {}", playerName, roomId);
        return ResponseEntity.ok(ApiResponse.success("Successfully left room"));
    }

    /**
     * Retrieves room data including players, settings, and status.
     * SECURED ENDPOINT - Requires JWT token bound to this room and active membership.
     *
     * @param roomId room identifier
     * @param playerPrincipal authenticated player principal
     * @return room data, or 404 if not found
     */
    @GetMapping("/{roomId}")
    public ResponseEntity<RoomDataResponse> getRoomInfo(
            @PathVariable String roomId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        // Check binding before exposing resource existence
        if (!playerPrincipal.roomId().equals(roomId)) {
            throw new UnauthorisedActionException("Token is not valid for this room.");
        }

        gameLifecycleService.validatePlayerMembership(roomId, playerPrincipal.playerName(), playerPrincipal.membershipId());

        try (LockLease ignored = durableMutationAspect != null ? durableMutationAspect.acquireRoomLock(roomId) : LockLease.none()) {
            logger.debug("Fetching room info for room: {}", roomId);
            Room room = roomService.getRoom(roomId);
            if (room == null) {
                logger.warn("Room not found: {}", roomId);
                return ResponseEntity.notFound().build();
            }
            synchronized (room) {
                if (!room.getPlayers().contains(playerPrincipal.playerName())) {
                    throw new UnauthorisedActionException("You are not a member of this room.");
                }
                RoomDataResponse roomData = roomService.getRoomData(roomId);
                logger.debug("Room info retrieved successfully for room: {}", roomId);
                return ResponseEntity.ok(roomData);
            }
        }
    }

    /**
     * Starts a poker game from a room. Only the room host can start the game.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param roomId    room identifier
     * @param playerPrincipal authenticated player principal (must be host)
     * @return created game ID
     * @throws UnauthorisedActionException if the player is not the room host
     */
    @PostMapping("/{roomId}/start-game")
    public ResponseEntity<ApiResponse<String>> startGame(
            @PathVariable String roomId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Player {} attempting to start game for room {}", playerName, roomId);

        // Security check: ensure the token is actually for THIS room
        if (!playerPrincipal.roomId().equals(roomId)) {
            throw new UnauthorisedActionException("Token is not valid for this room.");
        }

        gameLifecycleService.validatePlayerMembership(roomId, playerName, playerPrincipal.membershipId());

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
