# Poker Backend AI Project Context

This is the canonical architecture and runtime-invariant reference for agents. Root [AGENTS.md](../../AGENTS.md), [CLAUDE.md](../../CLAUDE.md), and [GEMINI.md](../../GEMINI.md) point here; review-time standards live in [CODING_STANDARDS.md](../../CODING_STANDARDS.md), domain vocabulary in [GLOSSARY.md](../../GLOSSARY.md), and subagent delegation rules in [docs/agents/roles.md](../agents/roles.md). Read relevant [ADRs](../adr/) before changing architecture and use source/configuration for exact behavior.

## Project Overview

A Java 25 / Spring Boot 4.0.9 real-time multiplayer Texas Hold'em backend paired with the React client in `../frontend-poker`. It owns room membership, poker rules, chip accounting, hand progression, deadlines, public/private projections, and player authorization. Live rooms and games stay in memory, with encrypted room-snapshot WAL persistence restoring state after a single-server restart (no external database, user accounts, or multi-node writer support). Authoritative dependencies live in [pom.xml](../../pom.xml).

## Development And Configuration

[application.properties](../../src/main/resources/application.properties) maps required settings from environment variables; [.env.example](../../.env.example) documents local setup (dotenv supports a local ignored `.env`). `SERVER_PORT` sets the listen port (`8080` is expected by the companion frontend).

- **`test` profile** ([application-test.properties](../../src/test/resources/application-test.properties)): random port, test keys, short timings, and disabled rate limiting/persistence (persistence tests enable their own recovery config).
- **`frontend-e2e` profile** ([application-frontend-e2e.properties](../../src/test/resources/application-frontend-e2e.properties)): layered with `test` via `.\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.profiles=test,frontend-e2e"` to bind port `8080` and the display/ready timings Playwright expects.

[Dockerfile](../../Dockerfile) packages a Java 25 runtime on port `8080` with `/actuator/health` and WAL/log mount paths; supply runtime configuration and persistent storage separately.

## Code Map

All production packages are under `src/main/java/com/pokergame/`.

| Package / entry point | Responsibility |
| --- | --- |
| [RoomController](../../src/main/java/com/pokergame/controller/RoomController.java) | Create/join, room snapshot, leave, and host-authorized start. |
| [GameController](../../src/main/java/com/pokergame/controller/GameController.java) | Public/private snapshots, post-game review endpoint, STOMP action/ready/unready, REST claim-win. |
| [RoomService](../../src/main/java/com/pokergame/service/RoomService.java) | Room lifecycle on top of `RoomRegistry` (the room map), name lookup, lobby membership, host tracking on `Room`, lobby inactivity cleanup, and room broadcasts. `getRoom(...)` asserts room lock ownership. |
| [RoomCoordinator](../../src/main/java/com/pokergame/util/RoomCoordinator.java) and `ActiveGameRegistry` | Single per-room concurrency lock boundary ([ADR-0011](../adr/0011-room-coordinator-single-boundary-concurrency.md)) and live game lookup. `ActiveGameRegistry` methods and `RoomService.getRoom(...)` verify the calling thread already holds the room lock. |
| [GameLifecycleService](../../src/main/java/com/pokergame/service/GameLifecycleService.java) | Game creation, room-coordinated entry points, and workflow routing. |
| Lifecycle collaborators (`service/`) | Package-private Spring `@Component` beans: `GameReadinessCoordinator` (ready gate/countdown), `HandProgressionService` (dealing/street progression), `PlayerDepartureHandler` (leave, host transfer, timeout, reconnect, grace expiry, claim-win), `GameCompletionHandler` (settlement, inactivity abandonment, review lookup, game/room deletion), `GameTaskScheduler` (timer scheduling and claim validation), `GameTimerRegistry` (`TaskScheduler` futures for game timers, active-game inactivity, and lobby inactivity), and `LobbyInactivityScheduler` (the lobby expiry callback: it takes the room lock, checks the deadline, and calls the durable close through `ObjectProvider<RoomService>` only when the lobby has expired). |
| [PlayerActionService](../../src/main/java/com/pokergame/service/PlayerActionService.java) | Action validation, betting mutations, turn/round progression, and lifecycle scheduling. |
| [GameStateService](../../src/main/java/com/pokergame/service/GameStateService.java) | Public/private snapshots and WebSocket broadcasts queued via `PostSaveActions` until saving completes. |
| `model/` and `enums/` | `Game` (including `GameRestoreBuilder`), `Room`, `Player`, `Deck`/`Card` state; package-private collaborators `BettingRound`, `TableSeating`, `PotCalculator`, `ShowdownResolver`, `GameScheduling`, and `ReadyGate`; [HandEvaluator](../../src/main/java/com/pokergame/model/HandEvaluator.java); poker phases/actions/statuses and scheduled-task identifiers. |
| `dto/request/`, `dto/response/`, `dto/internal/` | Validated input, client projections, and service-internal results. |
| `advice/` and `exception/` | HTTP/STOMP error mapping and domain exceptions. |
| `security/` and `config/` | JWT principal/filters, payload/rate limits, origins, STOMP authorization, session events, executor/scheduler setup. |
| `RoomAggregates` (`persistence/`), `RoomAggregateStore` (`service/`) | The only view persistence has of live rooms and games: `find`, `replace` (rollback, timers rescheduled) and `remove`. `persistence/` imports nothing from `service`, `config` or `controller`. |
| `persistence/transaction/`, `snapshot/`, `wal/`, `config/` | Durable AOP boundary (`DurableMutationAspect`, `PostSaveActions`), room-snapshot mappings (`RoomSnapshot`, `RoomSnapshotMapper`), encrypted WAL/keyring, and persistence settings/health. |
| [PersistenceRecovery](../../src/main/java/com/pokergame/persistence/PersistenceRecovery.java) | Startup restoration, disconnected-client assumptions, and deadline rescheduling. |
| `util/` | Input sanitization, principal/client-IP helpers, MDC context, and log throttling. |

