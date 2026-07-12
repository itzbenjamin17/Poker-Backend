## Agent skills

### Issue tracker

GitHub — issues live in the repo's GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout using `GEMINI.md`. See `docs/agents/domain.md`.

## Repo Context

### Project overview

This repo is a Java 25 + Spring Boot 4.0.6 backend for multiplayer poker. It uses STOMP over WebSockets, JWT authentication, Bucket4j rate limiting, and encrypted restart recovery for room/game state.

### Main technologies

- Java 25
- Spring Boot 4.0.6
- JJWT (`io.jsonwebtoken`)
- STOMP over WebSockets
- Bucket4j
- `springboot4-dotenv`
- Maven
- JUnit 5, AssertJ, Awaitility

### Architecture and state

- Layered architecture: controller, service, model, dto, event, listener, config, security, persistence, exception, util.
- Game and room state lives in memory at runtime, with encrypted state-image persistence for restart recovery.
- Mutative room/game operations use `@DurableMutation` and synchronized game mutation blocks.
- Recovery restores aggregate state, scheduled work, reconnect deadlines, and client-session assumptions from the latest committed WAL record.
- The design is single-server and single-writer per room.

### Build and run

- Build: `./mvnw clean package`
- Run: `./mvnw spring-boot:run`
- Test: `./mvnw test`
- Specific test: `./mvnw test -Dtest=GameLifecycleIntegrationTest`

### Development conventions

- Prefer behavior-focused tests over implementation-detail assertions.
- Use client-centric validation for REST and WebSocket flows.
- Keep integration tests isolated and deterministic.
- REST uses `ControllerExceptionHandler` and exceptions extending `PokerException`.
- WebSocket connections are secured by `WebSocketAuthInterceptor`.
- Player identity comes from the authenticated `Principal`.
