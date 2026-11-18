# AGENTS.md

Canonical architecture and domain invariants live in [docs/ai/PROJECT.md](docs/ai/PROJECT.md). Read it before modifying code, then consult the relevant [ADRs](docs/adr/) for architecture changes. Use source and configuration for exact behavior.

## Build, Test, And Context

- Use Java 25 and the checked-in Maven wrapper: `.\mvnw.cmd` on Windows, `./mvnw` on POSIX.
- Run the 3 test tiers defined in [CODING_STANDARDS.md](CODING_STANDARDS.md#tests): `*Test.java` via `.\mvnw.cmd test`, and `*IT.java` / `*E2E.java` via `.\mvnw.cmd verify` (or `-Dgroups=integration` / `-Dgroups=e2e`). To run a single `*IT` or `*E2E` class without running unit tests first, pass `"-Dtest=None" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=ClassIT"`.
- For Maven checks, redirect full output to a local `.scratch/` log (create `.scratch/` first if it does not exist), for example: `.\mvnw.cmd test "-Dtest=ClassName" > .scratch/test.log 2>&1`.
- Report the command, pass/fail status, test counts, and log path. On failure, inspect the relevant error and stack trace; keep full logs out of the conversation.
- Run the narrowest check that covers changed behavior during iteration. Run the full suite (`.\mvnw.cmd verify`) once after integrated code changes before claiming completion; documentation-only changes use relevant documentation checks.
- Do not repeat a passing check unless a later change affects it, a review finding requires it, or it is part of final verification.
- CI: `dev` runs unit tests ([dev-tests.yml](.github/workflows/dev-tests.yml)); `main` runs full `./mvnw clean verify` ([deploy.yml](.github/workflows/deploy.yml)).

## Behavioral Tests

- For bug fixes and game-rule changes, write a meaningful failing behavioral regression test first, confirm the intended failure, implement the fix, then confirm it passes.
- Before a refactor, add characterization or regression coverage to the existing 1:1 `*Test` or related `*IT`, not a new file. When you extract logic, move its tests with it. Delete temporary differential or characterization scaffolding before committing.
- Never weaken assertions, delete tests, or change acceptance criteria to claim completion.

## Git And Working Tree

- Inspect `git status` and `git diff` before editing, and preserve unrelated working-tree changes.
- Stage files explicitly by path (`git add <file>`); do not stage the whole working tree.
- Push, publish, or create remote issues or PRs only when the task explicitly asks for it.

## Architecture And Domain Invariants

- **Room coordination:** `RoomCoordinator` is the single concurrency boundary per room ([ADR-0011](docs/adr/0011-room-coordinator-single-boundary-concurrency.md)); follow the two-part room-lock rule in [docs/ai/PROJECT.md](docs/ai/PROJECT.md#room-game-and-concurrency-model) and add no independent module locks or transactions.
- **Module ownership:** Internal lifecycle modules call their owners directly, not `GameLifecycleService` compatibility delegates. Avoid hand-progression/readiness dependency cycles.
- **Durability:** Preserve `@DurableMutation` boundaries, injected-clock semantics, and `MEMORY_ONLY` runtime persistence-outage behavior ([docs/ai/PROJECT.md](docs/ai/PROJECT.md#durable-recovery), [ADR-0001](docs/adr/0001-encrypted-state-image-wal-with-spring-aop.md), [persistence operations](docs/persistence-operations.md)).
- **Coding standards and logging:** Reviewers enforce [CODING_STANDARDS.md](CODING_STANDARDS.md) and [docs/ai/LOGGING.md](docs/ai/LOGGING.md).

## Issue Tracker And Domain References

- Domain vocabulary: [GLOSSARY.md](GLOSSARY.md).
- Delegating to subagents: [docs/agents/roles.md](docs/agents/roles.md).
- GitHub Issues workflow: [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md).
- Triage labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`; see [docs/agents/triage-labels.md](docs/agents/triage-labels.md).
- Domain engineering guidance: [docs/agents/domain.md](docs/agents/domain.md).
