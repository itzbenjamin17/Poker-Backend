# Poker Backend AI Project Context

This is the canonical project context for agents. Root `AGENTS.md` and `GEMINI.md` point here. Read relevant [ADRs](../adr/) before changing architecture and use source/configuration for exact behavior.

## Project Overview

A Java 25 and Spring Boot 4.0.6 server for real-time multiplayer Texas Hold'em, paired with the React client in `../frontend-poker`. It owns room membership, poker rules, chip accounting, hand progression, deadlines, public/private projections, and player authorization.

Live rooms and games reside in memory. Optional encrypted state-image persistence restores their exact state after a single-server restart. There is no database, account-registration flow, or multi-node writer design.

[pom.xml](../../pom.xml) defines versions and dependencies: Spring Web/Security/WebSocket/Actuator/Validation/AspectJ, JJWT, Bucket4j, dotenv, and Spring-managed JUnit/AssertJ/Awaitility testing support. Avoid copying test-framework versions from older README tables; Maven dependency management is authoritative.

## Development And Configuration

Use Java 25 and the checked-in Maven wrapper. On Windows:

| Command | Purpose |
| --- | --- |
| `.\mvnw.cmd test` | Run unit and integration tests. |
| `.\mvnw.cmd test "-Dtest=GameLifecycleIntegrationTest"` | Run one test class. |
| `.\mvnw.cmd clean package` | Test and package the application. |
| `.\mvnw.cmd spring-boot:run` | Run with local environment configuration. |
| `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=e2e"` | Run with browser-test feature/timing overrides. |

On POSIX shells use `./mvnw`. Runtime port is configured by `SERVER_PORT`; 8080 is the companion frontend's expected development port, not a hardcoded application default.

[application.properties](../../src/main/resources/application.properties) maps required settings from environment variables. [.env.example](../../.env.example) is the local setup reference; dotenv supports a local ignored `.env`. Supply real keys through the environment and keep them out of Git and diagnostic output.

Configuration groups include:

- `SERVER_PORT`, `JWT_SECRET`, and `JWT_EXPIRATION_MILLIS`.
- `CORS_ALLOWED_ORIGINS` and `TRUST_PROXY` for browser origins and client-IP resolution.
- `POKER_DISCONNECT_GRACE_PERIOD_MS`, `POKER_ROUND_END_DISPLAY_DELAY_MS`, `POKER_READY_COUNTDOWN_MS`, and `POKER_GAME_END_DISPLAY_DELAY_MS`.
- `POKER_RATE_LIMITING_ENABLED`.
- `POKER_PERSISTENCE_ENABLED`, directory, current key ID/key material, and compaction thresholds.

The `e2e` profile in [application-e2e.properties](../../src/main/resources/application-e2e.properties) disables rate limiting and persistence and shortens ready/game-end timing. It still relies on base runtime configuration for other settings.

The `test` profile is defined in [src/test/resources/application-test.properties](../../src/test/resources/application-test.properties). It uses a random port, test keys, short lifecycle timings, and disabled rate limiting/persistence. Those test resources are not the production runtime configuration; persistence-specific tests enable their own recovery settings.

## Code Map

All production packages are under `src/main/java/com/pokergame/`.

