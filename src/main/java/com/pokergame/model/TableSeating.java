package com.pokergame.model;

import java.util.List;
import java.util.Objects;

/**
 * Package-private collaborator providing pure table seating, blind position,
 * dealer anchor, and turn rotation calculations.
 * Mutable state remains owned by {@link Game}.
 */
final class TableSeating {

    private TableSeating() {
        // Pure utility collaborator; prevent instantiation
    }

    /**
     * Positions calculated for a table of a given size with a specified dealer position.
     */
    record TablePositions(int smallBlindPosition, int bigBlindPosition, int currentPlayerPosition) {}

    /**
     * Normalized small blind and big blind index pair.
     */
    record BlindPositions(int smallBlindPosition, int bigBlindPosition) {}

    /**
     * Result of advancing table positions for a new hand.
     */
    record PositionRotationResult(
            int dealerPosition,
            String dealerAnchorPlayerId,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition) {}

    /**
     * Alias for position rotation result used in scheduling and state views.
     */
    record SeatingPositions(
            int dealerPosition,
            String dealerAnchorPlayerId,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition) {}

    /**
     * Result of reconciling position indices after a player leaves the table.
     */
    record PlayerRemovalResult(
            int dealerPosition,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition) {}

    /**
     * Seating result record used during player removal reconciliation.
     */
    record RemovalSeatingResult(
            int dealerPosition,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition) {}

