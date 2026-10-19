# ADR-0001: Encrypted state-image WAL behind Spring mutation services

- Status: Accepted
- Date: 2026-07-12
- Failure policy revised: 2026-10-19

## Context

The server owns live rooms and games in memory. Command replay is unsafe because room IDs, timestamps, shuffles, asynchronous progression, and client broadcasts are nondeterministic. Persisted poker state also contains passwords, hole cards, complete deck order, chip accounting, and reconnect deadlines.

## Decision

Use one local WAL per `roomId` for the single-server deployment model. Explicit `@DurableMutation` advice serializes a room, attempts an encrypted `PREPARE`, executes the authoritative service mutation, captures an immutable versioned state image, and attempts an authenticated `COMMIT` before releasing queued client effects.

Gameplay availability takes precedence over saving every accepted move. If runtime storage or snapshot serialization fails, a successfully applied mutation still releases its messages and timers and returns success. Domain mutation failures still reject the command and discard its effects. Per-room serialization and nested mutation boundaries remain active during unsaved play.

Retain each affected room's latest immutable image, including deletion tombstones, and retry every five seconds as well as on subsequent accepted mutations. A full encrypted checkpoint replaces an uncertain WAL before ordinary appends resume. A room becomes saved only after its replacement and directory flush succeed; repairing another room cannot clear its failure. If serialization failed, a later rejected mutation prevents background recapture until another mutation succeeds.

Records use AES-256-GCM. Associated data binds format/schema version, file/room identity, sequence, transaction identity, record type, and key ID. The keyring supplies one current write key and optional older read keys. Domain objects rehydrate through explicit state-image mappings; runtime schedulers, futures, WebSocket sessions, locks, evaluators, publishers, and rate-limit buckets are rebuilt.

Absolute scheduled-work and reconnect deadlines are part of the state image. Recovery restores every aggregate before declaring readiness, treats every prior socket as disconnected, grants a fresh reconnect grace period, and reschedules future or overdue work with state-based idempotence guards.

## Consequences

- When persistence is healthy, acknowledged mutations and resulting broadcasts correspond to a flushed state image. During an outage, accepted progress exists in memory until a checkpoint catches up.
- Exact deck order and private state survive restart without replaying randomness.
- Independent rooms can write concurrently; one room has one serialized transaction.
- Crash-torn final writes are recoverable, while committed-prefix corruption and missing keys fail closed.
- Compaction rewrites the latest state under the current key; permanent completion removes the WAL.
- Runtime save failures leave gameplay available. Health reports `MEMORY_ONLY`, the number of unsaved rooms and a sanitized storage error; recovery protection returns after confirmed checkpoints. A crash or restart before catch-up can lose accepted progress, including departures and cleanup, and an interrupted write can require recovery repair. Avoid restarting solely because saving is degraded.
- An accepted mutation's callbacks run independently, so one failed broadcast does not suppress subsequent timers or report the accepted move as rejected.
- The design does not provide multi-instance writing, cross-node failover, an audit archive, or online schema migration. Those require a different durable-store architecture.

## Alternatives rejected

- Command/event replay: nondeterministic IDs, time, scheduling, and shuffling make exact recovery substantially harder.
- Serializing mutable domain objects: leaks runtime collaborators and couples storage compatibility to implementation fields.
- Database migration in this change: exceeds the local single-server requirement and changes the operational model.
- Unencrypted snapshots: exposes live credentials and private card/deck state to filesystem readers.
