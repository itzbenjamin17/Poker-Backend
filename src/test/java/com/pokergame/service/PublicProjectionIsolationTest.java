package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Protects the public projection contract under showdown settlement: a settled hand keeps its
 * winners/winnings and suppresses turn pointers regardless of churn, folded hole cards stay
 * hidden, settlement in one room never leaks into another, and game-end capacity is clamped to
 * the legal range.
 */
@ExtendWith(MockitoExtension.class)
class PublicProjectionIsolationTest {

    private GameStateService gameStateService;

    @Mock
    private RoomService roomService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private HandEvaluatorService handEvaluator;

    private static final String ROOM_A = "room-alpha";
    private static final String ROOM_B = "room-beta";

    private Room roomA;
    private Room roomB;

    @BeforeEach
    void setUp() {
        gameStateService = new GameStateService(roomService, messagingTemplate);

        roomA = new Room(ROOM_A, "Room A", "HostA", 6, 5, 10, 100, null);
        roomB = new Room(ROOM_B, "Room B", "HostB", 4, 10, 20, 200, null);

        roomA.addPlayer("Alice");
        roomA.addPlayer("Bob");
        roomA.addPlayer("Charlie");

        roomB.addPlayer("Xavier");
        roomB.addPlayer("Yvonne");
    }

