# 0007: Hierarchical Monitor Locking Order for Aggregate Concurrency

> [!NOTE]
> **Superseded by [ADR-0011](0011-room-coordinator-single-boundary-concurrency.md)**: The four-layer hierarchical locking order (`Room Lease -> Player Lease -> Room Monitor -> Game Monitor`) is superseded by a single explicit concurrency boundary per room managed by `RoomCoordinator`. During intermediate migration phases, any remaining legacy monitors must be acquired strictly beneath the `RoomCoordinator` lease.

We decided to govern concurrent room and game mutations using intrinsic JVM monitor synchronization with a strict acquisition hierarchy (`Room` lock before `Game` lock) rather than an actor framework, STM, or database transactions.

## Context

Concurrent requests originate from multiple asynchronous sources: HTTP REST endpoints (joining, leaving, starting games), WebSocket STOMP frames (betting actions, ready toggles), disconnect events from `WebSocketEventListener`, and background timers from Spring's `TaskScheduler`. Because the application maintains authoritative aggregates in process memory without an external database, concurrent threads mutating both `Room` (membership, host, connectivity) and `Game` (pot, turn, betting state) risk deadlocks if locks are acquired in arbitrary order.

## Decision

1. **Strict Monitor Hierarchy**: Whenever an operation requires mutating or reading consistent states across both aggregates, the executing thread must synchronize on the `Room` monitor first, and then on the `Game` monitor (`synchronized (room) { synchronized (game) { ... } }`).
2. **Reverse Acquisition Forbidden**: No code path is permitted to acquire the `Game` lock and subsequently attempt to acquire the `Room` lock.
3. **Single-Aggregate Scoping**: Operations affecting only a single aggregate (e.g. `Game` turn evaluations or `Room` name lookups) synchronize exclusively on that aggregate's instance.
4. **Reentrant Lock Serialization**: `DurableMutationAspect` wraps room reads and durable mutations in the room's per-ID `ReentrantLock` lease (`roomLocks`), preserving serialization across membership validation, in-memory mutation, and WAL writes. Session transitions in `WebSocketEventListener` acquire this room lease before the per-player session `ReentrantLock` (`userLocks`), then any `Room` and `Game` monitors. Reconnect, disconnect, grace expiry, membership retirement, and room destruction all follow this same order; no player lease may be held while first waiting for the room lease.

## Consequences

- Prevented aggregate-monitor inversion only when every participant followed the hierarchy; it did not eliminate all JVM deadlocks. ADR-0011 now defines the room-scoped guarantees and assumptions.
- Requires strict developer discipline and static/runtime auditing to guarantee that no new code path violates the `Room Lease -> Player Lease -> Room Monitor -> Game Monitor` acquisition hierarchy.
