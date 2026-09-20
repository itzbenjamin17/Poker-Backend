package com.pokergame.persistence;

import com.pokergame.config.WebSocketEventListener;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.config.PersistenceException;

/** Tests persistence recovery coordinator behavior. */
@ExtendWith(MockitoExtension.class)
class PersistenceRecoveryTest {

    @Mock
    private EncryptedWalStore store;

    @Mock
    private AggregateSnapshotMapper mapper;

    @Mock
    private RoomService roomService;

    @Mock
    private GameLifecycleService gameLifecycleService;

    @Mock
    private WebSocketEventListener webSocketEventListener;

    private GenericApplicationContext applicationContext;
    private static final long DISCONNECT_GRACE_PERIOD_MS = 15_000L;

    @BeforeEach
    void setUp() {
        applicationContext = new GenericApplicationContext();
        applicationContext.refresh();
    }

    /**
     * Protects the contract that recovery rejects snapshots whose room identity does not match the WAL file identity.
     */
    @Test
    void mismatchedRoomIdentityThrowsPersistenceException() {
        Room roomY = mock(Room.class);
        when(roomY.getRoomId()).thenReturn("room-Y");
        RecoveredAggregate aggregate = new RecoveredAggregate(roomY, "host", null, false);

        when(store.recoverAll()).thenReturn(Map.of("room-X", new byte[]{1, 2, 3}));
        when(mapper.deserialize(any())).thenReturn(aggregate);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> recovery.run(new DefaultApplicationArguments()));

