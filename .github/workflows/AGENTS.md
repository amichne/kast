# GitHub workflow guide

This directory owns GitHub Actions workflow definitions. Follow [the GitHub guide](../AGENTS.md)
for action versions, permissions, and CI constraints.

## Local scope

- Keep required-check joins lightweight. Reuse existing build jobs for expensive verification.
- Bind pull-request evidence to `github.event.pull_request.head.sha`, not a merge commit.
- Keep documentation validation and Pages deployment in `docs.yml`. The build
  job owns the `site/` artifact, and only the deploy job receives Pages write
  permissions.
