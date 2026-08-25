# Codex dynamic-tools spike tests

This package owns the disposable Codex app-server `dynamicTools` spike. It may depend on CLI test
friend access to the canonical wire client, but it must not enter the installed `kast` command
graph or add a semantic implementation.

- Keep exactly the `kast.symbol_resolve` and `kast.relation_read` dynamic tools.
- Keep the shared comparison prompt capability-adaptive: inspect deferred Kast tools once, retain
  the resolve result, and chain its opaque selector into relation read in the same exec program;
  use the public CLI only when those dynamic tools are absent.
- Refine app-server JSON into existing protocol request types before UDS exchange.
- Preserve the exact selector as an opaque `ProtocolText`; never rebuild it from symbol fields.
- Spawn only `codex app-server`. Any model command-execution item makes the live proof fail.
- Run the observed CLI comparison in a separate same-model App Server process at the repository
  root with no dynamic tools, shell enabled, and `danger-full-access`; macOS Seatbelt otherwise
  denies the exact-root Unix socket and makes the ready indexer appear stale. Keep the final dynamic
  process shell-disabled and read-only. Inspect the worktree after the comparison.
- Return rejected dynamic-tool responses to App Server and continue to `turn/completed`. Qualified
  relation evidence is not a successful caller answer and must preserve the produced selector for
  a valid retry.
- Emit one combined generated-serializer evidence document with both paths and an explicit
  `go`/`no-go` decision; do not substitute a hard-coded CLI estimate.
- Run `./gradlew :cli:test --tests '*CodexDynamicToolsAdapterTest'` before the live spike task.
