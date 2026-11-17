package com.pokergame.persistence;

import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
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
 * We load everything all at once before letting players connect. If we only loaded
 * games when players asked for them, a player looking at the lobby might think
 * an ongoing game had disappeared just because it hadn't been loaded yet.
 * </p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PersistenceRecovery implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(PersistenceRecovery.class);

    private final EncryptedWalStore store;
    private final AggregateSnapshotMapper mapper;
    private final RoomRecoveryHandler recoveryHandler;
    private final ApplicationContext applicationContext;
    private final RoomCoordinator roomCoordinator;
    private volatile boolean complete;

    /**
     * Sets up the startup recovery runner with WAL storage, snapshot decoding, domain
     * restoration handler, readiness publication context, and room lock coordinator.
     *
     * @param store              where the encrypted game data is saved on disk
     * @param mapper             converts raw bytes back into game and room objects
     * @param recoveryHandler    applies domain restart rules to each recovered room
     * @param applicationContext tells the application when it's safe to start taking requests
     * @param roomCoordinator    coordinator managing per-room concurrency
     */
    public PersistenceRecovery(
            EncryptedWalStore store,
            AggregateSnapshotMapper mapper,
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
     * Loads all saved game data, cleans up deleted rooms, and delegates domain restart
     * restoration before letting any players connect.
     *
     * @param args application startup arguments
     * @throws PersistenceException if store-level WAL enumeration or I/O fails; per-room
     *                              corrupted or unreadable WAL files are quarantined
     *                              ({@code <roomId>.wal.corrupted}) so remaining rooms can recover
     */
    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /**
     * Tells us if the server has finished loading all the games from disk.
     * Even if the files on disk are fine, the server isn't ready until this is true.
     *
     * @return {@code true} only after all games and timers are fully loaded and ready
     */
    public boolean isComplete() {
        return complete;
    }

    private void recover() {
        long startMs = System.currentTimeMillis();
        logger.info("Starting persistence recovery");

        // Stop the server from accepting incoming requests while we load data
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);

        Map<String, byte[]> images = store.recoverAll();
        List<RecoveredAggregate> recoveredAggregates = new ArrayList<>();
        int roomsRestored = 0;
        int gamesRestored = 0;
        int roomsDeleted = 0;

        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            String roomId = entry.getKey();
            RecoveredAggregate aggregate;
            try {
                aggregate = mapper.deserialize(entry.getValue());
            } catch (Exception e) {
                logger.error("Failed to recover room {}; quarantining corrupted WAL", roomId, e);
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    store.quarantine(roomId, e.getMessage());
                }
                continue;
            }

            if (aggregate.deleted()) {
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    if (tryDeleteWal(roomId, "tombstone")) {
                        logger.info("Deleted tombstone room {}", roomId);
                        roomsDeleted++;
                    }
                }
                continue;
            }

            if (aggregate.room() == null || !roomId.equals(aggregate.room().getRoomId())) {
                logger.error("Snapshot room identity does not match WAL file identity for room {}", roomId);
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    store.quarantine(roomId, "Snapshot room identity does not match WAL file identity");
                }
                continue;
            }

            recoveredAggregates.add(aggregate);
        }

        for (RecoveredAggregate aggregate : recoveredAggregates) {
            String roomId = aggregate.room().getRoomId();
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                try {
                    RecoveryDecision decision = recoveryHandler.restore(aggregate);
                    if (decision == RecoveryDecision.DISCARD) {
                        if (tryDeleteWal(roomId, "discarded by recovery handler")) {
                            roomsDeleted++;
                        }
                    } else if (decision == RecoveryDecision.KEEP) {
                        roomsRestored++;
                        if (aggregate.game() != null) {
                            gamesRestored++;
                        }
                        logger.debug("Recovered room: roomId={}, hasGame={}", roomId, aggregate.game() != null);
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
