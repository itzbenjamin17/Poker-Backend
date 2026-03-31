package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.service.HandEvaluatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Represents a poker game instance with players, betting rounds, and game state
 * management.
 * Handles game progression, betting logic, and hand evaluation.
 */
public class Game {

    private static final Logger logger = LoggerFactory.getLogger(Game.class);

    private final String gameId;
    private final List<Player> players;
    private final List<Player> activePlayers;
    private Deck deck;
    private final List<Card> communityCards;
    private int pot;
    private int dealerPosition;
    private int smallBlindPosition;
    private int bigBlindPosition;
    private int currentPlayerPosition;
    private int currentHighestBet;
    private GamePhase currentPhase;
    private boolean gameOver;
    private final HandEvaluatorService handEvaluator;
    private final int smallBlind;
    private final int bigBlind;
    private final Map<String, Integer> handContributions;

    // Track if everyone has had their initial turn in the current betting round
    private boolean everyoneHasHadInitialTurn;

    /**
     * Creates a new poker game with the specified players and betting parameters.
     *
     * @param gameId        unique identifier for the game
     * @param players       list of players participating in the game (must have at
     *                      least 2 players)
     * @param smallBlind    the small blind amount
     * @param bigBlind      the big blind amount
     * @param handEvaluator service for evaluating poker hands
     * @throws BadRequestException if gameId is null/empty, players list is
     *                             invalid, or contains null elements
     */
    public Game(String gameId, List<Player> players, int smallBlind, int bigBlind, HandEvaluatorService handEvaluator) {
        if (gameId == null || gameId.trim().isEmpty()) {
            logger.error("[Game] Invalid gameId: '{}'", gameId);
            throw new BadRequestException("Game ID cannot be null or empty");
        }
        if (players == null || players.size() < 2) {
            logger.error("[Game] Invalid players list: null or too few players (size: {})",
                    players == null ? 0 : players.size());
            throw new BadRequestException("At least 2 players are required to start a game");
        }
        if (players.stream().anyMatch(Objects::isNull)) {
            logger.error("[Game] Players list contains null element(s)");
            throw new BadRequestException("Invalid players list. Please try again.");
        }
        this.gameId = gameId;
        this.players = new ArrayList<>(players);
        this.activePlayers = new ArrayList<>(players);
        this.deck = new Deck();
        this.communityCards = new ArrayList<>();
        this.pot = 0;
        this.dealerPosition = 0;
        updatePositionsForCurrentTable();
        this.currentHighestBet = 0;
        this.currentPhase = GamePhase.PRE_FLOP;
        this.gameOver = false;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.handEvaluator = handEvaluator;
        this.everyoneHasHadInitialTurn = false;
        this.handContributions = new HashMap<>();
    }

    /**
     * Resets the game state for a new hand.
     * Cleans up the finished hand and reports whether the game has ended.
     *
     * @return true if the game is over after hand cleanup, false otherwise
     */
    public boolean resetForNewHand() {
        // Doing this for readability
        int carryOverPot = pot;

        if (logger.isDebugEnabled()) {
            logger.debug("Resetting hand for game {} | carryOverPot={} | previousContributions={}",
                    gameId,
                    carryOverPot,
                    handContributions);
        }

        cleanupAfterHand();

        if (gameOver) {
            return true;
        }

        deck = new Deck();
        communityCards.clear();
        pot = carryOverPot;
        currentHighestBet = 0;
        currentPhase = GamePhase.PRE_FLOP;
        everyoneHasHadInitialTurn = false;
        handContributions.clear();

        logger.debug("Hand reset complete for game {} | contributions cleared", gameId);

        activePlayers.clear();
        for (Player player : players) {
            if (!player.getIsOut()) {
                player.resetAttributes();
                activePlayers.add(player);
            }
        }

        if (activePlayers.size() <= 1) {
            gameOver = true;
            return true;
        } else {
            gameOver = false;
            advancePositions();
            return false;
        }
    }

    /**
     * Deals two hole cards to each active player from the deck.
     */
    public void dealHoleCards() {
        for (Player player : activePlayers) {
            player.getHoleCards().addAll(deck.dealCards(2));
        }
    }

