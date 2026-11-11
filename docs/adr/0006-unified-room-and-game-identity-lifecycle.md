# 0006: Unified Room and Game Identity Lifecycle

We decided that a `Game` reuses the exact same UUID as its `Room` (`gameId` equals `roomId`), sharing the same JWT token and WebSocket topics from lobby creation through the end of the match.

## Context

In many multiplayer game servers, a waiting room has a `roomId`, and starting a match creates a separate `gameId` or `matchId`. That forces clients to swap tokens, tear down and reconnect WebSocket subscriptions, and keep two different IDs in sync whenever the host starts the game.

## Decision

1. **Same UUID for Room and Game**: When `GameLifecycleService.createGameFromRoom(roomId)` starts a poker match, the new `Game` is created with `roomId` as its `gameId`.
2. **One Token for Both**: The JWT issued when a player creates or joins a room carries `roomId`, which authorizes both room endpoints (`/api/room/{id}/*`) and game endpoints (`/api/game/{id}/*`).
3. **Unchanged WebSocket Topics**: Client STOMP subscriptions to `/room/{id}` and `/game/{id}` stay connected on the same ID across the entire match without reconnecting.

## Consequences

- Eliminates token swaps and WebSocket reconnection delays when transitioning from the lobby to the poker table.
- Simplifies the frontend: the client only ever tracks a single `roomId`.
- A single `Room` can only run one `Game` at a time, which matches how a single poker table works.