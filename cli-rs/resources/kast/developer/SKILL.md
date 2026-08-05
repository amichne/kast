---
name: developer
description: Use when a user explicitly requests Kast setup, runtime control, configuration, local-state inspection, raw RPC, or release engineering beyond the public Kast semantic operations.
---

# Kast developer operations

Use the installed control CLI only for the requested developer operation.

1. Run `kast` from the target workspace.
2. Read `developerOperations.cli` from the result. Treat that exact path as the
   executable authority. Do not assume `kastctl` is on `PATH`.
3. Execute `developerOperations.cli` with `developerOperations.helpArgs` as
   separate arguments before using the control surface. Live help defines the
   available commands. Do not evaluate the path and arguments as a shell string.
4. Pass the target root explicitly when the selected command accepts
   `--workspace-root`.

Keep compiler-backed discovery, diagnostics, graph analysis, and validated
changes on the public `kast` interface. Treat setup, configuration writes,
runtime stop or restart, raw RPC, and release operations as mutations. Run them
only when the user authorized that operation.
