# Poker Backend

Plain-English domain vocabulary and code terms for the multiplayer poker game server.

## Room Lifecycle & Identity

**Room**:
A poker table's lobby and configuration—holding the room name, blinds, buy-in, optional password, and the list of players who have joined.

**Host (`hostName`)**:
The player who created the room (or inherited host status) and is allowed to start the game.

**Player**:
A room/game participant whose authenticated identity is player name plus room ID.

**membershipId**:
A unique pass generated each time a player joins a room and embedded in their JWT token; if a player leaves and rejoins, their old token's `membershipId` no longer matches and is rejected.

**RoomCoordinator**:
The single concurrency gatekeeper for each room that locks by the `roomId` string (instead of `synchronized (room)` or `synchronized (game)`) so that only one thread at a time can read, change, roll back, or save a room and its game.

## Concurrency

**Lock**:
A concurrency mechanism that allows one thread at a time to run a protected operation for a key. `RoomCoordinator` uses `roomId` as the key, so operations for the same room take turns while operations for different rooms can proceed independently.

**LockLease**:
An `AutoCloseable` handle representing one successful lock acquisition. The acquiring thread holds the lock until it closes the lease, usually automatically at the end of a try-with-resources block. Like a C file handle represents an open file, a lease represents an acquired lock; closing it releases that acquisition but does not necessarily remove the lock from the registry.

**Room lock**:
The per-room lock managed by `RoomCoordinator` and held via a `LockLease` so only one thread operates on a room at a time.

## Game & Hand Progression

**Game**:
The live poker match running inside a `Room` (sharing the same ID), tracking the deck, seated players, chip stacks, betting rounds, and pots across hands.

**Hand / round**:
One deal and betting sequence ending in showdown, distinct from game completion.

**GamePhase**:
The current stage of a poker hand: `PRE_FLOP`, `FLOP`, `TURN`, `RIVER`, or `SHOWDOWN`.

**PotCalculator**:
The helper that tracks how many chips each player has put in during a hand (`handContributions`) and splits the main pot, side pots, and uncalled bet refunds when players go all-in.

**HandSettlementResult**:
The final breakdown at the end of a hand showing who won which pot, any uncalled bets returned, and who received any odd chip from a split pot.

**ReadyGate (`ReadyGateState`, ready countdown)**:
The shared between-hand pause and countdown gate (`CLOSED`, `COUNTING_DOWN`, `WAITING_FOR_PLAYERS`) where players mark ready before the next hand is dealt.

**ScheduledGameTask**:
A timed background action (`AUTO_ADVANCE`, `READY_OPEN`, `NEW_HAND`, `DISCONNECT_CLEANUP`, `CLEANUP`) stored on the game with an exact clock deadline (`deadlineEpochMs`) and timer version so timers can safely resume after a server restart.

**timerVersion (`timerVersions`)**:
The per-task counter on `Game` incremented whenever a `ScheduledGameTask` is scheduled or cancelled so stale timer callbacks are ignored.

**stateRevision**:
A counter (`1, 2, 3...`) that goes up every time the public game state changes, allowing clients to tell which update is newest and ignore out-of-order messages.

**disconnectGracePeriodMs (disconnect deadline)**:
The grace window (120 seconds by default) and resulting absolute deadline after a player's last WebSocket session disconnects during which their seat, chips, and cards are held before automatic fold and removal.

### End-State Flags

**`isGameOver()` (`gameOver`)**:
True once the match has permanently ended (via `settleGameEnd`, `resetForNewHand`, or `markGameOver`), stopping gameplay and turn timers while keeping post-game review available until cleanup.

**`isGameDecided()`**:
True when one or zero non-out players still hold chips (`chips > 0`), meaning no further hand can begin even before `settleGameEnd` marks `gameOver`.

**`isMatchConcluded()`**:
True when `!pausedForDisconnectedSurvivor` and either `gameOver` is true or a settled hand has decided the winner (`isHandSettled() && isGameDecided()`), so player departures no longer affect live play.

**`isFinalPotSettled()` (`finalPotSettled`)**:
True once `settleGameEnd` has awarded any remaining carry-over or unsettled pot to the match winner, preventing duplicate final-pot payouts on retries or crash recovery.

**`isHandSettled()`**:
True when `lastSettlementResult != null`, meaning the current or most recent hand has completed pot distribution (by fold, void hand, or showdown) and is awaiting the next hand or game end.

