# Output Module Instructions

This directory owns human and JSON rendering for CLI command results.

Keep rendering grouped by result family: core markdown/error output, typed
agent commands, runtime, readiness, tables, install, package, and runtime
helper formatting. Command modules own install, runtime, and config mutation.

Command result types encode the state being displayed; renderers project that
state into human, TOON, or JSON output.
