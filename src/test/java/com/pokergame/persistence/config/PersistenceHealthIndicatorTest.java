package com.pokergame.persistence.config;

import com.pokergame.persistence.PersistenceRecovery;
import com.pokergame.persistence.wal.EncryptedWalStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PersistenceHealthIndicator}. */
@Tag("unit")
class PersistenceHealthIndicatorTest {

    private final EncryptedWalStore store = mock(EncryptedWalStore.class);
    private final PersistenceRecovery recovery = mock(PersistenceRecovery.class);
    private final PersistenceHealthIndicator indicator = new PersistenceHealthIndicator(store, recovery);

    @Test
    void isDownAndRecoveringUntilRecoveryCompletes() {
        when(recovery.isComplete()).thenReturn(false);
        when(store.isHealthy()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("mode", "RECOVERING").containsEntry("recoveryComplete", false);
    }

    @Test
    void isUpAndDurableWhenRecoveredAndSavingWorks() {
        when(recovery.isComplete()).thenReturn(true);
        when(store.isHealthy()).thenReturn(true);
        when(store.lastError()).thenReturn(null);
        when(store.walCount()).thenReturn(2L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("mode", "DURABLE")
                .containsEntry("persistenceHealthy", true)
                .containsEntry("lastStorageError", "none")
                .containsEntry("activeWalCount", 2L);
    }

    @Test
    void staysUpButReportsMemoryOnlyWhenSavingFails() {
        when(recovery.isComplete()).thenReturn(true);
        when(store.isHealthy()).thenReturn(false);
        when(store.lastError()).thenReturn("disk full");
        when(store.unsavedRoomCount()).thenReturn(1);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("mode", "MEMORY_ONLY")
                .containsEntry("persistenceHealthy", false)
                .containsEntry("lastStorageError", "disk full")
                .containsEntry("unsavedRoomCount", 1);
    }
}
