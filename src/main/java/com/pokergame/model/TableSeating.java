package com.pokergame.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns table seating order, blind assignments, and turn rotation by player ID within a {@link Game}.
 */
final class TableSeating {

    private final List<String> handSeatOrder;
    private String dealerPlayerId;
    private String smallBlindPlayerId;
    private String bigBlindPlayerId;
    private String currentPlayerId;

    /**
     * Creates initial seating and turn assignment for a new game.
     *
     * @param activePlayers active players seated at game start
     * @param tablePlayers  full table roster establishing hand seat order
     */
    TableSeating(List<Player> activePlayers, List<Player> tablePlayers) {
        this.handSeatOrder = toPlayerIds(tablePlayers);
        if (activePlayers == null || activePlayers.isEmpty()) {
            this.dealerPlayerId = null;
            this.smallBlindPlayerId = null;
            this.bigBlindPlayerId = null;
            this.currentPlayerId = null;
            return;
        }
        this.dealerPlayerId = activePlayers.getFirst().getPlayerId();
        this.smallBlindPlayerId = null;
        this.bigBlindPlayerId = null;
        this.currentPlayerId = firstPreFlopActorId(activePlayers, 0);
    }

    /**
     * Reconstructs table seating state from saved snapshot fields.
     *
     * @param handSeatOrder      ordered player IDs for the current hand
     * @param dealerPlayerId     player ID holding the dealer button
     * @param smallBlindPlayerId player ID holding the small blind
     * @param bigBlindPlayerId   player ID holding the big blind
     * @param currentPlayerId    player ID whose turn it currently is
     * @param tablePlayers       full table roster used if {@code handSeatOrder} is empty
     */
    TableSeating(
            List<String> handSeatOrder,
            String dealerPlayerId,
            String smallBlindPlayerId,
            String bigBlindPlayerId,
            String currentPlayerId,
            List<Player> tablePlayers) {
        this.handSeatOrder = (handSeatOrder != null && !handSeatOrder.isEmpty())
                ? new ArrayList<>(handSeatOrder)
                : toPlayerIds(tablePlayers);
        this.dealerPlayerId = dealerPlayerId;
        this.smallBlindPlayerId = smallBlindPlayerId;
        this.bigBlindPlayerId = bigBlindPlayerId;
        this.currentPlayerId = currentPlayerId;
    }

