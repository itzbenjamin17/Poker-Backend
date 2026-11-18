package com.pokergame.persistence;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.snapshot.RoomSnapshotMapper;
import com.pokergame.persistence.snapshot.RecoveredRoom;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.util.RoomCoordinator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Tests persistence recovery storage, quarantine, locking, and handler dispatch contracts. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PersistenceRecoveryTest {

    @Mock
    private EncryptedWalStore store;

    @Mock
    private RoomSnapshotMapper mapper;

    @Mock
    private RoomRecoveryHandler recoveryHandler;

    private GenericApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        applicationContext = new GenericApplicationContext();
        applicationContext.refresh();
    }

    @AfterEach
    void tearDown() {
        if (applicationContext != null) {
            applicationContext.close();
        }
    }

    @Test
    void mismatchedRoomIdentityQuarantinesWalAndRecoversRemainingRooms() {
        Room roomY = mock(Room.class);
        when(roomY.getRoomId()).thenReturn("room-Y");
        RecoveredRoom aggregate = new RecoveredRoom(roomY, null, false);

        Room roomValid = roomForRecovery("room-valid", "Alice");
        RecoveredRoom validAggregate = new RecoveredRoom(roomValid, null, false);

        when(store.recoverAll()).thenReturn(Map.of(
                "room-X", new byte[]{1, 2, 3},
                "room-valid", new byte[]{4, 5, 6}));
        when(mapper.deserialize(new byte[]{1, 2, 3})).thenReturn(aggregate);
        when(mapper.deserialize(new byte[]{4, 5, 6})).thenReturn(validAggregate);
        when(recoveryHandler.restore(validAggregate)).thenReturn(RecoveryDecision.KEEP);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, recoveryHandler, applicationContext, new RoomCoordinator());

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store).quarantine("room-X", "Snapshot room identity does not match WAL file identity");
        verify(recoveryHandler).restore(validAggregate);
        verify(recoveryHandler, never()).restore(aggregate);
        assertTrue(recovery.isComplete());
    }

    @Test
    void deletionTombstoneDeletesWalAndDoesNotInvokeHandler() {
        RecoveredRoom tombstone = new RecoveredRoom(null, null, true);

        when(store.recoverAll()).thenReturn(Map.of("room-deleted", new byte[]{1, 2, 3}));
        when(mapper.deserialize(any())).thenReturn(tombstone);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, recoveryHandler, applicationContext, new RoomCoordinator());

        recovery.run(new DefaultApplicationArguments());

        verify(store).delete("room-deleted");
        verifyNoInteractions(recoveryHandler);
        assertTrue(recovery.isComplete());
    }

    @Test
    void restoreAcquiresRoomLockAroundHandlerAndPublishesReadinessTransitions() {
        Room room = roomForRecovery("room-1", "Alice");
        RecoveredRoom aggregate = new RecoveredRoom(room, null, false);
        when(store.recoverAll()).thenReturn(Map.of("room-1", new byte[]{1, 2, 3}));
        when(mapper.deserialize(any())).thenReturn(aggregate);

        RoomCoordinator coordinator = new RoomCoordinator();
        AtomicBoolean lockHeldDuringRestore = new AtomicBoolean(false);
        when(recoveryHandler.restore(aggregate)).thenAnswer(_ -> {
            lockHeldDuringRestore.set(coordinator.isHeldByCurrentThread("room-1"));
            return RecoveryDecision.KEEP;
        });

        List<ReadinessState> states = new ArrayList<>();
        applicationContext.addApplicationListener(
                (ApplicationListener<AvailabilityChangeEvent<ReadinessState>>) event -> states.add(event.getState()));

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, recoveryHandler, applicationContext, coordinator);
        assertFalse(recovery.isComplete());

        recovery.run(new DefaultApplicationArguments());

        assertTrue(lockHeldDuringRestore.get());
        assertFalse(coordinator.isHeldByCurrentThread("room-1"));
        verify(store, never()).delete("room-1");
        verify(store, never()).quarantine(anyString(), anyString());
        assertEquals(List.of(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC), states);
        assertTrue(recovery.isComplete());
    }

    @Test
    void handlerDiscardDecisionDeletesWalWithoutQuarantine() {
        Room expiredRoom = roomForRecovery("expired-room", "Alice");
        RecoveredRoom expiredAggregate = new RecoveredRoom(expiredRoom, null, false);
        Room validRoom = roomForRecovery("valid-room", "Bob");
        RecoveredRoom validAggregate = new RecoveredRoom(validRoom, null, false);

        byte[] expiredBytes = new byte[]{1};
        byte[] validBytes = new byte[]{2};
        when(store.recoverAll()).thenReturn(Map.of(
                "expired-room", expiredBytes,
                "valid-room", validBytes));
        when(mapper.deserialize(expiredBytes)).thenReturn(expiredAggregate);
        when(mapper.deserialize(validBytes)).thenReturn(validAggregate);
        when(recoveryHandler.restore(expiredAggregate)).thenReturn(RecoveryDecision.DISCARD);
        when(recoveryHandler.restore(validAggregate)).thenReturn(RecoveryDecision.KEEP);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, recoveryHandler, applicationContext, new RoomCoordinator());

        recovery.run(new DefaultApplicationArguments());

        verify(store).delete("expired-room");
        verify(store, never()).delete("valid-room");
        verify(store, never()).quarantine(anyString(), anyString());
        assertTrue(recovery.isComplete());
    }

    @Test
    void startupQuarantinesUnreadableAndUnrestorableRoomsWhileKeepingValidRooms(@TempDir Path directory) {
        EncryptionKeyring keys = new EncryptionKeyring("current",
                Map.of("current", new SecretKeySpec(new byte[32], "AES")));
        RoomCoordinator coordinator = new RoomCoordinator();
        EncryptedWalStore realStore = new EncryptedWalStore(directory, keys, coordinator);
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        Room active = roomForRecovery("valid-game", "Alice");
        active.addPlayer("Bob");
        active.setGameStarted(true);
        Game game = new Game(active.getRoomId(),
                List.of(new Player("Alice", "p1", 950), new Player("Bob", "p2", 950)), 6, 10, 20);
        Room waiting = roomForRecovery("valid-room", "Eve");
        Room unrestorable = roomForRecovery("unrestorable", "Dave");
        for (Room room : List.of(active, waiting, unrestorable)) {
            Game activeGame = room == active ? game : null;
            withRoomLock(coordinator, room.getRoomId(), () -> {
                realStore.commit(realStore.prepare(room.getRoomId()),
                        realMapper.serialize(realMapper.captureStateImage(room, activeGame)));
                return null;
            });
        }
        withRoomLock(coordinator, "malformed-json", () -> {
            realStore.commit(realStore.prepare("malformed-json"), "{".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        when(recoveryHandler.restore(any())).thenAnswer(invocation -> {
            RecoveredRoom recovered = invocation.getArgument(0);
            if (unrestorable.getRoomId().equals(recovered.room().getRoomId())) {
                throw new IllegalStateException("Injected room registration failure");
            }
            return RecoveryDecision.KEEP;
        });

        PersistenceRecovery recovery = new PersistenceRecovery(
                realStore, realMapper, recoveryHandler, applicationContext, coordinator);
        recovery.run(new DefaultApplicationArguments());

        assertTrue(recovery.isComplete());
        assertTrue(realStore.isHealthy());
        assertNull(realStore.lastError());
        assertEquals(2, realStore.walCount());
        for (String roomId : List.of("unrestorable", "malformed-json")) {
            assertFalse(Files.exists(directory.resolve(roomId + ".wal")));
            assertTrue(Files.exists(directory.resolve(roomId + ".wal.corrupted")));
        }
        for (Room room : List.of(active, waiting)) {
            assertTrue(Files.exists(directory.resolve(room.getRoomId() + ".wal")));
            verify(recoveryHandler).restore(argThat(recovered -> room.getRoomId().equals(recovered.room().getRoomId())));
        }
    }

    @Test
    void startupQuarantinesSnapshotWithUnknownKeyAndRecoversValidRooms(@TempDir Path directory) {
        EncryptionKeyring keys = new EncryptionKeyring("current",
                Map.of("current", new SecretKeySpec(new byte[32], "AES")));
        RoomCoordinator coordinator = new RoomCoordinator();
        EncryptedWalStore realStore = new EncryptedWalStore(directory, keys, coordinator);
        JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(jsonMapper);

        Room validRoom = roomForRecovery("valid-room", "Alice");
        Room unknownKeyRoom = roomForRecovery("unknown-key-room", "Bob");
        withRoomLock(coordinator, validRoom.getRoomId(), () -> {
            realStore.commit(realStore.prepare(validRoom.getRoomId()),
                    realMapper.serialize(realMapper.captureStateImage(validRoom, null)));
            return null;
        });

        byte[] baseSnapshot = realMapper.serialize(realMapper.captureStateImage(unknownKeyRoom, null));
        ObjectNode tree = (ObjectNode) jsonMapper.readTree(baseSnapshot);
        tree.put("staleFieldFromOlderBuild", "unexpected");
        withRoomLock(coordinator, unknownKeyRoom.getRoomId(), () -> {
            realStore.commit(realStore.prepare(unknownKeyRoom.getRoomId()), jsonMapper.writeValueAsBytes(tree));
            return null;
        });
        when(recoveryHandler.restore(any())).thenReturn(RecoveryDecision.KEEP);

        PersistenceRecovery recovery = new PersistenceRecovery(
                realStore, realMapper, recoveryHandler, applicationContext, coordinator);
        recovery.run(new DefaultApplicationArguments());

        assertTrue(recovery.isComplete());
        assertTrue(realStore.isHealthy());
        assertFalse(Files.exists(directory.resolve("unknown-key-room.wal")));
        assertTrue(Files.exists(directory.resolve("unknown-key-room.wal.corrupted")));
        assertTrue(Files.exists(directory.resolve("valid-room.wal")));
        verify(recoveryHandler, never()).restore(argThat(r -> "unknown-key-room".equals(r.room().getRoomId())));
        verify(recoveryHandler).restore(argThat(r -> "valid-room".equals(r.room().getRoomId())));
    }

    @Test
    void startupQuarantinesSnapshotWithMissingKeyAndRecoversValidRooms(@TempDir Path directory) {
        EncryptionKeyring keys = new EncryptionKeyring("current",
                Map.of("current", new SecretKeySpec(new byte[32], "AES")));
        RoomCoordinator coordinator = new RoomCoordinator();
        EncryptedWalStore realStore = new EncryptedWalStore(directory, keys, coordinator);
        JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(jsonMapper);

        Room validRoom = roomForRecovery("valid-room", "Alice");
        Room missingKeyRoom = roomForRecovery("missing-key-room", "Bob");
        withRoomLock(coordinator, validRoom.getRoomId(), () -> {
            realStore.commit(realStore.prepare(validRoom.getRoomId()),
                    realMapper.serialize(realMapper.captureStateImage(validRoom, null)));
            return null;
        });

        byte[] baseSnapshot = realMapper.serialize(realMapper.captureStateImage(missingKeyRoom, null));
        ObjectNode tree = (ObjectNode) jsonMapper.readTree(baseSnapshot);
        tree.remove("game");
        withRoomLock(coordinator, missingKeyRoom.getRoomId(), () -> {
            realStore.commit(realStore.prepare(missingKeyRoom.getRoomId()), jsonMapper.writeValueAsBytes(tree));
            return null;
        });
        when(recoveryHandler.restore(any())).thenReturn(RecoveryDecision.KEEP);

        PersistenceRecovery recovery = new PersistenceRecovery(
                realStore, realMapper, recoveryHandler, applicationContext, coordinator);
        recovery.run(new DefaultApplicationArguments());

        assertTrue(recovery.isComplete());
        assertTrue(realStore.isHealthy());
        assertFalse(Files.exists(directory.resolve("missing-key-room.wal")));
        assertTrue(Files.exists(directory.resolve("missing-key-room.wal.corrupted")));
        assertTrue(Files.exists(directory.resolve("valid-room.wal")));
        verify(recoveryHandler, never()).restore(argThat(r -> "missing-key-room".equals(r.room().getRoomId())));
        verify(recoveryHandler).restore(argThat(r -> "valid-room".equals(r.room().getRoomId())));
    }

    @Test
    void corruptedSnapshotDeserializationFailureQuarantinesWalAndRecoversRemainingRooms() {
        Room validRoom = roomForRecovery("room-valid", "Alice");
        RecoveredRoom validAggregate = new RecoveredRoom(validRoom, null, false);

        byte[] corruptBytes = new byte[]{9, 9, 9};
        byte[] validBytes = new byte[]{1, 2, 3};

        when(store.recoverAll()).thenReturn(Map.of(
                "room-corrupt", corruptBytes,
                "room-valid", validBytes));
        when(mapper.deserialize(corruptBytes)).thenThrow(new PersistenceException("Corrupted JSON payload in WAL"));
        when(mapper.deserialize(validBytes)).thenReturn(validAggregate);
        when(recoveryHandler.restore(validAggregate)).thenReturn(RecoveryDecision.KEEP);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, recoveryHandler, applicationContext, new RoomCoordinator());

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store).quarantine("room-corrupt", "Corrupted JSON payload in WAL");
        verify(recoveryHandler).restore(validAggregate);
        assertTrue(recovery.isComplete());
    }

    @Test
    void handlerRestorationExceptionQuarantinesWalAndRecoversRemainingRooms() {
        Room brokenRoom = roomForRecovery("room-broken", "Bob");
        RecoveredRoom brokenAggregate = new RecoveredRoom(brokenRoom, null, false);
        Room validRoom = roomForRecovery("room-valid", "Alice");
        RecoveredRoom validAggregate = new RecoveredRoom(validRoom, null, false);

        byte[] brokenBytes = new byte[]{4, 5, 6};
        byte[] validBytes = new byte[]{1, 2, 3};

        when(store.recoverAll()).thenReturn(Map.of(
                "room-broken", brokenBytes,
                "room-valid", validBytes));
        when(mapper.deserialize(brokenBytes)).thenReturn(brokenAggregate);
        when(mapper.deserialize(validBytes)).thenReturn(validAggregate);
        when(recoveryHandler.restore(brokenAggregate))
                .thenThrow(new IllegalStateException("Inconsistent aggregate state during restore"));
        when(recoveryHandler.restore(validAggregate)).thenReturn(RecoveryDecision.KEEP);

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, recoveryHandler, applicationContext, new RoomCoordinator());

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store).quarantine("room-broken", "Inconsistent aggregate state during restore");
        verify(recoveryHandler).restore(validAggregate);
        assertTrue(recovery.isComplete());
    }

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
                store, mapper, recoveryHandler, applicationContext, new RoomCoordinator());

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store).quarantine("room-corrupt-1", "Corrupt 1");
        verify(store).quarantine("room-corrupt-2", "Corrupt 2");
        verifyNoInteractions(recoveryHandler);
        assertTrue(recovery.isComplete());
    }

    @Test
    void tombstoneDeleteFailureDoesNotQuarantineOrAbortRecovery() {
        RecoveredRoom tombstone = new RecoveredRoom(null, null, true);
        Room validRoom = roomForRecovery("room-valid", "Alice");
        RecoveredRoom validAggregate = new RecoveredRoom(validRoom, null, false);

        byte[] tombstoneBytes = new byte[]{1};
        byte[] validBytes = new byte[]{2};
        when(store.recoverAll()).thenReturn(Map.of(
                "room-tombstone", tombstoneBytes,
                "room-valid", validBytes));
        when(mapper.deserialize(tombstoneBytes)).thenReturn(tombstone);
        when(mapper.deserialize(validBytes)).thenReturn(validAggregate);
        when(recoveryHandler.restore(validAggregate)).thenReturn(RecoveryDecision.KEEP);
        doThrow(new PersistenceException("Cannot delete WAL")).when(store).delete("room-tombstone");

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, recoveryHandler, applicationContext, new RoomCoordinator());

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store, never()).quarantine(eq("room-tombstone"), anyString());
        verify(store).clearRoomTracking("room-tombstone");
        verify(recoveryHandler).restore(validAggregate);
        verify(recoveryHandler, never()).restore(argThat(r -> r != null && r.room() != null
                && "room-tombstone".equals(r.room().getRoomId())));
        assertTrue(recovery.isComplete());
    }

    @Test
    void discardedRoomDeleteFailureDoesNotQuarantineOrAbortRecovery() {
        Room discardedRoom = roomForRecovery("expired-room", "Alice");
        RecoveredRoom discardedAggregate = new RecoveredRoom(discardedRoom, null, false);
        Room validRoom = roomForRecovery("room-valid", "Bob");
        RecoveredRoom validAggregate = new RecoveredRoom(validRoom, null, false);

        byte[] discardedBytes = new byte[]{1};
        byte[] validBytes = new byte[]{2};
        when(store.recoverAll()).thenReturn(Map.of(
                "expired-room", discardedBytes,
                "room-valid", validBytes));
        when(mapper.deserialize(discardedBytes)).thenReturn(discardedAggregate);
        when(mapper.deserialize(validBytes)).thenReturn(validAggregate);
        when(recoveryHandler.restore(discardedAggregate)).thenReturn(RecoveryDecision.DISCARD);
        when(recoveryHandler.restore(validAggregate)).thenReturn(RecoveryDecision.KEEP);
        doThrow(new PersistenceException("Cannot delete discarded WAL")).when(store).delete("expired-room");

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, recoveryHandler, applicationContext, new RoomCoordinator());

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        verify(store, never()).quarantine(eq("expired-room"), anyString());
        verify(store).clearRoomTracking("expired-room");
        verify(recoveryHandler).restore(validAggregate);
        assertTrue(recovery.isComplete());
    }

    @Test
    void realStoreDeleteFailureDuringRecoveryDoesNotQuarantineOrWedgeStoreHealth(@TempDir Path directory) {
        EncryptionKeyring keys = new EncryptionKeyring("current",
                Map.of("current", new SecretKeySpec(new byte[32], "AES")));
        RoomCoordinator coordinator = new RoomCoordinator();
        AtomicBoolean failDelete = new AtomicBoolean(false);
        EncryptedWalStore realStore = new EncryptedWalStore(directory, keys, (point, roomId) -> {
            if (failDelete.get() && point == WalFaultPoint.BEFORE_DELETE) {
                throw new PersistenceException("injected delete failure for " + roomId);
            }
        }, coordinator);
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        Room validRoom = roomForRecovery("valid-room", "Alice");
        Room expiredLobby = roomForRecovery("expired-lobby", "Bob");

        withRoomLock(coordinator, validRoom.getRoomId(), () -> {
            realStore.commit(realStore.prepare(validRoom.getRoomId()),
                    realMapper.serialize(realMapper.captureStateImage(validRoom, null)));
            return null;
        });
        withRoomLock(coordinator, expiredLobby.getRoomId(), () -> {
            realStore.commit(realStore.prepare(expiredLobby.getRoomId()),
                    realMapper.serialize(realMapper.captureStateImage(expiredLobby, null)));
            return null;
        });
        withRoomLock(coordinator, "tombstone-room", () -> {
            realStore.commit(realStore.prepare("tombstone-room"),
                    realMapper.serializeDeletion("tombstone-room"));
            return null;
        });

        when(recoveryHandler.restore(argThat(r -> r != null && r.room() != null
                && "expired-lobby".equals(r.room().getRoomId()))))
                .thenReturn(RecoveryDecision.DISCARD);
        when(recoveryHandler.restore(argThat(r -> r != null && r.room() != null
                && "valid-room".equals(r.room().getRoomId()))))
                .thenReturn(RecoveryDecision.KEEP);

        failDelete.set(true);

        PersistenceRecovery recovery = new PersistenceRecovery(
                realStore, realMapper, recoveryHandler, applicationContext, coordinator);

        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()));

        assertTrue(recovery.isComplete());
        assertTrue(realStore.isHealthy(), "Failed delete of un-restored room must not wedge store health");
        assertEquals(0, realStore.unsavedRoomCount());
        assertNull(realStore.lastError());
        assertTrue(realStore.isHealthy("expired-lobby"));
        assertTrue(realStore.isHealthy("tombstone-room"));
        assertFalse(withRoomLock(coordinator, "expired-lobby", () -> realStore.requiresCheckpoint("expired-lobby")));
        assertFalse(withRoomLock(coordinator, "tombstone-room", () -> realStore.requiresCheckpoint("tombstone-room")));

        assertTrue(Files.exists(directory.resolve("expired-lobby.wal")));
        assertFalse(Files.exists(directory.resolve("expired-lobby.wal.corrupted")));
        assertTrue(Files.exists(directory.resolve("tombstone-room.wal")));
        assertFalse(Files.exists(directory.resolve("tombstone-room.wal.corrupted")));

        verify(recoveryHandler).restore(argThat(r -> "valid-room".equals(r.room().getRoomId())));
        verify(recoveryHandler).restore(argThat(r -> "expired-lobby".equals(r.room().getRoomId())));
    }

    private Room roomForRecovery(String roomId, String host) {
        Room room = new Room(roomId, roomId, host, 6, 10, 20, 1000, null);
        room.addPlayer(host);
        return room;
    }
}
