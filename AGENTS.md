# AGENTS.md

Canonical architecture, domain invariants, and agent-role definitions live in [docs/ai/PROJECT.md](docs/ai/PROJECT.md). Read it before modifying code, then consult the relevant [ADRs](docs/adr/) for architecture changes. Use source and configuration for exact behavior.

## Build, Test, And Context

- Use Java 25 and the checked-in Maven wrapper: `.\mvnw.cmd` on Windows, `./mvnw` on POSIX.
- For Maven checks, redirect full output to a local `.scratch/` log (create `.scratch/` first if it does not exist), for example: `.\mvnw.cmd test "-Dtest=ClassName" > .scratch/test.log 2>&1`.
- Report the command, pass/fail status, test counts, and log path. On failure, inspect the relevant error and stack trace; keep full logs out of the conversation.
- Run the narrowest check that covers changed behavior during iteration. Run the full suite once after integrated code changes before claiming completion; documentation-only changes use relevant documentation checks.
- Do not repeat a passing check unless a later change affects it, a review finding requires it, or it is part of final verification.

## Behavioral Tests

- For bug fixes and game-rule changes, write a meaningful failing behavioral regression test first, confirm the intended failure, implement the fix, then confirm it passes.
- For extractions and refactors, establish passing behavioral or characterization coverage before moving code. Add characterization tests where coverage is missing; do not invent failures just to satisfy a TDD sequence.
- Assert public game outcomes, chip balances, client snapshots, and published messages rather than private fields or internal delegation.
- Never weaken assertions, delete tests, or change acceptance criteria to claim completion.

## Git And Working Tree

- Inspect `git status` and `git diff` before editing, and preserve unrelated working-tree changes.
- Stage files explicitly by path (`git add <file>`); do not stage the whole working tree.
- Push, publish, or create remote issues or PRs only when the task explicitly asks for it.

## Architecture And Domain Invariants

- **Room coordination:** `RoomCoordinator` is the authoritative concurrency boundary per room ([ADR-0011](docs/adr/0011-room-coordinator-single-boundary-concurrency.md)). Enter room operations through `coordinate(roomId, ...)` or acquire its lease before resolving mutable `Room` or `Game` aggregates. `assertHeldByCurrentThread(roomId)` checks that the current thread already owns the lease; it does not acquire it. Add no independent module locks or transactions.
- **Module ownership:** Internal lifecycle modules call their owners directly, not `GameLifecycleService` compatibility delegates. Avoid hand-progression/readiness dependency cycles.
- **Durability:** Preserve `@DurableMutation` boundaries, injected-clock semantics, and `MEMORY_ONLY` runtime persistence-outage behavior ([ADR-0001](docs/adr/0001-encrypted-state-image-wal-with-spring-aop.md), [persistence operations](docs/persistence-operations.md)).
- **Logging:** Follow [docs/ai/LOGGING.md](docs/ai/LOGGING.md) for MDC propagation, throttling, and secret or hole-card redaction.

## Issue Tracker And Domain References

- GitHub Issues workflow: [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md).
- Triage labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`; see [docs/agents/triage-labels.md](docs/agents/triage-labels.md).
- Domain vocabulary and engineering guidance: [docs/agents/domain.md](docs/agents/domain.md).
