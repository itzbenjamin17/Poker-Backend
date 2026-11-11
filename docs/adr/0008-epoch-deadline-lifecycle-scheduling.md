# 0008: Epoch-Deadline Lifecycle Scheduling and Recovery Resumption

We decided to store all game timer deadlines as exact wall-clock timestamps (milliseconds since the Unix epoch) directly on `Game` and `Player`, and have background timer callbacks verify their task generation counter before running so timers can safely resume after a server restart.

## Context

A Texas Hold'em match relies on several background timers (`ScheduledGameTask`: `AUTO_ADVANCE`, `READY_OPEN`, `NEW_HAND`, `DISCONNECT_CLEANUP`, and `CLEANUP`), including reconnect grace periods (120s default), round-end pauses (0ms default), between-hand ready countdowns (30s default), and finished-game cleanup delays (20s default). Standard Java `ScheduledFuture` handles live only in RAM and vanish when the server restarts, while persistent job schedulers like Quartz require an external database.

## Decision

1. **Save Deadlines in Game State**: Store the exact wall-clock expiration time (`deadlineEpochMs`, `disconnectDeadlineEpochMs`, `readyCountdownDeadlineEpochMs`) and a task generation counter (`scheduledTaskGenerations`) directly on `Game` and `Player` so they are saved to disk as part of `AggregateStateImage`.
2. **Ignore Outdated Timer Callbacks**: When a scheduled task fires on Spring's `ThreadPoolTaskScheduler`, it locks the room and checks whether the `Game`'s current task generation counter and state still match the timer that was scheduled. If the game has already moved on (for example, a player acted before their turn timer ran out), the outdated callback does nothing.
3. **Resume Timers on Startup**: When `PersistenceRecovery.java` restores saved rooms on server startup, it inspects each saved deadline:
   - If the deadline is still in the future, it schedules a timer for the remaining time.
   - If the deadline already passed while the server was down, it runs the task immediately.

## Consequences

- Turn timers, ready countdowns, and disconnect grace periods survive server restarts without an external database.
- Outdated timer callbacks can never accidentally advance or corrupt a newer hand or betting round.
- Sending exact wall-clock deadlines (`turnDeadlineEpochMs`, `readyCountdownDeadlineEpochMs`) lets the frontend draw accurate countdown bars regardless of network delay.