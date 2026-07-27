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

- Do not hand-edit installed plugin, skill, or runtime-cache copies. The
  `kast@kast` plugin is authored and published from
  [amichne/kast-marketplace](https://github.com/amichne/kast-marketplace);
  change that repository when the plugin contract changes.
- Route natural-language repository questions through
  `kast agent repository` and persisted topology through `kast agent graph`.
  Read scoped `kast agent --help` before using lower-level semantic commands.
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
.github/scripts/docs/test-docs-content-contract.sh
.github/scripts/docs/test-docs-navigation-contract.sh
git diff --check
```