| Package / entry point | Responsibility |
| --- | --- |
| [RoomController](../../src/main/java/com/pokergame/controller/RoomController.java) | Create/join, room snapshot, leave, and host-authorized start. |
| [GameController](../../src/main/java/com/pokergame/controller/GameController.java) | Public/private snapshots, STOMP action/ready/unready, REST leave/claim-win. |
| [RoomService](../../src/main/java/com/pokergame/service/RoomService.java) | Room registry, name lookup, membership, host tracking, lobby destruction, and room broadcasts. |
| [GameLifecycleService](../../src/main/java/com/pokergame/service/GameLifecycleService.java) | Active game registry, hand starts, auto-advance, ready gates, scheduled work, disconnect transitions, completion, and cleanup. |
| [PlayerActionService](../../src/main/java/com/pokergame/service/PlayerActionService.java) | Action authorization, betting mutations, turn/round progression, and lifecycle scheduling. |
| [GameStateService](../../src/main/java/com/pokergame/service/GameStateService.java) | Public/private projections and broadcasts deferred until durable commit. |
| [HandEvaluatorService](../../src/main/java/com/pokergame/service/HandEvaluatorService.java) | Best five-card hand evaluation from available cards. |
| `model/` and `enums/` | Game, room, player, deck/card state; poker phases/actions/statuses; scheduled-task identifiers. |
| `dto/request/`, `dto/response/`, `dto/internal/` | Validated input, client projections, and service-internal results. |
| `advice/` and `exception/` | HTTP/STOMP error mapping and domain exceptions. |
| `security/` and `config/` | JWT principal/filters, payload/rate limits, origins, STOMP authorization, session events, executor/scheduler setup. |
| `persistence/transaction/`, `snapshot/`, `wal/`, `config/` | Durable AOP boundary, state-image mappings, encrypted WAL/keyring, and persistence settings/health. |
| [PersistenceRecovery](../../src/main/java/com/pokergame/persistence/PersistenceRecovery.java) | Startup restoration, disconnected-client assumptions, and deadline rescheduling. |
| `util/` | Input sanitization, principal/client-IP helpers, MDC context, and log throttling. |

Lifecycle work is scheduled explicitly through services and durable task metadata. The old application `event/` and `listener/` packages are absent. [WebSocketEventListener](../../src/main/java/com/pokergame/config/WebSocketEventListener.java) still consumes Spring session events for connect/disconnect tracking.

## Room, Game, And Concurrency Model

Rooms have UUID identities and user-visible names; joining resolves the room name. Create-room validation permits 2–10 players. Games reuse the room ID, making room/game paths and token binding refer to the same aggregate identity.

`RoomService` and `GameLifecycleService` maintain concurrent registries, but registry concurrency does not make the mutable aggregates thread-safe. Room/game mutations use their existing synchronization boundaries, and enabled durable mutations add per-room serialization. Preserve lock ordering and route scheduled mutations through the Spring proxy so AOP advice executes.

`Game` owns the deck, players, betting state, phase, pot/side-pot bookkeeping, showdown outcomes, and durable scheduled-task metadata. Phases are `PRE_FLOP`, `FLOP`, `TURN`, `RIVER`, and `SHOWDOWN`. Preserve legal-action checks, heads-up blind rules, all-in eligibility, uncalled refunds, split payouts, and total chip conservation.

The lifecycle service records delayed work with absolute deadlines and task identifiers such as auto-advance, ready-open, new-hand, cleanup, and game-end. Runtime callbacks check their expected state/deadline so obsolete callbacks do not advance a newer hand.

WebSocket identity can have multiple active browser sessions. Session tracking only treats the player as disconnected after the last active session is gone, applies a grace deadline, and cancels cleanup on reconnect. A final game-end event and later aggregate cleanup are separate from an individual hand's showdown/ready transition.

## API And Real-Time Contracts

| Transport | Endpoints / destinations |
| --- | --- |
| Public room commands | `POST /api/room/create`, `POST /api/room/join`; return `ApiResponse<TokenResponse>`. |
| Room snapshot | `GET /api/room/{roomId}`; returns `RoomDataResponse`. |
| Secured room commands | `POST /api/room/{roomId}/start-game`, `POST /api/room/{roomId}/leave`. |
| Secured game snapshots | `GET /api/game/{gameId}/state`, `GET /api/game/{gameId}/private-state`; return direct public/private DTOs. |
| Secured game commands | `POST /api/game/{gameId}/leave`, `POST /api/game/{gameId}/claim-win`. |
| STOMP handshake | `/ws`; bearer token supplied in CONNECT headers. |
| Client SEND | `/app/{gameId}/action`, `/app/{gameId}/ready`, `/app/{gameId}/unready`. |
| Client SUBSCRIBE | `/room/{roomId}`, `/game/{gameId}`, `/user/queue/private`. |

Use [WebSocketConfig](../../src/main/java/com/pokergame/config/WebSocketConfig.java) and the controllers as the exact mapping references. The simple broker serves room/game/queue prefixes; Spring resolves private user destinations.

