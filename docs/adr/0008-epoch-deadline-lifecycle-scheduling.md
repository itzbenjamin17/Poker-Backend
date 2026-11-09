# 0008: Epoch-Deadline Lifecycle Scheduling and Recovery Resumption

We decided to model all asynchronous game timers as absolute epoch millisecond deadlines stored directly on aggregate state, executing via Spring TaskScheduler callbacks that validate their expected task token before acting and dynamically resuming after WAL recovery.

## Context

Texas Hold'em matches rely on timed lifecycle transitions (`ScheduledTaskType`: `AUTO_ADVANCE`, `READY_OPEN`, `NEW_HAND`, `DISCONNECT_CLEANUP`, and `GAME_END_CLEANUP`), including reconnect grace periods (default 120s), round-end display pauses (default 0ms), inter-hand ready countdowns (default 30s), and completed-game retention delays (default 20s). Standard Java concurrency uses `java.util.concurrent.ScheduledFuture` handles stored in volatile memory, while enterprise architectures use persistent job schedulers like Quartz backed by a relational database. Volatile futures are permanently lost during single-server process restarts, while Quartz introduces heavyweight database dependencies contrary to the in-memory architecture.

## Decision

1. **State-Embedded Deadlines**: Store absolute epoch millisecond timestamps (`deadlineEpochMs`, `disconnectDeadlineEpochMs`, `readyCountdownDeadlineEpochMs`) and a unique `scheduledTaskId` directly on the `Game` and `Player` aggregates.
2. **Defensive Callback Verification**: Scheduled callbacks submitted to Spring's `ThreadPoolTaskScheduler` do not blindly execute when their delay expires. Instead, they acquire the aggregate lock and verify that the aggregate's active `scheduledTaskId` and state still match the callback token. Superseded or cancelled tasks are dropped as no-ops.
3. **Startup Recovery Rescheduling**: In `PersistenceRecovery.java`, restored aggregates have their stored epoch deadlines inspected:
   - If the deadline is in the future, the remaining duration is calculated and rescheduled.
   - If the deadline has already passed while the server was down, the task is executed immediately to advance the game or mark disconnected status.

## Consequences

- Full crash resilience: game timers, turn countdowns, and disconnect windows survive server restarts and ungraceful process terminations without an external database.
- Prevents race conditions from obsolete delayed threads firing against newer game phases.
- Absolute epoch timestamps allow frontend clients to render synchronized visual countdown bars that remain accurate despite network latency.
