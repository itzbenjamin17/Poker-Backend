package com.pokergame.controller;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.PlayerActionService;
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

    private final PlayerActionService playerActionService;

    // Dependency Injection
    public GameController(PlayerActionService playerActionService, GameLifecycleService gameLifecycleService) {
        this.playerActionService = playerActionService;
        this.gameLifecycleService = gameLifecycleService;
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
}
