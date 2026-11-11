# 0003: In-Memory Aggregate Rollback on Mutation Failure

We decided to automatically restore the in-memory `Room` and `Game` objects from a pre-change snapshot (`AggregateStateImage`) whenever a `@DurableMutation` service method throws an exception.

## Context

Service methods annotated with `@DurableMutation` modify the live `Room` and `Game` objects in memory before saving an encrypted snapshot to disk. If an error was thrown halfway through a method (such as a validation error, full room, or unexpected runtime exception), the first half of the changes had already modified the live Java objects in RAM. Because the method failed before saving to disk, memory was left in a broken, half-updated state (such as missing chips or ghost seats) that contradicted the disk save file until the server restarted.

## Decision

1. **Pre-Change Snapshot**: `DurableMutationAspect` captures a frozen in-memory copy (`AggregateStateImage`) of the room and game before running each top-level `@DurableMutation` method. Because this copy is kept in memory before JSON serialization or disk writes, rollbacks still work even when disk persistence is disabled or experiencing an outage.
2. **Rollback or Eviction on Error**: If the service method throws any exception (`Throwable`), `DurableMutationAspect` discards any queued WebSocket broadcasts and restores the `Room`, `Game`, and connection state from the pre-change copy. Active timers are rebuilt from the restored deadlines without re-sending broadcasts. If no pre-change copy exists (for example, when creating a brand-new room fails), the room is removed from memory completely.
3. **Normal Error Responses**: After rolling back memory to a clean state, the original exception is rethrown so Spring's `@RestControllerAdvice` and `@MessageExceptionHandler` return the expected error response to the client.

## Consequences

- Failed requests never leave half-applied changes in server memory.
- Rollback works purely in memory without needing disk access or JSON deserialization, preserving the latest valid gameplay even during a disk outage (ADR-0001).
- Rejected actions leave existing turn and round timers intact and protected against duplicate payouts or deals.
- Avoids needing a relational database or complex transaction manager just to undo failed in-memory updates.