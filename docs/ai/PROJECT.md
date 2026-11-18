# Poker Backend AI Project Context

This is the canonical project context for agents. Root `AGENTS.md` and `GEMINI.md` point here. Read relevant [ADRs](../adr/) before changing architecture and use source/configuration for exact behavior.

## Project Overview

A Java 25 and Spring Boot 4.0.9 server for real-time multiplayer Texas Hold'em, paired with the React client in `../frontend-poker`. It owns room membership, poker rules, chip accounting, hand progression, deadlines, public/private projections, and player authorization.

Live rooms and games reside in memory, with encrypted state-image WAL persistence restoring their exact state after a single-server restart (disabled by default only for local development and non-persistence tests). There is no database, account-registration flow, or multi-node writer design.

[pom.xml](../../pom.xml) defines versions and dependencies: Spring Web/Security/WebSocket/Actuator/Validation/AspectJ, JJWT, Bucket4j, dotenv, and Spring-managed JUnit/AssertJ/Awaitility testing support. Avoid copying test-framework versions from older README tables; Maven dependency management is authoritative.

## Development And Configuration

Use Java 25 and the checked-in Maven wrapper. On Windows:

| Command | Purpose |
| --- | --- |
| `.\mvnw.cmd test` | Run Spring-free `@Tag("unit")` `*Test.java` unit tests (Surefire). |
| `.\mvnw.cmd verify` | Run all three test tiers: unit (`*Test.java`), integration (`*IT.java`), and E2E (`*E2E.java`). |
| `.\mvnw.cmd verify "-Dgroups=integration"` | Run `@Tag("integration")` suites via Failsafe. |
| `.\mvnw.cmd verify "-Dgroups=e2e"` | Run `@Tag("e2e")` suites via Failsafe. |
| `.\mvnw.cmd test "-Dtest=GameTest"` | Run one unit test class. |
| `.\mvnw.cmd verify "-Dit.test=LifecycleCoordinationIT"` | Run one integration or E2E test class. |
| `.\mvnw.cmd clean package` | Compile, run unit tests, and package the application. |
| `.\mvnw.cmd spring-boot:run` | Run with local environment configuration. |
| `.\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.profiles=test,frontend-e2e"` | Run on port 8080 for the frontend's Playwright run (no `.env` needed). |

On POSIX shells use `./mvnw`. Runtime port is configured by `SERVER_PORT`; 8080 is the companion frontend's expected development port, not a hardcoded application default.

[application.properties](../../src/main/resources/application.properties) maps required settings from environment variables. [.env.example](../../.env.example) is the local setup reference; dotenv supports a local ignored `.env`. Supply real keys through the environment and keep them out of Git and diagnostic output.

Configuration groups include:

- `SERVER_PORT`, `JWT_SECRET`, and `JWT_EXPIRATION_MILLIS`.
- `CORS_ALLOWED_ORIGINS` and `TRUST_PROXY` for browser origins and client-IP resolution.
- `POKER_DISCONNECT_GRACE_PERIOD_MS`, `POKER_ROUND_END_DISPLAY_DELAY_MS`, `POKER_READY_COUNTDOWN_MS`, and `POKER_GAME_END_DISPLAY_DELAY_MS`, plus optional overrides (defaults apply when unset) `POKER_GAME_TURN_ALLOWANCE_MS`, `POKER_GAME_AUTO_ADVANCE_STEP_DELAY_MS`, `POKER_GAME_FINAL_SHOWDOWN_DISPLAY_DELAY_MS`, `POKER_GAME_GAME_INACTIVITY_ALLOWANCE_MS`, `POKER_ROOM_LOBBY_INACTIVITY_MS`, and `POKER_PERSISTENCE_RETRY_INTERVAL_MS`.
- `POKER_RATE_LIMITING_ENABLED`.
- `POKER_PERSISTENCE_ENABLED`, directory, current key ID/key material, and compaction thresholds.

The `frontend-e2e` test profile in [src/test/resources/application-frontend-e2e.properties](../../src/test/resources/application-frontend-e2e.properties) is used together with `test` (`test,frontend-e2e`) by the frontend's Playwright run; it sets `server.port=8080` and the display/ready timings the browser specs depend on while inheriting the `test` baseline.

