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

Agent-facing semantic flows use `kast agent verify`, `kast agent symbol`,
`kast agent diagnostics`, `kast agent impact`, `kast agent rename`, and
`kast agent lsp`.
