---
name: sequential-tickets
description: Implement an approved numbered sequence of local backend tickets in order, with per-ticket verification and review. Use when asked to execute a multi-ticket implementation workflow.
---

# Sequential Ticket Workflow

Use this workflow for an approved numbered set of local tickets, such as `.scratch/<feature>/issues/`.

## Prepare

1. Read the ticket folder's `README.md` and `coordinator-prompt.md` when present, then read root [AGENTS.md](../../../AGENTS.md), [docs/ai/PROJECT.md](../../../docs/ai/PROJECT.md), and relevant domain or ADR references.
2. Treat the approved ticket set as authoritative when it conflicts with an earlier proposal. Inspect `git status` and `git diff`; preserve existing work and stay on the current branch unless the task says otherwise.
3. Confirm ticket order, scope, and acceptance criteria. Report a blocking ambiguity before changing dependent behavior.

## Execute tickets in order

Complete tickets one at a time from `01` through `N`. Finish and verify each ticket before starting the next. If work is delegated, give each worker explicit file ownership and prevent concurrent edits to shared files.

- For bug fixes and game-rule changes, add a meaningful behavioral regression test, confirm it fails for the intended reason, implement the fix, and confirm it passes.
- For extractions and refactors, establish passing behavioral or characterization coverage before moving code. Add characterization coverage where it is missing; do not manufacture a failure to imitate TDD.
- Assert public game outcomes, chip balances, client snapshots, and published messages rather than private fields or internal delegation. Never weaken tests or acceptance criteria.
- Follow each ticket's exact method ownership and approved class names. Preserve public signatures, `@DurableMutation` boundaries, injected-clock semantics, and `MEMORY_ONLY` runtime persistence-outage behavior. Resolve mutable aggregates only under the `RoomCoordinator` room lease; add no independent module locks or transactions. Internal lifecycle modules call their owners directly, not compatibility delegates, and avoid hand-progression/readiness dependency cycles.
- Run the narrowest check that covers the ticket and its boundaries. Use Java 25 and the Maven wrapper (`.\mvnw.cmd` on Windows, `./mvnw` on POSIX). Redirect full Maven output to `.scratch/` logs and report the command, pass/fail status, test counts, and log path. On failure, inspect only the relevant error and stack trace.
- Documentation-only tickets use relevant documentation checks; do not run Maven solely for documentation changes.
- Obtain a review for each completed ticket from the reviewer suited to its scope. Use `deep-reviewer` for poker rules, chip accounting, room concurrency, lifecycle scheduling, authorization, or durable recovery; use `fast-reviewer` for routine API, DTO, validation, configuration, and literal-spec checks. Resolve actionable findings before marking the ticket complete.
- Update ticket acceptance checkboxes or status only with observed evidence. Commit each completed ticket with explicit path staging only when the current task authorizes commits; stage only ticket files with `git add <file>`, excluding unrelated work. This workflow alone does not authorize commits, pushes, publication, or remote issue/PR creation.

## Integrate and report

After all tickets are integrated, run the complete backend Maven suite once unless the approved work is documentation-only. Obtain a final `deep-reviewer` review of approved method ownership, dependency structure, and concurrency and persistence invariants. Fix findings and rerun only checks affected by those fixes.

Report completed tickets, authorized ticket commits if any, checks and compact results, review outcomes, and remaining limitations. Report before/after line counts only for classes actually decomposed; omit line-count reporting for other work.