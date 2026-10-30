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
import static org.mockito.ArgumentMatchers.anyLong;
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
     * Protects the contract that recovery quarantines snapshots whose room identity does not match the WAL file identity.
     */
    @Test
    void mismatchedRoomIdentityThrowsPersistenceException() {
        Room roomY = mock(Room.class);
        when(roomY.getRoomId()).thenReturn("room-Y");
        RecoveredAggregate aggregate = new RecoveredAggregate(roomY, "host", null, false);

        Room roomValid = new Room("room-valid", "Valid Room", "Alice", 6, 10, 20, 1000, null);
        roomValid.addPlayer("Alice");
        RecoveredAggregate validAggregate = new RecoveredAggregate(roomValid, "Alice", null, false);

        when(store.recoverAll()).thenReturn(Map.of(
                "room-X", new byte[]{1, 2, 3},
                "room-valid", new byte[]{4, 5, 6}));
        when(mapper.deserialize(new byte[]{1, 2, 3})).thenReturn(aggregate);
        when(mapper.deserialize(new byte[]{4, 5, 6})).thenReturn(validAggregate);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store).quarantine("room-X", "Snapshot room identity does not match WAL file identity");
        verify(roomService).restoreRoom(roomValid, "Alice");
        assertTrue(recovery.isComplete());
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

    /**
     * Finding B10-8 / Ticket 36:
     * Protects the contract that when a corrupted snapshot causes mapper.deserialize to throw,
     * PersistenceRecovery catches the exception, calls store.quarantine(roomId, reason),
     * does not abort startup, and recovers all remaining valid rooms and games.
     */
    @Test
    void corruptedSnapshotDeserializationFailureQuarantinesWalAndRecoversRemainingRooms() {
        Room validRoom = new Room("room-valid", "Valid Room", "Alice", 6, 10, 20, 1000, null);
        validRoom.addPlayer("Alice");
        Player alice = new Player("Alice", "alice-id", 1000);
        Game validGame = mock(Game.class);
        when(validGame.getGameId()).thenReturn("room-valid");
        when(validGame.getPlayers()).thenReturn(List.of(alice));
        RecoveredAggregate validAggregate = new RecoveredAggregate(validRoom, "Alice", validGame, false);

        byte[] corruptBytes = new byte[]{9, 9, 9};
        byte[] validBytes = new byte[]{1, 2, 3};

        when(store.recoverAll()).thenReturn(Map.of(
                "room-corrupt", corruptBytes,
                "room-valid", validBytes));
        when(mapper.deserialize(corruptBytes)).thenThrow(new PersistenceException("Corrupted JSON payload in WAL"));
        when(mapper.deserialize(validBytes)).thenReturn(validAggregate);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        // Quarantine called for the corrupt room with error message
        verify(store).quarantine(eq("room-corrupt"), eq("Corrupted JSON payload in WAL"));

        // Valid room and game are restored into services
        verify(roomService).restoreRoom(validRoom, "Alice");
        verify(gameLifecycleService).restoreGame(validGame);
        verify(gameLifecycleService).resumeRecoveredTimers("room-valid");
        verify(webSocketEventListener).scheduleRecoveredDisconnect(eq("room-valid"), eq("Alice"), anyLong());

        assertTrue(recovery.isComplete(), "Recovery must be marked complete and accept traffic despite one corrupt room");
    }

    /**
     * Finding B10-8 / Ticket 36:
     * Protects the contract that when aggregate restoration throws (e.g. restoreGame throws runtime exception),
     * PersistenceRecovery catches the exception, calls store.quarantine(roomId, reason),
     * does not abort startup, and recovers all remaining valid rooms and games.
     */
    @Test
    void aggregateGameRestorationExceptionQuarantinesWalAndRecoversRemainingRooms() {
        Room brokenRoom = new Room("room-broken", "Broken Game Room", "Bob", 6, 10, 20, 1000, null);
        brokenRoom.addPlayer("Bob");
        Player bob = new Player("Bob", "bob-id", 1000);
        Game brokenGame = mock(Game.class);
        when(brokenGame.getPlayers()).thenReturn(List.of(bob));
        RecoveredAggregate brokenAggregate = new RecoveredAggregate(brokenRoom, "Bob", brokenGame, false);

        Room validRoom = new Room("room-valid", "Valid Room", "Alice", 6, 10, 20, 1000, null);
        validRoom.addPlayer("Alice");
        Player alice = new Player("Alice", "alice-id", 1000);
        Game validGame = mock(Game.class);
        when(validGame.getGameId()).thenReturn("room-valid");
        when(validGame.getPlayers()).thenReturn(List.of(alice));
        RecoveredAggregate validAggregate = new RecoveredAggregate(validRoom, "Alice", validGame, false);

        byte[] brokenBytes = new byte[]{4, 5, 6};
        byte[] validBytes = new byte[]{1, 2, 3};

        when(store.recoverAll()).thenReturn(Map.of(
                "room-broken", brokenBytes,
                "room-valid", validBytes));
        when(mapper.deserialize(brokenBytes)).thenReturn(brokenAggregate);
        when(mapper.deserialize(validBytes)).thenReturn(validAggregate);

        doThrow(new IllegalStateException("Inconsistent aggregate state during restoreGame"))
                .when(gameLifecycleService).restoreGame(brokenGame);
        doNothing().when(gameLifecycleService).restoreGame(validGame);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        // Quarantine called for the broken room
        verify(store).quarantine(eq("room-broken"), eq("Inconsistent aggregate state during restoreGame"));

        // Valid room and game restored cleanly
        verify(roomService).restoreRoom(validRoom, "Alice");
        verify(gameLifecycleService).restoreGame(validGame);
        verify(gameLifecycleService).resumeRecoveredTimers("room-valid");

        assertTrue(recovery.isComplete(), "Recovery must complete and accept traffic despite one broken game");
    }

    /**
     * Finding B10-8 / Ticket 36:
     * Protects the contract that when roomService.restoreRoom throws a runtime exception,
     * PersistenceRecovery catches the exception, calls store.quarantine(roomId, reason),
     * does not abort startup, and recovers all remaining valid rooms.
     */
    @Test
    void roomServiceRestorationExceptionQuarantinesWalAndRecoversRemainingRooms() {
        Room brokenRoom = new Room("room-broken", "Broken Room", "Bob", 6, 10, 20, 1000, null);
        brokenRoom.addPlayer("Bob");
        RecoveredAggregate brokenAggregate = new RecoveredAggregate(brokenRoom, "Bob", null, false);

        Room validRoom = new Room("room-valid", "Valid Room", "Alice", 6, 10, 20, 1000, null);
        validRoom.addPlayer("Alice");
        RecoveredAggregate validAggregate = new RecoveredAggregate(validRoom, "Alice", null, false);

        byte[] brokenBytes = new byte[]{7, 8};
        byte[] validBytes = new byte[]{1, 2};

        when(store.recoverAll()).thenReturn(Map.of(
                "room-broken", brokenBytes,
                "room-valid", validBytes));
        when(mapper.deserialize(brokenBytes)).thenReturn(brokenAggregate);
        when(mapper.deserialize(validBytes)).thenReturn(validAggregate);

        doThrow(new RuntimeException("Room host constraint violated"))
                .when(roomService).restoreRoom(brokenRoom, "Bob");
        doNothing().when(roomService).restoreRoom(validRoom, "Alice");

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store).quarantine(eq("room-broken"), eq("Room host constraint violated"));
        verify(roomService).restoreRoom(validRoom, "Alice");
        assertTrue(recovery.isComplete());
    }

    /**
     * Finding B10-8 / Ticket 36:
     * Protects the contract that when all room WAL files are corrupted,
     * PersistenceRecovery quarantines every corrupt file and still completes startup cleanly.
     */
    @Test
    void allRoomsCorruptedQuarantinesAllAndCompletesStartupWithoutCrash() {
        byte[] corrupt1 = new byte[]{1};
        byte[] corrupt2 = new byte[]{2};

        when(store.recoverAll()).thenReturn(Map.of(
                "room-corrupt-1", corrupt1,
                "room-corrupt-2", corrupt2));
        when(mapper.deserialize(corrupt1)).thenThrow(new PersistenceException("Corrupt 1"));
        when(mapper.deserialize(corrupt2)).thenThrow(new PersistenceException("Corrupt 2"));

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, roomService, gameLifecycleService,
                webSocketEventListener, applicationContext, DISCONNECT_GRACE_PERIOD_MS);

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store).quarantine("room-corrupt-1", "Corrupt 1");
        verify(store).quarantine("room-corrupt-2", "Corrupt 2");
        verifyNoInteractions(roomService);
        verifyNoInteractions(gameLifecycleService);
        assertTrue(recovery.isComplete());
    }
}
