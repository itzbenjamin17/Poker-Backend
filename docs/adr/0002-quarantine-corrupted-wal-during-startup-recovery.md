# 0002: Quarantine Corrupted WAL During Startup Recovery

We decided to isolate invalid or unreadable room WAL files by moving them to `<roomId>.wal.corrupted` during startup recovery rather than failing the entire server application context.

## Context

During startup, `PersistenceRecovery` iterates through all `.wal` files in the persistence directory and deserializes the latest aggregate snapshot for each room. Previously, if any single room snapshot had an unsupported schema version, invalid internal room identity, or corrupted record framing, `deserialize` threw an unhandled `PersistenceException`. Because `PersistenceRecovery` runs as an `ApplicationRunner`, this aborted the entire Spring Boot startup sequence, causing the entire server to enter a crash loop (`CrashLoopBackOff`) and denying service to all other valid rooms.

## Decision

1. **Per-Room Fault Isolation**: Wrap the deserialization and aggregate restoration of each room in a per-room fault boundary in `PersistenceRecovery.java`.
2. **Quarantine Move**: When a room snapshot fails recovery, invoke `store.quarantine(roomId, reason)` to atomically rename the file on disk from `<roomId>.wal` to `<roomId>.wal.corrupted` and clean up internal tracking maps.
3. **Continue Recovery**: Log a detailed error with diagnostic context and continue iterating through remaining rooms. Global infrastructure errors (e.g. inaccessible persistence directory or missing decryption keyring) still fail closed.

## Consequences

- A single corrupted room save never blocks or crashes the server startup.
- Corrupted WAL data is preserved on disk for forensic inspection or manual salvaging rather than permanently destroyed.
- Quarantined files are excluded from active WAL counts and future recovery loops.
