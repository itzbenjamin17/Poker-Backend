package com.pokergame.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Characterization tests that pin the complete public and private views {@link GameStateService}
 * produces, so the view-building code can be restructured without changing any client payload.
 */
@Tag("unit")
@DisplayName("Game state view snapshots")
@ExtendWith(MockitoExtension.class)
class GameStateViewSnapshotTest {
    private static final String GAME_ID = "view-game";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long NOW_MS = NOW.toEpochMilli();
    private static final Card ACE = new Card(Rank.ACE, Suit.SPADES);
    private static final Card KING = new Card(Rank.KING, Suit.HEARTS);
    private static final Card TWO = new Card(Rank.TWO, Suit.CLUBS);
    private static final Card THREE = new Card(Rank.THREE, Suit.CLUBS);
    private static final List<Card> BOARD = List.of(
            new Card(Rank.ACE, Suit.HEARTS),
            new Card(Rank.NINE, Suit.DIAMONDS),
            new Card(Rank.SEVEN, Suit.CLUBS));

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RoomService roomService;

    private GameStateService service;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new GameStateService(roomService, messagingTemplate, CLOCK);
        room = new Room(GAME_ID, "Room", "alice", 6, 5, 10, 100, null);
        room.setInactivityDeadlineEpochMs(9_000L);
    }

    // ---------- live view ----------

    @Test
    @DisplayName("live betting view names the actor, legal actions and hides hole cards")
    void liveBettingView() {
        Game game = game(GamePhase.FLOP, null, BOARD, false, ReadyGateState.CLOSED,
                Map.of(ScheduledGameTask.TURN_TIMEOUT, 5_000L), players(false, false), false);
        when(roomService.getRoom(GAME_ID)).thenReturn(room);

        PublicGameStateResponse actual = service.getPublicGameStateSnapshot(game);

        Player current = game.getCurrentPlayer();
        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.FLOP,
                game.getCurrentHighestBet(), BOARD,
                List.of(state("a", "Alice", 90, 10, "ACTIVE", false, current.getName().equals("Alice"), false,
                                true, false, null, null, null, null, null),
                        state("b", "Bob", 80, 20, "ACTIVE", false, current.getName().equals("Bob"), false,
                                false, true, null, null, null, null, null)),
                current.getName(), current.getPlayerId(), null, null, false, null, false, null, false, null,
                false, null, game.getLegalActionsFor(current), 10, game.getMinimumRaiseAmount(current),
                3L, ReadyGateState.CLOSED, NOW_MS, game.getStateRevision(), 5_000L, 9_000L), actual);
    }

    @Test
    @DisplayName("live view during auto-advance has no actor and carries the banner")
    void liveAutoAdvanceView() {
        Game game = game(GamePhase.FLOP, null, BOARD, false, ReadyGateState.CLOSED,
                Map.of(ScheduledGameTask.AUTO_ADVANCE, 7_000L), players(true, true), false);
        when(roomService.getRoom(GAME_ID)).thenReturn(room);

        PublicGameStateResponse actual = service.getPublicGameStateSnapshot(game);

        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.FLOP,
                game.getCurrentHighestBet(), BOARD,
                List.of(state("a", "Alice", 90, 10, "ALL_IN", true, false, false, true, false,
                                null, null, null, null, null),
                        state("b", "Bob", 80, 20, "ALL_IN", true, false, false, false, true,
                                null, null, null, null, null)),
                null, null, null, null, true, "Auto-advancing...", false, null, false, null, false, null,
                List.of(), null, null, 3L, ReadyGateState.CLOSED, NOW_MS, game.getStateRevision(), null,
                9_000L), actual);
    }

    @Test
    @DisplayName("live view of a settled hand at the ready gate shows results and reveals contested cards")
    void liveSettledReadyGateView() {
        HandSettlementResult settlement = new HandSettlementResult(
                Map.of("a", 30), Map.of(), Set.of("a"), 4, true);
        Game game = game(GamePhase.SHOWDOWN, settlement, BOARD, true, ReadyGateState.COUNTING_DOWN,
                Map.of(), players(false, false), false);
        when(roomService.getRoom(GAME_ID)).thenReturn(room);

        PublicGameStateResponse actual = service.getPublicGameStateSnapshot(game);

        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.SHOWDOWN,
                game.getCurrentHighestBet(), BOARD,
                List.of(state("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                HandRank.ONE_PAIR, List.of(ACE, KING), true, 30, List.of(ACE, KING)),
                        state("b", "Bob", 80, 20, "ACTIVE", false, false, false, false, true,
                                HandRank.HIGH_CARD, List.of(TWO, THREE), false, 0, List.of(TWO, THREE))),
                null, null, List.of("Alice"), 30, false, null, true, 12_345L, false, null, true, 4,
                List.of(), null, null, 3L, ReadyGateState.COUNTING_DOWN, NOW_MS, game.getStateRevision(),
                null, 9_000L), actual);
    }

    @Test
    @DisplayName("live view of an uncontested settled hand does not reveal hole cards or ranks")
    void liveUncontestedSettledView() {
        List<Player> players = players(false, false);
        players.get(0).setHandRank(HandRank.NO_HAND);
        HandSettlementResult settlement = new HandSettlementResult(
                Map.of("a", 30), Map.of(), Set.of("a"), 0, true);
        Game game = game(GamePhase.SHOWDOWN, settlement, BOARD, false, ReadyGateState.CLOSED,
                Map.of(), players, false);
        when(roomService.getRoom(GAME_ID)).thenReturn(room);

        PublicGameStateResponse actual = service.getPublicGameStateSnapshot(game);

        assertEquals(List.of(
                        state("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                HandRank.NO_HAND, List.of(ACE, KING), true, 30, null),
                        state("b", "Bob", 80, 20, "ACTIVE", false, false, false, false, true,
                                HandRank.HIGH_CARD, List.of(TWO, THREE), false, 0, null)),
                actual.players());
        assertEquals(List.of("Alice"), actual.winners());
        assertEquals(30, actual.winningsPerPlayer());
        assertEquals(true, actual.handSettled());
    }

    // ---------- broadcasts ----------

    @Test
    @DisplayName("showdown broadcast clears the actor, flags settlement and reveals contested cards")
    void showdownBroadcast() {
        HandSettlementResult settlement = new HandSettlementResult(
                Map.of("a", 30), Map.of(), Set.of("a"), 4, true);
        List<Player> players = players(false, false);
        players.set(1, Player.restoreBuilder().name("Bob").playerId("b").chips(80).currentBet(20)
                .holeCards(List.of(TWO, THREE)).bestHand(List.of(TWO, THREE)).handRank(HandRank.HIGH_CARD)
                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build());
        Game game = game(GamePhase.SHOWDOWN, settlement, BOARD, true, ReadyGateState.COUNTING_DOWN,
                Map.of(), players, false);
        long revisionBefore = game.getStateRevision();

        service.broadcastShowdownResults(game);

        assertEquals(revisionBefore + 1, game.getStateRevision());
        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.SHOWDOWN,
                game.getCurrentHighestBet(), BOARD,
                List.of(state("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                HandRank.ONE_PAIR, List.of(ACE, KING), true, 30, List.of(ACE, KING)),
                        state("b", "Bob", 80, 20, "FOLDED", false, false, true, false, true,
                                null, List.of(), false, 0, null)),
                null, null, List.of("Alice"), 30, null, null, true, 12_345L, null, null, true, 4,
                null, null, null, 3L, ReadyGateState.COUNTING_DOWN, NOW_MS, game.getStateRevision()),
                publicBroadcast());
    }

    @Test
    @DisplayName("showdown broadcast with an unequal split reports no equal award")
    void showdownBroadcastUnequalSplit() {
        HandSettlementResult settlement = new HandSettlementResult(
                Map.of("a", 30, "b", 10), Map.of(), Set.of("a", "b"), 0, true);
        Game game = game(GamePhase.SHOWDOWN, settlement, BOARD, false, ReadyGateState.CLOSED,
                Map.of(), players(false, false), false);

        service.broadcastShowdownResults(game);

        PublicGameStateResponse actual = publicBroadcast();
        assertEquals(List.of("Alice", "Bob"), actual.winners());
        assertEquals(null, actual.winningsPerPlayer());
        assertEquals(List.of(30, 10), actual.players().stream().map(PublicPlayerState::chipsWon).toList());
    }

    @Test
    @DisplayName("auto-advance broadcast has no actor, hides cards and carries the banner")
    void autoAdvanceBroadcast() {
        Game game = game(GamePhase.FLOP, null, BOARD, false, ReadyGateState.CLOSED,
                Map.of(), players(true, true), false);
        long revisionBefore = game.getStateRevision();

        service.broadcastGameStateWithAutoAdvance(game, "Banner");

        assertEquals(revisionBefore + 1, game.getStateRevision());
        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.FLOP,
                game.getCurrentHighestBet(), BOARD,
                List.of(state("a", "Alice", 90, 10, "ALL_IN", true, false, false, true, false,
                                HandRank.ONE_PAIR, List.of(), null, null, null),
                        state("b", "Bob", 80, 20, "ALL_IN", true, false, false, false, true,
                                HandRank.HIGH_CARD, List.of(), null, null, null)),
                null, null, null, null, true, "Banner", false, null, null, null, null, null, List.of(),
                null, null, 3L, ReadyGateState.CLOSED, NOW_MS, game.getStateRevision()),
                publicBroadcast());
    }

    @Test
    @DisplayName("normal broadcast sends the live view publicly and only own hole cards privately")
    void liveBroadcastPublicAndPrivate() {
        Game game = game(GamePhase.FLOP, null, BOARD, false, ReadyGateState.CLOSED,
                Map.of(), players(false, false), false);
        when(roomService.getRoom(GAME_ID)).thenReturn(room);
        room.addPlayer("Alice");
        room.addPlayer("Bob");

        service.broadcastGameState(game);

        PublicGameStateResponse pub = publicBroadcast();
        assertEquals(game.getStateRevision(), pub.stateRevision());
        assertEquals(9_000L, pub.inactivityDeadlineEpochMs());
        assertEquals(true, pub.players().stream().allMatch(p -> p.holeCards() == null));
        verify(messagingTemplate).convertAndSendToUser(eq("Alice:" + GAME_ID + ":" + room.getMembershipId("Alice")), eq("/queue/private"),
                eq(new PrivatePlayerState("a", 3L, List.of(ACE, KING))));
        verify(messagingTemplate).convertAndSendToUser(eq("Bob:" + GAME_ID + ":" + room.getMembershipId("Bob")), eq("/queue/private"),
                eq(new PrivatePlayerState("b", 3L, List.of(TWO, THREE))));
    }

    // ---------- private view ----------

    @Test
    @DisplayName("private view holds a copy of the player's own cards; an out seat or no cards gives an empty list")
    void privateViews() {
        List<Player> players = players(false, false);
        players.get(1).setIsOut();
        Game game = game(GamePhase.FLOP, null, BOARD, false, ReadyGateState.CLOSED,
                Map.of(), players, false);

        assertEquals(new PrivatePlayerState("a", 3L, List.of(ACE, KING)),
                service.getPrivatePlayerStateSnapshot(game, "Alice"));
        assertEquals(new PrivatePlayerState("b", 3L, List.of(TWO, THREE)),
                service.getPrivatePlayerStateSnapshot(game, "Bob"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.getPrivatePlayerStateSnapshot(game, "Spectator"));
    }

    // ---------- game end ----------

    @Test
    @DisplayName("contested win game-end view reveals in-hand cards and closes the ready gate")
    void gameEndContested() {
        HandSettlementResult settlement = new HandSettlementResult(
                Map.of("a", 30), Map.of(), Set.of("a"), 0, true);
        Game game = game(GamePhase.SHOWDOWN, settlement, BOARD, false, ReadyGateState.COUNTING_DOWN,
                Map.of(), players(false, false), true);
        Player winner = game.findPlayerByName("Alice");

        GameEndResponse response = service.buildGameEndResponse(game, winner, false);

        assertEquals(new GameEndResponse(ResponseMessage.GAME_END.getMessage(), GAME_ID, "Alice", 90, false,
                "Alice wins the game with 90 chips!",
                new PublicGameStateResponse(
                        6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(),
                        GamePhase.SHOWDOWN, game.getCurrentHighestBet(), BOARD,
                        List.of(state("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                        HandRank.ONE_PAIR, List.of(ACE, KING), true, null, List.of(ACE, KING)),
                                state("b", "Bob", 80, 20, "ACTIVE", false, false, false, false, true,
                                        HandRank.HIGH_CARD, List.of(TWO, THREE), false, null,
                                        List.of(TWO, THREE))),
                        null, null, List.of("Alice"), null, false, null, false, null, false, null, true, null,
                        List.of(), null, null, 3L, ReadyGateState.CLOSED, NOW_MS, game.getStateRevision())),
                response);
    }

    @Test
    @DisplayName("abandonment view has no winner, closed gate and the INACTIVITY reason")
    void abandonment() {
        Game game = game(GamePhase.FLOP, null, BOARD, false, ReadyGateState.CLOSED,
                Map.of(), players(false, false), false);

        GameEndResponse response = service.buildAbandonmentResponse(game);

        assertEquals(new GameEndResponse(ResponseMessage.GAME_END.getMessage(), GAME_ID, null, null, false,
                true, "INACTIVITY", "Game abandoned due to inactivity.",
                new PublicGameStateResponse(
                        6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.FLOP,
                        game.getCurrentHighestBet(), BOARD,
                        List.of(state("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                        null, List.of(), false, null, null),
                                state("b", "Bob", 80, 20, "ACTIVE", false, false, false, false, true,
                                        null, List.of(), false, null, null)),
                        null, null, List.of(), null, false, null, false, null, false, null, false, null,
                        List.of(), null, null, 3L, ReadyGateState.CLOSED, NOW_MS, game.getStateRevision())),
                response);
    }

    // ---------- helpers ----------

    private PublicGameStateResponse publicBroadcast() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        return (PublicGameStateResponse) captor.getValue();
    }

    private static PublicPlayerState state(String id, String name, int chips, int bet, String status,
            boolean allIn, boolean current, boolean folded, boolean smallBlind, boolean bigBlind,
            HandRank rank, List<Card> best, Boolean winner, Integer won, List<Card> hole) {
        return new PublicPlayerState(id, name, chips, bet, status, allIn, current, folded, smallBlind,
                bigBlind, rank, best, winner, won, hole, null, false);
    }

    private static List<Player> players(boolean allIn, boolean unused) {
        Player alice = Player.restoreBuilder().name("Alice").playerId("a").chips(90).currentBet(10)
                .holeCards(List.of(ACE, KING)).bestHand(List.of(ACE, KING)).handRank(HandRank.ONE_PAIR)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
        Player bob = Player.restoreBuilder().name("Bob").playerId("b").chips(80).currentBet(20)
                .holeCards(List.of(TWO, THREE)).bestHand(List.of(TWO, THREE)).handRank(HandRank.HIGH_CARD)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
        return new java.util.ArrayList<>(List.of(alice, bob));
    }

    private static Game game(GamePhase phase, HandSettlementResult settlement, List<Card> board,
            boolean countdown, ReadyGateState gate, Map<ScheduledGameTask, Long> deadlines,
            List<Player> players, boolean gameOver) {
        return Game.restoreBuilder()
                .gameId(GAME_ID)
                .maxPlayers(6)
                .players(players)
                .activePlayerIds(players.stream().filter(Player::isInHand).map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(board)
                .pot(30)
                .dealerAnchorPlayerId("a")
                .smallBlindPlayerId("a")
                .bigBlindPlayerId("b")
                .currentPlayerId("a")
                .currentHighestBet(20)
                .currentPhase(phase)
                .gameOver(gameOver)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of("a", 10, "b", 20))
                .preHandCarryOver(0)
                .lastSettlementResult(settlement)
                .readyCountdownActive(countdown)
                .readyCountdownDeadlineEpochMs(countdown ? 12_345L : null)
                .readyGateState(gate)
                .everyoneHasHadInitialTurn(false)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(deadlines)
                .handNumber(3L)
                .stateRevision(7L)
                .build();
    }
}
