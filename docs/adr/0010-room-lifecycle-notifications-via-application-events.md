# 0010: Room Lifecycle Notifications via Application Events

We decided that `RoomService` announces accepted membership and room changes by publishing Spring application events, and `WebSocketEventListener` subscribes to them, rather than `RoomService` holding and calling the listener directly.

## Context

`RoomService` (domain) called three methods on `WebSocketEventListener` (transport): `registerInitialConnection`, `publishMembershipRetired`, and `publishRoomDestroyed`. The listener also depends on `RoomService`, so the two beans formed a construction cycle that was broken with `@Lazy` on the `RoomService` constructor parameter, plus `!= null` guards at every call site for tests that built the service without a listener. A domain class therefore depended on a transport class, and an interface alone would not have removed the cycle because Spring's cycle is between bean instances.

All three calls already ran inside `DurableTransactionContext.afterCommit(...)`, so each is a notification that must fire only after a mutation is accepted, never an operation whose result `RoomService` consumes.

## Decision

1. **Events live on the domain side.** `RoomMemberRegistered`, `RoomMembershipRetired`, and `RoomDestroyed` are records in `com.pokergame.service.event`. `RoomService` imports nothing from `config`.
2. **Publish inside `afterCommit`.** `RoomService` publishes through `ApplicationEventPublisher` from within the same `DurableTransactionContext.afterCommit(...)` callbacks as before. Rolled-back or discarded mutations publish nothing.
3. **Plain `@EventListener`, not `@TransactionalEventListener`.** `DurableTransactionContext` is not a Spring transaction, so transactional listeners would never fire.
4. **Thin adapters on the listener.** `WebSocketEventListener` keeps `registerInitialConnection`, `publishMembershipRetired`, and `publishRoomDestroyed` as public methods and adds one `@EventListener` method per event that delegates to them. `PersistenceRecovery` and tests continue to call the originals directly.
5. **Grace-period deadlines are computed by `RoomService`.** The event carries the deadline, so the listener needs no knowledge of room internals beyond what it already read.

## Consequences

- `RoomService` no longer depends on `WebSocketEventListener`. The construction cycle and its `@Lazy` are gone, along with the null guards.
- Delivery is synchronous on the publishing thread, as `ApplicationEventPublisher` is by default, so ordering, locking order (ADR 0007), and exception behaviour match the previous direct calls. Listeners must not be made asynchronous without revisiting those guarantees.
- The call path from a room mutation to transient session cleanup is now indirect. Tracing it requires following the three event types rather than a method reference.
- Constructing `RoomService` without a real publisher (tests) silently drops notifications. `RoomServiceTest` asserts the published events, and `RoomEventsAfterCommitTest` asserts that they follow the durable transaction's accept-or-discard outcome.
- Remaining `@Lazy` use is unchanged: `RoomService.setSelf`, `GameLifecycleService.setSelf`, `WebSocketAuthInterceptor`'s `RoomService`, and `WebSocketConfig`'s broker scheduler.