    /**
     * Advances the dealer button and first pre-flop actor clockwise for the next hand.
     *
     * @param activePlayers active players seated for the next hand
     */
    void advancePositions(List<Player> activePlayers) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return;
        }
        if (handSeatOrder.isEmpty()) {
            dealerPlayerId = activePlayers.getFirst().getPlayerId();
            currentPlayerId = firstPreFlopActorId(activePlayers, 0);
            smallBlindPlayerId = null;
            bigBlindPlayerId = null;
            return;
        }

        int anchorIndex = dealerPlayerId != null ? handSeatOrder.indexOf(dealerPlayerId) : -1;
        int nextDealerIndex = 0;
        String nextDealerId = activePlayers.getFirst().getPlayerId();

        int seatCount = handSeatOrder.size();
        for (int offset = 1; offset <= seatCount; offset++) {
            String candidateId = handSeatOrder.get(Math.floorMod(anchorIndex + offset, seatCount));
            int activeIndex = indexOfActivePlayer(activePlayers, candidateId);
            if (activeIndex >= 0 && !activePlayers.get(activeIndex).getIsOut()) {
                nextDealerIndex = activeIndex;
                nextDealerId = candidateId;
                break;
            }
        }

        dealerPlayerId = nextDealerId;
        currentPlayerId = firstPreFlopActorId(activePlayers, nextDealerIndex);
        smallBlindPlayerId = null;
        bigBlindPlayerId = null;
    }

    /**
     * Assigns and records the small and big blind player IDs for the current active table.
     *
     * @param activePlayers active players seated for the hand
     */
    void assignBlindsForHand(List<Player> activePlayers) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            smallBlindPlayerId = null;
            bigBlindPlayerId = null;
            return;
        }
        smallBlindPlayerId = deriveBlindPlayerId(activePlayers, false);
        bigBlindPlayerId = deriveBlindPlayerId(activePlayers, true);
    }

    /**
     * Moves the turn pointer to the first actionable player clockwise after the dealer for post-flop streets.
     *
     * @param activePlayers active players in the current hand
     */
    void moveToFirstPostFlopActor(List<Player> activePlayers) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            currentPlayerId = null;
            return;
        }
        if (handSeatOrder.isEmpty()) {
            currentPlayerId = firstActionableOrFirstId(activePlayers);
            return;
        }
        int anchorIndex = dealerPlayerId != null ? handSeatOrder.indexOf(dealerPlayerId) : -1;
        int seatCount = handSeatOrder.size();
        for (int offset = 1; offset <= seatCount; offset++) {
            String candidateId = handSeatOrder.get(Math.floorMod(anchorIndex + offset, seatCount));
            int activeIndex = indexOfActivePlayer(activePlayers, candidateId);
            if (activeIndex < 0) {
                continue;
            }
            Player candidate = activePlayers.get(activeIndex);
            if (candidate.canAct()) {
                currentPlayerId = candidate.getPlayerId();
                return;
            }
        }
        currentPlayerId = firstActionableOrFirstId(activePlayers);
    }

    /**
     * Reconciles {@code currentPlayerId} after {@code removedPlayerId} leaves {@code activePlayers}.
     *
     * @param activePlayers   active players remaining in the current hand
     * @param removedPlayerId player ID that departed
     */
    void removePlayer(List<Player> activePlayers, String removedPlayerId) {
        if (removedPlayerId == null) {
            return;
        }
        if (activePlayers == null || activePlayers.isEmpty()) {
            currentPlayerId = null;
            return;
        }
        // Preserve the current actor's turn when another seat departs and the current actor remains active.
        if (!removedPlayerId.equals(currentPlayerId) && indexOfActivePlayer(activePlayers, currentPlayerId) >= 0) {
            return;
        }
        currentPlayerId = findNextActorAfterDeparture(activePlayers, removedPlayerId);
    }

    /**
     * Advances the turn pointer to the next active player who can act.
     *
     * @param activePlayers active players in the current hand
     */
    void nextPlayer(List<Player> activePlayers) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            currentPlayerId = null;
            return;
        }
        int currentIndex = indexOfActivePlayer(activePlayers, currentPlayerId);
        if (currentIndex < 0) {
            currentPlayerId = (currentPlayerId != null && handSeatOrder.contains(currentPlayerId))
                    ? findNextActorAfterDeparture(activePlayers, currentPlayerId)
                    : firstActionableOrFirstId(activePlayers);
            return;
        }
        int nextIndex = findNextActionableIndex(activePlayers, currentIndex);
        currentPlayerId = activePlayers.get(nextIndex).getPlayerId();
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
        int index = indexOfActivePlayer(activePlayers, currentPlayerId);
        return index >= 0 ? activePlayers.get(index) : activePlayers.getFirst();
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
        return deriveBlindPlayerId(activePlayers, false);
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
        return deriveBlindPlayerId(activePlayers, true);
    }

    List<String> getHandSeatOrderSnapshot() {
        return List.copyOf(handSeatOrder);
    }

    String getDealerPlayerId() {
        return dealerPlayerId;
    }

    String getCurrentPlayerId() {
        return currentPlayerId;
    }

    private String deriveBlindPlayerId(List<Player> activePlayers, boolean bigBlind) {
        if (activePlayers == null || activePlayers.isEmpty()) {
            return null;
        }
        int tableSize = activePlayers.size();
        if (tableSize == 1) {
            return activePlayers.getFirst().getPlayerId();
        }
        int dealerIndex = indexOfActivePlayer(activePlayers, dealerPlayerId);
        if (dealerIndex >= 0) {
            // Heads-up poker assigns the small blind to the dealer and big blind to the other player.
            int smallBlindIndex = tableSize == 2 ? dealerIndex : (dealerIndex + 1) % tableSize;
            int bigBlindIndex = (smallBlindIndex + 1) % tableSize;
            return activePlayers.get(bigBlind ? bigBlindIndex : smallBlindIndex).getPlayerId();
        }
        if (dealerPlayerId != null && handSeatOrder.contains(dealerPlayerId)) {
            return deriveClockwiseBlindFromSeatOrder(activePlayers, dealerPlayerId, bigBlind);
        }
        return null;
    }

    private String deriveClockwiseBlindFromSeatOrder(
            List<Player> activePlayers, String anchorPlayerId, boolean bigBlind) {
        int anchorIndex = handSeatOrder.indexOf(anchorPlayerId);
        int seatCount = handSeatOrder.size();
        String firstActive = null;
        for (int offset = 1; offset <= seatCount; offset++) {
            String candidateId = handSeatOrder.get(Math.floorMod(anchorIndex + offset, seatCount));
            if (indexOfActivePlayer(activePlayers, candidateId) < 0) {
                continue;
            }
            if (firstActive == null) {
                firstActive = candidateId;
                if (!bigBlind) {
                    return candidateId;
                }
            } else if (!candidateId.equals(firstActive)) {
                return candidateId;
            }
        }
        return null;
    }

    private String findNextActorAfterDeparture(List<Player> activePlayers, String departedPlayerId) {
        if (!handSeatOrder.isEmpty()) {
            int anchorIndex = handSeatOrder.indexOf(departedPlayerId);
            int seatCount = handSeatOrder.size();
            String firstActiveFallback = null;
            for (int offset = 1; offset <= seatCount; offset++) {
                String candidateId = handSeatOrder.get(Math.floorMod(anchorIndex + offset, seatCount));
                int activeIndex = indexOfActivePlayer(activePlayers, candidateId);
                if (activeIndex < 0) {
                    continue;
                }
                Player candidate = activePlayers.get(activeIndex);
                if (candidate.canAct()) {
                    return candidate.getPlayerId();
                }
                if (firstActiveFallback == null) {
                    firstActiveFallback = candidate.getPlayerId();
                }
            }
            if (firstActiveFallback != null) {
                return firstActiveFallback;
            }
        }
        return firstActionableOrFirstId(activePlayers);
    }

    private static String firstPreFlopActorId(List<Player> activePlayers, int dealerIndex) {
        int tableSize = activePlayers.size();
        if (tableSize == 1) {
            return activePlayers.getFirst().getPlayerId();
        }
        // Heads-up poker assigns the first pre-flop turn to the dealer/small blind.
        if (tableSize == 2) {
            return activePlayers.get(dealerIndex).getPlayerId();
        }
        return activePlayers.get((dealerIndex + 3) % tableSize).getPlayerId();
    }

    private static String firstActionableOrFirstId(List<Player> activePlayers) {
        return activePlayers.stream()
                .filter(Player::canAct)
                .map(Player::getPlayerId)
                .findFirst()
                .orElse(activePlayers.getFirst().getPlayerId());
    }

    private static List<String> toPlayerIds(List<Player> tablePlayers) {
        if (tablePlayers == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(tablePlayers.stream().filter(Objects::nonNull).map(Player::getPlayerId).toList());
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

    private static int findNextActionableIndex(List<Player> activePlayers, int currentPosition) {
        int originalPosition = Math.floorMod(currentPosition, activePlayers.size());
        int next = originalPosition;
        do {
            next = (next + 1) % activePlayers.size();
        } while (!activePlayers.get(next).canAct()
                && next != originalPosition);
        return next;
    }
}
