# ADR-0001: Encrypted state-image WAL behind Spring mutation services

- Status: Accepted
- Date: 2026-07-12
- Failure policy reaffirmed: 2026-10-19 (continue accepted gameplay in memory while saves retry)

## Context

The server owns live rooms and games in memory. Command replay is unsafe because room IDs, timestamps, shuffles, asynchronous progression, and client broadcasts are nondeterministic. Persisted poker state also contains passwords, hole cards, complete deck order, chip accounting, and reconnect deadlines.

## Decision

Use one local WAL per `roomId` for the single-server deployment model. Explicit `@DurableMutation` advice attempts an encrypted `PREPARE`, executes the authoritative service mutation, captures an immutable versioned state image, and attempts an authenticated `COMMIT`. When writes succeed, queued client events are released after commit. Runtime storage failures keep accepted gameplay and effects in memory and retry the latest state without replaying the command.

Records use AES-256-GCM. Associated data binds format/schema version, file/room identity, sequence, transaction identity, record type, and key ID. The keyring supplies one current write key and optional older read keys. Domain objects rehydrate through explicit state-image mappings; runtime schedulers, futures, WebSocket sessions, locks, evaluators, publishers, and rate-limit buckets are rebuilt.

Absolute scheduled-work and reconnect deadlines are part of the state image. Recovery restores every aggregate before declaring readiness, treats every prior socket as disconnected, grants a fresh reconnect grace period, and reschedules future or overdue work with state-based idempotence guards.

## Consequences

- When storage is healthy, mutations and resulting broadcasts correspond to a flushed state image. During a runtime storage outage, valid mutations continue in memory and their effects are released once; the latest accepted image or deletion tombstone is retried every five seconds and on later accepted mutations.
- Exact deck order and private state survive restart without replaying randomness.
- Independent rooms can write concurrently; one room has one serialized transaction.
- Crash-torn final writes are recoverable, while committed-prefix corruption and missing keys fail closed.
- Compaction rewrites the latest state under the current key; permanent completion removes the WAL.
- Runtime prepare, serialization, commit, and maintenance failures do not reject valid gameplay or change application readiness. If an in-flight mutation throws a domain or validation exception, in-memory aggregate state is restored to its pre-mutation snapshot (or evicted) to preserve RAM consistency. Persistence health reports `MEMORY_ONLY` and unsaved rooms while gameplay remains available. A process stop before saving catches up can lose accepted in-memory changes. Global infrastructure failures and missing keys fail startup, while single corrupted room WALs or unsupported schema versions are quarantined to `<roomId>.wal.corrupted` so healthy rooms recover without crashing the server. Schema 4 records terminal review results; older schemas are rejected cleanly prior to deserialization.
- The design does not provide multi-instance writing, cross-node failover, an audit archive, or online schema migration. Those require a different durable-store architecture.

## Alternatives rejected

- Command/event replay: nondeterministic IDs, time, scheduling, and shuffling make exact recovery substantially harder.
- Serializing mutable domain objects: leaks runtime collaborators and couples storage compatibility to implementation fields.
- Database migration in this change: exceeds the local single-server requirement and changes the operational model.
- Unencrypted snapshots: exposes live credentials and private card/deck state to filesystem readers.
