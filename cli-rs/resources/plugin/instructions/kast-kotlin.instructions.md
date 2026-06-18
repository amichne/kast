---
applyTo: "**/*.{kt,kts}"
---

# Kast Kotlin Routing

For every Kotlin file, start with the `kotlin` LSP server from
`.github/lsp.json`. Use LSP definition, references, hover, document symbols,
workspace symbols, implementations, call hierarchy, type hierarchy, prepare
rename, and rename before broad text search or manual file inspection.

When delegating, use `kast-reader` for read-only analysis and `kast-writer` for
edits. Both subagents must keep using `kotlin` LSP and the catalog-backed
`kast_*` tools; do not delegate Kotlin work to a general-purpose subagent that
lacks those tools.

For Kast-specific work outside standard LSP, inspect
`capabilities.experimental.kastMethods` from the LSP `initialize` response and
use the advertised `kast/*` methods before native tools or shell fallbacks.

Prefer the narrow funnel:

1. `symbol/query` or LSP workspace symbols for unknown names.
2. `symbol/resolve`, LSP definition, or hover for exact identity.
3. `symbol/references`, `symbol/callers`, LSP references, or hierarchy before
   impact claims.
4. `symbol/rename` or LSP prepare-rename/rename before any text edit.
5. `raw/*` only when you already have the exact file, offset, or bounded file
   list that the raw method requires.

Treat stale, not-ready, missing, ambiguous, partial, or truncated compiler facts
as blockers. Do not replace them with grep, recursive file dumps, or guesses.