The `test` profile is defined in [src/test/resources/application-test.properties](../../src/test/resources/application-test.properties). It uses a random port, test keys, short lifecycle timings, and disabled rate limiting/persistence. Those test resources are not the production runtime configuration; persistence-specific tests enable their own recovery settings.

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
| `model/` and `enums/` | `Game`, `Room`, `Player`, `Deck`/`Card` state and [GameRestoreBuilder](../../src/main/java/com/pokergame/model/GameRestoreBuilder.java); package-private collaborators `BettingRound`, `TableSeating`, `PotCalculator`, `ShowdownResolver`, `GameScheduling`, and `ReadyGate`; [HandEvaluator](../../src/main/java/com/pokergame/model/HandEvaluator.java); poker phases/actions/statuses and scheduled-task identifiers. |
| `dto/request/`, `dto/response/`, `dto/internal/` | Validated input, client projections, and service-internal results. |
| `advice/` and `exception/` | HTTP/STOMP error mapping and domain exceptions. |
| `security/` and `config/` | JWT principal/filters, payload/rate limits, origins, STOMP authorization, session events, executor/scheduler setup. |
| `RoomAggregates` (`persistence/`), `RoomAggregateStore` (`service/`) | The only view persistence has of live rooms and games: `find`, `replace` (rollback, timers rescheduled) and `remove`. `persistence/` imports nothing from `service`, `config` or `controller`. |
| `persistence/transaction/`, `snapshot/`, `wal/`, `config/` | Durable AOP boundary (`DurableMutationAspect`, `PostSaveActions`), state-image mappings, encrypted WAL/keyring, and persistence settings/health. |
| [PersistenceRecovery](../../src/main/java/com/pokergame/persistence/PersistenceRecovery.java) | Startup restoration, disconnected-client assumptions, and deadline rescheduling. |
| `util/` | Input sanitization, principal/client-IP helpers, MDC context, and log throttling. |

Lifecycle work is scheduled explicitly through services and durable task metadata. The old application `event/` and `listener/` packages are absent. [WebSocketEventListener](../../src/main/java/com/pokergame/config/WebSocketEventListener.java) still consumes Spring session events for connect/disconnect tracking.

## Room, Game, And Concurrency Model

Rooms have UUID identities and user-visible names; joining resolves the room name. Create-room validation permits 2–10 players. Games reuse the room ID, making room/game paths and token binding refer to the same aggregate identity.

`RoomService` and `ActiveGameRegistry` maintain concurrent registries, but registry concurrency does not make the mutable aggregates thread-safe. Under [ADR-0011](../adr/0011-room-coordinator-single-boundary-concurrency.md), `RoomCoordinator` provides the single authoritative concurrency boundary per room. All operations affecting a room—REST endpoints, WebSocket STOMP actions, session events, scheduled lifecycle callbacks, and projections—acquire its lease via `RoomCoordinator.acquire(roomId)`. Legacy intrinsic monitors (`synchronized (room)` and `synchronized (game)`) and redundant per-player session leases have been eliminated. Internal mutation methods assert lease ownership via `RoomCoordinator.assertHeldByCurrentThread(roomId)`. Nested same-room operations reenter safely, while cross-room nested coordination throws `IllegalStateException`. In persistence-disabled mode, `@DurableMutation` advice acts as a pure `RoomCoordinator` lease pass-through. Route scheduled mutations through the Spring proxy so transactional and coordination advice executes.

`Game` owns the deck, players, betting state, phase, pot/side-pot bookkeeping, showdown outcomes, and durable scheduled-task metadata. Phases are `PRE_FLOP`, `FLOP`, `TURN`, `RIVER`, and `SHOWDOWN`. Preserve legal-action checks, heads-up blind rules, all-in eligibility, uncalled refunds, split payouts, and total chip conservation.

Package-private model collaborators calculate betting rules (`BettingRound`), seating (`TableSeating`), pots (`PotCalculator`), showdown (`ShowdownResolver`), scheduling (`GameScheduling`), and readiness (`ReadyGate`). The public `GameRestoreBuilder` reconstructs a game from a state image. `Game` remains the aggregate root and owns its mutable state.

`Game.restoreBuilder()` returns the top-level `GameRestoreBuilder`; code compiled against the former nested `Game.GameRestoreBuilder` must be updated and recompiled.

