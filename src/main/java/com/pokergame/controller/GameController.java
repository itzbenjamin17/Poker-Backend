package com.pokergame.controller;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.util.LockLease;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.model.Game;
import com.pokergame.security.PlayerPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST controller for active poker game operations.
 * Manages player actions and game departures using WebSocket for real-time
 * updates.
 * Secured operations hold the room lease from membership validation through completion.
 */
@RestController
@RequestMapping("/api/game")
public class GameController {
    private static final Logger logger = LoggerFactory.getLogger(GameController.class);

    private final GameLifecycleService gameLifecycleService;

    private final GameStateService gameStateService;

    private final PlayerActionService playerActionService;

    private final RoomCoordinator roomCoordinator;

    /** Creates the controller with the shared room coordination boundary. */
    public GameController(PlayerActionService playerActionService, GameLifecycleService gameLifecycleService,
            GameStateService gameStateService, RoomCoordinator roomCoordinator) {
        this.playerActionService = playerActionService;
        this.gameLifecycleService = gameLifecycleService;
        this.gameStateService = gameStateService;
        this.roomCoordinator = requireNonNull(roomCoordinator);
    }

    /**
     * Fetches current public game state for authenticated players who are still in
     * the game.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param gameId    game identifier
     * @param playerPrincipal authenticated player principal
     * @return latest game-state snapshot
     */
    @GetMapping("/{gameId}/state")
    public ResponseEntity<PublicGameStateResponse> getGameState(
            @PathVariable String gameId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.debug("Player {} requested game state for {}", playerName, gameId);

        // Security check: ensure the token is actually for THIS game/room
        playerPrincipal.assertBoundTo(gameId, "Token is not valid for this game.");

        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            gameLifecycleService.validatePlayerMembership(gameId, playerName, playerPrincipal.membershipId());
            Game game = getAuthorisedGame(gameId, playerName);
            return ResponseEntity.ok(gameStateService.getPublicGameStateSnapshot(gameId, game));
        }
    }

    /**
     * Fetches the private state for the authenticated player (hole cards).
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param gameId    game identifier
     * @param playerPrincipal authenticated player principal
     * @return private player-state snapshot
     */
    @GetMapping("/{gameId}/private-state")
    public ResponseEntity<PrivatePlayerState> getPrivateState(
            @PathVariable String gameId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.debug("Player {} requested private state for {}", playerName, gameId);

        // Security check: ensure the token is actually for THIS game/room
        playerPrincipal.assertBoundTo(gameId, "Token is not valid for this game.");

        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            gameLifecycleService.validatePlayerMembership(gameId, playerName, playerPrincipal.membershipId());
            Game game = getAuthorisedGame(gameId, playerName);
            return ResponseEntity.ok(gameStateService.getPrivatePlayerStateSnapshot(game, playerName));
        }
    }

    /**
     * Resolves a game only when it exists and still contains the requesting player.
     *
     * @param gameId requested game identifier
     * @param playerName authenticated player name
     * @return authorized active game
     * @throws ResourceNotFoundException if the game does not exist
     * @throws UnauthorisedActionException if the player is no longer in the game
     */
    private Game getAuthorisedGame(String gameId, String playerName) {
        if (!gameLifecycleService.gameExists(gameId)) {
            throw new ResourceNotFoundException("Game not found");
        }

        if (!gameLifecycleService.playerExistsInGame(gameId, playerName)) {
            throw new UnauthorisedActionException("You are no longer part of this game.");
        }

        Game game = gameLifecycleService.getGame(gameId);
        if (game == null || game.isGameOver()) {
            throw new ResourceNotFoundException("Game not found");
        }

        return game;
    }

    /**
     * Processes a player action (fold, check, call, raise, all-in).
     * Now primarily handled via direct WebSocket communication.
     *
     * @param gameId        game identifier
     * @param actionRequest action type and amount
     * @param playerPrincipal authenticated player principal
     */
    @MessageMapping("/{gameId}/action")
    public void performAction(
            @DestinationVariable String gameId,
            @Payload @Valid PlayerActionRequest actionRequest,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Processing player action for game {} by {}: {}", gameId, playerName, actionRequest);

        // Security check: ensure the token is actually for THIS game/room
        playerPrincipal.assertBoundTo(gameId, "Token is not valid for this game.");

        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            gameLifecycleService.validatePlayerMembership(gameId, playerName, playerPrincipal.membershipId());
            playerActionService.processPlayerAction(gameId, actionRequest, playerName);
            logger.debug("Player action processed successfully for game {}", gameId);
        }
    }

    /**
     * Marks the authenticated player as READY during the post-round countdown.
     *
     * @param gameId    game identifier
     * @param playerPrincipal authenticated player principal
     */
    @MessageMapping("/{gameId}/ready")
    public void markReady(
            @DestinationVariable String gameId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Processing READY confirmation for game {} by {}", gameId, playerName);

        // Security check: ensure the token is actually for THIS game/room
        playerPrincipal.assertBoundTo(gameId, "Token is not valid for this game.");

        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            gameLifecycleService.validatePlayerMembership(gameId, playerName, playerPrincipal.membershipId());
            gameLifecycleService.markPlayerReadyForNextHand(gameId, playerName);
        }
    }

    /**
     * Unmarks the authenticated player as READY during the post-round countdown.
     *
     * @param gameId    game identifier
     * @param playerPrincipal authenticated player principal
     */
    @MessageMapping("/{gameId}/unready")
    public void unmarkReady(
            @DestinationVariable String gameId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Processing UNREADY for game {} by {}", gameId, playerName);

        // Security check: ensure the token is actually for THIS game/room
        playerPrincipal.assertBoundTo(gameId, "Token is not valid for this game.");

        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            gameLifecycleService.validatePlayerMembership(gameId, playerName, playerPrincipal.membershipId());
            gameLifecycleService.unmarkPlayerReadyForNextHand(gameId, playerName);
        }
    }

    /**
     * Removes a player from an active game. Hand is automatically folded if in a
     * round.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param gameId    game identifier
     * @param playerPrincipal authenticated player principal
     * @return success confirmation
     */
    @PostMapping("/{gameId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveGame(
            @PathVariable String gameId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Player {} requesting to leave game {}", playerName, gameId);

        // Security check: ensure the token is actually for THIS game
        playerPrincipal.assertBoundTo(gameId, "Token is not valid for this game.");

        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            gameLifecycleService.validatePlayerMembership(gameId, playerName, playerPrincipal.membershipId());
            gameLifecycleService.departParticipants(gameId, List.of(playerName));
            logger.info("Player {} successfully left game {}", playerName, gameId);
            return ResponseEntity.ok(ApiResponse.success("Successfully left game"));
        }
    }

    /**
     * Fetches the frozen terminal result for post-game review.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param gameId              game identifier
     * @param requestedPlayerName optional querying player name to match against principal
     * @param playerPrincipal     authenticated player principal
     * @return direct GameEndResponse
     */
    @GetMapping("/{gameId}/review")
    public ResponseEntity<GameEndResponse> getGameReview(
            @PathVariable String gameId,
            @RequestParam(value = "playerName", required = false) String requestedPlayerName,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        if (playerPrincipal == null) {
            throw new UnauthorisedActionException("Authentication required.");
        }

        String playerName = playerPrincipal.playerName();
        logger.debug("Player {} requested game review for {}", playerName, gameId);

        // Security check: ensure the token is actually for THIS game/room (null-safe)
        playerPrincipal.assertBoundTo(gameId, "Token is not valid for this game.");

        // Security check: ensure querying player name matches the authenticated principal
        if (requestedPlayerName != null && !requestedPlayerName.isBlank() && !requestedPlayerName.equals(playerName)) {
            throw new UnauthorisedActionException("Query player name does not match authenticated player.");
        }

        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            gameLifecycleService.validatePlayerMembership(gameId, playerName, playerPrincipal.membershipId());
            return ResponseEntity.ok(gameLifecycleService.getGameReview(gameId, playerName));
        }
    }

    /**
     * Allows the remaining connected player to claim a win immediately when all
     * other non-out players are disconnected.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param gameId    game identifier
     * @param playerPrincipal authenticated player principal
     * @return terminal result confirmation
     */
    @PostMapping("/{gameId}/claim-win")
    public ResponseEntity<ApiResponse<GameEndResponse>> claimWin(
            @PathVariable String gameId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        if (playerPrincipal == null) {
            throw new UnauthorisedActionException("Authentication required.");
        }

        String playerName = playerPrincipal.playerName();
        logger.info("Player {} attempting to claim win in game {}", playerName, gameId);

        // Security check: ensure the token is actually for THIS game/room (null-safe)
        playerPrincipal.assertBoundTo(gameId, "Token is not valid for this game.");

        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            gameLifecycleService.validatePlayerMembership(gameId, playerName, playerPrincipal.membershipId());
            GameEndResponse result = gameLifecycleService.claimWin(gameId, playerName);
            logger.info("Player {} successfully claimed win in game {}", playerName, gameId);
            return ResponseEntity.ok(ApiResponse.success("Win claimed successfully", result));
        }
    }
}
