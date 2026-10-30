# 0007: Unified Room and Game Identity Lifecycle

We decided that a Game aggregate reuses the exact same UUID as its parent Room aggregate, sharing the same cryptographic token and messaging channels throughout the entire table lifecycle.

## Context

In many gaming systems, a lobby or table room has an identifier, and each individual hand or match played inside that room receives a newly generated `gameId` or `matchId`. This requires clients to perform token exchanges, tear down and re-establish WebSocket subscriptions, and synchronize two different identifiers across every round transition.

## Decision

1. **Reused Identifier**: When `GameLifecycleService.createGameFromRoom(roomId)` initiates an active poker game, the `Game` is constructed using the `roomId` as its `gameId`.
2. **Unified Token Scope**: The JWT issued to a player upon creating or joining a room contains `roomId`, which serves directly as the authorization claim for both room endpoints (`/api/room/{id}/*`) and game endpoints (`/api/game/{id}/*`).
3. **Single Channel Lifecycle**: STOMP subscriptions to `/room/{id}` and `/game/{id}` remain static across the entire match without re-negotiation.

## Consequences

- Completely eliminates token exchange delays and WebSocket reconnection overhead between successive poker hands.
- Substantially simplifies client state: the client only ever tracks one active room identifier.
- Disallows running multiple concurrent games within a single room, which aligns with the physical reality of a single poker table.
