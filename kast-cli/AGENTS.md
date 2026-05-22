# Kast CLI shared agent guide

`kast-cli` owns the legacy JVM operator-facing wrapper. The non-JVM CLI lives
in the Rust `kast-rs` project and is the release CLI path.

## Ownership

Use this unit for public CLI behavior that does not require the standalone
backend implementation itself.

- Keep public command behavior here: command catalog, argument parsing, help
  text, JSON serialization, install flows, and stderr daemon notes.
- Keep detached-runtime orchestration here only while the JVM wrapper still
  needs it for compatibility. The Rust CLI is the owner for non-JVM command
  execution.
- Keep the hidden `internal daemon-run` path abstract here. The shared CLI can
  parse the command and report unsupported use, but only `kast` provides the
  JVM runner.
- Do not add new GraalVM native-image wiring here. Native release assets are
  built from the Rust CLI.

## Verification

Prove shared CLI changes here before you rely on the JVM shell or backend.

- Run `./gradlew :kast-cli:test` for CLI behavior changes.
- If you change public CLI wiring or cross-module launch behavior, also run
  `./gradlew :kast-cli:compileKotlin`.
- If you change packaged skill or Copilot extension resources, also run
  `./gradlew :kast-cli:processResources` and inspect the generated bundle under
  `kast-cli/build/resources/main/`.
