# Agent Roles And Model Selection

Choose the main chat model for the decisions the task requires. For architecture, ambiguous requirements, or difficult debugging, prefer GPT-6.1 Sol with high reasoning in Codex or the Pro tier in Antigravity. Luna with medium reasoning or Flash is suitable for straightforward work with clear acceptance criteria. These are task-specific recommendations, not a requirement to start every chat at a higher tier.

Custom subagents have their own model settings; choosing a stronger main model does not change the explicit model or reasoning setting in those role definitions. Treat the linked role files as the source of truth for models, reasoning effort, tools, and operating rules.

| Role | Codex definition | Antigravity definition | Delegate when |
| --- | --- | --- | --- |
| Explorer | [explorer](../../.codex/agents/explorer.toml) | [explorer](../../.agents/agents/explorer.md) | A specific file lookup, call-path trace, or coverage question would save the main agent substantial exploration. |
| Scoped implementer | [scoped_implementer](../../.codex/agents/scoped_implementer.toml) | [scoped-implementer](../../.agents/agents/scoped-implementer.md) | The plan is settled and file ownership, required behavior, and completion criteria are explicit. |
| Verifier | [verifier](../../.codex/agents/verifier.toml) | [verifier](../../.agents/agents/verifier.md) | Assigned checks need execution and a compact report of observed results. |
| Fast reviewer | [fast_reviewer](../../.codex/agents/fast_reviewer.toml) | [fast-reviewer](../../.agents/agents/fast-reviewer.md) | DTOs, REST/STOMP mappings, request validation, error contracts, Javadoc, and routine configuration changes. |
| Deep reviewer | [deep_reviewer](../../.codex/agents/deep_reviewer.toml) | [deep-reviewer](../../.agents/agents/deep-reviewer.md) | poker rules, chip conservation, side pots and refunds, RoomCoordinator single-boundary concurrency, async scheduling, authentication/authorization, and encrypted WAL recovery. |

Keep decisions about the deep-review areas with the main agent or a stronger model. A scoped implementer can carry out a settled plan in those areas; a deep review is still required even for a small diff.

## Delegation And Usage

- Handle tiny edits directly. Delegate bounded work when its benefit exceeds the extra agent context and coordination.
- Supply the question or acceptance criteria, relevant evidence, explicit file ownership, allowed checks, and the expected handoff. Reuse existing findings instead of repeating exploration.
- Choose the reviewer whose scope matches the change. Use both reviewers when the change spans both scopes.
- Preserve concurrent edits and give workers distinct file ownership. The main agent integrates results and resolves cross-module decisions.
- Run the smallest relevant checks first. Expand or repeat checks only for an assigned requirement, an intervening change, or an unresolved failure.
- After an unsuccessful fix-and-check attempt, return evidence to the main agent for diagnosis rather than continuing speculative repairs. Report blockers and unfinished checks explicitly.
- Keep handoffs concise: file:line evidence, changed files, observed check results, and unresolved decisions.

Explorer and reviewer work is read-only by instruction. Implementers edit assigned files; verifiers write only normal generated build/test outputs. Parent-session permissions remain authoritative: Codex custom-agent sandbox settings do not override the parent sandbox, and Antigravity subagents inherit the parent's safety boundaries.

Model and reasoning guidance: [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents). Antigravity discovery, model tiers, and permission inheritance: [custom subagents](https://www.antigravity.google/docs/subagents/).
