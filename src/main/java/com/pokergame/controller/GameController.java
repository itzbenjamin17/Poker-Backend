package com.pokergame.controller;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.model.Game;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.security.PlayerPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for active poker game operations.
 * Manages player actions and game departures using WebSocket for real-time
 * updates.
 */
@RestController
@RequestMapping("/api/game")
public class GameController {
    private static final Logger logger = LoggerFactory.getLogger(GameController.class);

    private final GameLifecycleService gameLifecycleService;

    private final GameStateService gameStateService;

    private final PlayerActionService playerActionService;

    /**
     * Creates the game controller with its action, lifecycle, and projection services.
     *
     * @param playerActionService validates and applies player actions
     * @param gameLifecycleService owns active games and lifecycle transitions
     * @param gameStateService creates and publishes client state
     */
    public GameController(PlayerActionService playerActionService,
            GameLifecycleService gameLifecycleService,
            GameStateService gameStateService) {
        this.playerActionService = playerActionService;
        this.gameLifecycleService = gameLifecycleService;
        this.gameStateService = gameStateService;
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
        if (!playerPrincipal.roomId().equals(gameId)) {
            throw new UnauthorisedActionException("Token is not valid for this game.");
        }

        Game game = getAuthorisedGame(gameId, playerName);

        return ResponseEntity.ok(gameStateService.getPublicGameStateSnapshot(gameId, game));
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
        if (!playerPrincipal.roomId().equals(gameId)) {
            throw new UnauthorisedActionException("Token is not valid for this game.");
        }

        Game game = getAuthorisedGame(gameId, playerName);

        return ResponseEntity.ok(gameStateService.getPrivatePlayerStateSnapshot(game, playerName));
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
        if (game == null) {
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
            @Payload PlayerActionRequest actionRequest,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Processing player action for game {} by {}: {}", gameId, playerName, actionRequest);

        // Security check: ensure the token is actually for THIS game/room
        if (!playerPrincipal.roomId().equals(gameId)) {
            throw new UnauthorisedActionException("Token is not valid for this game.");
        }

        playerActionService.processPlayerAction(gameId, actionRequest, playerName);
        logger.debug("Player action processed successfully for game {}", gameId);
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
        if (!playerPrincipal.roomId().equals(gameId)) {
            throw new UnauthorisedActionException("Token is not valid for this game.");
        }

        gameLifecycleService.markPlayerReadyForNextHand(gameId, playerName);
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
        if (!playerPrincipal.roomId().equals(gameId)) {
            throw new UnauthorisedActionException("Token is not valid for this game.");
        }

        gameLifecycleService.leaveGame(gameId, playerName);
        logger.info("Player {} successfully left game {}", playerName, gameId);
        return ResponseEntity.ok(ApiResponse.success("Successfully left game"));
    }

    /**
     * Allows the remaining connected player to claim a win immediately when all
     * other non-out players are disconnected.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param gameId    game identifier
     * @param playerPrincipal authenticated player principal
     * @return success confirmation
     */
    @PostMapping("/{gameId}/claim-win")
    public ResponseEntity<ApiResponse<Void>> claimWin(
            @PathVariable String gameId,
            @AuthenticationPrincipal PlayerPrincipal playerPrincipal) {
        String playerName = playerPrincipal.playerName();
        logger.info("Player {} attempting to claim win in game {}", playerName, gameId);

        // Security check: ensure the token is actually for THIS game/room
        if (!playerPrincipal.roomId().equals(gameId)) {
            throw new UnauthorisedActionException("Token is not valid for this game.");
        }

        gameLifecycleService.claimWin(gameId, playerName);
        logger.info("Player {} successfully claimed win in game {}", playerName, gameId);
        return ResponseEntity.ok(ApiResponse.success("Win claimed successfully"));
    }
}
