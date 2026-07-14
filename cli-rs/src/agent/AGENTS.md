# Agent Module Instructions

This directory owns pipe-friendly typed `kast agent` behavior.

Keep command dispatch, internal catalog projection, package verification,
request input normalization, response envelopes, and typed command execution
in separate part files. Public agent commands are typed noun/verb operations
with bounded flags and structured output.

Keep compact public result projection families under `projection/`; the
`projection.rs` wrapper owns only their deterministic include order. Shared
view routing and envelope helpers stay in `view.rs` and `common.rs`, while
symbol, impact, diagnostics, verification, and mutation evidence remain in their
same-named family files.

Agent-facing semantic flows use `kast agent verify`, `kast agent
workspace-files`, `kast agent symbol`, `kast agent diagnostics`, `kast agent
impact`, `kast agent rename`, and `kast agent lsp`.

`workspace_files.rs` owns exact-root admission, typed conjunctive discovery
filters, the query-bound public continuation, and command execution.
`projection/workspace_files.rs` owns compact, selected-field, count, verbose,
and explain views. `public_capabilities.rs` owns the callable public route
registry used by verification. Keep backend raw paging internal and preserve
the distinction between source and script lane relevance, candidate and filter
coverage, build-qualified Gradle owners, and proven or unproven package and
source-set evidence. Public continuation binds the exact root, backend,
normalized query, result projection, limit, and discriminated composition
stamp; invalid or stale state must fail instead of restarting at page one.
