# Agent Module Instructions

This directory owns pipe-friendly typed `kast agent` behavior.

Keep command dispatch, internal catalog projection, package verification,
request input normalization, response envelopes, and typed command execution
in separate part files. Public agent commands are typed noun/verb operations
with bounded flags and structured output.

Agent-facing semantic flows use `kast agent verify`, `kast agent symbol`,
`kast agent diagnostics`, `kast agent impact`, `kast agent rename`, and
`kast agent lsp`.
