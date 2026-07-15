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
`.agents/adr/0006-forward-system-definition-and-audit-scope.md` owns the broader
public product surface, system boundaries, supported workflows, AXI contract,
and audit scope. `.agents/adr/0023-signed-idea-plugin-distribution-and-runtime-authority.md`
supersedes it for IDEA plugin distribution, runtime compatibility, index
privacy, lifecycle, and semantic cockpit contracts.

When a change expands or contracts the public product surface, add a
superseding ADR before rewriting docs or generated assets. When a change alters
source ownership, generated outputs, or validation gates, update the nearest
scoped `AGENTS.md` in the same change. Agent-only ADRs stay under
`.agents/adr/`; published docs navigation is owned by the docs source.

## Signed IDEA Plugin Release Authority

The release tag owns the only production IDEA plugin build. The
`build-idea-plugin` release job must run JetBrains structure and compatibility
verification, sign exactly one ZIP with protected inputs, verify the signature
against the file-backed enrolled certificate, and record signer-bound
provenance before upload. Private keys and passwords remain GitHub secrets;
certificate fingerprints are explicit repository variables and never derive
from a shared release version.

`.github/scripts/upload-immutable-release-asset.sh` owns plugin-asset replay:
upload once, then prove byte identity or fail. It must never use `--clobber`.
`scripts/verify-idea-plugin-artifact.py`, the release provenance assembler, and
`scripts/verify-release-assets.sh` own the plugin ID, digest, signer, signature,
and verification-task evidence. Run
`.github/scripts/test-idea-plugin-signing-contract.sh`,
`.github/scripts/test-release-workflow-contract.sh`,
`.github/scripts/test-release-provenance-assembler.sh`, and
`.github/scripts/test-release-asset-verifier.sh` after changing this boundary.

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
- `.github/scripts/test-macos-installer-contract.sh` verifies the root macOS
  installer command surface, strict validation, tap overrides, and update path.

## Coding Style & Naming Conventions

Prefer compiler-enforced invariants over runtime checks or casts. Model the
missing state explicitly when types reveal a gap. Keep Kotlin package names
under `io.github.amichne.kast`, use `*Test.kt` for JVM tests, and keep
dependencies declared through `gradle/libs.versions.toml` where possible. Rust
uses edition 2024; keep CLI modules small, typed, and covered by `cargo fmt`
and `clippy`.

Production Kotlin source uses one non-private top-level named type per file,
with a filename matching the type. This includes classes, data and value
classes, enums, sealed roots, interfaces, fun interfaces, and named objects.
Keep direct sealed variants, companion objects, and tightly coupled private
implementation helpers with their owner. Top-level functions and extensions
follow semantic ownership; tests may keep private fixtures beside the scenario
that owns them. Apply this rule to new or materially edited code without
triggering unrelated repository-wide file migrations.

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

## Sub-Agent Delegation

Agents may delegate concrete, bounded tasks to sub-agents when doing so is
useful. Delegation is encouraged for independent investigation,
implementation, testing, or review work that can proceed safely in parallel,
but it is never mandatory.

The primary agent remains responsible for scope, coordination, integration,
reviewing delegated results, and final verification. Account for the shared
workspace: do not delegate parallel work that would edit the same files,
depend on unfinished shared state, or otherwise be tightly coupled.

<kast>
## Kast routing
Use `/Users/amichne/code/kast/.agents/skills/kast/SKILL.md` before Kotlin or Gradle semantic work.
Use `kast agent verify --workspace-root "$PWD"` to verify the plugin-prepared workspace.
Use typed commands such as `kast agent symbol --query <name>`, `kast agent diagnostics --file-path <path>`, and `kast agent rename --symbol <fq-name> --new-name <name> --apply`.
Do not run `kast setup` on macOS; the IntelliJ plugin owns workspace bootstrap.
</kast>