`GameTimerRegistry` owns in-memory `TaskScheduler` registrations for game tasks, active-game inactivity, and lobby inactivity; `LobbyInactivityScheduler` supplies the lobby callback. `Game` stores scheduled task deadlines and timer version counters along with `lastSettlementResult` and `gameEndResult`; `Room` owns mutable `hostName` and game inactivity deadline checks (`extendGameInactivityDeadline`, `isInactivityExpired`, `hasInactivityDeadline`). `GameLifecycleService` handles startup game recovery and timer resumption directly. `GameTaskScheduler` records timer deadlines before saving, queues runtime registrations via `PostSaveActions.runAfterSave(...)`, and verifies hand/timer version claims when timers fire. Scheduled callbacks re-enter `GameLifecycleService` through the Spring proxy (`ObjectProvider<GameLifecycleService>`).

`GameReadinessCoordinator` owns readiness activity, gate transitions, and countdown effects. `HandProgressionService` deals hands, advances streets, and signals when the ready gate should open immediately. `PlayerDepartureHandler` owns departures, host transfer, turn timeout, reconnect, grace expiry, and disconnect win claims. `GameCompletionHandler` owns game settlement, inactivity abandonment, post-game review lookups, and game/room deletion.

WebSocket identity can have multiple active browser sessions. Session tracking only treats the player as disconnected after the last active session is gone, applies a grace deadline, and cancels cleanup on reconnect. A final game-end event and later aggregate cleanup are separate from an individual hand's showdown/ready transition.

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

Use [WebSocketConfig](../../src/main/java/com/pokergame/config/WebSocketConfig.java) and the controllers as the exact mapping references. The simple broker serves room/game/queue prefixes; Spring resolves private user destinations.

Public game DTOs contain chips, players, board cards, turn/phase/pot information, and optional showdown, readiness, disconnect, or claim-win metadata. Private state carries player ID and hole cards. Preserve the backend/frontend distinction between `ApiResponse` command/room-event wrappers and direct game-state snapshots; optional/null payload fields must remain compatible with frontend decoders.

### Response Envelope Convention

Responses follow a deliberate split; keep new endpoints consistent with it rather than wrapping everything uniformly:

- **Commands (mutations) return `ApiResponse<T>`** with an outcome `message` and a `data` payload. This covers `POST /api/room/create`, `/api/room/join`, `/api/room/{roomId}/leave`, `/api/room/{roomId}/start-game`, and `/api/game/{gameId}/claim-win`, plus the room-event broadcasts published to `/room/{roomId}`.
- **Queries return the resource DTO directly** (no envelope). This covers `GET /api/room/{roomId}` (`RoomDataResponse`), `GET /api/game/{gameId}/state`, `/private-state`, and `/review`. The `message` field carries no meaning for a read, so a GET exposes the resource at the top level.
- **Errors use a consistent error body** through [the controller advice](../../src/main/java/com/pokergame/advice/), including `application/problem+json`, independent of the success-envelope shape.

This matches the client in `../frontend-poker/src/services/api.ts`, which unwraps `message`/`data` only on command responses and decodes query responses as bare DTOs (notably `getReview`, which decodes the review object at the top level). Do not retrofit an envelope onto a query response without a coordinated frontend decoder change — the two repos must move together.

## Security And Boundaries

HTTP is stateless JWT authentication. Room create/join, the WebSocket handshake, and health/info endpoints are public; remaining HTTP paths require authentication under [SecurityConfig](../../src/main/java/com/pokergame/config/SecurityConfig.java).

JWTs contain player-name subject, room claim, and a unique session `membershipId` claim ([ADR-0004](../adr/0004-stateless-jwt-session-membership-validation.md)). [PlayerPrincipal](../../src/main/java/com/pokergame/security/PlayerPrincipal.java) exposes the membership-bound `playerName:roomId:membershipId` identity for user-destination routing. Runtime connection tracking and rate-limit buckets use a separate `playerName:roomId` key. Derive actions from the authenticated principal, verify room/game and active membership binding, and apply membership/host checks where required.

`WebSocketAuthInterceptor` authenticates CONNECT, checks room/game subscription binding, and enforces `/app/` destination prefix whitelisting, room-targeted destination authorization, and active `membershipId` validation on STOMP frames. Controller/service checks protect actions. Private messages use user destinations rather than predictable private room topics.

Preserve payload-size rejection, endpoint and STOMP throttling, validation, name sanitization, configured CORS, and trusted-proxy handling. Exact bounds live in DTOs/filters/services; disabling these controls in test profiles does not establish production protection.

## Durable Recovery

