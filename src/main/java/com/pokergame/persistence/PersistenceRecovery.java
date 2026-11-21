package com.pokergame.persistence;

import com.pokergame.persistence.snapshot.RecoveredRoom;
import com.pokergame.persistence.snapshot.RoomSnapshotMapper;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Reloads all saved poker games and rooms back into memory when the server starts up.
 * <p>
 * We load everything up front before opening the server to player traffic. If we only loaded
 * games on demand, players looking at the lobby might think an active game had disappeared,
 * and background timers for ongoing rooms would not resume until someone reconnected.
 * </p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PersistenceRecovery implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(PersistenceRecovery.class);

    private final EncryptedWalStore store;
    private final RoomSnapshotMapper mapper;
    private final RoomRecoveryHandler recoveryHandler;
    private final ApplicationContext applicationContext;
    private final RoomCoordinator roomCoordinator;

    private volatile boolean complete;

    /**
     * Creates the startup recovery runner.
     *
     * @param store              reads, deletes, and quarantines encrypted room save files on disk
     * @param mapper             converts raw saved bytes back into Room and Game objects
     * @param recoveryHandler    applies restart rules (expiry checks, timers, disconnect grace) to each room
     * @param applicationContext publishes server readiness events so traffic waits until recovery finishes
     * @param roomCoordinator    locks each room while it is being restored or cleaned up
     */
    public PersistenceRecovery(
            EncryptedWalStore store,
            RoomSnapshotMapper mapper,
            RoomRecoveryHandler recoveryHandler,
            ApplicationContext applicationContext,
            RoomCoordinator roomCoordinator) {
        this.store = store;
        this.mapper = mapper;
        this.recoveryHandler = recoveryHandler;
        this.applicationContext = applicationContext;
        this.roomCoordinator = roomCoordinator;
    }

    /**
     * Loads every saved room from disk, removes files for deleted or expired rooms, and restores
     * active rooms and timers before opening the server to player traffic.
     *
     * @param args application startup arguments
     * @throws PersistenceException if the save folder cannot be read or a required decryption key is
     *                              missing; individual corrupted or unreadable room files are
     *                              quarantined ({@code <roomId>.wal.corrupted}) so other rooms still recover
     */
    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    public boolean isComplete() {
        return complete;
    }

    private void recover() {
        long startMs = System.currentTimeMillis();
        logger.info("Starting persistence recovery");

        // Stop the server from accepting incoming requests while we load data
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);

        Map<String, byte[]> images = store.recoverAll();
        List<RecoveredRoom> recoveredRooms = new ArrayList<>();
        int roomsRestored = 0;
        int gamesRestored = 0;
        int roomsDeleted = 0;

        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            String roomId = entry.getKey();
            RecoveredRoom recoveredRoom;
            try {
                recoveredRoom = mapper.deserialize(entry.getValue());
            } catch (Exception e) {
                logger.error("Failed to recover room {}; quarantining corrupted WAL", roomId, e);
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    store.quarantine(roomId, e.getMessage());
                }
                continue;
            }

            if (recoveredRoom.deleted()) {
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    if (tryDeleteWal(roomId, "tombstone")) {
                        logger.info("Deleted tombstone room {}", roomId);
                        roomsDeleted++;
                    }
                }
                continue;
            }

            if (recoveredRoom.room() == null || !roomId.equals(recoveredRoom.room().getRoomId())) {
                logger.error("Snapshot room identity does not match WAL file identity for room {}", roomId);
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    store.quarantine(roomId, "Snapshot room identity does not match WAL file identity");
                }
                continue;
            }

            recoveredRooms.add(recoveredRoom);
        }

        for (RecoveredRoom recoveredRoom : recoveredRooms) {
            String roomId = recoveredRoom.room().getRoomId();
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                try {
                    RecoveryDecision decision = recoveryHandler.restore(recoveredRoom);
                    if (decision == RecoveryDecision.DISCARD) {
                        if (tryDeleteWal(roomId, "discarded by recovery handler")) {
                            roomsDeleted++;
                        }
                    } else if (decision == RecoveryDecision.KEEP) {
                        roomsRestored++;
                        if (recoveredRoom.game() != null) {
                            gamesRestored++;
                        }
                        logger.debug("Recovered room: roomId={}, hasGame={}", roomId, recoveredRoom.game() != null);
                    } else {
                        throw new IllegalStateException("Unexpected recovery decision: " + decision);
                    }
                } catch (Exception e) {
                    logger.error("Failed to recover room {}; quarantining corrupted WAL", roomId, e);
                    store.quarantine(roomId, e.getMessage());
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        logger.info("Persistence recovery summary: rooms restored={}, games restored={}, rooms deleted={}, elapsed ms={}",
                roomsRestored, gamesRestored, roomsDeleted, elapsedMs);

        complete = true;
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
        logger.info("Recovery complete, accepting traffic");
    }

    private boolean tryDeleteWal(String roomId, String reason) {
        try {
            store.delete(roomId);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to delete WAL for {} room {} during recovery; skipping", reason, roomId, e);
            store.clearRoomTracking(roomId);
            return false;
        }
    }
}