    /**
     * Finds the index of a player by ID in the given active players list.
     *
     * @param activePlayers active player list
     * @param playerId player ID to find
     * @return index in activePlayers, or -1 if not found
     */
    static int indexOfActivePlayer(List<Player> activePlayers, String playerId) {
        if (activePlayers == null || playerId == null) {
            return -1;
        }
        for (int i = 0; i < activePlayers.size(); i++) {
            Player p = activePlayers.get(i);
            if (p != null && playerId.equals(p.getPlayerId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Normalizes the current player position to ensure it is within bounds [0, size - 1].
     *
     * @param activePlayers active player list
     * @param currentPlayerPosition position index to normalize
     * @return normalized position index
     */
    static int normalizeCurrentPlayerPosition(List<Player> activePlayers, int currentPlayerPosition) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return 0;
        }
        return Math.floorMod(currentPlayerPosition, activePlayers.size());
    }

    /**
     * Computes blind and current player positions for a table size and dealer index.
     * Heads-up poker (size == 2) has the dealer post small blind and act first pre-flop.
     *
     * @param tableSize number of active players
     * @param dealerPosition index of the dealer
     * @return computed table positions
     */
    static TablePositions computePositionsForTable(int tableSize, int dealerPosition) {
        if (tableSize <= 0) {
            return new TablePositions(0, 0, 0);
        }
        if (tableSize == 2) {
            return new TablePositions(dealerPosition, (dealerPosition + 1) % tableSize, dealerPosition);
        }
        int sb = (dealerPosition + 1) % tableSize;
        int bb = (sb + 1) % tableSize;
        int current = (bb + 1) % tableSize;
        return new TablePositions(sb, bb, current);
    }

    /**
     * Computes blind and current player positions from active players and dealer position.
     *
     * @param activePlayers active player list
     * @param dealerPosition index of the dealer
     * @return computed table positions
     */
    static TablePositions updatePositionsForCurrentTable(List<Player> activePlayers, int dealerPosition) {
        int tableSize = (activePlayers == null) ? 0 : activePlayers.size();
        return computePositionsForTable(tableSize, dealerPosition);
    }

    /**
     * Computes table positions preserving blind identities when available.
     *
     * @param players active or table player list
     * @param dealerAnchorPlayerId ID of the anchored dealer
     * @param smallBlindPlayerId ID of current small blind player, if known
     * @param bigBlindPlayerId ID of current big blind player, if known
     * @return computed table positions
     */
    static TablePositions updatePositionsForCurrentTable(
            List<Player> players,
            String dealerAnchorPlayerId,
            String smallBlindPlayerId,
            String bigBlindPlayerId) {
        if (players == null || players.isEmpty()) {
            return new TablePositions(0, 0, 0);
        }
        int dealerPos = indexOfActivePlayer(players, dealerAnchorPlayerId);
        if (dealerPos < 0) {
            dealerPos = 0;
        }
        TablePositions base = computePositionsForTable(players.size(), dealerPos);
        int sbPos = base.smallBlindPosition();
        int bbPos = base.bigBlindPosition();
        if (smallBlindPlayerId != null) {
            int idx = indexOfActivePlayer(players, smallBlindPlayerId);
            if (idx >= 0) {
                sbPos = idx;
            }
        }
        if (bigBlindPlayerId != null) {
            int idx = indexOfActivePlayer(players, bigBlindPlayerId);
            if (idx >= 0) {
                bbPos = idx;
            }
        }
        return new TablePositions(sbPos, bbPos, base.currentPlayerPosition());
    }

    /**
     * Normalizes small and big blind positions to be within bounds of tableSize.
     *
     * @param tableSize table size
     * @param smallBlindPosition small blind index
     * @param bigBlindPosition big blind index
     * @return normalized blind positions
     */
    static BlindPositions normalizeBlindPositions(int tableSize, int smallBlindPosition, int bigBlindPosition) {
        if (tableSize <= 0) {
            return new BlindPositions(0, 0);
        }
        return new BlindPositions(
                Math.floorMod(smallBlindPosition, tableSize),
                Math.floorMod(bigBlindPosition, tableSize)
        );
    }

    /**
     * Normalizes small and big blind positions to be within bounds of active players list.
     *
     * @param activePlayers active player list
     * @param smallBlindPosition small blind index
     * @param bigBlindPosition big blind index
     * @return normalized blind positions
     */
    static BlindPositions normalizeBlindPositions(List<Player> activePlayers, int smallBlindPosition, int bigBlindPosition) {
        int tableSize = (activePlayers == null) ? 0 : activePlayers.size();
        return normalizeBlindPositions(tableSize, smallBlindPosition, bigBlindPosition);
    }

    /**
     * Computes blind positions from active players and dealer position.
     *
     * @param players active player list
     * @param dealerPosition dealer position index
     * @return blind positions
     */
    static BlindPositions normalizeBlindPositions(List<Player> players, int dealerPosition) {
        if (players == null || players.isEmpty()) {
            return new BlindPositions(0, 0);
        }
        TablePositions positions = computePositionsForTable(players.size(), dealerPosition);
        return new BlindPositions(positions.smallBlindPosition(), positions.bigBlindPosition());
    }

    /**
     * Advances dealer, small blind, big blind, and current player positions clockwise
     * around the table for the next hand, respecting the stable seat order and dealer anchor.
     *
     * @param activePlayers active player list
     * @param handSeatOrder immutable seating order of player IDs
     * @param dealerAnchorPlayerId ID of the current dealer anchor
     * @return rotation result containing updated position indices and new dealer anchor ID
     */
    static PositionRotationResult advancePositions(
            List<Player> activePlayers,
            List<String> handSeatOrder,
            String dealerAnchorPlayerId) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return new PositionRotationResult(0, null, 0, 0, 0);
        }

        List<String> seatOrder = (handSeatOrder != null && !handSeatOrder.isEmpty())
                ? handSeatOrder
                : activePlayers.stream().filter(Objects::nonNull).map(Player::getPlayerId).toList();

        int anchorIndex = dealerAnchorPlayerId != null ? seatOrder.indexOf(dealerAnchorPlayerId) : -1;
        int newDealerPosition = 0;
        String newDealerAnchorPlayerId = activePlayers.getFirst().getPlayerId();

        int seatCount = seatOrder.size();
        for (int offset = 1; offset <= seatCount; offset++) {
            String candidateId = seatOrder.get(Math.floorMod(anchorIndex + offset, seatCount));
            int activeIndex = indexOfActivePlayer(activePlayers, candidateId);
            if (activeIndex >= 0 && !activePlayers.get(activeIndex).getIsOut()) {
                newDealerPosition = activeIndex;
                newDealerAnchorPlayerId = candidateId;
                break;
            }
        }

        TablePositions positions = computePositionsForTable(activePlayers.size(), newDealerPosition);
        return new PositionRotationResult(
                newDealerPosition,
                newDealerAnchorPlayerId,
                positions.smallBlindPosition(),
                positions.bigBlindPosition(),
                positions.currentPlayerPosition()
        );
    }

