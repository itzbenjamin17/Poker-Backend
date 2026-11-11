# Poker Backend

Plain-English domain vocabulary and code terms for the multiplayer poker game server.

## Room Lifecycle & Identity

**Room**:
A poker table's lobby and configuration—holding the room name, blinds, buy-in, optional password, and the list of players who have joined.

**Host (`hostName`)**:
The player who created the room (or inherited host status) and is allowed to start the game.

**membershipId**:
A unique pass generated each time a player joins a room and embedded in their JWT token; if a player leaves and rejoins, their old token's `membershipId` no longer matches and is rejected.

**RoomCoordinator**:
The single concurrency gatekeeper for each room that locks by the `roomId` string (instead of `synchronized (room)` or `synchronized (game)`) so that only one thread at a time can read, change, roll back, or save a room and its game.

## Concurrency

**Lock**:
A concurrency mechanism that allows one thread at a time to run a protected operation for a key. `RoomCoordinator` uses `roomId` as the key, so operations for the same room take turns while operations for different rooms can proceed independently.

**LockLease**:
An `AutoCloseable` handle representing one successful lock acquisition. The acquiring thread holds the lock until it closes the lease, usually automatically at the end of a try-with-resources block. Like a C file handle represents an open file, a lease represents an acquired lock; closing it releases that acquisition but does not necessarily remove the lock from the registry.

## Game & Hand Progression

**Game**:
The live poker match running inside a `Room` (sharing the same ID), tracking the deck, seated players, chip stacks, betting rounds, and pots across hands.

**GamePhase**:
The current stage of a poker hand: `PRE_FLOP`, `FLOP`, `TURN`, `RIVER`, or `SHOWDOWN`.

**PotCalculator**:
The helper that tracks how many chips each player has put in during a hand (`handContributions`) and splits the main pot, side pots, and uncalled bet refunds when players go all-in.

**HandSettlementResult**:
The final breakdown at the end of a hand showing who won which pot, any uncalled bets returned, and who received any odd chip from a split pot.

**ReadyGate (`ReadyGateState`)**:
The between-hand pause and countdown (`CLOSED`, `COUNTING_DOWN`, `WAITING_FOR_PLAYERS`) where players mark ready before the next hand is dealt.

**ScheduledGameTask**:
A timed background action (`AUTO_ADVANCE`, `READY_OPEN`, `NEW_HAND`, `DISCONNECT_CLEANUP`, `CLEANUP`) stored on the game with an exact clock deadline (`deadlineEpochMs`) and generation counter so timers can safely resume after a server restart.

**stateRevision**:
A counter (`1, 2, 3...`) that goes up every time the public game state changes, allowing clients to tell which update is newest and ignore out-of-order messages.

**disconnectGracePeriodMs**:
The time window (120 seconds by default) after a player's last browser tab disconnects during which their seat, chips, and cards are held before they are auto-folded and removed.

## Client Payloads & Views

**PublicGameStateResponse**:
The shared table state sent to everyone in the room (community cards, pots, chip stacks, turn timers, and current bets), with opponents' face-down hole cards hidden.

**PrivatePlayerState**:
A player's confidential view containing only their own player ID, hand number, and face-down hole cards, delivered only to that player.

**GameEndResponse**:
The final match summary recording the overall winner, winning chip total, whether the win was by forfeit or abandonment, and the final table snapshot for post-game review.

## Persistence & Crash Recovery Terms

**Aggregate (`RecoveredAggregate`, `AggregateSnapshotMapper`)**:
Domain-Driven Design term used in the persistence package for a room (`Room`) and its active game (`Game`) treated together as one self-contained unit when saving, restoring, or rolling back state.

**DurableMutation (`@DurableMutation`)**:
An annotation on room and game service methods that changes live state in memory, rolls back memory if an error is thrown, and saves an encrypted backup of the room to disk.

**AggregateStateImage**:
A frozen, versioned copy of a `Room` and its `Game` at a specific moment that can be safely encrypted and written to disk or used to roll back a failed change in memory.

**EncryptedWalStore (`WalTransaction`)**:
The per-room encrypted save file (`<roomId>.wal`, short for Write-Ahead Log) on disk that records the latest `AggregateStateImage` so active games survive a server restart; broken save files are renamed to `<roomId>.wal.corrupted` on startup so one bad file doesn't stop the server from booting.
