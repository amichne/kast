# Agent-only sources

This tree contains agent-readable source material and local tooling, not
published site content.

- `.agents/skills/` contains checked-in or installed skill material used by
  local agents.
- `.agents/marketplaces.md` records local marketplace context.

## Edit rules

- Do not hand-edit installed plugin, skill, or runtime-cache copies. The
  `kast@kast` plugin is authored and published from
  [amichne/kast-marketplace](https://github.com/amichne/kast-marketplace);
  change that repository when the plugin contract changes.
- Read `kast --help` and the scoped domain help before invoking semantic
  operations against this repository.
- Do not retain superseded migration logs, conversation summaries, or
  historical timelines.

## Verify

Run this check after changing agent-only source-routing guidance:

```console
git diff --check
```
