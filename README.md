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

> **Note:** On macOS/Linux, use `./mvnw` instead of `.\mvnw.cmd`. To run with browser E2E timing and rate-limit overrides, use `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=e2e"`.

**Companion frontend URL:** http://localhost:5173

## 🛠️ Tech Stack

| Technology | Version | Purpose |
| --- | --- | --- |
| **Java** | 25 | Language runtime and modern language features |
| **Spring Boot** | 4.0.6 | Web, Security, WebSocket/STOMP, Validation, Actuator, AspectJ |
| **JJWT** | 0.13.0 | Stateless JWT creation and verification |
| **Bucket4j** | 8.10.1 | REST and STOMP rate limiting |
| **spring-dotenv** | 5.1.0 | Local `.env` configuration loading |
| **JUnit Jupiter / AssertJ / Awaitility** | Spring-managed | Unit, concurrency, and asynchronous integration testing |

## 🏗️ Architecture

The engine follows a **Layered Architecture** with explicit concurrency and durability boundaries:

- **Controller Layer (`controller/`, `advice/`):** REST and STOMP endpoints for lobby and game interaction, acquiring per-room lock leases before membership validation, paired with centralized RFC 7807 / `ApiResponse` exception advice.
- **Service Layer (`service/`):** Orchestrates room registries (`RoomService`), active game lifecycles and deadline scheduling (`GameLifecycleService`), betting validation (`PlayerActionService`), post-commit projections (`GameStateService`), and 7-card hand evaluation (`HandEvaluatorService`).
- **Model Layer (`model/`):** Pure domain aggregate (`Game`, `Room`, `Player`, `Deck`) supported by package-private domain collaborators (`BettingRound`, `TableSeating`, `PotCalculator`, `ShowdownResolver`, `GameScheduling`, `ReadyGate`) and `GameRestoreBuilder`.
- **Persistence Layer (`persistence/`):** `@DurableMutation` Spring AOP aspect, versioned state-image snapshots, per-room AES-256-GCM write-ahead logs (WAL), startup `PersistenceRecovery`, and an HTTP `503` recovery traffic gate (`persistenceRecoveryTrafficGate`) that blocks non-health requests until restoration completes.
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
- **Rate Limiting:** Bucket4j throttles REST room entry endpoints and STOMP gameplay actions per identity/IP.
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
| `POKER_RATE_LIMITING_ENABLED` | `true` | Enable Bucket4j REST and STOMP rate limiting |
| `POKER_PERSISTENCE_ENABLED` | `false` | Enable encrypted per-room state-image WAL recovery |
| `POKER_PERSISTENCE_DIRECTORY` | `./data/poker-wal` | Directory for encrypted per-room WAL files |
| `POKER_PERSISTENCE_CURRENT_KEY_ID` | `current` | Active key identifier in the encryption keyring |
| `POKER_PERSISTENCE_KEY_CURRENT` | *(Base64, 256-bit / 32-byte AES key)* | AES-256-GCM key for `current` key ID |
| `POKER_PERSISTENCE_COMPACT_AFTER_RECORDS` | `1000` | Record threshold before WAL compaction |
| `POKER_PERSISTENCE_COMPACT_AFTER_BYTES` | `16777216` (16 MB) | Byte threshold before WAL compaction |

## 📡 API Reference

### Response Envelope Convention

- **Commands (`POST` mutations & `/room/{roomId}` broadcasts):** Return `ApiResponse<T>` (`{ message, data }`).
- **Queries (`GET` snapshots):** Return the resource DTO directly without an envelope.
- **Errors:** Standardized error responses (including `application/problem+json`) via controller advice.

### REST Endpoints

| Method | Endpoint | Auth | Response | Purpose |
| --- | --- | --- | --- | --- |
| `POST` | `/api/room/create` | Public | `ApiResponse<TokenResponse>` | Create a room (2–10 players, optional password) and issue a room-bound JWT |
| `POST` | `/api/room/join` | Public | `ApiResponse<TokenResponse>` | Join an existing room by name and issue a room-bound JWT |
| `GET` | `/api/room/{roomId}` | Bearer JWT | `RoomDataResponse` | Fetch current lobby/room snapshot |
| `POST` | `/api/room/{roomId}/start-game` | Bearer JWT (Host) | `ApiResponse<String>` | Start the poker game for the room |
| `POST` | `/api/room/{roomId}/leave` | Bearer JWT | `ApiResponse<Void>` | Leave a lobby room (transfers host if needed) |
| `GET` | `/api/game/{gameId}/state` | Bearer JWT | `PublicGameStateResponse` | Fetch current public table snapshot |
| `GET` | `/api/game/{gameId}/private-state` | Bearer JWT | `PrivatePlayerState` | Fetch authenticated player's hole cards |
| `GET` | `/api/game/{gameId}/review` | Bearer JWT | `GameEndResponse` | Fetch frozen terminal result after game completion |
| `POST` | `/api/game/{gameId}/leave` | Bearer JWT | `ApiResponse<Void>` | Leave an active game (auto-folds active hand) |
| `POST` | `/api/game/{gameId}/claim-win` | Bearer JWT | `ApiResponse<GameEndResponse>` | Claim victory when all remaining opponents are disconnected |
| `GET` | `/actuator/health` | Public | Actuator JSON | Server readiness and `pokerPersistenceHealth` details (`mode`: `DURABLE` / `MEMORY_ONLY` / `RECOVERING`, `unsavedRoomCount`, `activeWalCount`) |

