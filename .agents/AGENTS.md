# Agent-only source guide

This file applies to `.agents/` and descendants. This tree is for
agent-readable source material and local agent tooling, not published site
content.

## Local purpose

- `.agents/adr/` contains durable agent-only decision records.
- `.agents/skills/` contains checked-in or installed skill material used by
  local agents.
- `.agents/marketplaces.md` records local marketplace context.

Agent-only ADRs must stay out of `docs/` and out of `zensical.toml`.

## Edit rules

- Put durable agent decisions under `.agents/adr/`, not under the published
  docs site.
- Do not hand-edit generated or installed skill copies unless the change is
  intentionally to that checked-in source. Prefer authored sources under
  `cli-rs/resources/` for Kast package material.
- Keep ADRs current, source-backed, and actionable for future agents. Avoid
  migration logs and conversation summaries.
- When an agent-only decision changes public docs, update the docs source and
  contract tests separately.

## Verify

Run these checks after changing agent-only ADRs or source-routing guidance:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
```
