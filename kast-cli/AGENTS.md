# Kast CLI shared agent guide

`kast-cli` owns the shared operator-facing CLI that runs in both the GraalVM
native binary and the JVM shell.

## Ownership

Use this unit for public CLI behavior that does not require the standalone
backend implementation itself.

- Keep public command behavior here: command catalog, argument parsing, help
  text, JSON serialization, install flows, and stderr daemon notes.
- Keep detached-runtime orchestration here when it can run from either the
  native binary or the JVM shell. `WorkspaceRuntimeManager`, `ProcessLauncher`,
  and the socket RPC client stay here.
- Keep the hidden `internal daemon-run` path abstract here. The shared CLI can
  parse the command and report unsupported use, but only `kast` provides the
  JVM runner.
- Keep the native-image entrypoint and its configuration here. Changes that
  affect `CliMainKt`, `ProcessLauncher`, or
  `META-INF/native-image/io.github.amichne.kast/kast-cli/` must stay aligned
  with the packaged `runtime-libs` layout in `kast`.

## Verification

Prove shared CLI changes here before you rely on the JVM shell or backend.

- Run `./gradlew :kast-cli:test` for CLI behavior changes.
- If you change public CLI wiring or cross-module launch behavior, also run
  `./gradlew :kast-cli:compileKotlin :kast:compileKotlin`.
- If you change native-image wiring and GraalVM is available, also run
  `./gradlew :kast-cli:nativeCompile`.
