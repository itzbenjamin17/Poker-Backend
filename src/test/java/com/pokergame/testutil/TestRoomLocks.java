package com.pokergame.testutil;

import com.pokergame.util.RoomCoordinator;
import com.pokergame.util.LockLease;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.context.ApplicationContext;

/**
 * Shared test helper for running test setup and inspection under a room coordinator lease.
 */
public final class TestRoomLocks {
    private TestRoomLocks() {
    }

    /**
     * Acquires the room lease for {@code roomId} and runs {@code action} inside it.
     *
     * @param coordinator room coordinator managing room leases
     * @param roomId      target room identifier
     * @param action      work to run while holding the room lease
     * @param <T>         return type of {@code action}
     * @return the value produced by {@code action}
     */
    public static <T> T withRoomLock(RoomCoordinator coordinator, String roomId, Supplier<T> action) {
        Objects.requireNonNull(coordinator, "coordinator must not be null");
        Objects.requireNonNull(action, "action must not be null");
        try (LockLease _ = coordinator.acquire(roomId)) {
            return action.get();
        }
    }

    /**
     * Resolves {@link RoomCoordinator} from {@code context} and runs {@code action} under the room lease.
     *
     * @param context Spring application context containing {@link RoomCoordinator}
     * @param roomId  target room identifier
     * @param action  work to run while holding the room lease
     * @param <T>     return type of {@code action}
     * @return the value produced by {@code action}
     */
    public static <T> T withRoomLock(ApplicationContext context, String roomId, Supplier<T> action) {
        Objects.requireNonNull(context, "context must not be null");
        return withRoomLock(context.getBean(RoomCoordinator.class), roomId, action);
    }
}
