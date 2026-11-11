# 0001: Encrypted State-Image WAL Behind Spring Mutation Services

- Status: Accepted
- Date: 2026-07-12
- Failure policy reaffirmed: 2026-10-19 (continue accepted gameplay in memory while saves retry)

## Context

The server keeps live rooms and games in memory (`Room` and `Game`). Re-running past player commands to recover after a crash is unsafe because room IDs, timestamps, deck shuffles, background timers, and client broadcasts would produce different results on replay. Saved poker state also contains sensitive data: room passwords, players' face-down hole cards, the full deck order, chip balances, and reconnect deadlines.

## Decision

Use one local Write-Ahead Log (WAL) save file per `roomId` (`<roomId>.wal`) for the single-server deployment model. Service methods annotated with `@DurableMutation` write an encrypted `PREPARE` record, run the room or game change in memory, capture a frozen versioned snapshot (`AggregateStateImage`), and write an authenticated `COMMIT` record. When disk writes succeed, queued WebSocket broadcasts and events are sent after the commit finishes. If saving to disk fails at runtime, the server keeps the accepted gameplay change in memory, sends the client updates once, and retries saving the latest state snapshot without re-running the player command.

Save records are encrypted with AES-256-GCM. Authenticated metadata binds the schema version, room ID, sequence number, transaction ID, record type, and encryption key ID. `EncryptionKeyring` provides one current write key and optional older read keys for key rotation. On startup, `Room` and `Game` objects are rebuilt from `AggregateStateImage` via `AggregateSnapshotMapper`, while runtime-only collaborators (schedulers, timers, WebSocket sessions, locks, hand evaluators, event publishers, and rate-limit buckets) are recreated fresh.

Exact clock deadlines for scheduled tasks and reconnect windows are saved inside `AggregateStateImage`. Startup recovery (`PersistenceRecovery`) restores every saved room and game before marking the server ready, treats all previous WebSocket connections as disconnected, grants every player a fresh reconnect grace period, and reschedules future or overdue timers with generation checks so tasks never run twice.

## Consequences

- When disk storage is healthy, every state change and client broadcast corresponds to a saved snapshot on disk. During a temporary disk outage, valid gameplay continues in memory and broadcasts are sent once; the latest snapshot (or room deletion marker) is retried every five seconds and on subsequent changes.
- The exact shuffled deck order and private hole cards survive a server restart without re-running random shuffles.
- Different rooms can save to disk in parallel, while changes to the same room run one at a time.
- Half-written records at the end of a file after a sudden crash are safely recovered, while corruption earlier in the file or missing encryption keys fail safely.
- Log compaction rewrites the latest room snapshot under the current encryption key, and permanent room cleanup deletes the `.wal` file.
- Runtime disk or serialization failures do not block valid gameplay or flip the server's readiness probe. If a service method throws a domain or validation error, the in-memory `Room` and `Game` are rolled back to their pre-change snapshot (or removed) so memory never stays half-updated. The persistence health check reports `MEMORY_ONLY` and the count of unsaved rooms while gameplay stays online. Stopping the server before background retries catch up can lose those unsaved in-memory changes. Missing keys or unreadable storage directories stop startup, whereas a single corrupted `.wal` file or unsupported schema version is quarantined to `<roomId>.wal.corrupted` so healthy rooms still recover. Schema 4 stores final post-game review results (`GameEndResponse`); older schema versions are rejected cleanly before deserialization.
- This design is built for a single server and does not support multiple server instances writing to the same files, cross-node failover, historical hand archives, or live schema migration.

## Alternatives rejected

- Replaying player commands or events: random shuffles, IDs, clock times, and background timers make exact state recovery unreliable.
- Serializing live Java domain objects directly: leaks runtime helpers (locks, schedulers) into save files and breaks compatibility whenever internal fields change.
- Introducing an external database: adds operational complexity beyond what a single-server ephemeral poker table needs.
- Unencrypted save files: exposes room passwords, private hole cards, and remaining deck order to anyone who can read the filesystem.