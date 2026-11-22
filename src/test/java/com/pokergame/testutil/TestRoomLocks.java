package com.pokergame.testutil;

import static java.util.Objects.requireNonNull;

import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.util.function.Supplier;
import org.springframework.context.ApplicationContext;

/**
 * Shared test helper for running test setup and inspection under a room lock.
 */
public final class TestRoomLocks {
    private TestRoomLocks() {
    }

    /**
     * Acquires the room lock for {@code roomId} and runs {@code action} inside it.
     *
     * @param coordinator room coordinator managing room locks
     * @param roomId      target room identifier
     * @param action      work to run while holding the room lock
     * @param <T>         return type of {@code action}
     * @return the value produced by {@code action}
     */
    public static <T> T withRoomLock(RoomCoordinator coordinator, String roomId, Supplier<T> action) {
        requireNonNull(coordinator, "coordinator must not be null");
        requireNonNull(action, "action must not be null");
        try (LockLease _ = coordinator.acquire(roomId)) {
            return action.get();
        }
    }

    /**
     * Resolves {@link RoomCoordinator} from {@code context} and runs {@code action} under the room lock.
     *
     * @param context Spring application context containing {@link RoomCoordinator}
     * @param roomId  target room identifier
     * @param action  work to run while holding the room lock
     * @param <T>     return type of {@code action}
     * @return the value produced by {@code action}
     */
    public static <T> T withRoomLock(ApplicationContext context, String roomId, Supplier<T> action) {
        requireNonNull(context, "context must not be null");
        return withRoomLock(context.getBean(RoomCoordinator.class), roomId, action);
    }

    /**
     * Acquires the room lock for {@code roomId} and runs {@code action} inside it.
     *
     * @param coordinator room coordinator managing room locks
     * @param roomId      target room identifier
     * @param action      work to run while holding the room lock
     */
    public static void runWithRoomLock(RoomCoordinator coordinator, String roomId, Runnable action) {
        requireNonNull(coordinator, "coordinator must not be null");
        requireNonNull(action, "action must not be null");
        try (LockLease _ = coordinator.acquire(roomId)) {
            action.run();
        }
    }

    /**
     * Resolves {@link RoomCoordinator} from {@code context} and runs {@code action} under the room lock.
     *
     * @param context Spring application context containing {@link RoomCoordinator}
     * @param roomId  target room identifier
     * @param action  work to run while holding the room lock
     */
    public static void runWithRoomLock(ApplicationContext context, String roomId, Runnable action) {
        requireNonNull(context, "context must not be null");
        runWithRoomLock(context.getBean(RoomCoordinator.class), roomId, action);
    }
}