## Room, Game, And Concurrency Model

Rooms have UUID identities and user-visible names; joining resolves the room name. Create-room validation permits 2–10 players. Games reuse the room ID, making room/game paths and token binding refer to the same aggregate identity.

`RoomService` and `ActiveGameRegistry` maintain concurrent registries, but registry concurrency does not make mutable `Room` and `Game` objects thread-safe. Under [ADR-0011](../adr/0011-room-coordinator-single-boundary-concurrency.md), `RoomCoordinator` provides the single concurrency boundary per room, following a two-part room-lock rule:

1. **Only entry points and `DurableMutationAspect` acquire the room lock** via `roomCoordinator.acquire(roomId)`: controllers (`RoomController`, `GameController`), `WebSocketEventListener`, `WebSocketAuthInterceptor`, `LobbyInactivityScheduler`, `PersistenceRecovery`, `EncryptedWalStore.recoverAll`, and `DurableMutationAspect` (`persist` and `retryPendingSaves`). When persistence is disabled, `DurableMutationAspect` remains registered (`PersistenceConfiguration`) and acts as a room-lock pass-through.
2. **Nothing else takes the lock:** service methods and aspect helpers assert `roomCoordinator.assertHeldByCurrentThread(roomId)` instead (non-durable methods; `@DurableMutation` entry methods rely on the aspect). `ActiveGameRegistry` and non-durable `RoomService`/`GameLifecycleService` accessors enforce this so internal lifecycle collaborators rely on them rather than repeating lock assertions or `...UnderLock` method suffixes. Store canonical mutable room/game state directly on `Room` (`hostName`, `inactivityDeadlineEpochMs`, `isFinished`) and `Game` (`lastHandPayout`, `gameEndResult`, `pausedForDisconnectedSurvivor`) rather than shadow maps in services.

Nested same-room operations reenter safely, while cross-room nested coordination throws `IllegalStateException`. Route scheduled mutations through the Spring proxy so coordination and persistence advice executes.

`Game` owns the deck, players, betting state, phase (`PRE_FLOP`, `FLOP`, `TURN`, `RIVER`, `SHOWDOWN`), pot/side-pot bookkeeping, showdown outcomes, and scheduled-task metadata, remaining the root owner of its mutable state while `GameRestoreBuilder` restores it from a `RoomSnapshot`. Preserve legal-action checks, heads-up blind rules, all-in eligibility, uncalled refunds, split payouts, and chip conservation.

`Room` owns `hostName` and game inactivity deadline checks (`extendGameInactivityDeadline`, `isInactivityExpired`, `hasInactivityDeadline`); `Game` stores scheduled task deadlines, timer versions, `lastHandPayout`, and `gameEndResult`. `GameTaskScheduler` records task deadlines and timer versions on `Game` before saving, queues `GameTimerRegistry` futures via `PostSaveActions.runAfterSave(...)`, and re-enters `GameLifecycleService` through `ObjectProvider<GameLifecycleService>` so coordination/persistence advice runs before verifying hand and timer-version claims. `RoomRestoreService` applies startup recovery rules and delegates post-restart inactivity, disconnect-grace, and game timer resumption to `RecoveredRoomTimerService`.

WebSocket identity allows multiple browser tabs per player; disconnect tracking triggers a grace deadline only after the last active session closes and cancels cleanup on reconnect. Final game-end and cleanup scheduling remain separate from single-hand showdown/ready transitions.

## API And Real-Time Contracts

