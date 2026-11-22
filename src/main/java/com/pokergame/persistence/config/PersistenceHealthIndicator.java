package com.pokergame.persistence.config;

import com.pokergame.persistence.PersistenceRecovery;
import com.pokergame.persistence.wal.EncryptedWalStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports whether saved games have been reloaded and whether saving is working.
 * The status is DOWN until recovery has finished. After that it stays UP even if saving fails,
 * because the game keeps running in memory; the {@code mode} detail says "MEMORY_ONLY" in that case.
 */
final class PersistenceHealthIndicator implements HealthIndicator {
    private final EncryptedWalStore store;
    private final PersistenceRecovery recovery;

    /**
     * Creates the indicator.
     *
     * @param store    the encrypted save store
     * @param recovery the startup recovery
     */
    PersistenceHealthIndicator(EncryptedWalStore store, PersistenceRecovery recovery) {
        this.store = store;
        this.recovery = recovery;
    }

    @Override
    public Health health() {
        boolean persistenceHealthy = store.isHealthy();
        String lastStorageError = store.lastError();
        Health.Builder builder = recovery.isComplete() ? Health.up() : Health.down();
        return builder.withDetail("recoveryComplete", recovery.isComplete())
                .withDetail("persistenceHealthy", persistenceHealthy)
                .withDetail("mode", !recovery.isComplete() ? "RECOVERING" : persistenceHealthy ? "DURABLE" : "MEMORY_ONLY")
                .withDetail("unsavedRoomCount", store.unsavedRoomCount())
                .withDetail("activeWalCount", store.walCount())
                .withDetail("lastStorageError", lastStorageError == null ? "none" : lastStorageError)
                .build();
    }
}