        assertEquals("Snapshot room identity does not match WAL file identity", ex.getMessage());
        assertFalse(recovery.isComplete());
    }

    /**
     * Protects the contract that committed deletion tombstones delete the WAL file and do not restore the room.
     */
    @Test
    void deletionTombstoneDeletesWalAndDoesNotRegisterRoom() {
        RecoveredAggregate tombstone = new RecoveredAggregate(null, null, null, true);

        when(store.recoverAll()).thenReturn(Map.of("room-deleted", new byte[]{1, 2, 3}));
        when(mapper.deserialize(any())).thenReturn(tombstone);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        recovery.run(new DefaultApplicationArguments());

        verify(store).delete("room-deleted");
        verifyNoInteractions(roomService);
        verifyNoInteractions(gameLifecycleService);
        verifyNoInteractions(webSocketEventListener);
        assertTrue(recovery.isComplete());
    }

    /**
     * Protects the contract that aggregate recovery marks players disconnected with Math.max deadline logic and resumes timers.
     */
    @Test
    void happyPathRecoversAggregateAndMarksPlayersDisconnectedWithCorrectDeadlines() {
        Room room = new Room("room-1", "Room 1", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");

        Player alice = new Player("Alice", "alice-id", 1000);
        long futureStoredDeadline = System.currentTimeMillis() + 60_000L;
        alice.setDisconnectDeadlineEpochMs(futureStoredDeadline);

        Player bob = new Player("Bob", "bob-id", 1000);
        bob.setDisconnectDeadlineEpochMs(null);

        Game game = mock(Game.class);
        when(game.getGameId()).thenReturn("room-1");
        when(game.getPlayers()).thenReturn(List.of(alice, bob));

        RecoveredAggregate aggregate = new RecoveredAggregate(room, "Alice", game, false);
        when(store.recoverAll()).thenReturn(Map.of("room-1", new byte[]{1, 2, 3}));
        when(mapper.deserialize(any())).thenReturn(aggregate);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        long beforeRun = System.currentTimeMillis();
        recovery.run(new DefaultApplicationArguments());
        long afterRun = System.currentTimeMillis();

        verify(roomService).restoreRoom(room, "Alice");
        verify(gameLifecycleService).restoreGame(game);
        verify(gameLifecycleService).resumeRecoveredTimers("room-1");

        assertTrue(alice.getIsDisconnected());
        assertEquals(futureStoredDeadline, alice.getDisconnectDeadlineEpochMs());
        verify(webSocketEventListener).scheduleRecoveredDisconnect("room-1", "Alice", futureStoredDeadline);

        assertTrue(bob.getIsDisconnected());
        assertNotNull(bob.getDisconnectDeadlineEpochMs());
        assertTrue(bob.getDisconnectDeadlineEpochMs() >= beforeRun + DISCONNECT_GRACE_PERIOD_MS);
        assertTrue(bob.getDisconnectDeadlineEpochMs() <= afterRun + DISCONNECT_GRACE_PERIOD_MS);

        ArgumentCaptor<Long> bobDeadlineCaptor = ArgumentCaptor.forClass(Long.class);
        verify(webSocketEventListener).scheduleRecoveredDisconnect(eq("room-1"), eq("Bob"), bobDeadlineCaptor.capture());
        assertEquals(bob.getDisconnectDeadlineEpochMs(), bobDeadlineCaptor.getValue());

        assertTrue(recovery.isComplete());
    }

    /**
     * Protects the contract that recovery re-registers scheduled timers for active games and disconnect deadlines for all players.
     */
    @Test
    void happyPathRecoveryReRegistersScheduledTimersAndDisconnectDeadlines() {
        Room activeRoom = new Room("room-active", "Active Game Room", "Alice", 6, 10, 20, 1000, null);
        activeRoom.addPlayer("Alice");
        activeRoom.addPlayer("Bob");

        Player alice = new Player("Alice", "alice-id", 1000);
        long futureDeadline = System.currentTimeMillis() + 45_000L;
        alice.setDisconnectDeadlineEpochMs(futureDeadline);

        Player bob = new Player("Bob", "bob-id", 1000);
        bob.setDisconnectDeadlineEpochMs(null);

        Game activeGame = mock(Game.class);
        when(activeGame.getGameId()).thenReturn("room-active");
        when(activeGame.getPlayers()).thenReturn(List.of(alice, bob));

        RecoveredAggregate activeAggregate = new RecoveredAggregate(activeRoom, "Alice", activeGame, false);

        Room lobbyRoom = new Room("room-lobby", "Lobby Room", "Charlie", 6, 10, 20, 1000, null);
        lobbyRoom.addPlayer("Charlie");
        RecoveredAggregate lobbyAggregate = new RecoveredAggregate(lobbyRoom, "Charlie", null, false);

        when(store.recoverAll()).thenReturn(Map.of(
                "room-active", new byte[]{1, 2},
                "room-lobby", new byte[]{3, 4}));
        when(mapper.deserialize(new byte[]{1, 2})).thenReturn(activeAggregate);
        when(mapper.deserialize(new byte[]{3, 4})).thenReturn(lobbyAggregate);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        long beforeRun = System.currentTimeMillis();
        recovery.run(new DefaultApplicationArguments());
        long afterRun = System.currentTimeMillis();

        // Active game: restored and timers resumed
        verify(gameLifecycleService).restoreGame(activeGame);
        verify(gameLifecycleService).resumeRecoveredTimers("room-active");

        // Lobby room: no game restored or timers resumed
        verify(gameLifecycleService, never()).resumeRecoveredTimers("room-lobby");

        // Disconnect deadlines registered for all players
        verify(webSocketEventListener).scheduleRecoveredDisconnect("room-active", "Alice", futureDeadline);

        ArgumentCaptor<Long> bobDeadlineCaptor = ArgumentCaptor.forClass(Long.class);
        verify(webSocketEventListener).scheduleRecoveredDisconnect(eq("room-active"), eq("Bob"), bobDeadlineCaptor.capture());
        assertTrue(bobDeadlineCaptor.getValue() >= beforeRun + DISCONNECT_GRACE_PERIOD_MS);
        assertTrue(bobDeadlineCaptor.getValue() <= afterRun + DISCONNECT_GRACE_PERIOD_MS);

        ArgumentCaptor<Long> charlieDeadlineCaptor = ArgumentCaptor.forClass(Long.class);
        verify(webSocketEventListener).scheduleRecoveredDisconnect(eq("room-lobby"), eq("Charlie"), charlieDeadlineCaptor.capture());
        assertTrue(charlieDeadlineCaptor.getValue() >= beforeRun + DISCONNECT_GRACE_PERIOD_MS);
        assertTrue(charlieDeadlineCaptor.getValue() <= afterRun + DISCONNECT_GRACE_PERIOD_MS);

        assertTrue(recovery.isComplete());
    }
}
