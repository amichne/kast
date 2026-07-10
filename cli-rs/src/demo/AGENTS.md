# Demo Module Instructions

This directory owns the public repo-native semantic story, its evidence
degradation contract, and the focused source-index exploration UI code.

Keep model types, entrypoints, SQLite access, symbol app state, compare app
state, TUI event loops, rendering, and JSON/compare transformations separated.
The database layer should return typed snapshots rather than UI-specific text.

Keep `kast demo` read-only. Compiler and source-index availability must be
modeled explicitly; never replace missing evidence with fixture or inferred
claims. Public chapters must hand reusable work to typed `kast agent` commands.

Metrics and symbol-query contracts own their own result models and rendering
boundaries.