[ADR-0001](../adr/0001-encrypted-state-image-wal-with-spring-aop.md) defines encrypted state-image WAL persistence. [Persistence operations](../persistence-operations.md) covers keys, backup/rotation, health, retention, and restore drills.

When enabled, `@DurableMutation` advice attempts PREPARE, runs the authoritative mutation, captures an immutable state image, and attempts authenticated COMMIT. When writes succeed, queued client messages and post-commit work are released after commit. Runtime storage failures keep accepted gameplay and effects in memory and retry saving without replaying the command. Nested durable calls join the same room transaction; cross-room nesting is rejected.

**Check-first rule:** Validate everything before changing any state, so a throw never leaves a half-done change. With persistence off, nothing is rolled back.

AES-256-GCM protects state images containing passwords, private cards, deck order, chips, and deadlines. Associated data binds the record and aggregate identity. Keep keys separate from WAL files.

Recovery restores all aggregates, recreates process-local collaborators, treats old sockets as disconnected, grants reconnect grace, and resumes overdue/future work with guards. It restores exact state without replaying randomness or player commands.

A torn tail can be recovered. Corrupted per-room WAL data, unparseable per-room snapshots, and per-room restoration failures are quarantined so other rooms can recover; failed WAL deletions during recovery log a warning and skip without quarantining the file or aborting startup. Missing decryption keys, an unhealthy store, WAL directory-enumeration failures, and failures in required store operations remain fatal; a missing key is preserved without quarantining the encrypted data. Runtime prepare, serialization, commit, or maintenance failures are isolated per room: only the affected room pauses ordinary appends until a checkpoint or deletion clears its failure, while unaffected rooms continue appending, compacting, and deleting normally. Accepted gameplay continues in memory and releases its effects once, retrying the latest accepted image or deletion tombstone every five seconds and on subsequent accepted mutations using the room lock and checkpointing uncertain tails. Overall `pokerPersistenceHealth` reports `persistenceHealthy = false` and `mode = MEMORY_ONLY` whenever startup recovery failed or any room has an outstanding write failure (`unsavedRoomCount > 0`) while gameplay remains UP; stopping before a retry saves the latest state can lose those unsaved changes. Permanent aggregate deletion uses a tombstone/WAL cleanup (allowed even while a room or store has runtime write failures); compaction retains current recoverable state. The save format isn't versioned. After any change to `persistence/snapshot` or the frame layout, delete `POKER_PERSISTENCE_DIRECTORY`.

One room has one writer on one server. A shared WAL volume must not be written by multiple instances. Actuator persistence health reports recovery completion, overall persistence health (`persistenceHealthy`), persistence mode, unsaved-room count, active WAL count, and sanitized storage failure status when enabled. Runtime save failures leave gameplay health UP; operators should alert on `MEMORY_ONLY` and increasing unsaved-room count.

## Development Conventions And Tests

Use Java naming conventions, focused services, explicit DTO contracts, centralized exceptions, and `@Valid` for validated REST request bodies. Create/update Javadoc on classes and `public`/`protected`/package-private APIs being changed (skipping trivial getters/setters), and default `private` methods to no Javadoc; comments explain decisions, assumptions, locking/ordering invariants, and workarounds rather than narrate code.

For bug fixes and game-rule changes, write a failing behavioral regression test first, confirm it fails for the intended reason, implement the fix, and confirm it passes. For structural extractions and refactors, establish passing behavioral or characterization coverage before moving code; add characterization tests where coverage is missing rather than inventing artificial failures. Assert public game outcomes, chip balances, client snapshots, and published messages rather than private fields or internal delegation. Never weaken tests or acceptance criteria. Clean up threads, sockets, and Spring contexts in integration tests; use Awaitility/state conditions instead of brittle timing sleeps.

When running Maven checks, redirect full output to a local log file under `.scratch/` and report the command, pass/fail status, test counts, and log path. On failure, inspect only the relevant error and stack trace. Run the narrowest check covering changed behavior during iteration and the full suite once after integrated code changes. For documentation-only changes, use relevant documentation checks such as validating local links and inspecting the diff. Stage files explicitly by path (`git add <file>`), preserve unrelated working-tree changes, and push or create remote PRs/issues only when explicitly requested.

Tests under `src/test/java/com/pokergame/` follow a 3-tier pyramid: `@Tag("unit")` `*Test.java` unit tests mirroring production packages with zero Spring context; `@Tag("integration")` `*IT.java` suites; and `@Tag("e2e")` `*E2E.java` suites under `com.pokergame.e2e`. Find relevant tests from those packages rather than rely on a cached test count.