    /**
     * Posts the small blind and big blind at the start of a hand.
     * If a player has insufficient chips, they automatically go all-in.
     * Updates the pot and sets the current highest bet to the big blind amount.
     */
    public void postBlinds() {
        if (activePlayers.size() >= 2) {
            normalizeBlindPositions();
            Player smallBlindPlayer = activePlayers.get(smallBlindPosition);
            Player bigBlindPlayer = activePlayers.get(bigBlindPosition);

            logger.debug("Posting blinds for game {} | SB={} (chips={}) | BB={} (chips={}) | potBefore={}",
                    gameId,
                    smallBlindPlayer.getName(),
                    smallBlindPlayer.getChips(),
                    bigBlindPlayer.getName(),
                    bigBlindPlayer.getChips(),
                    pot);

            @SuppressWarnings("DuplicatedCode")
            int smallBlindBefore = smallBlindPlayer.getCurrentBet();

            if (smallBlindPlayer.getChips() <= smallBlind) {
                this.pot = smallBlindPlayer.doAction(PlayerAction.ALL_IN, 0, this.pot);
            } else {
                this.pot = smallBlindPlayer.doAction(PlayerAction.BET, smallBlind, this.pot);
            }
            trackContribution(smallBlindPlayer, smallBlindPlayer.getCurrentBet() - smallBlindBefore);

            @SuppressWarnings("DuplicatedCode")
            int bigBlindBefore = bigBlindPlayer.getCurrentBet();

            if (bigBlindPlayer.getChips() <= bigBlind) {
                this.pot = bigBlindPlayer.doAction(PlayerAction.ALL_IN, 0, this.pot);
            } else {
                this.pot = bigBlindPlayer.doAction(PlayerAction.BET, bigBlind, this.pot);
            }
            trackContribution(bigBlindPlayer, bigBlindPlayer.getCurrentBet() - bigBlindBefore);

            currentHighestBet = Math.max(smallBlindPlayer.getCurrentBet(), bigBlindPlayer.getCurrentBet());

            logger.debug(
                    "Blinds posted for game {} | sbBet={} | bbBet={} | potAfter={} | currentHighestBet={} | contributions={}",
                    gameId,
                    smallBlindPlayer.getCurrentBet(),
                    bigBlindPlayer.getCurrentBet(),
                    pot,
                    currentHighestBet,
                    handContributions);
        } else {
            throw new BadRequestException(
                    String.format("Trying to post blinds for a game %s which has less than 2 players", gameId));
        }
    }

    /**
     * Processes a player's betting decision and updates the game state accordingly.
     *
     * @param player   the player making the decision
     * @param decision the player's betting decision (action and amount)
     * @return a message if the action was converted (e.g. call to all-in), null
     *         otherwise
     * @throws UnauthorisedActionException if a raise amount is invalid
     */
    public String processPlayerDecision(Player player, PlayerDecision decision) {
        if (decision == null || decision.action() == null) {
            throw new BadRequestException("Invalid player action");
        }

        if (decision.amount() < 0) {
            throw new BadRequestException("Action amount cannot be negative");
        }

        logger.debug(
                "Processing decision in game {} | player={} | decision={} | currentBet={} | currentHighestBet={} | pot={}",
                gameId,
                player.getName(),
                decision,
                player.getCurrentBet(),
                currentHighestBet,
                pot);

        PlayerDecision actualDecision = decision;
        String conversionMessage = null;

        // Validate raise amounts (only if still a raise after conversion)
        if (actualDecision.action() == PlayerAction.RAISE) {
            if (actualDecision.amount() == 0) {
                throw new BadRequestException("Raise amount must be greater than 0");
            }

            if (actualDecision.amount() > player.getChips()) {
                throw new BadRequestException("Raise amount cannot exceed available chips");
            }

            int totalBetAfterRaise = player.getCurrentBet() + actualDecision.amount();
            if (totalBetAfterRaise <= currentHighestBet) {
                logger.warn("[Game] Invalid raise: player {} tried to raise to {}, current highest bet is {}",
                        player.getName(), totalBetAfterRaise, currentHighestBet);
                throw new UnauthorisedActionException(
                        "Raise amount must result in a bet higher than current highest bet of " + currentHighestBet +
                                ". Your current bet is " + player.getCurrentBet() +
                                ", so you need to raise by at least "
                                + (currentHighestBet - player.getCurrentBet() + 1));
            }
        }

        if (actualDecision.action() == PlayerAction.BET) {
            if (currentHighestBet > 0) {
                throw new UnauthorisedActionException(
                        "Cannot bet when there is an active bet. You must call, raise, or fold.");
            }

            if (actualDecision.amount() == 0) {
                throw new BadRequestException("Bet amount must be greater than 0");
            }

            if (actualDecision.amount() > player.getChips()) {
                throw new BadRequestException("Bet amount cannot exceed available chips");
            }
        }

        if (actualDecision.action() == PlayerAction.CHECK) {
            if (currentHighestBet > player.getCurrentBet()) {
                throw new UnauthorisedActionException(
                        "Cannot check when there is an active bet. You must call, raise, or fold.");
            }
        }

        if (actualDecision.action() == PlayerAction.CALL) {
            int requiredCall = Math.max(0, currentHighestBet - player.getCurrentBet());
            if (requiredCall > player.getChips()) {
                logger.info("Player {} attempted CALL for {} with only {} chips. Converting to ALL_IN",
                        player.getName(), requiredCall, player.getChips());
                actualDecision = new PlayerDecision(PlayerAction.ALL_IN, 0, decision.playerId());
                conversionMessage = "Your call was converted to all-in because the call amount exceeded your available chips.";
            }
        }

        int potBeforeDecision = this.pot;

        switch (actualDecision.action()) {
            case FOLD, CHECK -> player.doAction(actualDecision.action(), 0, this.pot);
            case CALL, BET, RAISE -> {
                int amount = calculateActualAmount(player, actualDecision);
                this.pot = player.doAction(actualDecision.action(), amount, this.pot);
                if (player.getCurrentBet() > currentHighestBet) {
                    currentHighestBet = player.getCurrentBet();
                }
            }
            case ALL_IN -> {
                this.pot = player.doAction(PlayerAction.ALL_IN, 0, this.pot);
                if (player.getCurrentBet() > currentHighestBet) {
                    currentHighestBet = player.getCurrentBet();
                }
            }
        }

        trackContribution(player, this.pot - potBeforeDecision);

        logger.debug(
                "Decision applied in game {} | player={} | action={} | potDelta={} | potNow={} | playerBet={} | playerChips={} | currentHighestBet={} | contributions={}",
                gameId,
                player.getName(),
                actualDecision.action(),
                (this.pot - potBeforeDecision),
                this.pot,
                player.getCurrentBet(),
                player.getChips(),
                currentHighestBet,
                handContributions);

        return conversionMessage; // Return null if no conversion, or the message if converted
    }

