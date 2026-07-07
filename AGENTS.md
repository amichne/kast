# Repository Guidelines

## Project Structure & Module Organization

Kast is an agent-first, compiler-backed Kotlin and Gradle semantic control
plane. Kotlin modules are declared in `settings.gradle.kts`: `analysis-api`
owns the host-agnostic semantic contract and shared models, `analysis-server`
owns JSON-RPC transport and dispatch, `index-store` owns the SQLite-backed
source index, and `backend-headless`, `backend-shared`, and `backend-idea` own
runtime hosts. `cli-rs/` owns the Rust AXI CLI, typed agent commands,
installation, runtime orchestration, source-index CLI reads, release
packaging, and repository agent assets. `build-logic` contains Gradle
convention plugins. `docs/` plus `zensical.toml` are the documentation source;
`site/` is generated output.

Deeper `AGENTS.md` files narrow these rules for their subtrees. Follow the
nearest guide when editing inside a scoped directory.

## Decision Records & Source Of Truth

Use durable agent-only ADRs for product and agent-surface decisions.
`.agents/adr/0006-forward-system-definition-and-audit-scope.md` owns the
current public product surface, system boundaries, supported workflows, AXI
contract, extension points, audit assertions, validation gates, and future
change rule.

When a change expands or contracts the public product surface, add a
superseding ADR before rewriting docs or generated assets. When a change alters
source ownership, generated outputs, or validation gates, update the nearest
scoped `AGENTS.md` in the same change. Agent-only ADRs stay under
`.agents/adr/`; published docs navigation is owned by the docs source.

## Build, Test, and Development Commands

- `./gradlew test` runs the Kotlin/JVM test suite.
- `./gradlew :analysis-api:test` runs a focused module test; replace the module
  name for narrower checks.
- `./gradlew buildIdeaPlugin` builds the IDEA plugin zip.
- `./gradlew installDevelopmentLocal` installs the development CLI and IDEA
  plugin into the configured local profile.
- `cargo test --manifest-path cli-rs/Cargo.toml --locked` runs Rust CLI tests.
- `cargo clippy --manifest-path cli-rs/Cargo.toml --all-targets --all-features -- -D warnings`
  enforces Rust lint cleanliness.
- `.github/scripts/test-docs-content-contract.sh` and `zensical build --clean`
  verify documentation contracts and rendering.

## Coding Style & Naming Conventions

Prefer compiler-enforced invariants over runtime checks or casts. Model the
missing state explicitly when types reveal a gap. Keep Kotlin package names
under `io.github.amichne.kast`, use `*Test.kt` for JVM tests, and keep
dependencies declared through `gradle/libs.versions.toml` where possible. Rust
uses edition 2024; keep CLI modules small, typed, and covered by `cargo fmt`
and `clippy`.

## Testing Guidelines

Use JUnit Jupiter for Kotlin tests under `src/test/kotlin`. Add focused tests
before behavior changes, then broaden to integration or backend tests when a
shared contract moves. Rust smoke and integration tests live in `cli-rs/tests`.
Generated docs, RPC catalogs, and package manifests require their contract
scripts alongside unit tests.

## Commit & Pull Request Guidelines

History uses concise conventional commits such as `fix: ...`, `feat: ...`,
`refactor: ...`, and `docs: ...`. PRs should describe the behavior change,
list verification commands, link issues when relevant, and call out contract,
docs, release, or packaging impact.

## Agent-Specific Instructions

For Kotlin symbol identity, references, hierarchy, and safe edits, use Kast
semantic tooling for compiler-backed evidence. Generated package, protocol,
catalog, and site outputs come from their source owners and contract scripts;
edit the source tree and regenerate.
