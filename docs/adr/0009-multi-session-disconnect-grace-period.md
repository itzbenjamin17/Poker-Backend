# 0009: Multi-Session Disconnect Tracking with Reconnect Grace Period

We decided to track all active WebSocket connections for each player in a room and wait for a configurable grace period (`disconnectGracePeriodMs`, 120 seconds by default) after their last connection closes before removing them from the table.

## Context

In browser games, players often refresh the page, switch tabs, or briefly lose Wi-Fi. If the server treated every closed WebSocket (`SessionDisconnectEvent`) as quitting the match, a simple page refresh would immediately fold the player's cards and forfeit their buy-in. Also, if a player opens a second tab or reconnects before the old socket finishes closing, tracking only a single session ID would mistakenly mark the player as disconnected when the old socket closes.

## Decision

1. **Track a Set of Active Connections per Player**: In `WebSocketEventListener`, map each player in a room (`playerName:roomId`) to a set of active STOMP session IDs (`userSessions`). A player is only marked disconnected when their set of active connections becomes completely empty.
2. **Start a Grace Period Timer on Last Disconnect**: When a player's last connection closes:
   - Mark the player as `disconnected = true` on `Game` and `Room`.
   - Record the exact clock time when their grace window expires (`System.currentTimeMillis() + disconnectGracePeriodMs`, 120 seconds by default).
   - Schedule a `DISCONNECT_CLEANUP` task with `TaskScheduler`.
3. **Cancel Cleanup on Reconnect**: If the player reconnects and subscribes (`SessionSubscribeEvent`) before the deadline runs out:
   - Clear the `disconnected` flag.
   - Cancel the scheduled `DISCONNECT_CLEANUP` task.
   - Keep the player's seat, chip stack, and hole cards intact.

## Consequences

- Page refreshes and brief network drops no longer fold a player's hand or forfeit their chips.
- Opening or closing extra tabs for the same player does not falsely trigger a disconnect as long as one tab stays connected.
- If a player stays offline past the grace deadline, the scheduled task automatically folds their hand, refunds any uncalled bets, and frees their seat so the table does not stall forever.