    private Player createPlayer(String name, String id, int chips, boolean folded) {
        return Player.restoreBuilder()
                .name(name)
                .playerId(id)
                .chips(chips)
                .holeCards(new ArrayList<>(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.HEARTS))))
                .bestHand(new ArrayList<>())
                .currentBet(0)
                .hasFolded(folded)
                .allIn(false)
                .out(false)
                .disconnected(false)
                .readyForNextHand(false)
                .build();
    }

    @Test
    @DisplayName("Repeated ready/unready toggling during showdown retains settlement and suppresses currentPlayer")
    void readyFlapping_preservesSettlementIntegrity() {
        when(roomService.getRoom(ROOM_A)).thenReturn(roomA);

        Player p1 = createPlayer("Alice", "p-1", 500, false);
        Player p2 = createPlayer("Bob", "p-2", 300, false);
        Player p3Folded = createPlayer("Charlie", "p-3", 200, true);

        p1.setHandRank(HandRank.FULL_HOUSE);
        p2.setHandRank(HandRank.FLUSH);
        p3Folded.setHandRank(HandRank.NO_HAND);

        List<Player> players = List.of(p1, p2, p3Folded);
        HandSettlementResult settlement = new HandSettlementResult(
                Map.of("p-1", 450),
                Map.of(),
                Set.of("p-1"),
                0,
                true
        );

        Game game = Game.restoreBuilder()
                .gameId(ROOM_A)
                .players(players)
                .activePlayerIds(List.of("p-1", "p-2"))
                .remainingDeck(List.of())
                .communityCards(List.of(new Card(Rank.TEN, Suit.DIAMONDS), new Card(Rank.TEN, Suit.CLUBS), new Card(Rank.TEN, Suit.SPADES)))
                .pot(0)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(1) // Points to Bob, but must be nullified
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(settlement)
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(System.currentTimeMillis() + 10000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        // Broadcast initial showdown state
        gameStateService.broadcastShowdownResults(ROOM_A, game, List.of(p1), 450);

        // Flap ready flags 100 times
        Random rng = new Random(42);
        for (int i = 0; i < 100; i++) {
            boolean r1 = rng.nextBoolean();
            boolean r2 = rng.nextBoolean();
            p1.setReadyForNextHand(r1);
            p2.setReadyForNextHand(r2);

            PublicGameStateResponse snapshot = gameStateService.getPublicGameStateSnapshot(ROOM_A, game);

            // Turn pointers MUST be nullified
            assertNull(snapshot.currentPlayerName(), "Iteration " + i + ": currentPlayerName must be null during showdown countdown");
            assertNull(snapshot.currentPlayerId(), "Iteration " + i + ": currentPlayerId must be null during showdown countdown");
            assertTrue(snapshot.legalActions().isEmpty(), "Iteration " + i + ": legalActions must be empty");

            // Settlement MUST be retained
            assertTrue(snapshot.handSettled(), "Iteration " + i + ": handSettled must remain true");
            assertEquals(List.of("Alice"), snapshot.winners(), "Iteration " + i + ": winners must remain [Alice]");
            assertEquals(450, snapshot.winningsPerPlayer(), "Iteration " + i + ": winningsPerPlayer must remain 450");

            PublicPlayerState pubP1 = snapshot.players().stream().filter(p -> p.id().equals("p-1")).findFirst().orElseThrow();
            PublicPlayerState pubP2 = snapshot.players().stream().filter(p -> p.id().equals("p-2")).findFirst().orElseThrow();
            PublicPlayerState pubP3 = snapshot.players().stream().filter(p -> p.id().equals("p-3")).findFirst().orElseThrow();

            assertFalse(pubP1.isCurrentPlayer());
            assertFalse(pubP2.isCurrentPlayer());
            assertFalse(pubP3.isCurrentPlayer());

            assertTrue(pubP1.isWinner());
            assertEquals(450, pubP1.chipsWon());
            assertNotNull(pubP1.holeCards(), "Winner holeCards must be revealed");

            assertFalse(pubP2.isWinner());
            assertEquals(0, pubP2.chipsWon());
            assertNotNull(pubP2.holeCards(), "Contender holeCards must be revealed");

            assertFalse(pubP3.isWinner());
            assertNull(pubP3.holeCards(), "Folded player holeCards must NEVER be revealed");
        }
    }

    @Test
    @DisplayName("Retained settlement in one room does not leak into an active hand in another room")
    void multiRoomIsolation_noSettlementLeak() {
        when(roomService.getRoom(ROOM_A)).thenReturn(roomA);
        when(roomService.getRoom(ROOM_B)).thenReturn(roomB);

        Player pA1 = createPlayer("Alice", "p-a1", 500, false);
        Player pA2 = createPlayer("Bob", "p-a2", 300, false);
        Game gameA = Game.restoreBuilder()
                .gameId(ROOM_A)
                .players(List.of(pA1, pA2))
                .activePlayerIds(List.of("p-a1", "p-a2"))
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(100)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(new HandSettlementResult(Map.of("p-a1", 100), Map.of(), Set.of("p-a1"), 0, true))
                .readyCountdownActive(true)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        gameStateService.broadcastShowdownResults(ROOM_A, gameA, List.of(pA1), 100);

        // Room B is active in FLOP phase with no settlement
        Player pB1 = createPlayer("Xavier", "p-b1", 400, false);
        Player pB2 = createPlayer("Yvonne", "p-b2", 400, false);
        Game gameB = Game.restoreBuilder()
                .gameId(ROOM_B)
                .players(List.of(pB1, pB2))
                .activePlayerIds(List.of("p-b1", "p-b2"))
                .remainingDeck(List.of())
                .communityCards(List.of(new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.THREE, Suit.DIAMONDS), new Card(Rank.FOUR, Suit.HEARTS)))
                .pot(40)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(20)
                .currentPhase(GamePhase.FLOP)
                .gameOver(false)
                .smallBlind(10)
                .bigBlind(20)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(null)
                .readyCountdownActive(false)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        PublicGameStateResponse snapshotB = gameStateService.getPublicGameStateSnapshot(ROOM_B, gameB);

        assertFalse(snapshotB.handSettled(), "Room B must NOT be marked settled");
        assertNull(snapshotB.winners(), "Room B must have no winners");
        assertNotNull(snapshotB.currentPlayerName(), "Room B must have an active currentPlayer");
        assertEquals("Xavier", snapshotB.currentPlayerName());
    }

    @Test
    @DisplayName("captureGameEnd clamps maxPlayers into [2, 10] for every out-of-range input")
    void captureGameEnd_clampsMaxPlayersToLegalRange() {
        Player winner = createPlayer("Champ", "p-1", 1000, false);
        Game game = Game.restoreBuilder()
                .gameId(ROOM_A)
                .players(List.of(winner))
                .activePlayerIds(List.of("p-1"))
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(0)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(0)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(true)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(null)
                .readyCountdownActive(false)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        int[] outOfRangeValues = {
                Integer.MIN_VALUE, -999999, -1, 0, 1, 11, 12, 100, 999999, Integer.MAX_VALUE
        };

        for (int val : outOfRangeValues) {
            when(roomService.getRoom(ROOM_A)).thenReturn(null);
            GameEndResponse resNullRoom = gameStateService.captureGameEnd(ROOM_A, game, winner, false, val);
            int maxP = resNullRoom.finalState().maxPlayers();
            assertTrue(maxP >= 2 && maxP <= 10,
                    "val=" + val + " with null room produced out-of-bounds maxPlayers: " + maxP);

            Room corruptRoom = mock(Room.class);
            when(corruptRoom.getMaxPlayers()).thenReturn(val);
            when(roomService.getRoom(ROOM_A)).thenReturn(corruptRoom);
            GameEndResponse resCorruptRoom = gameStateService.captureGameEnd(ROOM_A, game, winner, false, val);
            int maxPCorrupt = resCorruptRoom.finalState().maxPlayers();
            assertTrue(maxPCorrupt >= 2 && maxPCorrupt <= 10,
                    "val=" + val + " with corrupt room produced out-of-bounds maxPlayers: " + maxPCorrupt);
        }
    }
}
