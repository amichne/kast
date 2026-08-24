# GitHub workflow guide

This directory owns GitHub Actions workflow definitions. Follow [the GitHub guide](../AGENTS.md)
for action versions, permissions, and CI constraints.

## Local scope

- Keep required-check joins lightweight. Reuse existing build jobs for expensive verification.
- Bind pull-request evidence to `github.event.pull_request.head.sha`, not a merge commit.
- Run the release-flow contract in the repository-contract job so release-only
  script drift cannot merge to main.
- Run the isolated public-installer contract in pull-request CI and again
  before a release build mutates or publishes release state.
