# 0005: Authoritative In-Memory Domain Model Without External Database

We keep all active `Room` and `Game` objects in JVM memory—backed only by a per-room encrypted save file (WAL) on disk rather than an external database—because poker tables are temporary and only need to survive a server restart while a match is in progress.

## Context

The application does not have permanent user accounts, lifetime hand histories, leaderboards, or cross-room search queries. Once a `Game` ends (and its short post-game review window finishes), its state is permanently deleted. Running an external database like PostgreSQL or MongoDB with tables, schema migrations, and ORM mappings would add operational overhead for temporary state that is never queried across rooms or kept long-term.

## Decision

1. **Live State in Memory**: Active `Room` and `Game` objects live directly in server memory (`RoomService` and `GameLifecycleService`) for the life of the table.
2. **Disk Saves for Crash Recovery Only**: In production, `@DurableMutation` saves an encrypted snapshot of each room to a local `<roomId>.wal` file ([ADR-0001](0001-encrypted-state-image-wal-with-spring-aop.md)), which can be turned off for local development and fast unit tests. The `.wal` files are read only once when the server boots to restore active matches after a restart.
3. **Automatic Cleanup**: When a room empties out or a finished game's post-match review window expires, the `Room` and `Game` are removed from memory and the room's `.wal` file is deleted from disk.

## Consequences

- No external database needs to be provisioned, backed up, or migrated.
- Active games still recover their exact state after a server crash or restart using local disk files.
- Finished games and hand histories are deleted after the post-game review window closes; adding permanent player profiles or historical stats in the future would require adding a database.