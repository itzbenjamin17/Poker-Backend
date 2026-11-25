# ♠️ Poker Backend - Real-time Game Engine

A **Java 25 + Spring Boot 4.0** backend for multiplayer Texas Hold'em poker, featuring real-time game state management, STOMP/WebSocket communication, stateless JWT session-membership authentication, encrypted WAL restart recovery, reconnect handling, and a comprehensive rules engine.

**Purpose:** Showcase project demonstrating enterprise backend patterns, real-time systems, and clean architecture with a focus on security, concurrency correctness, and operational resilience.

Companion frontend showcase: ([Repo](https://github.com/itzbenjamin17/Poker-Frontend))

## 📋 Table of Contents

- [Quick Start](#-quick-start)
- [Tech Stack](#-tech-stack)
- [Architecture](#-architecture)
- [Key Features](#-key-features)
- [Security & Hardening](#-security--hardening)
- [Configuration & Environment Variables](#-configuration--environment-variables)
- [API Reference](#-api-reference)
- [Build & Verification](#-build--verification)
- [Docker & CI/CD](#-docker--cicd)
- [Documentation](#-documentation)
- [Future Roadmap](#-future-roadmap)

## 🚀 Quick Start

### Prerequisites

- **JDK 25** (Eclipse Temurin 25 recommended; the Maven Wrapper `./mvnw` / `.\mvnw.cmd` is included in the repository)

### Local Run

```bash
# Clone the repository
git clone https://github.com/itzbenjamin17/Poker-Backend.git
cd Poker-Backend/

# Copy the example environment configuration (required at startup)
cp .env.example .env

# Run directly in development mode (using Maven Wrapper)
.\mvnw.cmd spring-boot:run

# Or package and run the JAR
.\mvnw.cmd clean package
java -jar target/Poker-0.0.1-SNAPSHOT.jar

# Server starts on SERVER_PORT (default in .env.example: http://localhost:8080)
```

> **Note:** On macOS/Linux, use `./mvnw` instead of `.\mvnw.cmd`. To run the backend for the frontend's Playwright run on port 8080, use `.\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.profiles=test,frontend-e2e"`.

**Companion frontend URL:** http://localhost:5173

## 🛠️ Tech Stack

| Technology | Version | Purpose |
| --- | --- | --- |
| **Java** | 25 | Language runtime and modern language features |
| **Spring Boot** | 4.0.9 | Web, Security, WebSocket/STOMP, Validation, Actuator, AspectJ |
| **JJWT** | 0.13.0 | Stateless JWT creation and verification |
| **Bucket4j** | 8.10.1 | REST and STOMP rate limiting |
| **spring-dotenv** | 5.1.0 | Local `.env` configuration loading |
| **JUnit Jupiter / AssertJ / Awaitility** | Spring-managed | Unit, concurrency, and asynchronous integration testing |

## 🏗️ Architecture

The engine follows a **Layered Architecture** with explicit concurrency and durability boundaries:

- **Controller Layer (`controller/`, `advice/`):** REST and STOMP endpoints for lobby and game interaction, acquiring per-room lock leases before membership validation, paired with centralized RFC 9457 `ProblemDetail` (`application/problem+json`) REST exception advice (`ControllerExceptionHandler`) and STOMP error handling (`WebSocketExceptionHandler`).
- **Service Layer (`service/`):** Orchestrates room registries (`RoomService`), active game lifecycles and package-private lifecycle collaborators (`GameLifecycleService`, `GameReadinessCoordinator`, `HandProgressionService`, `PlayerDepartureHandler`, `GameCompletionHandler`, `GameTaskScheduler`, `GameTimerRegistry`, `LobbyInactivityScheduler`), betting validation (`PlayerActionService`), and post-save projections (`GameStateService`).
- **Model Layer (`model/`):** Pure domain aggregate (`Game`, `Room`, `Player`, `Deck`) supported by `HandEvaluator` (7-card hand evaluation), package-private domain collaborators (`BettingRound`, `TableSeating`, `PotCalculator`, `ShowdownResolver`, `GameScheduling`, `ReadyGate`), and `GameRestoreBuilder`.
- **Persistence & Concurrency (`persistence/`, `util/`):** `RoomCoordinator` single-boundary per-room concurrency lock, `@DurableMutation` Spring AOP aspect with in-memory aggregate rollback on mutation failure, strictly parsed unversioned immutable state-image snapshots (`RoomSnapshot`), per-room AES-256-GCM write-ahead logs (WAL), startup `PersistenceRecovery`, and an HTTP `503` recovery traffic gate (`RecoveryTrafficGate`) that blocks non-health requests until restoration completes.
- **Security Layer (`security/`, `config/`):** Stateless JWT filters, STOMP CONNECT and SEND authorization interceptors, rate limiters, and payload size guards.

## ✨ Key Features

- **Real-time Texas Hold'em:** Full game loop (`PRE_FLOP`, `FLOP`, `TURN`, `RIVER`, `SHOWDOWN`) with automated phase transitions and all-in runouts.
- **Advanced Pot Management:** Support for main and side pots, split pots, odd-chip distribution, and uncalled bet refunds while preserving strict chip conservation.
- **Heads-up & Multiplayer (2–10 Players):** Dynamic dealer button, heads-up blind rules, and clockwise seat progression for 2 to 10 players.
- **Resilient Connections & Win Claiming:** Configurable reconnect grace window (2 minutes by default) allowing dropped players to resume their seats, plus `claim-win` support when all opponents remain disconnected.
- **Ready Gate & Post-Game Review:** Shared inter-hand countdown with player `ready`/`unready` controls, plus a frozen terminal game review (`GET /api/game/{gameId}/review`) retained after game completion.
- **Encrypted Restart Recovery:** Per-room AES-256-GCM WALs preserve lobbies, active hands, deck order, hole cards, chips, and scheduled deadlines across a single-server restart without replaying randomness (disabled by default in `.env.example` for local development). Runtime storage failures degrade gracefully to `MEMORY_ONLY` mode while keeping active gameplay online and retrying saves in the background. See [Persistence Operations](docs/persistence-operations.md).
- **Production Observability & Correlation:** SLF4J + Logback with MDC correlation (`roomId`, `playerName`, `sessionId`), async task context propagation, rolling file persistence (14-day retention, 500MB cap), and custom Actuator health indicators. See [Backend Logging Guide](docs/ai/LOGGING.md).

## 🛡️ Security & Hardening

- **Session-Bound JWT Identity:** Tokens bind `playerName` (subject), `roomId`, and a unique session `membershipId` ([ADR-0004](docs/adr/0004-stateless-jwt-session-membership-validation.md)), preventing cross-room impersonation and immediately invalidating tokens from vacated or rejoined seats.
- **STOMP Destination Authorization:** `WebSocketAuthInterceptor` enforces `/app/` destination prefix whitelisting, room-targeted SEND/SUBSCRIBE authorization, and active membership checks on every frame.
- **Private User Queues:** Hole cards and player-specific errors are routed exclusively via Spring user destinations (`/user/queue/private` bound to `playerName:roomId:membershipId`), preventing topic eavesdropping.
- **Rate Limiting:** Bucket4j limits room create/join to 20 requests per minute per client IP and endpoint, wrong join passwords to 10 per 5 minutes per client IP and room, and STOMP gameplay messages to 5 per second per player. REST 429 responses state the real wait and send `Retry-After`.
- **Lifecycle & Concurrency Enforcement:** Per-room lock leases serialize room/game mutations across REST requests, STOMP frames, and scheduled tasks, preventing late joins, duplicate starts, or stale timer callbacks.
- **Input & Payload Hardening:** Request bodies are validated and sanitized against control/format characters and HTML tags; oversized payloads (10KB limit), malformed JSON, and BCrypt-truncating passwords (>72 UTF-8 bytes) are rejected early.

## ⚙️ Configuration & Environment Variables

All required runtime settings in [`application.properties`](src/main/resources/application.properties) map from environment variables (or a local `.env` file copied from [`.env.example`](.env.example)) for fail-fast startup validation:

| Variable | Example / Default in `.env.example` | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP and WebSocket server port |
| `JWT_SECRET` | *(Base64, >= 512 bits / 64 bytes)* | HMAC signing key for room-bound JWTs |
| `JWT_EXPIRATION_MILLIS` | `14400000` (4 hours) | Token validity duration in milliseconds |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,https://production-ui.com` | Comma-separated allowed browser origins |
| `TRUST_PROXY` | `false` | Trust `X-Forwarded-For` headers when behind a reverse proxy |
| `POKER_DISCONNECT_GRACE_PERIOD_MS` | `120000` (2 minutes) | Per-player reconnect grace period before removal |
| `POKER_ROUND_END_DISPLAY_DELAY_MS` | `0` | Delay before opening the ready gate after showdown |
| `POKER_READY_COUNTDOWN_MS` | `30000` (30 seconds) | Shared inter-hand ready countdown duration |
| `POKER_GAME_END_DISPLAY_DELAY_MS` | `20000` (20 seconds) | Retention delay before destroying a finished room/game |
| `POKER_GAME_TURN_ALLOWANCE_MS` | `60000` (optional; default applies when unset) | Time the player to act has before being timed out |
| `POKER_GAME_AUTO_ADVANCE_STEP_DELAY_MS` | `4000` (optional) | Pause between automatically dealt streets when all remaining players are all-in |
| `POKER_GAME_FINAL_SHOWDOWN_DISPLAY_DELAY_MS` | `6000` (optional) | How long the final showdown stays on screen before the game ends |
| `POKER_GAME_GAME_INACTIVITY_ALLOWANCE_MS` | `900000` (optional, 15 minutes) | Idle time before an active game is abandoned |
| `POKER_ROOM_LOBBY_INACTIVITY_MS` | `1800000` (optional, 30 minutes) | Idle time before a lobby is deleted |
| `POKER_RATE_LIMITING_ENABLED` | `true` | Enable Bucket4j REST and STOMP rate limiting |
| `POKER_PERSISTENCE_ENABLED` | `false` | Enable encrypted per-room state-image WAL recovery |
| `POKER_PERSISTENCE_DIRECTORY` | `./data/poker-wal` | Directory for encrypted per-room WAL files |
| `POKER_PERSISTENCE_CURRENT_KEY_ID` | `current` | Active key identifier in the encryption keyring |
| `POKER_PERSISTENCE_KEY_CURRENT` | *(Base64, 256-bit / 32-byte AES key)* | AES-256-GCM key for `current` key ID |
| `POKER_PERSISTENCE_COMPACT_AFTER_RECORDS` | `1000` | Record threshold before WAL compaction |
| `POKER_PERSISTENCE_COMPACT_AFTER_BYTES` | `16777216` (16 MB) | Byte threshold before WAL compaction |
| `POKER_PERSISTENCE_RETRY_INTERVAL_MS` | `5000` (optional) | Delay between retries of failed room saves |

## 📡 API Reference

### Response & Error Conventions

- **REST success responses:** Return bare resource DTOs directly (`TokenResponse`, `RoomDataResponse`, `PublicGameStateResponse`, `PrivatePlayerState`) or `204 No Content` for void commands (`/start-game`, `/leave`).
- **WebSocket broadcasts:** `/room/{roomId}` sends bare `RoomDataResponse` (with `closedReason` set on closure); `/game/{gameId}` sends bare `PublicGameStateResponse` (with `(handNumber, stateRevision)` for client ordering and `gameEnd` set on completion); `/user/queue/private` sends `PrivatePlayerState` and `PlayerFeedbackResponse`.
- **HTTP errors:** Standardized RFC 9457 `ProblemDetail` (`application/problem+json`) via `ControllerExceptionHandler`.

### REST Endpoints

| Method | Endpoint | Auth | Response | Purpose |
| --- | --- | --- | --- | --- |
| `POST` | `/api/room/create` | Public | `TokenResponse` | Create a room (2–10 players, optional password) and issue a room-bound JWT |
| `POST` | `/api/room/join` | Public | `TokenResponse` | Join an existing room by name and issue a room-bound JWT |
| `GET` | `/api/room/{roomId}` | Bearer JWT | `RoomDataResponse` | Fetch current lobby/room snapshot |
| `POST` | `/api/room/{roomId}/start-game` | Bearer JWT (Host) | `204 No Content` | Start the poker game for the room |
| `POST` | `/api/room/{roomId}/leave` | Bearer JWT | `204 No Content` | Leave a lobby or active game (transfers host in lobby or auto-folds active hand) |
| `GET` | `/api/game/{gameId}/state` | Bearer JWT | `PublicGameStateResponse` | Fetch current public table snapshot |
| `GET` | `/api/game/{gameId}/private-state` | Bearer JWT | `PrivatePlayerState` | Fetch authenticated player's hole cards |
| `GET` | `/api/game/{gameId}/review` | Bearer JWT | `PublicGameStateResponse` | Fetch frozen terminal result (`gameEnd` populated) after game completion |
| `POST` | `/api/game/{gameId}/claim-win` | Bearer JWT | `PublicGameStateResponse` | Claim victory (`gameEnd` populated) when all remaining opponents are disconnected |
| `GET` | `/actuator/health` | Public | Actuator JSON | Server readiness and `pokerPersistenceHealth` details (`mode`: `DURABLE` / `MEMORY_ONLY` / `RECOVERING`, `unsavedRoomCount`, `activeWalCount`) |

### WebSocket / STOMP Channels

- **Handshake Endpoint:** `/ws` (Bearer JWT supplied in STOMP `CONNECT` headers)
- **Client Publish (`SEND`):**
  - `/app/{gameId}/action` — Submit betting action (`FOLD`, `CHECK`, `CALL`, `BET`, `RAISE`, `ALL_IN`)
  - `/app/{gameId}/ready` — Mark player ready during post-hand countdown
  - `/app/{gameId}/unready` — Unmark player ready during post-hand countdown
- **Client Subscribe (`SUBSCRIBE`):**
  - `/room/{roomId}` — `RoomDataResponse` snapshots for membership, host transfer, game start, and room closure (`closedReason`)
  - `/game/{gameId}` — `PublicGameStateResponse` snapshots (with `handNumber`/`stateRevision` and optional `gameEnd`)
  - `/user/queue/private` — Authenticated player's `PrivatePlayerState` hole cards and `PlayerFeedbackResponse` errors/notices

## ✅ Build & Verification

### Domain-Driven & Behavior-Focused Testing

The test suite verifies the mathematical correctness of the poker rules engine, concurrency invariants, and real-time contract integrity across three Maven tiers (`*Test`, `*IT`, `*E2E`):

- **Rules & Chip Conservation Assertions:** Tests verify concrete domain outcomes (side-pot splits, blind posting, uncalled bet refunds, action legality, and total chip conservation) rather than internal implementation details.
- **Client-Centric STOMP & REST Validation:** Integration tests simulate real client flows and assert on REST and STOMP payloads consumed by the React frontend.
- **Deterministic Async & Recovery Suites:** Concurrency, scheduled deadlines, and encrypted WAL crash-recovery flows are tested using Awaitility and isolated temporary storage without brittle sleeps.

```bash
# Run unit tests (*Test.java)
.\mvnw.cmd test

# Run full verification suite (unit *Test, integration *IT, and *E2E)
.\mvnw.cmd verify

# Run a single unit test class
.\mvnw.cmd test "-Dtest=HandEvaluatorTest"

# Run a single integration or E2E test class
.\mvnw.cmd verify "-Dtest=None" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=GameplayLifecycleE2E"
```

### Key Verification Areas

- `HttpSecurityIT`, `WebSocketSecurityAndRateLimitIT`, & `WebSocketAdviceAndBoundaryIT`: Identity isolation, membership-bound JWTs, STOMP destination guards, rate limiting, and private queue protection.
- `PlayerReconnectE2E` & `TimingAndInactivityIT`: Disconnect grace windows, multi-session tracking, reconnect state restoration, inactivity expiry, and win claiming.
- `GameplayLifecycleE2E`, `RoomLobbyE2E`, & `LifecycleCoordinationIT`: End-to-end lobby lifecycle, deal, betting rounds, showdown, ready gates, concurrency coordination, and game completion.
- `HandEvaluatorTest`: Mathematical evaluation of 7-card combinations down to the best 5-card hand.
- `PersistenceRecoveryTest`, `PersistenceRestartIT`, `PersistenceCoordinationIT`, & `PersistenceAvailabilityTransportIT`: Encrypted state-image WAL commits, torn-tail recovery, quarantine, `MEMORY_ONLY` runtime degradation, and deadline rescheduling across restarts.

## 🐳 Docker & CI/CD

- **Container Build ([`Dockerfile`](Dockerfile)):** Multi-stage build using `maven:3.9-eclipse-temurin-25` for compilation and `eclipse-temurin:25-jre-alpine` for runtime. Declares `/var/lib/poker/wal` and `/app/logs` volumes, exposes port `8080`, and configures a `HEALTHCHECK` against `http://localhost:8080/actuator/health`.
- **CI/CD Workflows:**
  - [`.github/workflows/dev-tests.yml`](.github/workflows/dev-tests.yml): Runs `./mvnw -B test` (unit tests) on Java 25 for pushes and pull requests to `dev`.
  - [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml): Runs `./mvnw clean verify` (full unit, integration, and E2E suite) on Java 25 for pushes and pull requests to `main`; passing pushes to `main` build and push multi-architecture (`linux/amd64`, `linux/arm64`) Docker images (`suame/poker-backend:latest` and `:<sha>`).

```bash
# Build local Docker image
docker build -t poker-backend .

# Run container with local .env file
docker run --rm -p 8080:8080 --env-file .env poker-backend
```

## 📚 Documentation

- [AI & Project Context](docs/ai/PROJECT.md) — Canonical architecture, concurrency model, and developer guide.
- [Domain Glossary](GLOSSARY.md) — Plain-English definitions of room, game, betting, and recovery terms.
- [Persistence Operations](docs/persistence-operations.md) — WAL encryption keys, rotation, health checks, and recovery drills.
- [Backend Logging Guide](docs/ai/LOGGING.md) — MDC correlation, log levels, redaction rules, and diagnostic recipes.
- [Architecture Decision Records](docs/adr/) — ADRs covering `RoomCoordinator` single-boundary concurrency, encrypted state-image WAL & in-memory rollback, JWT session-membership validation, epoch-deadline scheduling, and multi-session disconnect grace.

## 🗺️ Future Roadmap

- **Persistence Scale-out:** Optional migration from the single-node per-room WAL to a distributed durable store.
- **Profiles & Leaderboards:** Optional persistent player accounts and historical hand summaries.
- **Horizontal Scalability:** External STOMP broker relay and distributed room routing.

---

**Companion frontend showcase:** ([Repo](https://github.com/itzbenjamin17/Poker-Frontend)). Together they demonstrate end-to-end real-time gameplay architecture.
