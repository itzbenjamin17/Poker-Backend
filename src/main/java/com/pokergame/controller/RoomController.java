package com.pokergame.controller;

import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.TokenResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.dto.internal.RoomEntryResult;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.pokergame.model.Room;
import com.pokergame.util.LockLease;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;


/**
 * REST controller for poker room management.
 * Handles room creation, joining, leaving, and game initialisation.
 * Secured operations hold the room lease from membership validation through completion.
 */
@RestController
@RequestMapping("/api/room")
public class RoomController {
    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;

    private final GameLifecycleService gameLifecycleService;

    private final JwtService jwtService;

    private final DurableMutationAspect durableMutationAspect;

    /**
     * Creates the room controller with its room, game, and token services.
     *
     * @param roomService owns lobby state and membership
     * @param gameLifecycleService transitions rooms into active games
     * @param jwtService issues room-bound player tokens
     * @param durableMutationAspect supplies the per-room lock shared with durable mutations
     */
    public RoomController(RoomService roomService,
            GameLifecycleService gameLifecycleService,
            JwtService jwtService,
            DurableMutationAspect durableMutationAspect) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.jwtService = jwtService;
        this.durableMutationAspect = durableMutationAspect;
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
            HttpServletRequest request) {
        logger.info("Creating new room with request: {}", createRequest);

        // Sanitization is owned by the room service; it returns the canonical player
        // name so the token binds to exactly the name that was stored.
        RoomEntryResult entry = roomService.createRoomForEntry(createRequest);
        String roomId = entry.roomId();
        String canonicalPlayerName = entry.canonicalPlayerName();

        Room room = roomService.getRoom(roomId);
        String membershipId = room != null ? room.getMembershipId(canonicalPlayerName) : null;

        // Generate JWT token for this player, bound to this room and membership
        String token = membershipId != null
                ? jwtService.generateToken(canonicalPlayerName, roomId, membershipId)
                : jwtService.generateToken(canonicalPlayerName, roomId);

        logger.info("Room created successfully with ID: {}", roomId);
        TokenResponse tokenResponse = new TokenResponse(token, roomId, canonicalPlayerName);
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
            HttpServletRequest request) {
        logger.info("Player {} attempting to join room: {}", joinRequest.playerName(), joinRequest.roomName());

        // Sanitization is owned by the room service; it returns the canonical player
        // name so the token binds to exactly the name that was stored.
        RoomEntryResult entry = roomService.joinRoomForEntry(joinRequest);
        String roomId = entry.roomId();
        String canonicalPlayerName = entry.canonicalPlayerName();

        Room room = roomService.getRoom(roomId);
        String membershipId = room != null ? room.getMembershipId(canonicalPlayerName) : null;

        // Generate JWT token for this player, bound to this room and membership
        String token = membershipId != null
                ? jwtService.generateToken(canonicalPlayerName, roomId, membershipId)
                : jwtService.generateToken(canonicalPlayerName, roomId);

        logger.info("Player {} successfully joined room: {}", canonicalPlayerName, joinRequest.roomName());
        TokenResponse tokenResponse = new TokenResponse(token, roomId, canonicalPlayerName);
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
        playerPrincipal.assertBoundTo(roomId);

        try (LockLease _ = durableMutationAspect.acquireRoomLock(roomId)) {
            if (roomService.checkAndCloseIfExpired(roomId)) {
                throw new BadRequestException("Room has expired due to inactivity.");
            }
            gameLifecycleService.validatePlayerMembership(roomId, playerName, playerPrincipal.membershipId());
            gameLifecycleService.departParticipants(roomId, List.of(playerName));
            logger.info("Player {} successfully left room {}", playerName, roomId);
            return ResponseEntity.ok(ApiResponse.success("Successfully left room"));
        }
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
        playerPrincipal.assertBoundTo(roomId);

        try (LockLease _ = durableMutationAspect.acquireRoomLock(roomId)) {
            gameLifecycleService.validatePlayerMembership(roomId, playerPrincipal.playerName(), playerPrincipal.membershipId());
            logger.debug("Fetching room info for room: {}", roomId);
            Room room = roomService.getRoom(roomId);
            if (room == null) {
                logger.warn("Room not found: {}", roomId);
                return ResponseEntity.notFound().build();
            }
            // The room lease (acquireRoomLock) and this Room intrinsic monitor are DISTINCT locks
            // guarding different concerns: the lease serializes durable mutations on the room, while
            // this monitor guards Room's own mutable fields and enforces the room -> game lock
            // ordering. Both are held intentionally so the membership check and the room-data read
            // are atomic against a concurrent removePlayer. Do not "simplify" by dropping either.
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
        playerPrincipal.assertBoundTo(roomId);

        try (LockLease _ = durableMutationAspect.acquireRoomLock(roomId)) {
            if (roomService.checkAndCloseIfExpired(roomId)) {
                throw new BadRequestException("Room has expired due to inactivity.");
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
}
