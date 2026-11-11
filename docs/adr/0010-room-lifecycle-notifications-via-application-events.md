# 0010: Room Lifecycle Notifications via Application Events

We decided that `RoomService` should announce room membership changes by publishing Spring application events (`RoomMemberRegistered`, `RoomMembershipRetired`, `RoomDestroyed`) that `WebSocketEventListener` listens for, rather than `RoomService` calling `WebSocketEventListener` directly.

## Context

Previously, `RoomService` called three methods directly on `WebSocketEventListener`: `registerInitialConnection`, `publishMembershipRetired`, and `publishRoomDestroyed`. Because `WebSocketEventListener` also calls `RoomService`, the two Spring beans depended on each other in a circle, requiring `@Lazy` on the `RoomService` constructor and `!= null` checks at every call site in unit tests.

All three calls were already registered inside `DurableTransactionContext.afterCommit(...)`, meaning they are one-way notifications that only run after a room change succeeds.

## Decision

1. **Domain Event Records**: Define `RoomMemberRegistered`, `RoomMembershipRetired`, and `RoomDestroyed` as Java records in `com.pokergame.service.event`. `RoomService` no longer imports anything from the `config` package.
2. **Publish After Commit**: `RoomService` publishes these events through Spring's `ApplicationEventPublisher` inside `DurableTransactionContext.afterCommit(...)`. If a room change throws an error and rolls back, no event is published.
3. **Standard `@EventListener`**: Use `@EventListener` (not `@TransactionalEventListener`, which only fires for database transactions).
4. **Listener Delegation**: `WebSocketEventListener` has one `@EventListener` method per event that forwards to `registerInitialConnection`, `publishMembershipRetired`, and `publishRoomDestroyed`. `PersistenceRecovery` and tests can still call those public methods directly.
5. **Pre-Calculated Deadlines**: `RoomService` calculates the initial connection deadline and includes it in `RoomMemberRegistered` so the listener does not need to inspect room internals.

## Consequences

- Breaks the circular dependency between `RoomService` and `WebSocketEventListener`, removing the `@Lazy` workaround and `!= null` checks.
- Because Spring's `ApplicationEventPublisher` runs listeners synchronously on the same thread by default, execution order, lock ownership, and error handling remain identical to direct method calls.
- Tracing from a room change to WebSocket session cleanup now goes through the three event records (`RoomMemberRegistered`, `RoomMembershipRetired`, `RoomDestroyed`), which is covered by `RoomServiceTest` and `RoomEventsAfterCommitTest`.