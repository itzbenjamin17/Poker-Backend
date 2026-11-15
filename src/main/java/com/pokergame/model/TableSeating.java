package com.pokergame.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns table seating indices, blind assignments, and turn rotation within a {@link Game}.
 */
final class TableSeating {

    private int dealerPosition;
    private int smallBlindPosition;
    private int bigBlindPosition;
    private int currentPlayerPosition;
    private final List<String> handSeatOrder;
    private String dealerPlayerId;
    private String smallBlindPlayerId;
    private String bigBlindPlayerId;

    /**
     * Creates initial seating and blind positions for a new game.
     *
     * @param activePlayers active players seated at game start
     * @param tablePlayers  full table roster establishing hand seat order
     */
    TableSeating(List<Player> activePlayers, List<Player> tablePlayers) {
        this.dealerPosition = 0;
        updatePositionsForTableSize(activePlayers.size());
        this.handSeatOrder = new ArrayList<>(tablePlayers.stream().map(Player::getPlayerId).toList());
        this.dealerPlayerId = activePlayers.isEmpty() ? null : activePlayers.get(dealerPosition).getPlayerId();
    }

    /**
     * Reconstructs table seating state from saved snapshot fields.
     *
     * @param dealerPosition        index of the dealer in {@code activePlayers}
     * @param smallBlindPosition    index of the small blind in {@code activePlayers}
     * @param bigBlindPosition      index of the big blind in {@code activePlayers}
     * @param currentPlayerPosition index of the acting player in {@code activePlayers}
     * @param handSeatOrder         ordered player IDs for the current hand
     * @param dealerPlayerId        player ID holding the dealer button
     * @param smallBlindPlayerId    player ID holding the small blind
     * @param bigBlindPlayerId      player ID holding the big blind
     * @param tablePlayers          full table roster used if {@code handSeatOrder} is empty
     */
    TableSeating(
            int dealerPosition,
            int smallBlindPosition,
            int bigBlindPosition,
            int currentPlayerPosition,
            List<String> handSeatOrder,
            String dealerPlayerId,
            String smallBlindPlayerId,
            String bigBlindPlayerId,
            List<Player> tablePlayers) {
        this.dealerPosition = dealerPosition;
        this.smallBlindPosition = smallBlindPosition;
        this.bigBlindPosition = bigBlindPosition;
        this.currentPlayerPosition = currentPlayerPosition;
        this.handSeatOrder = (handSeatOrder != null && !handSeatOrder.isEmpty())
                ? new ArrayList<>(handSeatOrder)
                : new ArrayList<>(tablePlayers.stream().map(Player::getPlayerId).toList());
        this.dealerPlayerId = dealerPlayerId;
        this.smallBlindPlayerId = smallBlindPlayerId;
        this.bigBlindPlayerId = bigBlindPlayerId;
    }

