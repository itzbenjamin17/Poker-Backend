# 0003: In-Memory Aggregate Rollback on Mutation Failure

We decided to automatically restore in-memory game and room aggregates from pre-mutation snapshots whenever an intercepted durable mutation throws an exception.

## Context

Service methods annotated with `@DurableMutation` execute in-memory state changes directly on shared Java aggregates (`Room` and `Game`) before serializing a snapshot to disk. If a domain exception occurred midway through execution (e.g. an unexpected validation failure, capacity overflow, or downstream runtime error), partial mutations were already applied to in-memory state. Because persistence failed or was skipped, the in-memory aggregate became desynchronized from the disk WAL, causing corrupt game state (such as vanished chips or phantom player seats) to persist in RAM until server restart.

## Decision

1. **Pre-Mutation Snapshot Capture**: `DurableMutationAspect` captures an independent immutable state image before executing each top-level domain mutation. This image is separate from JSON serialization and WAL saving, so a storage or serialization outage cannot make rollback discard newer accepted in-memory gameplay.
2. **Rollback / Eviction on Failure**: If the intercepted join point throws any `Throwable`, discard its queued effects and restore the pre-mutation room, game, and connection metadata. Rebuild runtime callbacks from the restored absolute deadlines without replaying broadcasts or applying startup reconnect policy. If no safe pre-mutation image is available, evict the aggregate rather than restoring an older disk image over accepted state. Failed room creation restores absence.
3. **Transparent Error Propagation**: After restoring in-memory consistency, the original exception is rethrown to standard Spring `@RestControllerAdvice` and `@MessageExceptionHandler` handlers so users receive the appropriate error status and message.

## Consequences

- Failed mutations never leave dirty, half-applied state in server memory.
- In-memory aggregates retain the latest accepted gameplay; disk may lag during the continue-in-memory policy of ADR-0001. Rollback does not require successful serialization or storage access.
- A rejected command preserves lifecycle progression, and obsolete callbacks remain guarded against duplicate dealing or payouts.
- Does not require complex distributed two-phase commit or transactional database rollback infrastructure.
