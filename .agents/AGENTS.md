# Agent-only sources

This tree contains agent-readable source material and local tooling, not
published site content.

- `.agents/adr/` contains only current, durable decisions that are not already
  clear from code, tests, or published documentation.
- `.agents/skills/` contains checked-in or installed skill material used by
  local agents.
- `.agents/marketplaces.md` records local marketplace context.

Agent-only ADRs must stay out of `docs/` and out of `zensical.toml`.

## Edit rules

- Do not hand-edit generated or installed skill copies unless the change is
  intentionally to that checked-in source. Prefer authored sources under
  `cli-rs/resources/` for Kast package material.
- Keep ADRs current, source-backed, and actionable. Update an ADR in place when
  its decision changes.
- Remove an ADR when its decision no longer affects current or future work, or
  when the decision is fully expressed by code, tests, or published docs. Git
  preserves its history; `.agents/adr/` is not an archive.
- Do not retain superseded ADRs, migration logs, conversation summaries, or
  historical timelines.
- When an agent-only decision changes public docs, update the docs source and
  contract tests separately.

## Verify

Run these checks after changing agent-only ADRs or source-routing guidance:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
```
