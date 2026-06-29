# Output Module Instructions

This directory owns human and JSON rendering for CLI command results.

Keep rendering grouped by result family: core markdown/error output, agent-up,
runtime/package output, readiness, tables, install output, and runtime helper
formatting. Output code must not perform install, runtime, or config mutation.

Do not bury behavior decisions in prose rendering. The command result type
should already encode the state being displayed.
