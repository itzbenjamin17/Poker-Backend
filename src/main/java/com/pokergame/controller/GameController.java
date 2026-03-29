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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * REST controller for active poker game operations.
 * Manages player actions and game departures using WebSocket for real-time
 * updates.
 */
@RestController
@RequestMapping("/game")
public class GameController {
    private static final Logger logger = LoggerFactory.getLogger(GameController.class);

    private final GameLifecycleService gameLifecycleService;

    private final GameStateService gameStateService;

    private final PlayerActionService playerActionService;

    // Dependency Injection
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
     * @param principal authenticated player (from JWT)
     * @return latest game-state snapshot
     */
    @GetMapping("/{gameId}/state")
    public ResponseEntity<PublicGameStateResponse> getGameState(
            @PathVariable String gameId,
            Principal principal) {
        String playerName = principal.getName();
        logger.debug("Player {} requested game state for {}", playerName, gameId);

        Game game = getAuthorisedGame(gameId, playerName);

        return ResponseEntity.ok(gameStateService.getPublicGameStateSnapshot(gameId, game));
    }

    /**
     * Fetches the private state for the authenticated player (hole cards).
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param gameId    game identifier
     * @param principal authenticated player (from JWT)
     * @return private player-state snapshot
     */
    @GetMapping("/{gameId}/private-state")
    public ResponseEntity<PrivatePlayerState> getPrivateState(
            @PathVariable String gameId,
            Principal principal) {
        String playerName = principal.getName();
        logger.debug("Player {} requested private state for {}", playerName, gameId);

        Game game = getAuthorisedGame(gameId, playerName);

        return ResponseEntity.ok(gameStateService.getPrivatePlayerStateSnapshot(game, playerName));
    }

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
     * Uses WebSocket messaging for real-time updates.
     *
     * @param gameId        game identifier
     * @param actionRequest action type and amount
     * @param principal     authenticated player (from JWT)
     */
    @MessageMapping("/{gameId}/action")
    public void performAction(
            @DestinationVariable String gameId,
            @Payload PlayerActionRequest actionRequest,
            Principal principal) {
        String playerName = principal.getName();
        logger.info("Processing player action for game {} by {}: {}", gameId, playerName, actionRequest);
        playerActionService.processPlayerAction(gameId, actionRequest, playerName);
        logger.debug("Player action processed successfully for game {}", gameId);
    }

    /**
     * Removes a player from an active game. Hand is automatically folded if in a
     * round.
     * SECURED ENDPOINT - Requires JWT token.
     *
     * @param gameId    game identifier
     * @param principal authenticated player (from JWT)
     * @return success confirmation
     */
    @PostMapping("/{gameId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveGame(
            @PathVariable String gameId,
            Principal principal) {
        String playerName = principal.getName();
        logger.info("Player {} requesting to leave game {}", playerName, gameId);
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
     * @param principal authenticated player (from JWT)
     * @return success confirmation
     */
    @PostMapping("/{gameId}/claim-win")
    public ResponseEntity<ApiResponse<Void>> claimWin(
            @PathVariable String gameId,
            Principal principal) {
        String playerName = principal.getName();
        logger.info("Player {} attempting to claim win in game {}", playerName, gameId);
        gameLifecycleService.claimWin(gameId, playerName);
        logger.info("Player {} successfully claimed win in game {}", playerName, gameId);
        return ResponseEntity.ok(ApiResponse.success("Win claimed successfully"));
    }
}
