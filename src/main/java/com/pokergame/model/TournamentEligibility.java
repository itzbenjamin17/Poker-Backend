package com.pokergame.model;

import java.util.List;
import com.pokergame.enums.GamePhase;

/** Shared phase-aware rules for tournament contenders and disconnect claims. */
public final class TournamentEligibility {
    private TournamentEligibility() { }

    public static boolean isContender(Game game, Player player) {
        if (player.getIsOut()) return false;
        boolean unsettled = game.getCurrentPhase() != GamePhase.SHOWDOWN
                || !game.isHandSettled();
        return unsettled || player.getChips() > 0;
    }

    /**
     * Counts the players in the game who still qualify as tournament contenders.
     *
     * @param game active game aggregate
     * @return number of contending players
     */
    public static int countContenders(Game game) {
        return (int) game.getPlayers().stream().filter(player -> isContender(game, player)).count();
    }

    public static boolean canClaim(Game game) {
        if (game.isGameOver() || (game.isHandSettled() && game.isTournamentOver())) return false;
        List<Player> contenders = game.getPlayers().stream().filter(player -> isContender(game, player)).toList();
        List<Player> connected = contenders.stream().filter(player -> !player.getIsDisconnected()).toList();
        return contenders.size() >= 2 && connected.size() == 1
                && contenders.stream().anyMatch(Player::getIsDisconnected);
    }
}