| Transport | Endpoints / destinations |
| --- | --- |
| Public room commands | `POST /api/room/create`, `POST /api/room/join`; return `ApiResponse<TokenResponse>`. |
| Room snapshot | `GET /api/room/{roomId}`; returns `RoomDataResponse`. |
| Secured room commands | `POST /api/room/{roomId}/start-game`, `POST /api/room/{roomId}/leave`. |
| Secured game snapshots | `GET /api/game/{gameId}/state`, `GET /api/game/{gameId}/private-state`; return direct public/private DTOs. |
| Secured game commands | `POST /api/game/{gameId}/claim-win`. |
| STOMP handshake | `/ws`; bearer token supplied in CONNECT headers. |
| Client SEND | `/app/{gameId}/action`, `/app/{gameId}/ready`, `/app/{gameId}/unready`. |
| Client SUBSCRIBE | `/room/{roomId}`, `/game/{gameId}`, `/user/queue/private`. |

Use [WebSocketConfig](../../src/main/java/com/pokergame/config/WebSocketConfig.java) and controllers as exact mapping references; the simple broker serves `/room`, `/game`, and `/user/queue` prefixes. Public DTOs carry chips, players, board cards, turn/phase/pot state, and optional showdown/readiness/disconnect/claim-win metadata; private state carries player ID and hole cards. Keep optional/null payload fields compatible with frontend decoders.

Public snapshots also carry `handNumber` and `stateRevision`: `GameStateService` increments `stateRevision` on each public broadcast (resetting it to `1` in `Game.beginHand()`), and `RoomSnapshot` saves it so clients can drop slower REST `/state` responses or out-of-order WebSocket messages—even across a server restart.

### Response Envelope Convention

The command/query split documented in [CODING_STANDARDS.md](../../CODING_STANDARDS.md#response-envelope) matches `../frontend-poker/src/services/api.ts`, which unwraps `message`/`data` on command responses and decodes `GET` queries (notably `getReview`) as bare top-level DTOs. Do not change a query envelope without coordinating the frontend decoder.

## Security And Boundaries

HTTP uses stateless JWT authentication: create/join, `/ws`, and health/info endpoints are public; remaining HTTP routes require JWT auth under [SecurityConfig](../../src/main/java/com/pokergame/config/SecurityConfig.java). JWTs bind player name, room ID, and active `membershipId` ([ADR-0004](../adr/0004-stateless-jwt-session-membership-validation.md)). [PlayerPrincipal](../../src/main/java/com/pokergame/security/PlayerPrincipal.java) uses `playerName:roomId:membershipId` for private user routing, while connection tracking and rate limits key by `playerName:roomId`. Derive actions from the authenticated principal and verify room/game, membership, and host checks.

`WebSocketAuthInterceptor` authenticates CONNECT, enforces `/app/` destination whitelisting, checks room/game subscription binding and active `membershipId`, and routes private payloads to user destinations. Preserve payload-size caps, REST/STOMP rate limiting, validation, name sanitization, CORS, and trusted-proxy handling defined in DTOs/filters/services.

## Durable Recovery

[ADR-0001](../adr/0001-encrypted-state-image-wal-with-spring-aop.md) defines encrypted room-snapshot WAL persistence; [persistence operations](../persistence-operations.md) covers keys, backup/rotation, health, retention, and restore drills.

When enabled, `@DurableMutation` runs PREPARE, executes the annotated method, snapshots an immutable `RoomSnapshot`, and runs authenticated COMMIT—releasing queued `PostSaveActions` client messages and timer registrations only after saving succeeds. Nested `@DurableMutation` calls join the same room transaction; cross-room nesting throws.

**Check-first rule:** Validate everything before changing any state so a throw never leaves a partial change. With persistence off, nothing is rolled back.

AES-256-GCM protects `RoomSnapshot` records (passwords, private cards, deck order, chips, deadlines), binding associated data to the record and room identity. Keep encryption keys separate from WAL files; one room has one writer on one server (never share a WAL volume across instances).

Startup recovery restores all live rooms and games, recreates process-local collaborators, treats prior sockets as disconnected with reconnect grace, and resumes overdue/future timers with version guards without replaying randomness or commands. A torn tail is recoverable. Per-room corrupted WALs, unparseable snapshots, and restoration errors are quarantined so healthy rooms recover; failed WAL deletions during recovery warn and skip without quarantining. Missing decryption keys, an unhealthy store, directory-enumeration errors, and required store failures remain fatal (preserving data when a key is missing).

Runtime save/maintenance failures are isolated per room: affected rooms pause ordinary appends until a checkpoint or deletion clears the error while other rooms continue normally. Accepted gameplay stays in memory, releases effects once, and retries the latest accepted `RoomSnapshot` or deletion tombstone every 5s (and on subsequent mutations) under the room lock, checkpointing uncertain tails. `pokerPersistenceHealth` reports `persistenceHealthy = false` and `mode = MEMORY_ONLY` whenever startup recovery failed or `unsavedRoomCount > 0`, while gameplay remains UP. Permanent room deletion uses a tombstone/WAL cleanup allowed even during write failures; compaction retains recoverable state. The save format is unversioned—delete `POKER_PERSISTENCE_DIRECTORY` after changing `persistence/snapshot` or frame layout.
