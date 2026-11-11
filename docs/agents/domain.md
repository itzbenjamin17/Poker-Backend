# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root for the canonical ubiquitous language and avoided synonyms.
- **`docs/ai/PROJECT.md`** as the canonical project context.
- **`GEMINI.md`** at the repo root for compatibility with older workflows.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in.

## File structure

Single-context repo:

```
/
├── GLOSSARY.md
├── GEMINI.md
├── docs/ai/PROJECT.md
├── docs/adr/
│   ├── 0001-encrypted-state-image-wal-with-spring-aop.md
│   ├── 0002-quarantine-corrupted-wal-during-startup-recovery.md
│   ├── 0003-in-memory-aggregate-rollback-on-mutation-failure.md
│   ├── 0004-stateless-jwt-session-membership-validation.md
│   ├── 0005-authoritative-in-memory-domain-model-without-external-database.md
│   ├── 0006-unified-room-and-game-identity-lifecycle.md
│   ├── 0007-hierarchical-monitor-locking-order.md
│   ├── 0008-epoch-deadline-lifecycle-scheduling.md
│   ├── 0009-multi-session-disconnect-grace-period.md
│   ├── 0010-room-lifecycle-notifications-via-application-events.md
│   └── 0011-room-coordinator-single-boundary-concurrency.md
└── src/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap to capture via `/domain-modeling`.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0002 (quarantine corrupted wal during startup recovery) — but worth reopening because…_
