package com.pokergame.testutil;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe controllable UTC {@link Clock} for deterministic timing tests.
 */
public final class MutableClock extends Clock {
    private final AtomicLong epochMs;
    private final ZoneId zone;

    /**
     * Creates a controllable UTC clock initialized to {@code initialEpochMs}.
     *
     * @param initialEpochMs initial timestamp in UTC epoch milliseconds
     */
    public MutableClock(long initialEpochMs) {
        this(new AtomicLong(initialEpochMs), ZoneOffset.UTC);
    }

    private MutableClock(AtomicLong epochMs, ZoneId zone) {
        this.epochMs = Objects.requireNonNull(epochMs, "epochMs must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /**
     * Sets the current clock reading to {@code newEpochMs}.
     *
     * @param newEpochMs new timestamp in UTC epoch milliseconds
     */
    public void setMillis(long newEpochMs) {
        epochMs.set(newEpochMs);
    }

    /**
     * Advances the current clock reading by {@code deltaMs} milliseconds.
     *
     * @param deltaMs number of milliseconds to add to the current reading
     */
    public void advanceByMillis(long deltaMs) {
        epochMs.addAndGet(deltaMs);
    }

    /** {@inheritDoc} */
    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** {@inheritDoc} */
    @Override
    public Clock withZone(ZoneId zone) {
        ZoneId targetZone = Objects.requireNonNull(zone, "zone must not be null");
        return Objects.equals(this.zone, targetZone) ? this : new MutableClock(epochMs, targetZone);
    }

    /** {@inheritDoc} */
    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(epochMs.get());
    }

    /** {@inheritDoc} */
    @Override
    public long millis() {
        return epochMs.get();
    }
}
