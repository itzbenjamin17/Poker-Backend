# 0003: In-Memory Aggregate Rollback on Mutation Failure

We decided to automatically restore in-memory game and room aggregates from pre-mutation snapshots whenever an intercepted durable mutation throws an exception.

## Context

Service methods annotated with `@DurableMutation` execute in-memory state changes directly on shared Java aggregates (`Room` and `Game`) before serializing a snapshot to disk. If a domain exception occurred midway through execution (e.g. an unexpected validation failure, capacity overflow, or downstream runtime error), partial mutations were already applied to in-memory state. Because persistence failed or was skipped, the in-memory aggregate became desynchronized from the disk WAL, causing corrupt game state (such as vanished chips or phantom player seats) to persist in RAM until server restart.

## Decision

1. **Pre-Mutation Snapshot Capture**: `DurableMutationAspect` retains the latest accepted serialized snapshot image before executing domain mutations.
2. **Rollback / Eviction on Failure**: If the intercepted join point throws any `Throwable`, the aspect catches it and restores the in-memory aggregate from the snapshot using `snapshotMapper.deserialize()` and `restoreRoom`/`restoreGame`. If no valid prior snapshot exists (such as a failed room creation), the aggregate is completely evicted from memory.
3. **Transparent Error Propagation**: After restoring in-memory consistency, the original exception is rethrown to standard Spring `@RestControllerAdvice` and `@MessageExceptionHandler` handlers so users receive the appropriate error status and message.

## Consequences

- Failed mutations never leave dirty, half-applied state in server memory.
- In-memory aggregates always match the authoritative state stored on disk.
- Does not require complex distributed two-phase commit or transactional database rollback infrastructure.