- **Integration (`*IT`):** checks one boundary or behaviour (security, rate limits, persistence, timing, a controller's web layer) with real Spring wiring. It may assert on internal beans and may start a real port.
- **E2E (`*E2E`):** a multi-step player journey through the public REST and STOMP interfaces only. Internal beans are allowed for setup, cleanup and waiting on subscriptions, never for assertions.

[The CI workflow](../../.github/workflows/deploy.yml) runs `./mvnw clean verify` with Java 25 on pushes and pull requests targeting `main`. Successful pushes to `main` then build/push Docker images. The Dockerfile packages with tests skipped after that separate test job; a Docker build alone is not test evidence.

## Service Layout, Naming, And Null-Handling Conventions

Follow these conventions across `src/main/java/com/pokergame/service/`:

1. **Class member order:** Place `logger`, then `static final` constants, then `private final` Spring-injected fields grouped with no blank lines between them, then one blank line before internal state collections/locks, then the single constructor, then `public` methods, package-private methods, `private` helpers, and finally nested records, enums, or helper classes at the bottom.
2. **Constructors and dependency injection:** Use a single constructor per `@Service` or package-private `@Component` bean (no test-only overload constructors, no `@Autowired(required = false)` setters, no `Objects.requireNonNull` or null checks on Spring-injected beans, and no `@Lazy self` / `mutationProxy()` fields). When a scheduled callback needs a transactional proxy, use `ObjectProvider<...>`; when a same-bean call must enter `@DurableMutation`, enable `exposeProxy = true` and read `AopContext.currentProxy()` with a direct fallback.
3. **Null-handling:** Import `static java.util.Objects.requireNonNull;` and use `requireNonNull(arg, "arg must not be null");` as a standalone statement for arguments validated without logging. Keep `if (obj == null)` checks when logging or throwing domain exceptions such as `ResourceNotFoundException` or `BadRequestException`.
4. **Concurrency and canonical state:** Follow the two-part room-lock rule:
   1. Only entry points (controllers, `WebSocketEventListener`, `WebSocketAuthInterceptor`, `PersistenceRecovery`, and `DurableMutationAspect.retryPendingSaves`) and `DurableMutationAspect.persist` take the room lock.
   2. Nothing else takes it: service methods and aspect helpers assert `roomCoordinator.assertHeldByCurrentThread(id)` instead (non-durable methods; `@DurableMutation` entry methods rely on the aspect). `ActiveGameRegistry` and non-durable `RoomService`/`GameLifecycleService` accessors enforce this so internal lifecycle collaborators rely on them instead of repeating lock assertions or `...UnderLock` method suffixes. Store mutable room/game state directly on `Room` (`hostName`, `inactivityDeadlineEpochMs`) and `Game` (`lastSettlementResult`, `gameEndResult`) rather than shadow maps in services.
5. **Naming and Javadocs:** Prefer plain-English verb phrases (`removePlayers`, `selectNextHost`, `deleteGameAndRoom`, `buildLiveGameState`, `PostSaveActions`) over internal jargon (*durable mutation*, *terminal result/review/room*, *cleanup intent*, *rehydrated*). Document classes and non-`private` methods (`public`, `protected`, and package-private collaborator entry points) in plain English focusing on contract, side effects, and exceptions—with `@param`, `@return`, and `@throws` tags where relevant, skipping trivial getters/setters. Default `private` methods to no Javadoc and let descriptive naming carry the intent; add a short comment (or lightweight tagless `/** ... */`) only when explaining a non-obvious *why*, complex domain rule or edge case, locking/concurrency assumption, or ordering dependency.

## Logging And Operations

Follow [LOGGING.md](LOGGING.md) for SLF4J/Logback levels, MDC correlation, redaction rules, retention, and diagnostic recipes. `MdcTaskDecorator` propagates room/player context to configured async/scheduled work.

Use parameterized logs. Keep JWTs, passwords, encryption keys, and live private cards out of logs. Log lifecycle transitions at useful levels and keep per-action/evaluation detail bounded.

[Dockerfile](../../Dockerfile) uses a Java 25 build/runtime, exposes port 8080, checks `/actuator/health`, and declares WAL/log storage paths. Supply required runtime settings and persistent storage separately; the repo does not contain the live host's full Compose deployment.

## Domain Vocabulary

| Term | Meaning |
| --- | --- |
| Room | Named membership/lobby container identified by UUID, with a tracked host and settings. |
| Game | Active poker aggregate for a room; game ID equals room ID. |
| Hand / round | One deal and betting sequence ending in showdown, distinct from game completion. |
| Player | Room/game participant whose authenticated identity is player name plus room ID. |
| Public snapshot | Projection safe for the table's clients; excludes live private hole cards/deck order. |
| Private state | Authenticated player's ID and hole cards, delivered through REST authorization or user destinations. |
| Ready countdown | Shared gate between completed hand and the next hand. |
| Disconnect deadline | Absolute grace-period deadline after the player's last socket disconnects. |
| Durable mutation | A room-scoped authoritative change intercepted for state-image commit when persistence is enabled. |
| State image | Aggregate data sufficient to restore room, game, and durable deadlines. |
| WAL | Encrypted per-room recovery records retaining live state, rather than a historical hand archive. |

## Agent Roles And Model Selection

Choose the main chat model for the decisions the task requires. For architecture, ambiguous requirements, or difficult debugging, prefer GPT-6.1 Sol with high reasoning in Codex or the Pro tier in Antigravity. Luna with medium reasoning or Flash is suitable for straightforward work with clear acceptance criteria. These are task-specific recommendations, not a requirement to start every chat at a higher tier.

Custom subagents have their own model settings; choosing a stronger main model does not change the explicit model or reasoning setting in those role definitions. Treat the linked role files as the source of truth for models, reasoning effort, tools, and operating rules.

| Role | Codex definition | Antigravity definition | Delegate when |
| --- | --- | --- | --- |
| Explorer | [explorer](../../.codex/agents/explorer.toml) | [explorer](../../.agents/agents/explorer.md) | A specific file lookup, call-path trace, or coverage question would save the main agent substantial exploration. |
| Scoped implementer | [scoped_implementer](../../.codex/agents/scoped_implementer.toml) | [scoped-implementer](../../.agents/agents/scoped-implementer.md) | The plan is settled and file ownership, required behavior, and completion criteria are explicit. |
| Verifier | [verifier](../../.codex/agents/verifier.toml) | [verifier](../../.agents/agents/verifier.md) | Assigned checks need execution and a compact report of observed results. |
| Fast reviewer | [fast_reviewer](../../.codex/agents/fast_reviewer.toml) | [fast-reviewer](../../.agents/agents/fast-reviewer.md) | DTOs, REST/STOMP mappings, request validation, error contracts, Javadoc, and routine configuration changes. |
| Deep reviewer | [deep_reviewer](../../.codex/agents/deep_reviewer.toml) | [deep-reviewer](../../.agents/agents/deep-reviewer.md) | poker rules, chip conservation, side pots and refunds, RoomCoordinator single-boundary concurrency, async scheduling, authentication/authorization, and encrypted WAL recovery. |

Keep decisions about the deep-review areas with the main agent or a stronger model. A scoped implementer can carry out a settled plan in those areas; a deep review is still required even for a small diff.

### Delegation And Usage

- Handle tiny edits directly. Delegate bounded work when its benefit exceeds the extra agent context and coordination.
- Supply the question or acceptance criteria, relevant evidence, explicit file ownership, allowed checks, and the expected handoff. Reuse existing findings instead of repeating exploration.
- Choose the reviewer whose scope matches the change. Use both reviewers when the change spans both scopes.
- Preserve concurrent edits and give workers distinct file ownership. The main agent integrates results and resolves cross-module decisions.
- Run the smallest relevant checks first. Expand or repeat checks only for an assigned requirement, an intervening change, or an unresolved failure.
- After an unsuccessful fix-and-check attempt, return evidence to the main agent for diagnosis rather than continuing speculative repairs. Report blockers and unfinished checks explicitly.
- Keep handoffs concise: file:line evidence, changed files, observed check results, and unresolved decisions.

Explorer and reviewer work is read-only by instruction. Implementers edit assigned files; verifiers write only normal generated build/test outputs. Parent-session permissions remain authoritative: Codex custom-agent sandbox settings do not override the parent sandbox, and Antigravity subagents inherit the parent's safety boundaries.

Model and reasoning guidance: [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents). Antigravity discovery, model tiers, and permission inheritance: [custom subagents](https://www.antigravity.google/docs/subagents/).
