# Coding Standards

Reviewers enforce these standards on every change. System architecture and runtime invariants live in [docs/ai/PROJECT.md](docs/ai/PROJECT.md); domain terms live in [GLOSSARY.md](GLOSSARY.md).

## Naming And Javadoc

- **Plain-English identifiers:** Use clear verb phrases (`removePlayers`, `selectNextHost`, `deleteGameAndRoom`, `buildLiveGameState`, `PostSaveActions`).
- **Domain terms:** Prefer plain-English terms from [GLOSSARY.md](GLOSSARY.md) (`RoomSnapshot`, `RecoveredRoom`, `RoomSnapshotMapper`, `GameResultState`, `pausedForDisconnectedSurvivor`, `timerVersion`, and plain *"room lock"*); avoid internal jargon (*durable mutation* outside `@DurableMutation`, *terminal result / terminal review / terminal room*, *cleanup intent*, *rehydrated*, *state image*, *authoritative mutation*, *room lease*, or *coordination lease*).
- **Javadoc scope:** Document classes and non-`private` methods (`public`, `protected`, and package-private collaborator entry points) in plain English covering contract, side effects, and exceptions, with `@param`, `@return`, and `@throws` tags where relevant; leave trivial getters and setters undocumented.
- **Private methods and inline comments:** Let descriptive `private` method names carry intent without Javadoc; add a short comment only to explain a non-obvious *why*, domain edge case, room-lock assumption, or ordering dependency.

## Class Layout, Imports, And Dependency Injection

- **Imports:** Write explicit single-type imports (no wildcard `import ...*;`). Order `static` imports alphabetically first, then a blank line, then standard imports alphabetically.
- **Member order (across `src/main/java/com/pokergame/`):**
  1. `logger`
  2. `static final` constants
  3. `private final` Spring-injected fields (grouped with no blank lines between them)
  4. One blank line
  5. Mutable state fields / collections
  6. Single constructor
  7. `public` methods
  8. Package-private methods
  9. `private` helper methods
  10. Simple getters and setters
  11. Nested records, enums, or helper types
- **Constructors and DI:** Use a single constructor per `@Service`, `@Component`, or controller bean injected directly by Spring (omit test-only constructor overloads, `@Autowired(required = false)` setters, `Objects.requireNonNull` on Spring-injected beans, and `@Lazy self` / `mutationProxy()` fields). Use `ObjectProvider<...>` when a scheduled callback needs a proxy, and `AopContext.currentProxy()` (with a direct fallback) when a same-bean call must enter `@DurableMutation`.
- **Null-handling:** `import static java.util.Objects.requireNonNull;` and use `requireNonNull(arg, "arg must not be null");` as a standalone statement for arguments validated without logging. Keep `if (obj == null)` checks when logging or throwing a domain exception.
- **Validation and exceptions:** Annotate validated REST request bodies with `@Valid`. Map client and state rejections through `BadRequestException` or `IllegalArgumentException` (400), `UnauthorisedActionException` (403), `ResourceNotFoundException` (404), `ConflictException` (409), and `TooManyRequestsException` (429). Throw `IllegalStateException` (500) directly when an internal invariant is broken and let `ControllerExceptionHandler` log the error once.

## Response Envelope

- **Commands (`POST`) and `/room/{roomId}` broadcasts** return `ApiResponse<T>` (`message` + `data`).
- **Queries (`GET`)** return the resource DTO directly at the top level as a bare DTO.
- **Errors** use `ControllerExceptionHandler` (`application/problem+json`).
- See [docs/ai/PROJECT.md](docs/ai/PROJECT.md#response-envelope-convention) for why (frontend decoder coupling).

## Tests

- **Unit (`@Tag("unit")` `*Test.java`):** Map 1:1 to production classes under matching packages with zero Spring context.
- **Integration (`@Tag("integration")` `*IT.java`):** Grouped by related files to check one boundary or behavior (security, rate limits, persistence, timing, web layer) with real Spring wiring; may assert on internal beans and start a real port.
- **E2E (`@Tag("e2e")` `*E2E.java`):** Multi-step player journeys through public REST and STOMP interfaces only. Internal beans are allowed for setup, cleanup, and waiting on subscriptions, never for assertions.
- **File discipline:** Keep `@Tag` aligned with the class suffix, fold new tests into an existing related `*IT` or 1:1 `*Test` (adding a new test file only alongside a new production class), and keep tests distinct without duplicates.
- **Assertions and async cleanup:** Assert public game outcomes, chip balances, client snapshots, and published messages rather than private fields or internal delegation. Use Awaitility or state conditions instead of timing sleeps, and clean up threads, sockets, and Spring contexts.

## Logging

- Use parameterized SLF4J logs for bounded operational detail and follow [docs/ai/LOGGING.md](docs/ai/LOGGING.md) for MDC propagation, throttling, and secret or hole-card redaction.

## Design Invariants

Check every diff against [docs/ai/PROJECT.md](docs/ai/PROJECT.md):
- **Room-lock rule:** §Room, Game, And Concurrency Model in [docs/ai/PROJECT.md](docs/ai/PROJECT.md#room-game-and-concurrency-model).
- **Package boundary:** §Code Map (`persistence/` imports nothing from `service`, `config`, and `controller`).
- **Check-first rule:** §Durable Recovery (validate all preconditions before mutating state).
