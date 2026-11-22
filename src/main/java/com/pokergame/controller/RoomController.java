package com.pokergame.controller;

import static java.util.Objects.requireNonNull;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.response.TokenResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.InvalidRoomPasswordException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.TooManyRequestsException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Room;
import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for poker room management.
 * Handles room creation, joining, leaving, and game initialisation.
 * Secured operations hold the room lock from membership validation through completion.
 */
@RestController
@RequestMapping("/api/room")
public class RoomController {
    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;
    private final JwtService jwtService;
    private final RoomCoordinator roomCoordinator;
    private final RateLimitService rateLimitService;

    @Value("${poker.security.trust-proxy:false}")
    private boolean trustProxy;

    /** Creates the controller with the shared room coordination boundary and the join rate limiter. */
    public RoomController(RoomService roomService, GameLifecycleService gameLifecycleService,
            JwtService jwtService, RoomCoordinator roomCoordinator, RateLimitService rateLimitService) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.jwtService = jwtService;
        this.roomCoordinator = requireNonNull(roomCoordinator);
        this.rateLimitService = rateLimitService;
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
    public ResponseEntity<TokenResponse> createRoom(
            @Valid @RequestBody CreateRoomRequest createRequest,
            HttpServletRequest request) {
        logger.info("Creating new room with request: {}", createRequest);

        // Sanitization is owned by the room service; it returns the canonical player
        // name so the token binds to exactly the name that was stored.
        RoomService.EntryResult entry = roomService.createRoom(UUID.randomUUID().toString(), createRequest);
        String roomId = entry.roomId();
        String canonicalPlayerName = entry.canonicalPlayerName();

        // Generate JWT token for this player, bound to this room and membership
        String token = jwtService.generateToken(canonicalPlayerName, roomId, entry.membershipId());

        logger.info("Room created successfully with ID: {}", roomId);
        return ResponseEntity.ok(new TokenResponse(token, roomId, canonicalPlayerName));
    }

    /**
     * Joins an existing poker room.
     * PUBLIC ENDPOINT - Issues JWT token. Wrong passwords are counted per client IP and room;
     * once that budget is spent, further attempts on the room are refused until it refills.
     *
     * @param joinRequest room name and player information
     * @param request current servlet request
     * @return room ID and JWT token
     * @throws TooManyRequestsException if this client has used up its wrong-password budget for the room
     * @throws InvalidRoomPasswordException if the password is wrong (and records the failure)
     */
    @PostMapping("/join")
    public ResponseEntity<TokenResponse> joinRoom(
            @Valid @RequestBody JoinRoomRequest joinRequest,
            HttpServletRequest request) {
        logger.info("Player {} attempting to join room: {}", joinRequest.playerName(), joinRequest.roomName());

        // Sanitization is owned by the room service; it returns the canonical player
        // name so the token binds to exactly the name that was stored.
        String targetRoomId = roomService.resolveRoomIdByName(joinRequest.roomName());

        // Password guessing is limited per IP + room (not per typed name, which is free to change).
        // Only wrong passwords spend the budget; once spent, every attempt on this room waits.
        String clientIp = SecurityUtils.getClientIp(request, trustProxy);
        RateLimitService.Decision attempt = rateLimitService.checkJoinPasswordAttempt(clientIp, targetRoomId);
        if (!attempt.allowed()) {
            throw new TooManyRequestsException(
                    RateLimitService.tooManyRequestsMessage(attempt.retryAfterSeconds()), attempt.retryAfterSeconds());
        }

        RoomService.EntryResult entry;
        try (LockLease _ = roomCoordinator.acquire(targetRoomId)) {
            if (roomService.closeLobbyIfExpired(targetRoomId)) {
                throw new BadRequestException("Room has expired due to inactivity.");
            }
            entry = roomService.joinRoom(targetRoomId, joinRequest);
        } catch (InvalidRoomPasswordException wrongPassword) {
            rateLimitService.recordFailedJoinPassword(clientIp, targetRoomId);
            throw wrongPassword;
        }
        String roomId = entry.roomId();
        String canonicalPlayerName = entry.canonicalPlayerName();

        // Generate JWT token for this player, bound to this room and membership
        String token = jwtService.generateToken(canonicalPlayerName, roomId, entry.membershipId());

        logger.info("Player {} successfully joined room: {}", canonicalPlayerName, joinRequest.roomName());
        return ResponseEntity.ok(new TokenResponse(token, roomId, canonicalPlayerName));
    }

    /**
     * Removes a player from a poker room. Host privileges transfer if needed.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param roomId    room identifier
     * @param playerPrincipal authenticated player principal
     * @return empty 204 response
     */
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable String roomId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Player {} requesting to leave room {}", playerName, roomId);
        // Security check: ensure the token is actually for THIS room
        playerPrincipal.assertBoundTo(roomId);

        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            if (roomService.closeLobbyIfExpired(roomId)) {
                throw new ResourceNotFoundException("Room not found");
            }
            gameLifecycleService.validatePlayerMembership(roomId, playerName, playerPrincipal.membershipId());
            gameLifecycleService.removePlayers(roomId, List.of(playerName));
            logger.info("Player {} successfully left room {}", playerName, roomId);
            return ResponseEntity.noContent().build();
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

        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            if (roomService.closeLobbyIfExpired(roomId)) {
                logger.warn("Room has expired: {}", roomId);
                return ResponseEntity.notFound().build();
            }
            gameLifecycleService.validatePlayerMembership(roomId, playerPrincipal.playerName(), playerPrincipal.membershipId());
            logger.debug("Fetching room info for room: {}", roomId);
            Room room = roomService.getRoom(roomId);
            if (room == null) {
                logger.warn("Room not found: {}", roomId);
                return ResponseEntity.notFound().build();
            }
            if (!room.getPlayers().contains(playerPrincipal.playerName())) {
                throw new UnauthorisedActionException("You are not a member of this room.");
            }
            RoomDataResponse roomData = roomService.getRoomData(roomId);
            logger.debug("Room info retrieved successfully for room: {}", roomId);
            return ResponseEntity.ok(roomData);
        }
    }

    /**
     * Starts a poker game from a room. Only the room host can start the game.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param roomId    room identifier
     * @param playerPrincipal authenticated player principal (must be host)
     * @return empty 204 response
     * @throws UnauthorisedActionException if the player is not the room host
     */
    @PostMapping("/{roomId}/start-game")
    public ResponseEntity<Void> startGame(
            @PathVariable String roomId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Player {} attempting to start game for room {}", playerName, roomId);

        // Security check: ensure the token is actually for THIS room
        playerPrincipal.assertBoundTo(roomId);

        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            if (roomService.closeLobbyIfExpired(roomId)) {
                throw new ResourceNotFoundException("Room not found");
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

            return ResponseEntity.noContent().build();
        }
    }
}
