# 0009: Hierarchical Monitor Locking Order for Aggregate Concurrency

We decided to govern concurrent room and game mutations using intrinsic JVM monitor synchronization with a strict acquisition hierarchy (`Room` lock before `Game` lock) rather than an actor framework, STM, or database transactions.

## Context

Concurrent requests originate from multiple asynchronous sources: HTTP REST endpoints (joining, leaving, starting games), WebSocket STOMP frames (betting actions, ready toggles), disconnect events from `WebSocketEventListener`, and background timers from Spring's `TaskScheduler`. Because the application maintains authoritative aggregates in process memory without an external database, concurrent threads mutating both `Room` (membership, host, connectivity) and `Game` (pot, turn, betting state) risk deadlocks if locks are acquired in arbitrary order.

## Decision

1. **Strict Monitor Hierarchy**: Whenever an operation requires mutating or reading consistent states across both aggregates, the executing thread must synchronize on the `Room` monitor first, and then on the `Game` monitor (`synchronized (room) { synchronized (game) { ... } }`).
2. **Reverse Acquisition Forbidden**: No code path is permitted to acquire the `Game` lock and subsequently attempt to acquire the `Room` lock.
3. **Single-Aggregate Scoping**: Operations affecting only a single aggregate (e.g. `Game` turn evaluations or `Room` name lookups) synchronize exclusively on that aggregate's instance.
4. **Reentrant Lock Serialization**: For WAL persistence, `DurableMutationAspect` wraps the entire mutation block in the room's reentrant lock lease (`KeyedLockRegistry`), preserving serialization across durable writes. Session transitions acquire this room lease before the keyed player/session lease, then any `Room` and `Game` monitors. Reconnect, disconnect, grace expiry, membership retirement, and room destruction all follow this same order; no player lease may be held while first waiting for the room lease.

## Consequences

- Completely eliminates JVM deadlocks across all concurrent game actions, disconnect handlers, and scheduled lifecycle timers.
- Achieves sub-millisecond execution latency with zero database connection pool overhead.
- Requires strict developer discipline and static/runtime auditing to guarantee that no new code path violates the `Room -> Game` acquisition hierarchy.
