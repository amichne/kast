---
name: developer
description: Use when a user explicitly requests Kast setup, runtime control, configuration, local-state inspection, raw RPC, repair, or release engineering beyond the public semantic operations; do not use for public semantic work.
---

# Kast developer operations

Use the installed control CLI only for the requested developer operation.

1. Run `kast` from the target workspace. Confirm that it selected the intended
   root.
2. Read `developerOperations.cli` and `developerOperations.helpArgs`. Treat the
   exact path and each help argument as separate process arguments. Do not
   evaluate them as a shell string. Do not assume `kastctl` is on `PATH`.
3. Run that live help before selecting a control command. Do not reuse command
   names or arguments from memory.
4. Pass the target root explicitly when the selected command accepts
   `--workspace-root`.

If an operation fails, preserve its error code, message, `limitation`, and
`next` fields. Resolve the failed predicate or run its exact suggested action;
do not substitute a generic reset, restart, refresh, or repair.

Keep compiler-backed discovery, diagnostics, graph analysis, and validated
changes on the public `kast` interface. Treat setup, configuration writes,
runtime stop or restart, raw RPC, repair, and release operations as mutations.
Run them only when the user authorized that exact operation.

Present the result as outcome, decisive evidence or blocker, and next action.
Omit unrelated state and full help output after the required command is known.