    /**
     * Calculates the actual chip amount required for a player's action.
     *
     * @param player   the player making the action
     * @param decision the player's decision containing the action and amount
     * @return the calculated chip amount needed for the action
     */
    private int calculateActualAmount(Player player, PlayerDecision decision) {
        return switch (decision.action()) {
            case CALL -> currentHighestBet - player.getCurrentBet();
            case BET, RAISE -> decision.amount();
            default -> 0;
        };
    }

    /**
     * Checks if the current betting round is complete.
     * A betting round is complete when everyone has had their initial turn
     * and no active player needs to act (all non-folded, non-all-in players
     * have matched the current highest bet).
     *
     * @return true if the betting round is complete, false otherwise
     */
    public boolean isBettingRoundComplete() {
        boolean hasPlayerWhoNeedsToAct = activePlayers.stream()
                .anyMatch(p -> p.getCurrentBet() < currentHighestBet &&
                        !p.getHasFolded() &&
                        !p.getIsAllIn());

        if (!everyoneHasHadInitialTurn) {
            logger.debug("Betting round not complete: Not everyone has had initial turn");
            return false;
        }

        logger.debug("Checking if betting round complete - Current highest bet: {}, Everyone has had initial turn: {}",
                currentHighestBet, true);

        if (logger.isDebugEnabled()) {
            activePlayers.forEach(p -> logger.debug("Player {}: bet={}, folded={}, all-in={}, needsToAct={}",
                    p.getName(), p.getCurrentBet(), p.getHasFolded(), p.getIsAllIn(),
                    (p.getCurrentBet() < currentHighestBet && !p.getHasFolded() && !p.getIsAllIn())));
        }

        boolean isComplete = !hasPlayerWhoNeedsToAct;
        logger.debug("Betting round result: {} (hasPlayerWhoNeedsToAct={})",
                (isComplete ? "Complete" : "Not complete"), hasPlayerWhoNeedsToAct);

        return isComplete;
    }

    /**
     * Sets whether all players have had their initial turn in the current betting
     * round.
     *
     * @param value true if everyone has had their initial turn, false otherwise
     */
    public void setEveryoneHasHadInitialTurn(boolean value) {
        this.everyoneHasHadInitialTurn = value;
        logger.debug("Set everyoneHasHadInitialTurn = {}", value);
    }

    /**
     * Deals the flop (first three community cards) and advances to the FLOP phase.
     * Resets all player bets for the new betting round.
     */
    public void dealFlop() {
        communityCards.addAll(deck.dealCards(3));
        currentPhase = GamePhase.FLOP;
        resetBetsForRound();
    }

    /**
     * Deals the turn (fourth community card) and advances to the TURN phase.
     * Resets all player bets for the new betting round.
     */
    public void dealTurn() {
        communityCards.add(deck.dealCard());
        currentPhase = GamePhase.TURN;
        resetBetsForRound();
    }

    /**
     * Deals the river (fifth and final community card) and advances to the RIVER
     * phase.
     * Resets all player bets for the new betting round.
     */
    public void dealRiver() {
        communityCards.add(deck.dealCard());
        currentPhase = GamePhase.RIVER;
        resetBetsForRound();
    }

