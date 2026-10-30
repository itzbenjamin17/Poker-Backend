# Poker Backend

Ubiquitous language and domain vocabulary for the multiplayer poker game server.

## Room Lifecycle

**Room**:
An aggregate managing table configuration, player capacity, and admission before hand play begins.
_Avoid_: Lobby, TableGroup, MatchRoom

**RoomHost**:
The player authorized to configure room parameters and initiate transition into an active game.
_Avoid_: Admin, Owner, RoomLeader

**MembershipId**:
An opaque identifier binding a player's active session to a specific room.
_Avoid_: SessionId, PlayerToken, MemberKey

## Game & Hand Progression

**Game**:
The aggregate managing poker rules, betting rounds, deck shuffling, pot accounting, and showdown settlement.
_Avoid_: TableMatch, PokerTable

**HandPhase**:
The active stage of a poker hand: PRE_FLOP, FLOP, TURN, RIVER, or SHOWDOWN.
_Avoid_: GameState, RoundState, StreetPhase

**PotContributionLedger**:
The internal accounting ledger recording cumulative chip wagers per player across betting rounds to compute main and side pot caps.
_Avoid_: PotTracker, BetBook, ChipLedger

**HandSettlementResult**:
An immutable record of pot allocations, uncalled wager refunds, and odd-chip distributions concluding a hand.
_Avoid_: PayoutSummary, RoundResult, WinnerState

**EpochDeadline**:
An absolute wall-clock timestamp defining when an active player turn, readiness countdown, or disconnect grace period expires.
_Avoid_: TimerDeadline, TaskExpiry, CountdownTime

## Information Boundaries

**PublicProjection**:
The sanitized view of table state containing community cards, bets, and chip totals while opponents' hole cards remain concealed.
_Avoid_: PublicState, TableBroadcast, ClientView

**PrivateProjection**:
The confidential view containing seat assignment and secret hole cards delivered exclusively to that individual player.
_Avoid_: PrivateState, PlayerSecret, HandPayload

## Resilience & Durability

**DurableMutation**:
An operation that applies a state change to the live table and commits an encrypted recovery image to disk.
_Avoid_: SaveAspect, PersistHook

**QuarantinedWal**:
A corrupted or unsupported recovery log isolated to prevent it from halting overall system recovery.
_Avoid_: BadFile, DeadLetterWal, DroppedRoom

**StateRevision**:
A monotonically increasing counter tracking public game state changes within an active table.
_Avoid_: VersionNumber, SequenceId

**DisconnectGracePeriod**:
An absolute window of time during which an absent player's seat and chips are preserved before automatic forfeiture.
_Avoid_: ReconnectTimeout, AbsenceWindow, DropWait
