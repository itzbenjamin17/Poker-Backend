# 0006: Authoritative In-Memory Domain Model Without External Database

We decided to maintain all active rooms and poker games in JVM memory as the authoritative state of truth, backed only by an append-only encrypted Write-Ahead Log (WAL), rather than using an external database.

## Context

Multiplayer Texas Hold'em requires high-frequency, low-latency state mutations (betting actions, turn timeouts, deck shuffling, card deals, pot calculations) with strict serialization per room. Introducing a relational or document database (e.g., PostgreSQL, MongoDB) introduces network roundtrips, database connection pool contention, ORM mapping overhead, and complex serialization of mutable game trees on every sub-second player action.

## Decision

1. **In-Memory Authoritative State**: Live `Room` and `Game` aggregates reside in process memory (`RoomService.rooms`, `GameLifecycleService.activeGames`).
2. **Synchronized Isolation**: Thread safety is achieved via synchronized aggregate methods and per-room reentrant lock leases (`KeyedLockRegistry<String>`).
3. **WAL-Only Durability**: Optional durability is provided via an append-only encrypted WAL written by `@DurableMutation` aspects. The WAL is only used for crash recovery, not runtime queries.
4. **Single-Node Deployment**: The application is architected as a single-node stateful server. Distributed clustering and cross-node database failover are intentionally excluded from the architecture.

## Consequences

- Ultra-low turn latency (<1 millisecond mutation execution) with zero database roundtrip delays.
- Eliminates database schema migrations, connection pooling, and ORM object-relational impedance mismatch.
- Horizontal scaling across multiple nodes is not supported; high availability relies on fast single-server process restarts and encrypted WAL snapshot replay.
