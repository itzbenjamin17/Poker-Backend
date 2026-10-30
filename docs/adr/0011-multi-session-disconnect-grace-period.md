# 0011: Multi-Session Disconnect Tracking with Reconnect Grace Period

We decided to track multiple active STOMP session IDs per composite player identity and apply an absolute 60-second grace period before evicting disconnected players or folding active hands.

## Context

In web-based multiplayer games, players frequently refresh their browser tab, navigate back and forth, or switch between Wi-Fi and mobile data. Naive WebSocket servers treat any socket closure (`SessionDisconnectEvent`) as an immediate player abandonment, instantly folding their hand, forfeiting their chips, or kicking them from the room. Furthermore, if a player opens a second tab or reconnects before the first socket unregisters, single-session tracking mistakenly treats the new connection as disconnected when the old socket tears down.

## Decision

1. **Multi-Session Set Tracking**: In `WebSocketEventListener`, maintain a concurrent mapping from composite player identity (`playerName:roomId`) to a set of active STOMP session IDs (`userSessions`). A player is only considered disconnected when their active session set becomes empty.
2. **Scheduled Grace Period**: When a player's last active session disconnects:
   - Mark the player as `disconnected = true` on the `Game` and `Room` aggregates.
   - Assign an absolute disconnect deadline (`System.currentTimeMillis() + disconnectGracePeriodMs`, default 60 seconds).
   - Schedule a delayed eviction task with `TaskScheduler`.
3. **Seamless Reconnection Cancellation**: When the player re-establishes a WebSocket connection (`SessionSubscribeEvent`) before the deadline expires:
   - Clear the `disconnected` flag.
   - Cancel the scheduled disconnect eviction task.
   - Retain the player's active seat, chip stack, and hole cards without interruption.

## Consequences

- Prevents accidental folds and buy-in forfeiture during transient network hiccups, browser refreshes, or tab switches.
- Accurately tracks multi-tab players without cross-tab disconnect races.
- If the player fails to reconnect within the 60-second window, the scheduled cleanup auto-folds their hand, awards any uncalled chips, and vacates their seat to protect the table from stalling.
