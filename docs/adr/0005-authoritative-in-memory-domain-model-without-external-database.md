# 0005: Authoritative In-Memory Domain Model Without External Database

We keep all active `Room` and `Game` aggregates in JVM memory backed only by a per-room encrypted Write-Ahead Log (WAL) rather than an external database, because game state is strictly ephemeral and only needs to survive a server restart while a game is in progress.

## Context

The application does not maintain persistent user accounts, historical hand archives, or cross-game analytics, and nothing queries across rooms or completed hands. Once a `Game` finishes (and its short post-game review retention window expires), its state is permanently discarded. Running a relational or document database (e.g., PostgreSQL, MongoDB) with linked tables, schemas, migrations, and ORM mappings would add operational and architectural complexity for data that is never queried relationally or retained long-term.

## Decision

1. **In-Memory Authoritative State**: Live `Room` and `Game` aggregates reside in process memory (`RoomService`, `GameLifecycleService`) for the duration of their lifecycle.
2. **Crash-Recovery Persistence Only**: Production durability is handled by a per-room append-only encrypted WAL written via `DurableMutation` aspects ([ADR-0001](0001-encrypted-state-image-wal-with-spring-aop.md)), toggled off only for local development and non-persistence test runs. The WAL exists solely to restore active rooms and games after a process restart, not for runtime queries or historical storage.
3. **Ephemeral Lifecycle & Cleanup**: When a room is emptied or a completed game passes its retention window, the in-memory aggregates are evicted and the room's WAL file is deleted.

## Consequences

- Avoids provisioning, operating, and migrating an external database for state that has no long-term or relational query requirements.
- Provides full crash recovery for active games using only local filesystem storage.
- Completed games and hand histories are intentionally not archived once the post-game review window closes; adding player profiles, leaderboards, or historical analytics in the future would require introducing a separate durable store.
