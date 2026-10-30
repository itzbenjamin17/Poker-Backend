# 0008: Dual-Channel Public and Private State Projection

We decided to project poker table state into two strictly isolated data streams—public table state with masked opponent cards and private player state containing hole cards—delivered over distinct HTTP endpoints and STOMP destinations to prevent client-side card inspection cheats.

## Context

Texas Hold'em relies on imperfect information: each player's hole cards must remain secret until showdown. In simpler web architectures, all player state is frequently bundled into a single JSON payload with hidden cards masked by frontend CSS or client logic. Any player using browser DevTools or WebSocket frame inspection could view secret opponent cards directly from memory. Furthermore, encrypting cards per-client on a single shared channel adds high key-management overhead for real-time play.

## Decision

1. **Public Projection**: `GameStateService.buildPublicGameState()` constructs `GameStateResponse` containing community cards, pot/side-pot totals, current turn, player chip stacks, and action histories. Non-hero hole cards are set to `null` unless the hand has reached a contested showdown. This payload is broadcast to `/game/{roomId}` and served via `GET /api/game/{roomId}/state`.
2. **Private Projection**: `GameStateService.buildPrivatePlayerState()` extracts only the authenticated player's own seat ID and secret hole cards into `PrivatePlayerResponse`. This payload is delivered exclusively over Spring's user-destination queue (`/user/queue/private`) and served via `GET /api/game/{roomId}/private-state`.
3. **Membership-Bound Routing**: Spring user identities include player, room, and `membershipId`. The publisher captures the active membership when the mutation is accepted. A socket retained after REST departure can remain subscribed to its old queue but cannot receive a replacement membership’s private projection. All sockets in the same current membership still receive private state. The client subscribes to the unchanged `/user/queue/private` destination.
4. **Decoupled Delivery**: Real-time broadcasts defer until accepted durable mutations complete, ensuring neither channel receives speculative or uncommitted state.

## Consequences

- Completely eliminates client-side cheating via browser DevTools inspection or memory dumping.
- Opponent hole cards exist in client memory only when legally revealed at showdown.
- Requires frontend clients to maintain two distinct reception streams and reconcile them into a unified table presentation.
