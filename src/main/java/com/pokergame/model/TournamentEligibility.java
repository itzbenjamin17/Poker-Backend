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

    /**
     * Checks whether the specified player may claim an immediate disconnect win.
     *
     * @param game       game being evaluated
     * @param playerName prospective claimant name
     * @return {@code true} if an immediate disconnect win is legal for {@code playerName}
     */
    public static boolean canPlayerClaimWin(Game game, String playerName) {
        return canClaim(game) && game.getPlayers().stream()
                .anyMatch(player -> player.getName().equals(playerName) && isContender(game, player)
                        && !player.getIsDisconnected());
    }

    /**
     * Returns the names of all disconnected contenders other than the specified claimant.
     *
     * @param game       game being evaluated
     * @param playerName claimant to exclude
     * @return list of disconnected opponent contender names
     */
    public static List<String> disconnectedContenderNamesExcept(Game game, String playerName) {
        return game.getPlayers().stream()
                .filter(player -> !player.getName().equals(playerName))
                .filter(player -> isContender(game, player) && player.getIsDisconnected())
                .map(Player::getName)
                .toList();
    }
}
