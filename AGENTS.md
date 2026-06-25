# Repository Guidelines

## Project Structure & Module Organization

Kast is a mixed Kotlin/Gradle and Rust project. Kotlin modules are declared in
`settings.gradle.kts`: `analysis-api` owns shared contracts and models,
`analysis-server` owns JSON-RPC dispatch, `index-store` owns SQLite-backed
indexing, and `backend-headless`, `backend-shared`, and `backend-idea` own the
runtime hosts. `build-logic` contains Gradle convention plugins. `cli-rs/` is
the Rust CLI and installer. `docs/` plus `zensical.toml` are the documentation
source; `site/` is generated output. Copilot and skill source lives under
`cli-rs/resources/plugin/` and `cli-rs/resources/kast-skill/`.

Deeper `AGENTS.md` files narrow these rules for their subtrees. Follow the
nearest guide when editing inside a scoped directory.

## Decision Records & Source Of Truth

Use durable agent-only docs for product and agent-surface decisions instead of
preserving conversation-only context. `.agents/adr/0001-agent-first-install-and-docs-operating-model.md`
owns the global binary, repository Copilot, and docs operating model.
`.agents/adr/0002-agent-resource-and-workflow-source-of-truth.md` owns
manifest-backed agent resources, `kast agent workflow`, and the rule that stale
binary/resource combinations fail loudly and require upgrade or reinstall.

When a change alters source ownership, generated outputs, or validation gates,
update the nearest scoped `AGENTS.md` in the same change. Agent-only ADRs stay
under `.agents/adr/` and must not be added to the published docs nav.

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

Prefer compiler-enforced invariants over runtime checks or casts. Do not work
around type errors; model the missing state explicitly. Keep Kotlin package
names under `io.github.amichne.kast`, use `*Test.kt` for JVM tests, and keep
dependencies declared through `gradle/libs.versions.toml` where possible. Rust
uses edition 2024; keep CLI modules small, typed, and covered by `cargo fmt`
and `clippy`.

## Testing Guidelines

Use JUnit Jupiter for Kotlin tests under `src/test/kotlin`. Add focused tests
before behavior changes, then broaden to integration or backend tests when a
shared contract moves. Rust smoke and integration tests live in `cli-rs/tests`.
Generated docs, RPC catalogs, and package manifests require their contract
scripts, not just unit tests.

## Commit & Pull Request Guidelines

History uses concise conventional commits such as `fix: ...`, `feat: ...`,
`refactor: ...`, and `docs: ...`. PRs should describe the behavior change,
list verification commands, link issues when relevant, and call out contract,
docs, release, or packaging impact.

## Agent-Specific Instructions

For Kotlin symbol identity, references, hierarchy, and safe edits, use Kast
semantic tooling when available instead of text search. Treat `.github/`,
`.agents/`, and `site/` package or site material as generated unless a scoped
guide says otherwise; edit the source tree and regenerate.