    /**
     * Advances positions using active players, seat order, anchor ID, and current dealer position fallback.
     */
    static PositionRotationResult advancePositions(
            List<Player> activePlayers,
            List<String> handSeatOrder,
            String currentDealerAnchorId,
            int currentDealerPosition) {
        return advancePositions(activePlayers, handSeatOrder, currentDealerAnchorId);
    }

    /**
     * Advances positions using current player list and indices.
     *
     * @param players player list
     * @param dealerPosition current dealer position
     * @param smallBlindPosition current small blind position
     * @param bigBlindPosition current big blind position
     * @param currentPlayerPosition current player position
     * @return rotation result
     */
    static PositionRotationResult advancePositions(
            List<Player> players,
            int dealerPosition,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition) {
        if (players == null || players.isEmpty()) {
            return new PositionRotationResult(0, null, 0, 0, 0);
        }
        List<String> seatOrder = players.stream().filter(Objects::nonNull).map(Player::getPlayerId).toList();
        String currentAnchor = (dealerPosition >= 0 && dealerPosition < players.size() && players.get(dealerPosition) != null)
                ? players.get(dealerPosition).getPlayerId()
                : null;
        return advancePositions(players, seatOrder, currentAnchor);
    }

    /**
     * Advances to the next active player who has not folded and is not all-in.
     *
     * @param activePlayers active player list
     * @param currentPlayerPosition current player index
     * @return next actionable or next available player index
     */
    static int nextPlayer(List<Player> activePlayers, int currentPlayerPosition) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return 0;
        }
        int normalized = normalizeCurrentPlayerPosition(activePlayers, currentPlayerPosition);
        int originalPosition = normalized;
        int next = normalized;
        do {
            next = (next + 1) % activePlayers.size();
        } while ((activePlayers.get(next).getHasFolded() || activePlayers.get(next).getIsAllIn())
                && next != originalPosition);
        return next;
    }

    /**
     * Alias for {@link #nextPlayer(List, int)}.
     */
    static int nextPlayerIndex(List<Player> activePlayers, int currentPlayerPosition) {
        return nextPlayer(activePlayers, currentPlayerPosition);
    }

    /**
     * Determines the first actionable seat clockwise after the dealer anchor for post-flop betting.
     *
     * @param activePlayers active player list
     * @param handSeatOrder immutable seating order of player IDs
     * @param dealerAnchorPlayerId ID of dealer anchor
     * @param dealerPosition fallback dealer index
     * @return active index of first actionable seat, or 0 fallback
     */
    static int firstActionableSeatAfterDealerAnchor(
            List<Player> activePlayers,
            List<String> handSeatOrder,
            String dealerAnchorPlayerId,
            int dealerPosition) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return 0;
        }
        List<String> seatOrder = (handSeatOrder != null && !handSeatOrder.isEmpty())
                ? handSeatOrder
                : activePlayers.stream().filter(Objects::nonNull).map(Player::getPlayerId).toList();
        int anchorIndex = dealerAnchorPlayerId != null ? seatOrder.indexOf(dealerAnchorPlayerId) : -1;
        if (anchorIndex < 0) {
            anchorIndex = Math.max(0, Math.min(dealerPosition, seatOrder.size() - 1));
        }
        int seatCount = seatOrder.size();
        for (int offset = 1; offset <= seatCount; offset++) {
            String candidateId = seatOrder.get(Math.floorMod(anchorIndex + offset, seatCount));
            int activeIndex = indexOfActivePlayer(activePlayers, candidateId);
            if (activeIndex < 0) {
                continue;
            }
            Player candidate = activePlayers.get(activeIndex);
            if (!candidate.getHasFolded() && !candidate.getIsAllIn() && !candidate.getIsOut()) {
                return activeIndex;
            }
        }
        return 0;
    }

    /**
     * Determines the first actionable seat clockwise after the dealer anchor.
     *
     * @param players player list
     * @param dealerAnchorPlayerId ID of dealer anchor
     * @return active index of first actionable seat, or 0 fallback
     */
    static int firstActionableSeatAfterDealerAnchor(List<Player> players, String dealerAnchorPlayerId) {
        if (players == null || players.isEmpty()) {
            return 0;
        }
        List<String> seatOrder = players.stream().filter(Objects::nonNull).map(Player::getPlayerId).toList();
        return firstActionableSeatAfterDealerAnchor(players, seatOrder, dealerAnchorPlayerId, 0);
    }

    /**
     * Reconciles table seating and turn order when a player is removed from the table.
     *
     * @param activePlayers active players list after removal
     * @param playerToRemove player instance removed
     * @param previousCurrent player instance who had turn prior to removal
     * @param currentDealerPosition dealer position before removal
     * @param smallBlindPosition small blind position fallback
     * @param bigBlindPosition big blind position fallback
     * @param dealerAnchorPlayerId ID of dealer anchor
     * @param smallBlindPlayerId ID of small blind player, if known
     * @param bigBlindPlayerId ID of big blind player, if known
     * @param currentPlayerPosition current player position before removal
     * @return reconciled positions
     */
    static RemovalSeatingResult reconcileSeatingAfterRemoval(
            List<Player> activePlayers,
            Player playerToRemove,
            Player previousCurrent,
            int currentDealerPosition,
            int smallBlindPosition,
            int bigBlindPosition,
            String dealerAnchorPlayerId,
            String smallBlindPlayerId,
            String bigBlindPlayerId,
            int currentPlayerPosition) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return new RemovalSeatingResult(0, 0, 0, 0);
        }

        int dealerPos = currentDealerPosition;
        int anchoredDealerIndex = indexOfActivePlayer(activePlayers, dealerAnchorPlayerId);
        if (anchoredDealerIndex >= 0) {
            dealerPos = anchoredDealerIndex;
        } else {
            dealerPos = Math.max(0, Math.min(dealerPos, activePlayers.size() - 1));
        }

        int sbPos = (smallBlindPlayerId != null)
                ? indexOfActivePlayer(activePlayers, smallBlindPlayerId)
                : smallBlindPosition;
        int bbPos = (bigBlindPlayerId != null)
                ? indexOfActivePlayer(activePlayers, bigBlindPlayerId)
                : bigBlindPosition;

        if (playerToRemove == null) {
            return new RemovalSeatingResult(dealerPos, Math.max(0, sbPos), Math.max(0, bbPos), currentPlayerPosition);
        }

        // Preserve the same player turn when someone else leaves and that player is still present.
        if (previousCurrent != null && !previousCurrent.equals(playerToRemove)) {
            int preservedIndex = activePlayers.indexOf(previousCurrent);
            if (preservedIndex >= 0) {
                return new RemovalSeatingResult(
                        dealerPos,
                        Math.max(0, sbPos),
                        Math.max(0, bbPos),
                        preservedIndex
                );
            }
        }

        if (smallBlindPlayerId != null) {
            sbPos = Math.max(0, indexOfActivePlayer(activePlayers, smallBlindPlayerId));
        }
        if (bigBlindPlayerId != null) {
            bbPos = Math.max(0, indexOfActivePlayer(activePlayers, bigBlindPlayerId));
        }

        int newCurrentPos = normalizeCurrentPlayerPosition(activePlayers, currentPlayerPosition);
        Player current = activePlayers.get(newCurrentPos);
        if (current.getHasFolded() || current.getIsAllIn()) {
            newCurrentPos = nextPlayer(activePlayers, newCurrentPos);
        }

        return new RemovalSeatingResult(dealerPos, Math.max(0, sbPos), Math.max(0, bbPos), newCurrentPos);
    }

    /**
     * Overload for {@link #reconcileSeatingAfterRemoval} without blind index fallbacks.
     */
    static RemovalSeatingResult reconcileSeatingAfterRemoval(
            List<Player> activePlayers,
            Player playerToRemove,
            Player previousCurrent,
            int currentDealerPosition,
            String dealerAnchorPlayerId,
            String smallBlindPlayerId,
            String bigBlindPlayerId,
            int currentPlayerPosition) {
        return reconcileSeatingAfterRemoval(
                activePlayers,
                playerToRemove,
                previousCurrent,
                currentDealerPosition,
                0,
                0,
                dealerAnchorPlayerId,
                smallBlindPlayerId,
                bigBlindPlayerId,
                currentPlayerPosition
        );
    }

    /**
     * Removes player from collections and reconciles table seating and turn order.
     *
     * @param players table players list (modified to remove player)
     * @param activePlayers active players list (modified to remove player)
     * @param playerToRemove player instance to remove
     * @param dealerPosition current dealer position
     * @param smallBlindPosition current small blind position
     * @param bigBlindPosition current big blind position
     * @param currentPlayerPosition current player position
     * @param dealerAnchorPlayerId ID of dealer anchor
     * @param smallBlindPlayerId ID of small blind player, if known
     * @param bigBlindPlayerId ID of big blind player, if known
     * @return reconciled positions
     */
    static PlayerRemovalResult removePlayerFromGame(
            List<Player> players,
            List<Player> activePlayers,
            Player playerToRemove,
            int dealerPosition,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition,
            String dealerAnchorPlayerId,
            String smallBlindPlayerId,
            String bigBlindPlayerId) {
        if (playerToRemove == null) {
            return new PlayerRemovalResult(dealerPosition, smallBlindPosition, bigBlindPosition, currentPlayerPosition);
        }

        Player previousCurrent = null;
        if (activePlayers != null && !activePlayers.isEmpty()) {
            int normCur = normalizeCurrentPlayerPosition(activePlayers, currentPlayerPosition);
            previousCurrent = activePlayers.get(normCur);
        }

        if (players != null) {
            players.remove(playerToRemove);
        }
        if (activePlayers != null) {
            activePlayers.remove(playerToRemove);
        }

        RemovalSeatingResult res = reconcileSeatingAfterRemoval(
                activePlayers,
                playerToRemove,
                previousCurrent,
                dealerPosition,
                smallBlindPosition,
                bigBlindPosition,
                dealerAnchorPlayerId,
                smallBlindPlayerId,
                bigBlindPlayerId,
                currentPlayerPosition
        );

        return new PlayerRemovalResult(
                res.dealerPosition(),
                res.smallBlindPosition(),
                res.bigBlindPosition(),
                res.currentPlayerPosition()
        );
    }

    /**
     * Overload accepting playerId instead of Player instance.
     */
    static PlayerRemovalResult removePlayerFromGame(
            List<Player> players,
            List<Player> activePlayers,
            String playerId,
            int dealerPosition,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition,
            String dealerAnchorPlayerId,
            String smallBlindPlayerId,
            String bigBlindPlayerId) {
        if (playerId == null) {
            return new PlayerRemovalResult(dealerPosition, smallBlindPosition, bigBlindPosition, currentPlayerPosition);
        }
        Player playerToRemove = null;
        if (players != null) {
            for (Player p : players) {
                if (p != null && playerId.equals(p.getPlayerId())) {
                    playerToRemove = p;
                    break;
                }
            }
        }
        if (playerToRemove == null && activePlayers != null) {
            for (Player p : activePlayers) {
                if (p != null && playerId.equals(p.getPlayerId())) {
                    playerToRemove = p;
                    break;
                }
            }
        }
        if (playerToRemove == null) {
            playerToRemove = new Player(playerId, playerId, 0);
        }
        return removePlayerFromGame(
                players,
                activePlayers,
                playerToRemove,
                dealerPosition,
                smallBlindPosition,
                bigBlindPosition,
                currentPlayerPosition,
                dealerAnchorPlayerId,
                smallBlindPlayerId,
                bigBlindPlayerId
        );
    }

    /**
     * Overload matching 8-argument signature without blind IDs.
     */
    static PlayerRemovalResult removePlayerFromGame(
            List<Player> players,
            List<Player> activePlayers,
            String playerId,
            int dealerPosition,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition,
            String dealerAnchorPlayerId) {
        return removePlayerFromGame(
                players,
                activePlayers,
                playerId,
                dealerPosition,
                smallBlindPosition,
                bigBlindPosition,
                currentPlayerPosition,
                dealerAnchorPlayerId,
                null,
                null
        );
    }
}
