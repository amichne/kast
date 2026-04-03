# Kast CLI agent guide

`kast` owns the repo-local CLI, wrapper packaging, helper binary, and daemon
control plane.

## Ownership

Keep public CLI behavior here and nowhere else.

- Keep public command behavior here: command catalog, argument parsing, help
  text, JSON serialization, and stderr daemon notes.
- Preserve the public contract that successful command results stay machine-
  readable JSON on stdout while human-readable daemon status notes go to
  stderr.
- Keep detached-runtime orchestration here. `WorkspaceRuntimeManager` owns
  descriptor discovery, readiness checks, daemon start and stop, and backend
  selection.
- Keep wrapper and helper behavior aligned with the packaging layout.
  `src/helper/kast_helper.c` builds the native launcher; `build/scripts/`,
  `build/libs/`, and `build/runtime-libs/` are generated outputs.
- Keep the hidden `internal daemon-run` path internal. Changes that affect the
  standalone process contract must stay aligned with `backend-standalone` and
  `analysis-server`.

## Verification

Prove CLI changes at the module boundary before you rely on downstream fixes.

- Run `./gradlew :kast:test` for CLI behavior changes.
- If you touch the wrapper, helper, or packaging layout, also run
  `./gradlew :kast:syncRuntimeLibs :kast:writeWrapperScript` or `./build.sh`.