    /**
     * Conducts the showdown phase where remaining players' hands are evaluated
     * and winners are determined. Distributes the pot to the winner(s).
     *
     * @return list of winning players
     */
    public List<Player> conductShowdown() {
        logger.info("Conducting showdown for game {}", gameId);
        currentPhase = GamePhase.SHOWDOWN;

        List<Player> showdownPlayers = activePlayers.stream()
                .filter(p -> !p.getHasFolded())
                .toList();

        logger.info("Showdown players: {}, Current pot: {}",
                showdownPlayers.stream().map(Player::getName).toList(), pot);

        if (showdownPlayers.size() == 1) {
            logger.info("Only one player remaining, auto-win for {}", showdownPlayers.getFirst().getName());
            distributePot(showdownPlayers);
            return showdownPlayers;
        }

        logger.debug("Evaluating hands for {} players", showdownPlayers.size());
        evaluateHands(showdownPlayers);

        if (logger.isDebugEnabled()) {
            showdownPlayers
                    .forEach(p -> logger.debug("Player {}: {} with {}", p.getName(), p.getHandRank(), p.getBestHand()));
        }

        logger.debug("Determining winners from {} players", showdownPlayers.size());
        List<Player> winners;

        boolean hasAllInShowdownPlayer = showdownPlayers.stream().anyMatch(Player::getIsAllIn);
        if (!hasAllInShowdownPlayer) {
            winners = determineWinners(showdownPlayers);
            logger.info("Winners: {}", winners.stream().map(Player::getName).toList());
            logger.info("Distributing pot of {} to {} winner(s)", pot, winners.size());
            distributePot(winners);
        } else {
            winners = distributeSidePotsAtShowdown();
            logger.info("Side-pot winners: {}", winners.stream().map(Player::getName).toList());
        }

        logger.debug("Pot after distribution: {}", pot);
        if (logger.isDebugEnabled()) {
            winners.forEach(p -> logger.debug("Player {} now has {} chips", p.getName(), p.getChips()));
        }

        logger.info("Showdown complete");
        return winners;
    }

    /**
     * Tracks how many chips each player has committed during the current hand.
     * This contribution map is later used to build main/side pots and detect
     * uncalled chips.
     *
     * @param player the player whose contribution changed
     * @param amount number of chips newly committed to the pot
     */
    private void trackContribution(Player player, int amount) {
        if (player == null || amount <= 0) {
            return;
        }

        handContributions.merge(player.getPlayerId(), amount, Integer::sum);

        logger.debug("Tracked contribution in game {} | player={} | added={} | totalContribution={} | contributions={}",
                gameId,
                player.getName(),
                amount,
                handContributions.getOrDefault(player.getPlayerId(), 0),
                handContributions);
    }

    /**
     * Represents a single pot layer (main pot or a side pot) and the players
     * who are eligible to win that layer.
     */
    private record PotAllocation(int amount, List<Player> eligiblePlayers) {
    }

    /**
     * Result container for pot decomposition:
     * - allocations: contested pots to award at showdown
     * - uncalledByPlayer: chips that must be refunded (single-participant layers)
     * - uncalledTotal: sum of all uncalled chips
     */
    private record PotComputationResult(List<PotAllocation> allocations,
            Map<String, Integer> uncalledByPlayer,
            int uncalledTotal) {
    }

