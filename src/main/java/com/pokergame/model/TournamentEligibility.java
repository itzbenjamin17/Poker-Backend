package com.pokergame.model;

import java.util.List;

/** Shared phase-aware rules for tournament contenders and disconnect claims. */
public final class TournamentEligibility {
    private TournamentEligibility() { }

    public static boolean isContender(Game game, Player player) {
        if (player.getIsOut()) return false;
        boolean unsettled = game.getCurrentPhase() != com.pokergame.enums.GamePhase.SHOWDOWN
                || game.getLastSettlementResult() == null || !game.getLastSettlementResult().settled();
        return unsettled || player.getChips() > 0;
    }

    public static boolean canClaim(Game game) {
        if (game.isGameOver() || (game.getLastSettlementResult() != null
                && game.getLastSettlementResult().settled() && game.isTournamentOver())) return false;
        List<Player> contenders = game.getPlayers().stream().filter(player -> isContender(game, player)).toList();
        List<Player> connected = contenders.stream().filter(player -> !player.getIsDisconnected()).toList();
        return contenders.size() >= 2 && connected.size() == 1
                && contenders.stream().anyMatch(Player::getIsDisconnected);
    }
}
