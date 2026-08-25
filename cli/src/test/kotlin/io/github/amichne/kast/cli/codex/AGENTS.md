# Codex dynamic-tools spike tests

This package owns the disposable Codex app-server `dynamicTools` spike. It may depend on CLI test
friend access to the canonical wire client, but it must not enter the installed `kast` command
graph or add a semantic implementation.

- Keep exactly the `kast.symbol_resolve` and `kast.relation_read` dynamic tools.
- Refine app-server JSON into existing protocol request types before UDS exchange.
- Preserve the exact selector as an opaque `ProtocolText`; never rebuild it from symbol fields.
- Spawn only `codex app-server`. Any model command-execution item makes the live proof fail.
- Run `./gradlew :cli:test --tests '*CodexDynamicToolsAdapterTest'` before the live spike task.
