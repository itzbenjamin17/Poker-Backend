# 0005: STOMP SEND Prefix Whitelisting and Destination Authorization

We decided to validate destination paths and enforce per-room authorization on all incoming WebSocket STOMP SEND frames inside `WebSocketAuthInterceptor`.

## Context

Spring WebSocket applications authenticate `CONNECT` frames and authorize `SUBSCRIBE` topics, but by default allow arbitrary `SEND` frames through unless channel interceptors inspect them. A malicious or compromised client could send STOMP `SEND` messages directly to public broker topics (`/topic/**`) to broadcast forged messages, or send to another table's endpoint (`/app/game/{otherRoomId}/action`) to tamper with games they were not seated at.

## Decision

1. **Prefix Whitelisting**: Reject any STOMP `SEND` frame whose destination does not begin with `/app/`. Direct publishing to broker topics (`/topic/`, `/queue/`) is forbidden.
2. **Room Destination Authorization**: Extract the target room ID from room-scoped destinations (e.g. `/app/game/{roomId}/*`, `/app/room/{roomId}/*`) and verify that it matches the authenticated `PlayerPrincipal.roomId()`.
3. **Fail-Fast Rejection**: Drop unauthorized frames immediately and throw `AccessDeniedException`.

## Consequences

- Clients cannot inject forged messages or bypass application controllers.
- Cross-room message injection and game tampering are blocked at the protocol transport boundary.
- All client messages are strictly channeled through application controllers.
