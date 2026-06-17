# ADR agent guide

This file applies to `docs/adr/` and descendants. ADRs here are durable
decision and specification records for the published Kast product story,
documentation operating model, and agent-facing workflow.

## Work here

- Add a new ADR when the product promise, primary reader path, install
  posture, runtime posture, or documentation operating model changes.
- Update an existing ADR only for small clarifications that do not change the
  decision. Use a superseding ADR for a new direction.
- Keep each ADR grounded in current checked-in files, commands, manifests,
  scripts, or explicit user decisions.

## Edit rules

- Use front matter with `title`, `description`, and `icon` so Zensical can
  render the page when it is in navigation.
- State status and date near the top.
- Include source-of-truth files and validation commands when the ADR governs
  a workflow that future agents must maintain.
- Do not turn ADRs into migration logs. Capture the current contract and the
  rule for changing it.

## Verify

Run these checks after adding or changing an ADR that is part of the site:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

Also re-read `docs/AGENTS.md`, `docs/index.md`, and the ADR you changed to
confirm the current reader path and validation loop still agree.
