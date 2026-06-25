# ADR agent guide

This file applies to `.agents/adr/` and descendants. ADRs here are durable
agent-only decision and specification records for repository agents. They are
not part of the published Zensical site.

## Work here

- Add a new ADR when the product promise, primary reader path, install
  posture, runtime posture, documentation operating model, agent resource
  ownership, or agent workflow contract changes.
- Update an existing ADR only for small clarifications that do not change the
  decision. Use a superseding ADR for a new direction.
- Keep each ADR grounded in current checked-in files, commands, manifests,
  scripts, or explicit user decisions.
- Keep ADR 0002 current when agent package ownership, install manifest resource
  trust, workflow commands, or compatibility posture changes.

## Edit rules

- Do not add Zensical front matter or navigation entries. These records are for
  agents, not the public pages site.
- State status and date near the top.
- Include source-of-truth files and validation commands when the ADR governs
  a workflow that future agents must maintain.
- Do not turn ADRs into migration logs. Capture the current contract and the
  rule for changing it.

## Verify

Run these checks after adding or changing an agent ADR:

```console
.github/scripts/test-docs-content-contract.sh
```

Also re-read `AGENTS.md`, `.agents/docs/AGENTS.md`, and the ADR you changed to
confirm the current reader path, source-of-truth map, and validation loop still
agree.
