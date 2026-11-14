package com.pokergame.model;

import com.pokergame.enums.GamePhase;

import java.util.Comparator;
import java.util.List;

/**
 * Shared phase-aware rules for contending players, disconnect win claims, game-decided checks,
 * and winner resolution.
 */
public final class GameEligibility {

    private GameEligibility() {
    }

    /**
     * Returns whether {@code player} still qualifies as a contender in {@code game}.
     *
     * @param game   active game aggregate
     * @param player player to evaluate
     * @return {@code true} if the player is not out and either the hand is unsettled or they hold chips
     */
    public static boolean isContender(Game game, Player player) {
        if (player.getIsOut()) {
            return false;
        }
        boolean unsettled = game.getCurrentPhase() != GamePhase.SHOWDOWN || !game.isHandSettled();
        return unsettled || player.getChips() > 0;
    }

    /**
     * Counts the players in {@code game} who still qualify as contenders.
     *
     * @param game active game aggregate
     * @return number of contending players
     */
    public static int countContenders(Game game) {
        return (int) game.getPlayers().stream().filter(player -> isContender(game, player)).count();
    }

    /**
     * Returns whether at least one connected contender remains while all other contenders are disconnected.
     *
     * @param game active game aggregate
     * @return {@code true} if an immediate disconnect win claim is currently possible
     */
    public static boolean canClaim(Game game) {
        if (game.isGameOver() || (game.isHandSettled() && game.isGameDecided())) {
            return false;
        }
        List<Player> contenders = game.getPlayers().stream().filter(player -> isContender(game, player)).toList();
        List<Player> connected = contenders.stream().filter(player -> !player.getIsDisconnected()).toList();
        return contenders.size() >= 2 && connected.size() == 1
                && contenders.stream().anyMatch(Player::getIsDisconnected);
    }

    /**
     * Checks whether {@code playerName} may claim an immediate disconnect win.
     *
     * @param game       game being evaluated
     * @param playerName prospective claimant name
     * @return {@code true} if an immediate disconnect win is legal for {@code playerName}
     */
    public static boolean canPlayerClaimWin(Game game, String playerName) {
        return canClaim(game) && game.getPlayers().stream()
                .anyMatch(player -> player.getName().equals(playerName)
                        && isContender(game, player)
                        && !player.getIsDisconnected());
    }

    /**
     * Returns the names of all disconnected contenders other than {@code playerName}.
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

    /**
     * Returns whether one or zero non-eliminated players with chips remain.
     *
     * @param players table player roster
     * @return {@code true} if the game is decided
     */
    public static boolean isGameDecided(List<Player> players) {
        if (players == null) {
            return true;
        }
        long playersWithChips = players.stream()
                .filter(player -> !player.getIsOut() && player.getChips() > 0)
                .count();
        return playersWithChips <= 1;
    }

    /**
     * Resolves the game winner, preferring {@code preferredWinner} when seated, otherwise
     * choosing by chip count and falling back to the first connected non-out or seated player.
     *
     * @param game            finished or settling game
     * @param preferredWinner explicit forfeit survivor, or {@code null} for chip-based resolution
     * @return resolved winner, or {@code null} if the roster is empty
     */
    public static Player resolveWinnerOrFallback(Game game, Player preferredWinner) {
        List<Player> players = game.getPlayers();
        if (players == null || players.isEmpty()) {
            return null;
        }
        Player winner = preferredWinner != null && players.contains(preferredWinner)
                ? preferredWinner
                : resolveChipWinner(players);
        if (winner != null) {
            return winner;
        }
        return players.stream()
                .filter(player -> !player.getIsOut() && !player.getIsDisconnected())
                .findFirst()
                .orElse(players.getFirst());
    }

    private static Player resolveChipWinner(List<Player> players) {
        return players.stream()
                .filter(player -> !player.getIsOut())
                .max(Comparator.comparingInt(Player::getChips)
                        .thenComparing(Comparator.comparingInt(players::indexOf).reversed()))
                .orElse(null);
    }
}
