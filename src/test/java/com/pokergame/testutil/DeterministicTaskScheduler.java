package com.pokergame.testutil;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * Thread-safe deterministic {@link TaskScheduler} fake for unit tests.
 * <p>
 * Records one-shot tasks scheduled via {@link #schedule(Runnable, Instant)} with their target
 * execution instants, returns real cancellable {@link ScheduledFuture} handles, and executes due
 * non-cancelled tasks on demand against a {@link Clock} or {@link Instant}.
 * </p>
 */
public final class DeterministicTaskScheduler implements TaskScheduler {

    /**
     * Snapshot entry representing a single task submitted to {@link DeterministicTaskScheduler}.
     *
     * @param task      scheduled callback
     * @param startTime target execution instant
     * @param future    cancellable future handle returned to the caller
     */
    public record ScheduledTask(Runnable task, Instant startTime, ScheduledFuture<?> future) {

        /** Returns whether this task's future was cancelled before execution. */
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }

    private final Clock clock;
    private final List<ScheduledTask> tasks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean rejectNext = new AtomicBoolean(false);
    private final AtomicReference<Runnable> nextScheduleHook = new AtomicReference<>();

    /**
     * Creates a scheduler backed by {@code clock} (for example, a {@link MutableClock}).
     *
     * @param clock clock used for {@link #getClock()} and {@link #runDueTasks()}
     */
    public DeterministicTaskScheduler(Clock clock) {
        this.clock = requireNonNull(clock, "clock must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public Clock getClock() {
        return clock;
    }

    /**
     * Records {@code task} for execution at {@code startTime} and returns a real cancellable
     * {@link ScheduledFuture}.
     *
     * @throws TaskRejectedException if {@link #setRejectNext(boolean)} was armed
     */
    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        requireNonNull(task, "task must not be null");
        requireNonNull(startTime, "startTime must not be null");
        if (rejectNext.compareAndSet(true, false)) {
            throw new TaskRejectedException("Scheduler configured to reject next task");
        }

        DeterministicScheduledFuture future = new DeterministicScheduledFuture(clock, startTime);
        tasks.add(new ScheduledTask(task, startTime, future));

        Runnable oneShot = nextScheduleHook.getAndSet(null);
        if (oneShot != null) {
            oneShot.run();
        }
        return future;
    }

    /** {@inheritDoc} */
    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        throw new UnsupportedOperationException("Trigger scheduling is not supported by DeterministicTaskScheduler");
    }

    /** {@inheritDoc} */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        throw new UnsupportedOperationException("Fixed-rate scheduling is not supported by DeterministicTaskScheduler");
    }

    /** {@inheritDoc} */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        throw new UnsupportedOperationException("Fixed-rate scheduling is not supported by DeterministicTaskScheduler");
    }

    /** {@inheritDoc} */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        throw new UnsupportedOperationException("Fixed-delay scheduling is not supported by DeterministicTaskScheduler");
    }

    /** {@inheritDoc} */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        throw new UnsupportedOperationException("Fixed-delay scheduling is not supported by DeterministicTaskScheduler");
    }

    /**
     * Runs all pending non-cancelled tasks whose scheduled instant is at or before the scheduler's
     * current clock reading.
     *
     * @return number of tasks executed
     */
    public int runDueTasks() {
        return runDueTasks(clock.instant());
    }

    /**
     * Runs all pending non-cancelled tasks whose scheduled instant is at or before {@code targetClock}'s
     * current instant.
     *
     * @param targetClock clock supplying the current instant
     * @return number of tasks executed
     */
    public int runDueTasks(Clock targetClock) {
        requireNonNull(targetClock, "targetClock must not be null");
        return runDueTasks(targetClock.instant());
    }

    /**
     * Runs all pending non-cancelled tasks whose scheduled instant is at or before {@code now},
     * ordered chronologically. Due tasks are snapshotted before execution, so tasks scheduled
     * re-entrantly during execution run on a subsequent {@code runDueTasks} call.
     *
     * @param now cutoff instant (inclusive)
     * @return number of tasks executed
     */
    public int runDueTasks(Instant now) {
        requireNonNull(now, "now must not be null");
        List<ScheduledTask> due = new ArrayList<>();
        for (ScheduledTask entry : tasks) {
            if (!entry.startTime().isAfter(now) && isPending(entry)) {
                due.add(entry);
            }
        }
        return executeSorted(due);
    }

    /**
     * Runs all currently pending non-cancelled tasks regardless of their scheduled instants.
     *
     * @return number of tasks executed
     */
    public int runAllPending() {
        List<ScheduledTask> pending = new ArrayList<>(pendingTasks());
        return executeSorted(pending);
    }

    private int executeSorted(List<ScheduledTask> candidates) {
        candidates.sort(Comparator.comparing(ScheduledTask::startTime));
        int executed = 0;
        for (ScheduledTask entry : candidates) {
            DeterministicScheduledFuture future = (DeterministicScheduledFuture) entry.future();
            if (future.tryStart()) {
                try {
                    entry.task().run();
                } finally {
                    future.markDone();
                }
                executed++;
            }
        }
        return executed;
    }

    /**
     * Returns the number of scheduled tasks that are still pending execution.
     *
     * @return pending task count
     */
    public int pendingCount() {
        int count = 0;
        for (ScheduledTask entry : tasks) {
            if (isPending(entry)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns an immutable snapshot of all tasks recorded by this scheduler in submission order.
     *
     * @return all recorded {@link ScheduledTask} entries
     */
    public List<ScheduledTask> scheduledTasks() {
        return List.copyOf(tasks);
    }

    /**
     * Returns an immutable snapshot of tasks that are still pending execution.
     *
     * @return pending {@link ScheduledTask} entries
     */
    public List<ScheduledTask> pendingTasks() {
        return tasks.stream()
                .filter(DeterministicTaskScheduler::isPending)
                .toList();
    }

    private static boolean isPending(ScheduledTask entry) {
        return ((DeterministicScheduledFuture) entry.future()).isPending();
    }

    /**
     * Clears all recorded tasks, hooks, and rejection state.
     */
    public void clear() {
        tasks.clear();
        rejectNext.set(false);
        nextScheduleHook.set(null);
    }

    /**
     * Configures whether the next {@link #schedule(Runnable, Instant)} call throws
     * {@link TaskRejectedException} (auto-resets after one rejection).
     *
     * @param reject {@code true} to reject the next schedule call, {@code false} to disarm
     */
    public void setRejectNext(boolean reject) {
        rejectNext.set(reject);
    }

    /**
     * Registers a one-shot hook invoked inside {@link #schedule(Runnable, Instant)} immediately
     * before returning the {@link ScheduledFuture}. Useful for testing cancellation races before
     * caller future attachment.
     *
     * @param hook callback to run once before the next {@code schedule} returns, or {@code null} to clear
     */
    public void onNextSchedule(Runnable hook) {
        nextScheduleHook.set(hook);
    }

    private static final class DeterministicScheduledFuture implements ScheduledFuture<Object> {
        private static final int PENDING = 0;
        private static final int RUNNING = 1;
        private static final int DONE = 2;
        private static final int CANCELLED = 3;

        private final Clock clock;
        private final Instant startTime;
        private final AtomicInteger state = new AtomicInteger(PENDING);

        private DeterministicScheduledFuture(Clock clock, Instant startTime) {
            this.clock = clock;
            this.startTime = startTime;
        }

        private boolean isPending() {
            return state.get() == PENDING;
        }

        private boolean tryStart() {
            return state.compareAndSet(PENDING, RUNNING);
        }

        private void markDone() {
            state.compareAndSet(RUNNING, DONE);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return state.compareAndSet(PENDING, CANCELLED);
        }

        @Override
        public boolean isCancelled() {
            return state.get() == CANCELLED;
        }

        @Override
        public boolean isDone() {
            int current = state.get();
            return current == DONE || current == CANCELLED;
        }

        @Override
        public Object get() {
            return awaitResult();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return awaitResult();
        }

        private Object awaitResult() {
            int current = state.get();
            if (current == CANCELLED) {
                throw new CancellationException("Scheduled task was cancelled");
            }
            if (current == DONE) {
                return null;
            }
            throw new IllegalStateException("Task has not been executed yet");
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long nanos = Duration.between(clock.instant(), startTime).toNanos();
            return unit.convert(nanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
