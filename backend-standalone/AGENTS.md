# Standalone backend agent guide

`backend-standalone` owns the headless runtime, CLI parsing, and standalone
backend implementation.

## Ownership

Use this unit for headless host concerns and nowhere else.

- Keep host bootstrapping here: CLI arguments, environment fallbacks, server
  startup, shutdown hooks, and runtime packaging.
- Keep capability advertising conservative. The standalone backend is
  scaffolded and currently implements `APPLY_EDITS` only.
- Do not advertise read capabilities or rename support until a real
  implementation exists end to end.
- Preserve the current CLI contract: `--key=value` arguments,
  `KAST_WORKSPACE_ROOT` and `KAST_TOKEN` fallbacks, and normalized absolute
  workspace roots.
- Reuse shared transport and edit semantics from `analysis-server` and
  `analysis-api` instead of re-implementing them here.

## Verification

Build the standalone host after changes, and add tests when the surface grows.

- Run `./gradlew :backend-standalone:build`.
- If you expand the backend surface, add or update tests that prove the new
  advertised capabilities.
