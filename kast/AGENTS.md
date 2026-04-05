# Kast CLI agent guide

`kast` owns the JVM-only CLI shell, wrapper packaging, and the real
`internal daemon-run` implementation.

## Ownership

Keep JVM-only launch behavior and packaging here. Shared CLI behavior belongs in
`kast-cli`.

- Keep the JVM shell here: `JvmCliMainKt` wires `kast-cli` to
  `backend-standalone` by supplying the real `internal daemon-run` runner.
- Keep wrapper and portable distribution behavior aligned with the packaging
  layout. `build/scripts/`, `build/bin/`, `build/libs/`, and
  `build/runtime-libs/` are generated outputs.
- Keep native-binary-first launcher behavior here. The wrapper must stay
  aligned with the colocated `runtime-libs` fallback and packaged skill assets.
- Keep the hidden `internal daemon-run` path internal. Changes that affect the
  standalone process contract must stay aligned with `kast-cli`,
  `backend-standalone`, and `analysis-server`.

## Verification

Prove JVM-shell and packaging changes here before you rely on installers or
portable builds.

- Run `./gradlew :kast:compileKotlin` for JVM shell changes.
- If you touch the wrapper or packaging layout, also run
  `./gradlew :kast:syncRuntimeLibs :kast:writeWrapperScript :kast-cli:test` or
  `./build.sh`.
- For local/dev packaging workflows, prefer `./build.sh --help` and use the
  supported install flags (`--install`, `--no-install`, `--instance <name>`)
  instead of invoking install helpers directly.
- If you validate a named local instance launcher, run
  `./scripts/validate-instance.sh <name>` (it wraps the repo smoke script
  against `~/.local/bin/kast-<name>`).