Public game DTOs contain chips, players, board cards, turn/phase/pot information, and optional showdown, readiness, disconnect, or claim-win metadata. Private state carries player ID and hole cards. Preserve the backend/frontend distinction between `ApiResponse` command/room-event wrappers and direct game-state snapshots; optional/null payload fields must remain compatible with frontend decoders.

## Security And Boundaries

HTTP is stateless JWT authentication. Room create/join, the WebSocket handshake, and health/info endpoints are public; remaining HTTP paths require authentication under [SecurityConfig](../../src/main/java/com/pokergame/config/SecurityConfig.java).

JWTs contain player-name subject and room claim. [PlayerPrincipal](../../src/main/java/com/pokergame/security/PlayerPrincipal.java) exposes the composite `playerName:roomId` identity for user-destination routing. Derive actions from the authenticated principal, verify room/game binding, and apply membership/host checks where required.

`WebSocketAuthInterceptor` authenticates CONNECT and checks room/game subscription binding. Controller/service checks protect actions. Private messages use user destinations rather than predictable private room topics.

Preserve payload-size rejection, endpoint and STOMP throttling, validation, name sanitization, configured CORS, and trusted-proxy handling. Exact bounds live in DTOs/filters/services; disabling these controls in test profiles does not establish production protection.

## Durable Recovery

[ADR-0001](../adr/0001-encrypted-state-image-wal-with-spring-aop.md) defines encrypted state-image WAL persistence. [Persistence operations](../persistence-operations.md) covers keys, backup/rotation, health, retention, and restore drills.

When enabled, `@DurableMutation` advice serializes a room, flushes PREPARE, runs the authoritative mutation, captures an immutable versioned state image, and flushes authenticated COMMIT before releasing queued client messages and post-commit work. Nested durable calls join the same room transaction; cross-room nesting is rejected.

AES-256-GCM protects state images containing passwords, private cards, deck order, chips, and deadlines. Associated data binds the record and aggregate identity. Keep keys separate from WAL files.

Recovery restores all aggregates, recreates process-local collaborators, treats old sockets as disconnected, grants reconnect grace, and resumes overdue/future work with guards. It restores exact state without replaying randomness or player commands.

A torn tail can be recovered; committed-prefix corruption, missing/wrong keys, and unsupported schemas fail closed. A failed commit can leave memory ahead of disk, so later durable mutations are rejected and the process needs restart/recovery. Permanent aggregate deletion uses a tombstone/WAL cleanup; compaction retains current recoverable state.

One room has one writer on one server. A shared WAL volume must not be written by multiple instances. Actuator persistence health reports recovery completion and sanitized storage failure status when enabled.

## Development Conventions And Tests

Use Java naming conventions, focused services, explicit DTO contracts, centralized exceptions, and `@Valid` for validated REST request bodies. Create/update Javadoc on classes and functions being changed; comments explain decisions, assumptions, and workarounds rather than narrate code.

For bug fixes and game-rule changes, write a failing behavioral regression test first. Assert public game outcomes, chip balances, client snapshots, and published messages rather than private fields or internal delegation. Clean up threads, sockets, and Spring contexts in integration tests; use Awaitility/state conditions instead of brittle timing sleeps.

Tests under `src/test/java/com/pokergame/` cover models, actions, lifecycle, controllers/advice, filters/interceptors, identity isolation, input hardening, reconnect behavior, STOMP actions/throttling, and durable mapping/WAL/restart recovery. Find relevant tests from those packages rather than rely on a cached test count.

[The CI workflow](../../.github/workflows/deploy.yml) runs `./mvnw clean test` with Java 25 on pushes and pull requests targeting `main`. Successful pushes to `main` then build/push Docker images. The Dockerfile packages with tests skipped after that separate test job; a Docker build alone is not test evidence.

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
| State image | Versioned aggregate data sufficient to restore room, game, and durable deadlines. |
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
| Deep reviewer | [deep_reviewer](../../.codex/agents/deep_reviewer.toml) | [deep-reviewer](../../.agents/agents/deep-reviewer.md) | poker rules, chip conservation, side pots and refunds, shared room/game state, lock ordering, async scheduling, authentication/authorization, and encrypted WAL recovery. |

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
