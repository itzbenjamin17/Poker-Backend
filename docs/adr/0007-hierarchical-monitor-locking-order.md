# 0007: Hierarchical Monitor Locking Order for Aggregate Concurrency

> [!NOTE]
> **Superseded by [ADR-0011](0011-room-coordinator-single-boundary-concurrency.md)**: The four-layer lock hierarchy (`Room Lease -> Player Lease -> Room Monitor -> Game Monitor`) has been replaced by a single per-room lock boundary managed by `RoomCoordinator`. Any remaining `synchronized` blocks during migration must be acquired strictly inside the `RoomCoordinator` lease.

We originally governed concurrent changes to a `Room` and its `Game` using Java locks acquired in a strict top-down order (`Room` lock before `Game` lock) rather than an actor framework or database transactions.

## Context

Requests for the same poker table can arrive at the same time from multiple threads: HTTP REST calls (joining, leaving, starting the game), WebSocket STOMP messages (betting actions, ready toggles), disconnect events from `WebSocketEventListener`, and background timers from Spring's `TaskScheduler`. Because `Room` and `Game` live in server memory, two threads locking `Room` and `Game` in opposite orders would cause a JVM deadlock.

## Decision

1. **Lock `Room` Before `Game`**: Whenever code needs to read or update both `Room` and `Game` together, it must lock `Room` first and `Game` second (`synchronized (room) { synchronized (game) { ... } }`).
2. **Never Lock in Reverse**: Code holding the `Game` lock must never try to acquire the `Room` lock.
3. **Single-Object Locking**: Operations that only touch `Game` (such as evaluating a turn) or only touch `Room` (such as looking up a room name) lock only that object.
4. **Outer ReentrantLocks**: `DurableMutationAspect` acquires a per-room `ReentrantLock` (`roomLocks`) before validating membership, mutating state, or writing to disk. `WebSocketEventListener` acquires that same room lock before the per-player session lock (`userLocks`), followed by any `Room` and `Game` `synchronized` blocks.

## Consequences

- Prevented deadlocks across concurrent player actions, disconnect handlers, and background timers as long as every code path obeyed the four-level lock order (`Room Lease -> Player Lease -> Room Monitor -> Game Monitor`).
- Proved difficult to reason about across many classes, which motivated simplifying to the single `RoomCoordinator` boundary in [ADR-0011](0011-room-coordinator-single-boundary-concurrency.md).