### WebSocket / STOMP Channels

- **Handshake Endpoint:** `/ws` (Bearer JWT supplied in STOMP `CONNECT` headers)
- **Client Publish (`SEND`):**
  - `/app/{gameId}/action` — Submit betting action (`FOLD`, `CHECK`, `CALL`, `RAISE`, `ALL_IN`)
  - `/app/{gameId}/ready` — Mark player ready during post-hand countdown
  - `/app/{gameId}/unready` — Unmark player ready during post-hand countdown
- **Client Subscribe (`SUBSCRIBE`):**
  - `/room/{roomId}` — Room membership, host transfer, and game-start events
  - `/game/{gameId}` — Public game state snapshots, notifications, and game-end events
  - `/user/queue/private` — Authenticated player's private hole cards and action errors

## ✅ Build & Verification

### Domain-Driven & Behavior-Focused Testing

The test suite verifies the mathematical correctness of the poker rules engine, concurrency invariants, and real-time contract integrity:

- **Rules & Chip Conservation Assertions:** Tests verify concrete domain outcomes (side-pot splits, blind posting, uncalled bet refunds, action legality, and total chip conservation) rather than internal implementation details.
- **Client-Centric STOMP & REST Validation:** Integration tests simulate real client flows and assert on REST and STOMP payloads consumed by the React frontend.
- **Deterministic Async & Recovery Suites:** Concurrency, scheduled deadlines, and encrypted WAL crash-recovery flows are tested using Awaitility and isolated temporary storage without brittle sleeps.

```bash
# Run full suite (Unit + Integration)
.\mvnw.cmd test

# Run a single test class
.\mvnw.cmd test "-Dtest=GameLifecycleIntegrationTest"
```

### Key Verification Areas

- `SecurityHardeningIntegrationTest` & `WebSocketSecurityTest`: Identity isolation, membership-bound tokens, STOMP destination guards, and private queue protection.
- `ResiliencyIntegrationTest`: Disconnect grace windows, multi-session tracking, reconnect state restoration, and win claiming.
- `GameLifecycleIntegrationTest`: End-to-end deal, betting rounds, showdown, ready gates, and game completion.
- `HandEvaluatorServiceTest`: Mathematical evaluation of 7-card combinations down to the best 5-card hand.
- `PersistenceRecoveryIntegrationTest`: Encrypted state-image WAL commits, torn-tail recovery, and deadline rescheduling across restarts.

## 🐳 Docker & CI/CD

- **Container Build ([`Dockerfile`](Dockerfile)):** Multi-stage build using `maven:3.9-eclipse-temurin-25` for compilation and `eclipse-temurin:25-jre-alpine` for runtime. Declares `/var/lib/poker/wal` and `/app/logs` volumes, exposes port `8080`, and configures a `HEALTHCHECK` against `http://localhost:8080/actuator/health`.
- **CI/CD Workflow ([`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)):** Runs `./mvnw clean test` on Java 25 for all pushes and pull requests to `main`. Passing pushes to `main` build and push multi-architecture (`linux/amd64`, `linux/arm64`) Docker images (`suame/poker-backend:latest` and `:<sha>`).

```bash
# Build local Docker image
docker build -t poker-backend .

# Run container with local .env file
docker run --rm -p 8080:8080 --env-file .env poker-backend
```

## 📚 Documentation

- [AI & Project Context](docs/ai/PROJECT.md) — Canonical architecture, concurrency model, and developer guide.
- [Persistence Operations](docs/persistence-operations.md) — WAL encryption keys, rotation, health checks, and recovery drills.
- [Backend Logging Guide](docs/ai/LOGGING.md) — MDC correlation, log levels, redaction rules, and diagnostic recipes.
- [Architecture Decision Records](docs/adr/) — ADRs covering encrypted state-image WAL, JWT session-membership validation, and STOMP authorization.

## 🗺️ Future Roadmap

- **Persistence Scale-out:** Optional migration from the single-node per-room WAL to a distributed durable store.
- **Profiles & Leaderboards:** Optional persistent player accounts and historical hand summaries.
- **Horizontal Scalability:** External STOMP broker relay and distributed room routing.

---

**Companion frontend showcase:** ([Repo](https://github.com/itzbenjamin17/Poker-Frontend)). Together they demonstrate end-to-end real-time gameplay architecture.
