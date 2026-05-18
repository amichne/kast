# Align CLI Cleanup And Remove `workspace *`

## Summary

Collapse the CLI lifecycle surface to the short commands only: `up`, `status`,
and `stop`. Remove the `workspace` command namespace entirely, including the
already-obsolete `workspace refresh` and `workspace files` entries.

## Key Changes

- Delete `workspace status`, `workspace ensure`, `workspace stop`,
  `workspace refresh`, and `workspace files` metadata from `CliCommandCatalog`.
- Delete `CliCommand.WorkspaceStatus`, `CliCommand.WorkspaceEnsure`, and
  `CliCommand.WorkspaceStop`, plus parser and executor branches for
  `workspace *`.
- Keep the underlying runtime service methods and result types, because `up`,
  `status`, `stop`, `capabilities`, and `rpc` still use the same daemon
  lifecycle implementation.
- Update help copy so daemon lifecycle docs point to `kast up`, `kast status`,
  and `kast stop`; update `daemon start` help to say
  `kast up --workspace-root=<path>` verifies readiness.
- Replace all docs that mention `kast workspace ensure/status/stop` with
  `kast up/status/stop`.
- Replace `kast workspace refresh` docs with
  `kast rpc '{"jsonrpc":"2.0","method":"raw/workspace-refresh",...}'`.
- Replace `kast workspace files` docs with
  `kast rpc '{"jsonrpc":"2.0","method":"raw/workspace-files",...}'`.
- Continue the previous alignment cleanup: remove stale direct
  analysis/mutation/metrics metadata, `removedCommandPaths`, and unused parser
  request builders.

## TDD Tracer Bullets

- First tracer: update parser/help tests so `workspace ensure`,
  `workspace status`, `workspace stop`, `workspace refresh`, and
  `workspace files` are unknown, while `up`, `status`, and `stop` still parse
  and execute.
- Second tracer: add a catalog integrity test that no active or inactive
  metadata path starts with `workspace`, `skill`, `metrics`, or any removed
  direct command.
- Third tracer: remove the dead catalog entries and command variants until the
  tests pass without compatibility filters.
- Fourth tracer: update docs tests or add a lightweight grep-style guard that
  forbids `kast workspace ` in source docs except when discussing removed legacy
  commands.

## Test Plan

- `./gradlew :kast-cli:test`
- `./gradlew :analysis-server:test`
- `./gradlew test`
- Manual checks:
  - `./kast-cli/build/scripts/kast-cli help` shows `up`, `status`, and `stop`,
    but no `workspace` group.
  - `./kast-cli/build/scripts/kast-cli help workspace` returns unknown.
  - `./kast-cli/build/scripts/kast-cli up --workspace-root=$(pwd)
    --accept-indexing=true` still works.
  - `./kast-cli/build/scripts/kast-cli rpc '{"jsonrpc":"2.0","method":"health","id":1}' --workspace-root=$(pwd)`
    still works.

## Assumptions

- The supported lifecycle CLI is `up`, `status`, and `stop`.
- `workspace refresh` and `workspace files` remain available only as RPC
  methods: `raw/workspace-refresh` and `raw/workspace-files`.
- No backwards compatibility is required for the `workspace *` command
  namespace.
