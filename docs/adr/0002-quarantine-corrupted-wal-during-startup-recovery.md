# 0002: Quarantine Corrupted WAL During Startup Recovery

We decided to isolate broken or unreadable room save files by renaming them to `<roomId>.wal.corrupted` during startup recovery instead of crashing the entire server.

## Context

On startup, `PersistenceRecovery` loops through every `.wal` file in the persistence folder and restores the latest `Room` and `Game` snapshot (`AggregateStateImage`) for each room. Previously, if even one room's save file had an unsupported schema version, a mismatched room ID, or corrupted bytes, deserialization threw an unhandled `PersistenceException`. Because `PersistenceRecovery` runs during Spring Boot startup (`ApplicationRunner`), one bad room file crashed the entire server on boot (`CrashLoopBackOff`) and took down every other healthy room.

## Decision

1. **Per-Room Fault Isolation**: Wrap the loading and restoration of each room inside a `try/catch` block in `PersistenceRecovery.java` so a failure in one room does not abort the loop.
2. **Quarantine Rename**: When a room file fails to load, call `store.quarantine(roomId, reason)` to atomically rename `<roomId>.wal` to `<roomId>.wal.corrupted` on disk and remove it from active tracking maps.
3. **Continue Startup**: Log the error with full diagnostic details and continue restoring the remaining rooms. System-wide problems (such as an unreadable storage folder or a missing decryption key) still stop server startup immediately.

## Consequences

- A single broken room save file never prevents the server from starting or affects other rooms.
- The broken `.wal.corrupted` file is kept on disk so developers can inspect or salvage it rather than losing the data.
- Quarantined files are ignored by active room counts and future startup recovery runs.