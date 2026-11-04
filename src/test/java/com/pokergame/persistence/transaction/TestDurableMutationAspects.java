package com.pokergame.persistence.transaction;

import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.mock;

/** Builds aspects for unit tests that need real per-room locking without storage. */
public final class TestDurableMutationAspects {
    private TestDurableMutationAspects() {
    }

    /**
     * Creates an aspect in memory-only mode: it hands out real room locks but never snapshots or writes.
     *
     * @return a fresh aspect with its own lock registry
     */
    @SuppressWarnings("unchecked")
    public static DurableMutationAspect lockOnly() {
        return new DurableMutationAspect(null, mock(AggregateSnapshotMapper.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), new PersistenceProperties());
    }
}