**`isHandInProgress()`**:
True when `currentPhase != GamePhase.SHOWDOWN || !isHandSettled()`, distinguishing an in-progress or pre-showdown hand from a completed showdown so mid-hand zero-chip contenders remain eligible and new-hand requests are rejected while a hand is still running.

**`isPausedForDisconnectedSurvivor()` (`pausedForDisconnectedSurvivor`)**:
True when all opponents leave or time out while one sole contender remains disconnected within their reconnect grace period. The game enters this holding state—pausing new hands, ready countdowns, and game-end settlement—until that survivor either reconnects (winning by forfeit) or their grace period expires (ending the abandoned match).

**`Room.isFinished()` (`isFinished`)**:
Room-level flag mirroring `gameOver` once the match finishes so `RoomService` keeps an empty room alive for post-game review instead of destroying it immediately.

## Poker Rules & Betting

**Betting round (street)**:
One cycle of betting during a stage of a hand (`PRE_FLOP`, `FLOP`, `TURN`, or `RIVER`), ending once every player who can still act has had a turn and either matched the highest bet or folded.

**Blinds (`smallBlind`, `bigBlind`)**:
Mandatory starting bets posted before cards are dealt by the two players after the dealer (or by the dealer and opponent in a two-player game) so every hand begins with chips in the pot.

**Hole cards**:
The two private face-down cards dealt to each player at the start of a hand.

**Community cards (board)**:
Up to five shared face-up cards dealt to the center of the table across the flop (three cards), turn (fourth card), and river (fifth card).

**Check / Call / Bet / Raise / Fold (`PlayerAction`)**:
The five standard turn choices: pass without betting when nothing is owed (`CHECK`), match the current highest bet (`CALL`), place the first bet of a round (`BET`), increase an existing bet (`RAISE`), or give up the hand and any chips already put in (`FOLD`).

**All-in (`ALL_IN`)**:
Putting all of a player's remaining chips into the pot; an all-in player stays in the hand to contest the pot up to their contribution but takes no further turns.

**Full raise vs. short all-in (reopening betting)**:
A raise of at least the minimum raise increment (the previous full raise in the round, or the big blind if none yet). If an all-in player raises by less than that full increment (a *short all-in*), players who have already acted in the round may call or fold but cannot raise again unless someone else makes a full raise.

**Auto-advance (runout)**:
Automatically dealing the remaining community cards on a timer to showdown when at most one player still has chips left to bet and no player owes a call.

**Contender**:
A player who is not out of the match and either is still in an unfinished hand or still holds chips.

## Client Payloads & Views

**Public snapshot (`PublicGameStateResponse`)**:
The shared table state projection sent to everyone in the room (community cards, pots, chip stacks, turn timers, and current bets), excluding live private hole cards and deck order.

**PrivatePlayerState**:
A player's confidential view containing only their own player ID, hand number, and face-down hole cards, delivered only to that player.

**GameEndResponse**:
The final match summary recording the overall winner, winning chip total, whether the win was by forfeit or abandonment, and the final table snapshot for post-game review.

## Persistence & Crash Recovery Terms

**RoomAggregate**:
The `persistence/` record pairing a live `Room` and its active `Game` (if any) as one unit when reading, replacing, or removing room state.

**RecoveredRoom**:
The record returned when loading a saved room during startup recovery, pairing the restored `Room`, its restored `Game` (if any), and a `deleted` tombstone flag.

**RoomSnapshotMapper**:
The mapper that converts live `Room` and `Game` state into a `RoomSnapshot` before saving and reconstructs a `RecoveredRoom` from a saved `RoomSnapshot`.

**GameResultState**:
The persisted form inside `RoomSnapshot.GameState` that stores a finished match's `GameEndResponse` for post-game review across restarts.

**DurableMutation (`@DurableMutation`)**:
An annotation on room and game service methods that changes live state in memory, rolls back memory if an error is thrown, and saves an encrypted backup of the room to disk.

**RoomSnapshot**:
A frozen copy of a `Room` and its `Game` at a specific moment that can be safely encrypted and written to disk or used to roll back a failed change in memory.

**WAL (`EncryptedWalStore`, `WalTransaction`)**:
The per-room encrypted save file (`<roomId>.wal`, short for Write-Ahead Log) on disk that retains current live recoverable state (`RoomSnapshot`) rather than a historical hand archive; broken save files are renamed to `<roomId>.wal.corrupted` on startup so one bad file doesn't stop the server from booting.

**Transient**:
Something that is in-memory so it is not persisted to disk and is lost on server restart.