    /**
     * Decomposes the current pot into contested allocation layers and uncalled
     * layers.
     *
     * @return a full breakdown used for payout distribution and UI pot rendering
     */
    private PotComputationResult computePotAllocationsAndUncalled() {
        List<PotAllocation> allocations = new ArrayList<>();
        Map<String, Integer> uncalledByPlayer = new HashMap<>();

        logger.debug("Building pot allocations for game {} | pot={} | contributions={}",
                gameId,
                pot,
                handContributions);

        // Only considering players who contributed chips to the pot.
        Map<String, Integer> contributions = handContributions.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .collect(HashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        HashMap::putAll);

        if (!contributions.isEmpty()) {
            // Side-pot boundaries are the distinct total contribution amounts.
            List<Integer> contributionLevels = contributions.values().stream()
                    .distinct()
                    .sorted()
                    .toList();

            logger.debug("Contribution levels for game {}: {}", gameId, contributionLevels);

            int previousLevel = 0;
            for (int level : contributionLevels) {
                // Processing each incremental layer between adjacent contribution levels.
                int layer = level - previousLevel;
                previousLevel = level;

                if (layer <= 0) {
                    continue;
                }

                // Participants at this level are players who have contributed at least
                // `level` chips in the hand.
                List<Player> participants = players.stream()
                        .filter(player -> contributions.getOrDefault(player.getPlayerId(), 0) >= level)
                        .toList();

                int amount = layer * participants.size();
                if (amount <= 0) {
                    continue;
                }

                if (participants.size() == 1) {
                    // A single-participant layer is uncalled and should be refunded.
                    Player owner = participants.getFirst();
                    uncalledByPlayer.merge(owner.getPlayerId(), amount, Integer::sum);
                    logger.debug("Detected uncalled layer for game {} | level={} | owner={} | amount={}",
                            gameId,
                            level,
                            owner.getName(),
                            amount);
                    continue;
                }

                List<Player> eligiblePlayers = participants.stream()
                        .filter(player -> !player.getHasFolded())
                        .toList();

                if (!eligiblePlayers.isEmpty()) {
                    // Multi-participant layers become contested allocations.
                    allocations.add(new PotAllocation(amount, eligiblePlayers));
                    logger.debug(
                            "Allocation layer for game {} | level={} | layer={} | participants={} | eligible={} | amount={}",
                            gameId,
                            level,
                            layer,
                            playerNames(participants),
                            playerNames(eligiblePlayers),
                            amount);
                }
            }
        } else {
            logger.debug("No tracked contributions for game {} while building allocations", gameId);
        }

        int uncalledTotal = uncalledByPlayer.values().stream().mapToInt(Integer::intValue).sum();
        int allocatedAmount = allocations.stream().mapToInt(PotAllocation::amount).sum();
        int unallocatedAmount = Math.max(0, pot - allocatedAmount - uncalledTotal);

        logger.debug("Allocation totals for game {} | allocated={} | uncalled={} | unallocated={} | pot={}",
                gameId,
                allocatedAmount,
                uncalledTotal,
                unallocatedAmount,
                pot);

        if (unallocatedAmount > 0) {
            // If there are any chips not accounted for in allocations or uncalled layers,
            // we need to carry them over.
            List<Player> eligibleCarryOverPlayers = activePlayers.stream()
                    .filter(player -> !player.getHasFolded())
                    .toList();

            if (eligibleCarryOverPlayers.isEmpty()) {
                eligibleCarryOverPlayers = players.stream()
                        .filter(player -> !player.getHasFolded())
                        .toList();
            }

            if (!eligibleCarryOverPlayers.isEmpty()) {
                if (allocations.isEmpty()) {
                    allocations.add(new PotAllocation(unallocatedAmount, eligibleCarryOverPlayers));
                    logger.debug(
                            "No base allocation existed, created carry-over allocation for game {} | amount={} | eligible={}",
                            gameId,
                            unallocatedAmount,
                            playerNames(eligibleCarryOverPlayers));
                } else {
                    PotAllocation mainPot = allocations.getFirst();
                    allocations.set(
                            0,
                            new PotAllocation(mainPot.amount() + unallocatedAmount, mainPot.eligiblePlayers()));
                    logger.debug(
                            "Merged unallocated chips into main allocation for game {} | added={} | newMainAmount={}",
                            gameId,
                            unallocatedAmount,
                            allocations.getFirst().amount());
                }
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Final allocations for game {}: {}", gameId, allocationSummary(allocations));
            logger.debug("Uncalled map for game {}: {}", gameId, uncalledByPlayer);
        }

        return new PotComputationResult(allocations, uncalledByPlayer, uncalledTotal);
    }

    /**
     * Resolves and distributes all side pots at showdown.
     *
     * @return ordered set of unique players who won at least one allocation
     */
    private List<Player> distributeSidePotsAtShowdown() {
        logger.info("Distributing side pots for game {}", gameId);

        // Build contested allocations and identify uncalled refunds.
        PotComputationResult computation = computePotAllocationsAndUncalled();
        List<PotAllocation> allocations = computation.allocations();
        if (logger.isDebugEnabled()) {
            logger.debug("Side-pot allocations for game {}: {}", gameId, allocationSummary(allocations));
        }

        int refundedUncalled = 0;
        // Refund single-owner (uncalled) layers before contested awards.
        for (Map.Entry<String, Integer> entry : computation.uncalledByPlayer().entrySet()) {
            Player player = players.stream()
                    .filter(p -> p.getPlayerId().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);

            if (player == null || entry.getValue() <= 0) {
                continue;
            }

            player.addChips(entry.getValue());
            refundedUncalled += entry.getValue();
            logger.info("Refunded uncalled chips for game {} | player={} | amount={}",
                    gameId,
                    player.getName(),
                    entry.getValue());
        }

        if (allocations.isEmpty()) {
            logger.warn("No side-pot allocations found. Falling back to standard distribution.");
            List<Player> winners = determineWinners(activePlayers.stream().filter(p -> !p.getHasFolded()).toList());
            distributePot(winners);
            return winners;
        }

        Set<Player> uniqueWinners = new LinkedHashSet<>();
        int remainder = 0;

        // Resolve each contested allocation, determine winners, and split chips.
        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            List<Player> potWinners = determineWinners(allocation.eligiblePlayers());

            if (potWinners.isEmpty()) {
                logger.warn("Pot {} has no winners. Keeping {} chips in pot.", i, allocation.amount());
                remainder += allocation.amount();
                continue;
            }

            int share = allocation.amount() / potWinners.size();
            // Keep modulo chips as table remainder.
            int potRemainder = allocation.amount() % potWinners.size();
            remainder += potRemainder;

            logger.info(
                    "Resolved side pot {} for game {} | amount={} | eligible={} | winners={} | share={} | remainder={}",
                    i,
                    gameId,
                    allocation.amount(),
                    playerNames(allocation.eligiblePlayers()),
                    playerNames(potWinners),
                    share,
                    potRemainder);

            for (Player winner : potWinners) {
                winner.addChips(share);
            }

            uniqueWinners.addAll(potWinners);
        }

        // Keep integer remainders in the table pot for the next hand.
        pot = remainder;
        logger.info(
                "Side-pot distribution complete for game {} | uniqueWinners={} | refundedUncalled={} | potRemainder={}",
                gameId,
                playerNames(new ArrayList<>(uniqueWinners)),
                refundedUncalled,
                pot);

        return new ArrayList<>(uniqueWinners);
    }