    /**
     * Advances the dealer button, blinds, and first pre-flop actor clockwise for the next hand.
     *
     * @param activePlayers active players seated for the next hand
     */
    void advancePositions(List<Player> activePlayers) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return;
        }
        List<String> seatOrder = !handSeatOrder.isEmpty()
                ? handSeatOrder
                : activePlayers.stream().filter(Objects::nonNull).map(Player::getPlayerId).toList();

        int anchorIndex = dealerPlayerId != null ? seatOrder.indexOf(dealerPlayerId) : -1;
        int nextDealerPosition = 0;
        String nextDealerId = activePlayers.getFirst().getPlayerId();

        int seatCount = seatOrder.size();
        for (int offset = 1; offset <= seatCount; offset++) {
            String candidateId = seatOrder.get(Math.floorMod(anchorIndex + offset, seatCount));
            int activeIndex = indexOfActivePlayer(activePlayers, candidateId);
            if (activeIndex >= 0 && !activePlayers.get(activeIndex).getIsOut()) {
                nextDealerPosition = activeIndex;
                nextDealerId = candidateId;
                break;
            }
        }

        dealerPosition = nextDealerPosition;
        dealerPlayerId = nextDealerId;
        updatePositionsForTableSize(activePlayers.size());
        smallBlindPlayerId = null;
        bigBlindPlayerId = null;
    }

    /**
     * Normalizes blind indices for the current active table and records the blind player IDs.
     *
     * @param activePlayers active players seated for the hand
     */
    void assignBlindsForHand(List<Player> activePlayers) {
        normalizeBlindPositions(activePlayers);
        smallBlindPlayerId = activePlayers.get(smallBlindPosition).getPlayerId();
        bigBlindPlayerId = activePlayers.get(bigBlindPosition).getPlayerId();
    }

    /**
     * Moves the turn pointer to the first actionable player clockwise after the dealer for post-flop streets.
     *
     * @param activePlayers active players in the current hand
     */
    void moveToFirstPostFlopActor(List<Player> activePlayers) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            currentPlayerPosition = 0;
            return;
        }
        List<String> seatOrder = !handSeatOrder.isEmpty()
                ? handSeatOrder
                : activePlayers.stream().filter(Objects::nonNull).map(Player::getPlayerId).toList();
        int anchorIndex = dealerPlayerId != null ? seatOrder.indexOf(dealerPlayerId) : -1;
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
            if (candidate.canAct()) {
                currentPlayerPosition = activeIndex;
                return;
            }
        }
        currentPlayerPosition = 0;
    }

    /**
     * Removes {@code playerToRemove} from both rosters and reconciles dealer, blind, and turn indices.
     *
     * @param players        all seated players
     * @param activePlayers  active players in the current hand
     * @param playerToRemove player to remove
     */
    void removePlayer(List<Player> players, List<Player> activePlayers, Player playerToRemove) {
        if (playerToRemove == null) {
            return;
        }
        Player previousCurrent = null;
        if (activePlayers != null && !activePlayers.isEmpty()) {
            previousCurrent = activePlayers.get(normalizeIndex(activePlayers, currentPlayerPosition));
        }

        if (players != null) {
            players.remove(playerToRemove);
        }
        if (activePlayers != null) {
            activePlayers.remove(playerToRemove);
        }

        if (activePlayers == null || activePlayers.isEmpty()) {
            dealerPosition = 0;
            smallBlindPosition = 0;
            bigBlindPosition = 0;
            currentPlayerPosition = 0;
            return;
        }

        int anchoredDealerIndex = indexOfActivePlayer(activePlayers, dealerPlayerId);
        dealerPosition = anchoredDealerIndex >= 0
                ? anchoredDealerIndex
                : Math.max(0, Math.min(dealerPosition, activePlayers.size() - 1));

        if (smallBlindPlayerId != null) {
            smallBlindPosition = Math.max(0, indexOfActivePlayer(activePlayers, smallBlindPlayerId));
        } else {
            smallBlindPosition = normalizeIndex(activePlayers, smallBlindPosition);
        }
        if (bigBlindPlayerId != null) {
            bigBlindPosition = Math.max(0, indexOfActivePlayer(activePlayers, bigBlindPlayerId));
        } else {
            bigBlindPosition = normalizeIndex(activePlayers, bigBlindPosition);
        }

        // Preserve the current actor's turn when another seat departs.
        if (previousCurrent != null && !previousCurrent.equals(playerToRemove)) {
            int preservedIndex = activePlayers.indexOf(previousCurrent);
            if (preservedIndex >= 0) {
                currentPlayerPosition = preservedIndex;
                return;
            }
        }

        int newCurrentPos = normalizeIndex(activePlayers, currentPlayerPosition);
        Player current = activePlayers.get(newCurrentPos);
        if (!current.canAct()) {
            newCurrentPos = findNextActionableIndex(activePlayers, newCurrentPos);
        }
        currentPlayerPosition = newCurrentPos;
    }

    /**
     * Advances the turn pointer to the next active player who can act.
     *
     * @param activePlayers active players in the current hand
     */
    void nextPlayer(List<Player> activePlayers) {
        currentPlayerPosition = findNextActionableIndex(activePlayers, currentPlayerPosition);
    }

    /**
     * Returns the player whose turn it currently is without mutating seating state.
     *
     * @param activePlayers active players in the current hand
     * @return current player
     * @throws IllegalStateException if no active players exist
     */
    Player getCurrentPlayer(List<Player> activePlayers) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            throw new IllegalStateException("No active players available");
        }
        return activePlayers.get(normalizeIndex(activePlayers, currentPlayerPosition));
    }

    /**
     * Returns the player ID assigned as small blind for the current hand.
     *
     * @param activePlayers active players in the current hand
     * @return small blind player ID, or {@code null} when no active players exist
     */
    String getSmallBlindPlayerId(List<Player> activePlayers) {
        if (smallBlindPlayerId != null) {
            return smallBlindPlayerId;
        }
        if (activePlayers == null || activePlayers.isEmpty()) {
            return null;
        }
        return activePlayers.get(normalizeIndex(activePlayers, smallBlindPosition)).getPlayerId();
    }

    /**
     * Returns the player ID assigned as big blind for the current hand.
     *
     * @param activePlayers active players in the current hand
     * @return big blind player ID, or {@code null} when no active players exist
     */
    String getBigBlindPlayerId(List<Player> activePlayers) {
        if (bigBlindPlayerId != null) {
            return bigBlindPlayerId;
        }
        if (activePlayers == null || activePlayers.isEmpty()) {
            return null;
        }
        return activePlayers.get(normalizeIndex(activePlayers, bigBlindPosition)).getPlayerId();
    }

    int getDealerPosition() {
        return dealerPosition;
    }

    int getSmallBlindPosition() {
        return smallBlindPosition;
    }

    int getBigBlindPosition() {
        return bigBlindPosition;
    }

    int getCurrentPlayerPosition() {
        return currentPlayerPosition;
    }

    List<String> getHandSeatOrderSnapshot() {
        return List.copyOf(handSeatOrder);
    }

    String getDealerPlayerId() {
        return dealerPlayerId;
    }

    private void updatePositionsForTableSize(int tableSize) {
        if (tableSize <= 0) {
            smallBlindPosition = 0;
            bigBlindPosition = 0;
            currentPlayerPosition = 0;
            return;
        }
        // Heads-up poker assigns the small blind and first pre-flop turn to the dealer.
        if (tableSize == 2) {
            smallBlindPosition = dealerPosition;
            bigBlindPosition = (dealerPosition + 1) % tableSize;
            currentPlayerPosition = dealerPosition;
            return;
        }
        smallBlindPosition = (dealerPosition + 1) % tableSize;
        bigBlindPosition = (smallBlindPosition + 1) % tableSize;
        currentPlayerPosition = (bigBlindPosition + 1) % tableSize;
    }

    private void normalizeBlindPositions(List<Player> activePlayers) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            smallBlindPosition = 0;
            bigBlindPosition = 0;
            return;
        }
        int tableSize = activePlayers.size();
        smallBlindPosition = Math.floorMod(smallBlindPosition, tableSize);
        bigBlindPosition = Math.floorMod(bigBlindPosition, tableSize);
    }

    private static int indexOfActivePlayer(List<Player> activePlayers, String playerId) {
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

    private static int normalizeIndex(List<Player> activePlayers, int position) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return 0;
        }
        return Math.floorMod(position, activePlayers.size());
    }

    private static int findNextActionableIndex(List<Player> activePlayers, int currentPosition) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return 0;
        }
        int originalPosition = normalizeIndex(activePlayers, currentPosition);
        int next = originalPosition;
        do {
            next = (next + 1) % activePlayers.size();
        } while (!activePlayers.get(next).canAct()
                && next != originalPosition);
        return next;
    }
}
