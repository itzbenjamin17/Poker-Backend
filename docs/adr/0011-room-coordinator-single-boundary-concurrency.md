# 0011: RoomCoordinator Single-Boundary Concurrency Model

**Status**: Accepted / Final (Supersedes [ADR-0007](0007-hierarchical-monitor-locking-order.md))

We decided to govern concurrent room and game mutations through a single, explicit concurrency boundary per room owned by `RoomCoordinator`, replacing the four-tier lock hierarchy (`Room Lease -> Player Lease -> Room Monitor -> Game Monitor`).

## Context

Under ADR-0007, concurrency was governed across four nested layers:
1. Per-room `ReentrantLock` lease in `DurableMutationAspect`
2. Per-player session `ReentrantLock` lease in `WebSocketEventListener`
3. Intrinsic monitor `synchronized (room)`
4. Intrinsic monitor `synchronized (game)`

Even with lock ordering strictly specified, this multi-tier model presented significant architectural fragility:
- **Distributed Reasoning**: Verifying whether a read, projection, session event, timer callback, or mutation was thread-safe required tracing lock acquisition chains across disparate controllers, aspects, listeners, and background services.
- **Lock Identity Splitting**: Aggregate replacement during error rollback or persistence recovery could risk stale references and identity splits between locks and replaced instances.
- **Accidental Deadlocks**: Any non-trivial refactor or new feature could inadvertently introduce reverse-order acquisitions or nested cross-room deadlocks.

The system requires a single, cohesive, and easily verifiable concurrency boundary per room that guarantees safety, atomicity, exception-safe lease management, and complete independence between rooms.

## Decision

1. **Single Room Concurrency Boundary**: `RoomCoordinator` is the single authoritative concurrency entry point for each room's live mutable state. All operations affecting a room—HTTP REST requests, WebSocket STOMP actions, session transitions, scheduled lifecycle tasks, background persistence retries, and projections—must enter through `RoomCoordinator.coordinate(roomId, ...)` or `RoomCoordinator.acquire(roomId)`.
2. **Stable Lock Identity**: The lock identity is the immutable room identifier (`String roomId`) managed within `KeyedLockRegistry<String>`. Live `Room` and `Game` aggregate instances may be replaced (e.g. during rollback or recovery) without invalidating or splitting the synchronization identity.
3. **Rejection of Cross-Room Nested Coordination**: A thread holding an active coordination lease for room A is strictly forbidden from acquiring or coordinating room B (`IllegalStateException` is thrown immediately). This invariant is enforced across all entry points in both persistence-enabled and persistence-disabled modes. Multi-room atomic operations are deliberately out of scope.
4. **Same-Room Reentrancy**: Nested operations on the same room reenter the existing boundary on the current thread without deadlocking, incrementing lease depth and preserving the outer transaction context.
5. **Elimination of Legacy Aggregate Monitors and Player Leases**: With the completion of Ticket #24:
   - All legacy `synchronized (room)` and `synchronized (game)` monitor blocks across `RoomService`, `GameLifecycleService`, `GameStateService`, `PlayerActionService`, `WebSocketEventListener`, `RoomController`, and `GameController` have been removed.
   - Redundant player-scoped session leases (`sessionLocks`) in `WebSocketEventListener` have been removed.
   - The single `RoomCoordinator` boundary is the sole synchronization mechanism protecting room and game mutable state.
6. **Global-Index Exceptions**: Global state operations (room-name uniqueness checking, room registry maps, session-to-room routing, and lobby enumeration) use concurrent collections or isolated, short-lived synchronization blocks (e.g. `roomRegistrationLock`). A thread must never hold a global lock while waiting to acquire a room lease. Global enumeration resolves identities first and inspects rooms independently without requiring a server-wide lock.
7. **Explicit Ownership Assertions**: `RoomCoordinator.isHeldByCurrentThread(roomId)` and `RoomCoordinator.assertHeldByCurrentThread(roomId)` allow internal domain services to assert caller compliance, failing fast with `IllegalStateException` if mutable state is accessed outside the boundary.
8. **Migration Completion Status**: The migration is fully complete:
   - All room-scoped production paths enter via `RoomCoordinator`.
   - Legacy `synchronized (room)` and `synchronized (game)` monitors are completely eliminated.
   - Redundant room-scoped player leases are removed.
   - External callers receive detached, immutable projections (`PublicProjection`, `PrivateProjection`).
   - The entire concurrency regression test suite passes without monitor synchronization.

## Consequences

- Eliminates cognitive overhead: developers only need to ensure operations enter `RoomCoordinator` for the room ID.
- Guarantees mutual exclusion and prevents partial-state observation during concurrent commands, projection generation, disconnect cleanup, and game progression.
- Operations on different rooms remain completely independent and execute in parallel without cross-room contention or serialization.
- Accidental cross-room synchronization cycles fail fast with explicit exceptions rather than causing unrecoverable JVM deadlocks.