    /**
     * Returns the contested pot amounts for display (main pot first, then side
     * pots).
     * Uncalled amounts are intentionally excluded from this list because they are
     * refunded, not contested.
     *
     * @return list of contested pot amounts in showdown order
     */
    public List<Integer> getPotBreakdown() {
        if (pot <= 0) {
            logger.debug("Pot breakdown requested for game {} with empty pot", gameId);
            return List.of();
        }

        boolean hasAllIn = activePlayers.stream()
                .anyMatch(player -> player.getIsAllIn() && !player.getHasFolded());

        if (!hasAllIn) {
            logger.debug("Pot breakdown for game {} without all-ins: [{}]", gameId, pot);
            return List.of(pot);
        }

        PotComputationResult computation = computePotAllocationsAndUncalled();
        List<Integer> breakdown = computation.allocations().stream()
                .map(PotAllocation::amount)
                .toList();

        logger.debug("Pot breakdown for game {} with all-ins: {} | uncalled={}",
                gameId,
                breakdown,
                computation.uncalledTotal());

        return breakdown;
    }

    /**
     * Returns the total uncalled amount currently detected in the pot
     * decomposition.
     *
     * @return total chips that should be refunded to players as uncalled
     */
    public int getUncalledAmount() {
        if (pot <= 0) {
            return 0;
        }

        boolean hasAllIn = activePlayers.stream()
                .anyMatch(player -> player.getIsAllIn() && !player.getHasFolded());

        if (!hasAllIn) {
            return 0;
        }

        return computePotAllocationsAndUncalled().uncalledTotal();
    }

    /**
     * Helper for concise player-name logging in side-pot diagnostics.
     */
    private List<String> playerNames(List<Player> players) {
        return players.stream().map(Player::getName).toList();
    }

