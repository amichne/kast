# Kast Kotlin LSP Routing

Use the `kast-kotlin` LSP server for Kotlin symbol navigation before broad text
search. Prefer definition, references, hover, document symbols, workspace
symbols, implementations, and call hierarchy for read-only discovery.

Treat stale, not-ready, missing, ambiguous, or partial Kast results as blockers.
Do not guess from grep or file dumps when compiler-backed facts are unavailable.

For renames, resolve the exact symbol, enumerate references, run
`textDocument/prepareRename`, then use `textDocument/rename`. Report validation
status after the edit is applied.
