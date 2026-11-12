package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;

import java.util.Set;

/**
 * Package-private collaborator for terminal game settlement, frozen result capture,
 * and post-game review authorization.
 */
final class GameTerminalSettlement {

    private final GameStateService gameStateService;
    private final GameTimerRegistry runtimeTimers;

    GameTerminalSettlement(GameStateService gameStateService, GameTimerRegistry runtimeTimers) {
        this.gameStateService = gameStateService;
        this.runtimeTimers = runtimeTimers;
    }

    /** Result of attempting to freeze and settle a terminal game under the game lock. */
    record SettlementOutcome(boolean settledNow, Player winner, GameEndResponse event) { }

    /**
     * Settles an ending game under the caller's room coordination lease, cancels
     * non-cleanup runtime timers, clears retained hand settlement, and freezes the
     * terminal {@link GameEndResponse}.
     */
    SettlementOutcome settleUnderLock(
            String gameId,
            Game game,
            Room room,
            boolean isForfeit,
            Player preferredWinner,
            Set<String> forfeitedPlayers) {
        if (game.isForfeitPending() && preferredWinner == null) {
            return new SettlementOutcome(false, null, null);
        }
        if (game.isGameOver() && game.isTerminalCarryOverSettled()) {
            return new SettlementOutcome(false, null, null);
        }
        Player winner = game.settleTerminalState(preferredWinner, forfeitedPlayers);
        runtimeTimers.cancelAllForGame(gameId, true);
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
        }
        int maxPlayers = room == null ? 0 : room.getMaxPlayers();
        GameEndResponse event = game.getRetainedTerminalResult();
        if (event == null && gameStateService != null) {
            event = gameStateService.captureGameEnd(gameId, game, winner, isForfeit, maxPlayers);
            game.setRetainedTerminalResult(event);
        }
        return new SettlementOutcome(true, winner, event);
    }

    /** Freezes an abandonment result without paying out the unfinished pot. */
    GameEndResponse captureAbandonmentUnderLock(String gameId, Game game, Room room) {
        GameEndResponse event = null;
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
            int maxPlayers = room != null ? room.getMaxPlayers() : game.getPlayers().size();
            event = gameStateService.captureAbandonment(gameId, game, maxPlayers);
        }
        game.setRetainedTerminalResult(event);
        return event;
    }

    /**
     * Resolves or synthesizes the terminal {@link GameEndResponse} for an already
     * finished game and verifies that {@code playerName} is part of its terminal roster.
     * Read-only callers ({@code persistIfSynthesized == false}) project the fallback
     * response without incrementing {@code stateRevision} or mutating durable state.
     */
    GameEndResponse resolveTerminalResultForParticipant(
            String gameId,
            Game game,
            Room room,
            String playerName,
            boolean persistIfSynthesized) {
        GameEndResponse retained = game.getRetainedTerminalResult();
        if (retained == null) {
            Player winner = game.resolveWinnerOrFallback(null);
            if (winner != null && gameStateService != null) {
                int maxPlayers = room != null ? room.getMaxPlayers() : game.getPlayers().size();
                boolean isForfeit = game.isFallbackForfeit();
                if (persistIfSynthesized) {
                    retained = gameStateService.captureGameEnd(gameId, game, winner, isForfeit, maxPlayers);
                    game.setRetainedTerminalResult(retained);
                } else {
                    retained = gameStateService.projectGameEnd(gameId, game, winner, isForfeit, maxPlayers);
                }
            } else if (gameStateService != null) {
                int maxPlayers = room != null ? room.getMaxPlayers() : game.getPlayers().size();
                if (persistIfSynthesized) {
                    retained = gameStateService.captureAbandonment(gameId, game, maxPlayers);
                    game.setRetainedTerminalResult(retained);
                } else {
                    retained = gameStateService.projectAbandonment(gameId, game, maxPlayers);
                }
            }
        }
        if (retained == null) {
            throw new ResourceNotFoundException("Game not found");
        }
        if (!game.isParticipantInTerminalRoster(playerName, retained)) {
            throw new UnauthorisedActionException("You are not part of this game's terminal result.");
        }
        return retained;
    }

    /**
     * Ensures a retained terminal result is captured for a disconnect-claim winner if not already present.
     */
    GameEndResponse ensureRetainedClaimResult(String gameId, Game game, Room room, String playerName) {
        GameEndResponse retained = game.getRetainedTerminalResult();
        if (retained == null && game.isGameOver()) {
            Player claimant = game.findPlayerByName(playerName);
            if (claimant != null && gameStateService != null) {
                retained = gameStateService.captureGameEnd(gameId, game, claimant, true, room.getMaxPlayers());
                game.setRetainedTerminalResult(retained);
            }
        }
        return retained;
    }
}
