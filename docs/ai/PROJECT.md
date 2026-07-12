# Poker Backend AI Project Context

## Project Overview

This is a **Java 25 + Spring Boot 4.0.6** backend for a multiplayer poker game. It features real-time game state management, WebSocket communication via STOMP, JWT authentication, encrypted restart recovery, and a comprehensive Texas Hold'em hand evaluation engine. The project follows a clean, layered architecture and keeps live state in memory with durable state-image persistence for recovery.

### Main Technologies

- **Java 25** (Utilizing modern features like Records and Sequences)
- **Spring Boot 4.0.6** (Web, Security, WebSockets, Actuator, Validation)
- **JJWT (io.jsonwebtoken)** for stateless JWT authentication
- **STOMP over WebSockets** for real-time bidirectional communication
- **Bucket4j** for API rate limiting
- **Dotenv** (`springboot4-dotenv`) for environment variable management
- **Maven** for build and dependency management
- **JUnit 5, AssertJ, Awaitility** for testing

## Repo Workflow

- **Domain docs:** Read this file first, then inspect relevant ADRs under `docs/adr/` before changing behavior in that area.
- **Issue tracker:** Repo issues live in GitHub Issues. Use `gh issue ...` for issue work.
- **Triage labels:** The canonical labels in this repo are `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`.
- **Single-context layout:** Keep terminology aligned with this file when naming issues, tests, and refactors.

## Architecture & Design Patterns

- **Layered Architecture:**
  - `Controller`: REST endpoints (`RoomController`, `GameController`) and WebSocket message mappings.
  - `Service`: Business logic orchestration (`GameLifecycleService`, `RoomService`, `PlayerActionService`).
  - `Model`: Domain entities and state (`Game`, `Room`, `Player`, `Card`, `Deck`).
  - `DTO`: Data Transfer Objects for requests and responses.
- **Concurrency & State:**
  - Game and room state lives in-memory for runtime access, with durable state-image persistence for restart recovery.
  - Mutative operations on the `Game` model (especially in `PlayerActionService`) are protected by `synchronized(game)` blocks to ensure thread safety during complex state transitions (e.g., betting rounds, side-pot calculations).
- **Event-Driven:** Uses `ApplicationEventPublisher` for asynchronous tasks like game cleanup, auto-advancing phases, and starting new hands.

## Building and Running

### Prerequisites

- Java 25
- Maven (or use provided `./mvnw` / `mvnw.cmd`)

### Key Commands

- **Build:** `./mvnw clean package`
- **Run:** `./mvnw spring-boot:run` (Server starts on `http://localhost:8080`)
- **Test:** `./mvnw test`
- **Check Specific Test:** `./mvnw test -Dtest=GameLifecycleIntegrationTest`

## Development Conventions

- **Naming:** standard Java camelCase for variables/methods, PascalCase for classes.
- **Error Handling:** Centralized via `ControllerExceptionHandler`. Custom exceptions extend `PokerException`.
- **Validation:** Uses `@Valid` on request DTOs.
- **Logging:** Uses SLF4J with Logback. Significant game events (betting, phase changes, showdowns) are logged at `DEBUG` level.
- **Comments** Create/Update Javadoc on any function/class you are creating or working on, also add helpful comments that capture decisions (why) not implementation (how). If it's not obvious why something is done, document why it's done that way.
- **Testing Conventions:**
  - **Test Behavior and Logic, Not Implementation:** Focus assertions on the public behavior of the game engine, domain rules (e.g., side pot splits, blinds deductions, uncalled chip refunds), and published messages rather than reflecting or asserting on private fields or internal method delegation.
  - **Query and Validate Like a Client:** Verify state updates by inspecting public state snapshots (DTOs sent to players) and websocket frames. Ensure that the client gets exactly the semantic information they need to render the game correctly.
  - **Test-Driven Development (TDD):** Implement all game rules or bug fixes with accompanying regression tests first.
  - **Isolated and Stable Integration:** Ensure integration tests (`WebSocketActionIntegrationTest`, `GameLifecycleIntegrationTest`) are decoupled, cleaning up context and managing threads cleanly to prevent port binding or memory collisions.
- **Security:**
  - REST endpoints are secured via `JwtAuthenticationFilter`.
  - WebSocket connections are secured via `WebSocketAuthInterceptor` (validates JWT during `CONNECT` frame).
  - Player identity is derived from the `Principal` in the `SecurityContext`.
  - Rate limiting is implemented using Bucket4j to prevent API abuse.

## Durable State

- `@DurableMutation` marks authoritative room/game mutations that must be captured as encrypted state images.
- Recovery restores aggregate state, scheduled work, reconnect deadlines, and client-session assumptions from the latest committed WAL record.
- The design is single-server and single-writer per room; do not describe it as multi-node or replay-based persistence.

## Domain Logic Highlights

- **Hand Evaluation:** `HandEvaluatorService` generates 5-card combinations from 7 (hole + community) and ranks them using the `HandRank` enum.
- **Pot Management:** `Game.java` handles complex side-pot logic and uncalled chip refunds using a layered allocation approach.
- **Real-time Updates:** `GameStateService` handles broadcasting state snapshots to players. Private data is sent through Spring user destinations rather than predictable room-scoped private topics.

## Project Structure (Key Paths)

- `src/main/java/com/pokergame/`
  - `config/`: Security, WebSocket, Async, rate limiting, and task configuration.
  - `controller/`: REST and STOMP endpoints.
  - `service/`: Core business logic and lifecycle orchestration.
  - `model/`: State-holding domain objects.
  - `persistence/`: Durable mutation and WAL/state-image support.
  - `security/`: JWT and principal handling.
  - `dto/`: Request/response structures.
  - `event/`: Application events for async processing.
  - `listener/`: Async event listeners.
  - `exception/`: Centralized domain and request exceptions.
  - `util/`: Shared helpers such as sanitization.
- `src/test/java/com/pokergame/`: Unit and integration test suites.
