# Agent-only sources

This tree contains agent-readable source material and local tooling, not
published site content.

- `.agents/adr/` contains only current, durable decisions that are not already
  clear from code, tests, or published documentation.
- `.agents/arch/` contains the current dependency-ordered architecture program,
  not execution transcripts or completed-task evidence.
- `.agents/skills/` contains checked-in or installed skill material used by
  local agents.
- `.agents/marketplaces.md` records marketplace state and explicit enablement
  policy.

Agent-only ADRs must stay out of `docs/` and out of `zensical.toml`.

## Edit rules

- Do not hand-edit installed plugin, skill, or runtime-cache copies. The
  `kast@kast` plugin is authored and published from
  [amichne/kast-marketplace](https://github.com/amichne/kast-marketplace);
  change that repository when the plugin contract changes.
- Kast agent tooling is disabled. Do not use its plugin, hooks, CLI, runtime,
  diagnostics MCP, or duplicate semantic index unless a later task explicitly
  re-enables them after a usefulness review.
- Route Kotlin discovery, code insight, formatting, inspection, and compile
  feedback through the bundled IntelliJ MCP according to root `AGENTS.md`.
  Always target the exact open worktree.
- Keep ADRs current, source-backed, and actionable. Update an ADR in place when
  its decision changes.
- Remove an ADR when its decision no longer affects current or future work, or
  when the decision is fully expressed by code, tests, or published docs. Git
  preserves its history; `.agents/adr/` is not an archive.
- Do not retain superseded ADRs, migration logs, conversation summaries, or
  historical timelines. Keep active task evidence only in ignored
  `.agent/TASK.md`; tests and code own durable completion proof.
- When an agent-only decision changes public docs, update the docs source and
  contract tests separately.

## Verify

Run these checks after changing agent-only ADRs or source-routing guidance:

```console
.github/scripts/docs/test-docs-content-contract.sh
.github/scripts/docs/test-docs-navigation-contract.sh
git diff --check
```
