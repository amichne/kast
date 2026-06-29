# Demo Module Instructions

This directory owns source-index inspection demos and comparison UI code.

Keep model types, entrypoints, SQLite access, symbol app state, compare app
state, TUI event loops, rendering, and JSON/compare transformations separated.
The database layer should return typed snapshots rather than UI-specific text.

Do not let demo rendering rules leak into metrics or symbol-query contracts.
