# 0011: RoomCoordinator Single-Boundary Concurrency Model

**Status**: Accepted (Supersedes [ADR-0007](0007-hierarchical-monitor-locking-order.md))

We decided to protect all concurrent reads and updates to a room and its game behind a single per-room lock managed by `RoomCoordinator`, replacing the old four-layer lock hierarchy (`Room Lease -> Player Lease -> Room Monitor -> Game Monitor`).

## Context

Under ADR-0007, thread safety relied on four nested locks:
1. A per-room `ReentrantLock` in `DurableMutationAspect`
2. A per-player `ReentrantLock` in `WebSocketEventListener`
3. `synchronized (room)` blocks
4. `synchronized (game)` blocks

Even with a strict lock order, spreading four locks across the codebase caused three problems:
- **Hard to Verify**: Checking whether a REST request, WebSocket action, disconnect handler, or timer callback was thread-safe required tracing locks across controllers, aspects, listeners, and services.
- **Replaced Objects during Rollback**: When a failed mutation rolled back by replacing the `Room` or `Game` instance in memory, any `synchronized (room)` or `synchronized (game)` lock was tied to the old object reference rather than the room ID.
- **Deadlock Risk**: Any refactor that accidentally acquired locks in the wrong order risked deadlocking the server.

### Why Not Just `synchronized (room)` and `synchronized (game)`?

In a standard Java app where objects in memory are never swapped out, `synchronized (room)` would be enough. In this server, `synchronized` on the `Room` and `Game` objects breaks down for four concrete reasons:

1. **Rollback Can Leave a Request Holding a Stale Reference.** When a durable mutation fails, `DurableMutationAspect` can restore the pre-mutation state by replacing the live `Room` and `Game` instances in their registries. Consider what happens if the room object's monitor is the only lock.

   - A locks the current `Room` instance. B looks up the same instance and waits for its monitor.
   - A fails, installs the restored instance under the same `roomId`, and releases the old instance's monitor.
   - B acquires the old monitor and adds a player to the old instance. A later lookup returns the restored instance, where that player is missing. This can happen even if B is the only request running after A.

   To prevent this, A and B acquire the same lock by `roomId` **before looking up the live instance**. A holds that lock through rollback. B waits with only the room ID and looks up the restored instance after acquiring the lock. A lookup done before acquiring the lock can still leave B with a stale reference.
2. **Locking Before the Object Exists (or While It Is Being Deleted)**: When `createGameFromRoom(roomId)` starts a match, the `Game` object does not exist yet (you cannot write `synchronized (game)` on `null`). Similarly, validating a player's `membershipId`, mutating state, and writing the `.wal` file on disk must happen inside one continuous lock; if you only lock `synchronized (room)` *after* looking it up in the map, another thread can replace or delete the room between the map lookup and the `synchronized` block.
3. **Two Objects (`Room` + `Game`) Mean Constant Deadlock Risk**: Because a table has both a `Room` (membership, host, connections) and a `Game` (cards, pots, turns), using `synchronized` required every developer to always lock `Room` before `Game`. If any method inside `synchronized (game)` called a helper that acquired `synchronized (room)`, two threads could lock them in opposite orders and freeze the server. One lock per `roomId` covers both `Room` and `Game` together.
4. **`try-with-resources` and Fast-Fail Checks (`LockLease`)**: `LockLease` implements `AutoCloseable` over a `ReentrantLock` (`try (LockLease lease = roomCoordinator.acquire(roomId))`), which works cleanly across Spring AOP interceptors (`DurableMutationAspect`) and lets internal methods call `roomCoordinator.assertHeldByCurrentThread(roomId)` to immediately catch bugs in tests if code ever touches mutable state without locking the room first.

## Decision

1. **One Gatekeeper per Room (`RoomCoordinator`)**: Every operation that reads or changes a room—REST endpoints, WebSocket actions, connect/disconnect events, scheduled timers, background WAL save retries, and state snapshots—must enter through `RoomCoordinator.coordinate(roomId, ...)` or `RoomCoordinator.acquire(roomId)`.
2. **Lock by `roomId` String, Not Object Instance**: `RoomCoordinator` locks on the room's UUID string inside `KeyedLockRegistry<String>`. Acquire that lock before looking up the live `Room` or `Game` and hold it through the operation, including any rollback. Restoring an instance under the same ID leaves the lock in place.
3. **No Cross-Room Locking**: A thread that already holds the lock for Room A is forbidden from acquiring the lock for Room B (`IllegalStateException` is thrown immediately). Rooms never lock each other.
4. **Same-Room Reentrancy**: If a method already inside `RoomCoordinator` for Room A calls another helper for Room A on the same thread, it re-enters cleanly without deadlocking.
5. **Phased Removal of Legacy Locks**: During migration (Tickets #19–#23), any remaining `synchronized (room)` or `synchronized (game)` blocks must only be acquired *after* entering `RoomCoordinator`.
6. **Global Lookup Maps**: Server-wide maps (room-name uniqueness, room registries, and session routing maps) use `ConcurrentHashMap` or brief isolated locks. A thread must never hold a global lock while waiting to acquire a `RoomCoordinator` lock.
7. **Fast-Fail Ownership Checks**: Internal services can call `RoomCoordinator.isHeldByCurrentThread(roomId)` and `RoomCoordinator.assertHeldByCurrentThread(roomId)` to fail immediately in tests or development if mutable state is touched outside `RoomCoordinator`.
8. **Migration Done Criteria (Ticket #24)**:
   - Every room-scoped code path enters through `RoomCoordinator`.
   - All legacy `synchronized (room)` and `synchronized (game)` blocks are removed.
   - Redundant per-player session locks are removed.
   - Callers outside the boundary receive read-only DTO snapshots (`PublicGameStateResponse`, `PrivatePlayerState`).
   - All concurrency tests pass using only `RoomCoordinator`.

## Consequences

- Developers only need to follow one rule for thread safety: enter `RoomCoordinator` for the `roomId`.
- Prevents race conditions and half-updated state across player actions, state broadcasts, disconnect cleanup, and timers.
- Different poker rooms run completely in parallel without blocking each other.
- Any accidental attempt to lock two rooms at once fails immediately with a clear `IllegalStateException` instead of freezing the server in a deadlock.