    /**
     * Helper for concise allocation logging in side-pot diagnostics.
     */
    private List<String> allocationSummary(List<PotAllocation> allocations) {
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            summary.add("pot[" + i + "] amount=" + allocation.amount() + " eligible="
                    + playerNames(allocation.eligiblePlayers()));
        }
        return summary;
    }

    /**
     * Evaluates the poker hands for all given players using community cards
     * and their hole cards. Updates each player's best hand and hand rank.
     *
     * @param players list of players whose hands should be evaluated
     */
    private void evaluateHands(List<Player> players) {
        for (Player player : players) {
            HandEvaluationResult result = handEvaluator.getBestHand(communityCards, player.getHoleCards());
            player.setBestHand(result.bestHand());
            player.setHandRank(result.handRank());
        }
    }

    /**
     * Determines the winning player(s) from the given list by comparing hand ranks.
     * Handles ties by checking for multiple players with the same best hand.
     *
     * @param players list of players to compare
     * @return list of winning players (may contain multiple players in case of a
     *         tie)
     */
    private List<Player> determineWinners(List<Player> players) {
        // Create a mutable copy of the list for sorting
        List<Player> sortablePlayers = new ArrayList<>(players);
        sortablePlayers.sort(Comparator.comparing(Player::getHandRank).reversed());

        if (sortablePlayers.isEmpty()) {
            return new ArrayList<>();
        }

        Player bestPlayer = sortablePlayers.getFirst();
        List<Player> winners = new ArrayList<>();
        winners.add(bestPlayer);

        for (int i = 1; i < sortablePlayers.size(); i++) {
            Player currentPlayer = sortablePlayers.get(i);
            if (currentPlayer.getHandRank() == bestPlayer.getHandRank()) {
                boolean challengerBeatsBest = handEvaluator.isBetterHandOfSameRank(
                        currentPlayer.getBestHand(),
                        bestPlayer.getBestHand(),
                        bestPlayer.getHandRank());

                boolean bestBeatsChallenger = handEvaluator.isBetterHandOfSameRank(
                        bestPlayer.getBestHand(),
                        currentPlayer.getBestHand(),
                        bestPlayer.getHandRank());

                if (challengerBeatsBest && !bestBeatsChallenger) {
                    bestPlayer = currentPlayer;
                    winners.clear();
                    winners.add(currentPlayer);
                } else if (!challengerBeatsBest && !bestBeatsChallenger) {
                    winners.add(currentPlayer);
                }
            } else {
                break;
            }
        }
        return winners;
    }

    /**
     * Distributes the pot evenly among the winning players.
     * Any remainder from integer division stays in the pot for the next hand.
     *
     * @param winners list of players who won the hand
     */
    private void distributePot(List<Player> winners) {
        logger.info("Distributing pot for game {}", gameId);
        if (winners.isEmpty()) {
            logger.warn("No winners to distribute pot to!");
            return;
        }

        logger.info("Pot to distribute: {}, Number of winners: {}", pot, winners.size());

        int potShare = pot / winners.size();
        logger.info("Each winner gets: {} chips", potShare);

        for (Player winner : winners) {
            int chipsBefore = winner.getChips();
            winner.addChips(potShare);
            logger.debug("Player {}: {} -> {} chips", winner.getName(), chipsBefore, winner.getChips());
        }

        pot = pot % winners.size(); // Any remainder stays for the next hand

        logger.debug("Pot remainder for next hand: {}", pot);
        logger.info("Pot distribution complete");
    }

    /**
     * Advances dealer, small blind, big blind, and current player positions
     * for the next hand. Rotates positions clockwise around the table.
     */
    public void advancePositions() {
        dealerPosition = (dealerPosition + 1) % activePlayers.size();
        updatePositionsForCurrentTable();
    }

    private void updatePositionsForCurrentTable() {
        int tableSize = activePlayers.size();

        if (tableSize == 2) {
            // Heads-up: dealer posts small blind and acts first pre-flop.
            smallBlindPosition = dealerPosition;
            bigBlindPosition = (dealerPosition + 1) % tableSize;
            currentPlayerPosition = dealerPosition;
            return;
        }

        smallBlindPosition = (dealerPosition + 1) % tableSize;
        bigBlindPosition = (smallBlindPosition + 1) % tableSize;
        currentPlayerPosition = (bigBlindPosition + 1) % tableSize;
    }

    /**
     * Cleans up after a hand by removing players with zero chips from active play.
     * Sets the game as over if only one or fewer active players remain.
     */
    public void cleanupAfterHand() {
        logger.debug("Cleaning up after hand for game {}", gameId);

        if (logger.isDebugEnabled()) {
            logger.debug("Players before cleanup:");
            players.forEach(p -> logger.debug("  {}: {} chips, isOut: {}", p.getName(), p.getChips(), p.getIsOut()));
        }

        players.forEach(p -> {
            if (p.getChips() == 0) {
                logger.info("Setting {} as out (0 chips)", p.getName());
                p.setIsOut();
            }
        });

        int sizeBefore = activePlayers.size();
        activePlayers.removeIf(Player::getIsOut);
        int sizeAfter = activePlayers.size();

        logger.info("Active players: {} -> {}", sizeBefore, sizeAfter);
        logger.debug("Active players after cleanup: {}", activePlayers.stream().map(Player::getName).toList());

        if (activePlayers.size() <= 1) {
            logger.info("Game over - only {} active players remaining", activePlayers.size());
            gameOver = true;
        } else {
            logger.debug("Game continues with {} active players", activePlayers.size());
        }
    }

    /**
     * Checks if the current hand is over (only one or zero active players remain
     * who haven't folded).
     *
     * @return true if the hand is over, false otherwise
     */
    public boolean isHandOver() {
        long activePlayerCount = activePlayers.stream().filter(p -> !p.getHasFolded()).count();
        return activePlayerCount <= 1;
    }

    /**
     * Resets all player bets to zero for a new betting round.
     * Also resets the current highest bet and the initial turn tracking flag.
     */
    public void resetBetsForRound() {
        for (Player player : activePlayers) {
            player.resetCurrentBet();
        }
        currentHighestBet = 0;
        everyoneHasHadInitialTurn = false; // Reset for the new betting round
        // Next player is the first active player after the dealer
        currentPlayerPosition = dealerPosition;
        nextPlayer();
    }

    /**
     * Returns the unique game identifier.
     *
     * @return the game ID
     */
    public String getGameId() {
        return gameId;
    }

    /**
     * Returns all players in the game, including those who are out.
     *
     * @return the list of all players
     */
    public List<Player> getPlayers() {
        return players;
    }

    /**
     * Returns the list of active players (players still in the game with chips).
     *
     * @return the list of active players
     */
    public List<Player> getActivePlayers() {
        return activePlayers;
    }

    /**
     * Removes a player from the game and reconciles turn index so it always points
     * to a valid seat in {@code activePlayers}.
     *
     * @param playerToRemove the player to remove from game and active lists
     */
    public void removePlayerFromGame(Player playerToRemove) {
        if (playerToRemove == null) {
            return;
        }

        Player previousCurrent = null;
        if (!activePlayers.isEmpty()) {
            normalizeCurrentPlayerPosition();
            previousCurrent = activePlayers.get(currentPlayerPosition);
        }

        players.remove(playerToRemove);
        activePlayers.remove(playerToRemove);

        if (activePlayers.isEmpty()) {
            currentPlayerPosition = 0;
            return;
        }

        normalizeBlindPositions();

        // Preserve the same player turn when someone else leaves and that player is
        // still present.
        if (previousCurrent != null && !previousCurrent.equals(playerToRemove)) {
            int preservedIndex = activePlayers.indexOf(previousCurrent);
            if (preservedIndex >= 0) {
                currentPlayerPosition = preservedIndex;
                return;
            }
        }

        // If current player left, point to the successor seat and skip non-actionable
        // seats.
        normalizeCurrentPlayerPosition();
        Player current = activePlayers.get(currentPlayerPosition);
        if (current.getHasFolded() || current.getIsAllIn()) {
            nextPlayer();
        }
    }

    /**
     * Returns the player whose turn it currently is.
     *
     * @return the current player
     */
    public Player getCurrentPlayer() {
        if (activePlayers.isEmpty()) {
            throw new IllegalStateException("No active players available");
        }

        normalizeCurrentPlayerPosition();
        return activePlayers.get(currentPlayerPosition);
    }

    /**
     * Advances to the next player in turn order.
     */
    public void nextPlayer() {
        if (activePlayers.isEmpty()) {
            currentPlayerPosition = 0;
            return;
        }

        normalizeCurrentPlayerPosition();

        int originalPosition = currentPlayerPosition;
        do {
            currentPlayerPosition = (currentPlayerPosition + 1) % activePlayers.size();
        } while ((activePlayers.get(currentPlayerPosition).getHasFolded() ||
                activePlayers.get(currentPlayerPosition).getIsAllIn()) &&
                currentPlayerPosition != originalPosition);
    }

    /**
     * Normalizes the current player position to be within the bounds of the active
     * players list.
     */
    private void normalizeCurrentPlayerPosition() {
        if (activePlayers.isEmpty()) {
            currentPlayerPosition = 0;
            return;
        }
        // floorMod handles negative indices correctly but this isn't necessary for my
        // code as I never subtract from currentPlayerPosition
        currentPlayerPosition = Math.floorMod(currentPlayerPosition, activePlayers.size());
    }

    /**
     * Normalizes the blind positions to be within the bounds of the active players
     * list.
     */
    private void normalizeBlindPositions() {
        if (activePlayers.isEmpty()) {
            smallBlindPosition = 0;
            bigBlindPosition = 0;
            return;
        }
        // floorMod handles negative indices correctly but this isn't necessary for my
        // code as I never subtract from the blind positions
        int tableSize = activePlayers.size();
        smallBlindPosition = Math.floorMod(smallBlindPosition, tableSize);
        bigBlindPosition = Math.floorMod(bigBlindPosition, tableSize);
    }

    /**
     * Returns the community cards currently on the table.
     *
     * @return the list of community cards
     */
    public List<Card> getCommunityCards() {
        return communityCards;
    }

    /**
     * Returns the current pot amount.
     *
     * @return the pot value
     */
    public int getPot() {
        return pot;
    }

    /**
     * Returns the current phase of the game (PRE_FLOP, FLOP, TURN, RIVER, or
     * SHOWDOWN).
     *
     * @return the current game phase
     */
    public GamePhase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Returns whether the game is over (one or fewer active players remain).
     *
     * @return true if the game is over, false otherwise
     */
    public boolean isGameOver() {
        return gameOver;
    }

    /**
     * Returns the current highest bet in the current betting round.
     *
     * @return the highest bet amount
     */
    public int getCurrentHighestBet() {
        return currentHighestBet;
    }

    /**
     * Returns the position index of the dealer in the active players list.
     *
     * @return the dealer position
     */
    public int getDealerPosition() {
        return dealerPosition;
    }

    /**
     * Returns the ID of the player currently assigned as small blind.
     *
     * @return small blind player ID, or null when no active players exist
     */
    public String getSmallBlindPlayerId() {
        if (activePlayers.isEmpty()) {
            return null;
        }

        normalizeBlindPositions();

        return activePlayers.get(smallBlindPosition).getPlayerId();
    }

    /**
     * Returns the ID of the player currently assigned as big blind.
     *
     * @return big blind player ID, or null when no active players exist
     */
    public String getBigBlindPlayerId() {
        if (activePlayers.isEmpty()) {
            return null;
        }

        normalizeBlindPositions();

        return activePlayers.get(bigBlindPosition).getPlayerId();
    }
}