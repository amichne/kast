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
owns runtime compatibility, index privacy, lifecycle, and semantic cockpit
contracts. `.agents/adr/0028-unsigned-github-idea-plugin-distribution.md`
supersedes its IDEA distribution and publication decisions.

When a change expands or contracts the public product surface, add a
superseding ADR before rewriting docs or generated assets. When a change alters
source ownership, generated outputs, or validation gates, update the nearest
scoped `AGENTS.md` in the same change. Agent-only ADRs stay under
`.agents/adr/`; published docs navigation is owned by the docs source.

## Revision-Coherent Contract Evolution

ADR 0024 makes the local-development CLI, backend, skill, guidance, and
configuration one attested source generation. Inside an execution path that
proves those components came from the same revision, treat their private
protocol and data model as one atomic contract. Prefer a clean replacement to
backward-compatibility scaffolding: required fields may become required,
internal variants may be renamed or removed, and both sides must move together
with their generators, tests, and installed-workflow proof.

Do not add nullable defaults, legacy decoders, dual-read or dual-write paths,
deprecated aliases, or fallback behavior solely to let revision-coherent
components consume payloads from an older peer that the authority model cannot
select. If a value may legitimately outlive its issuing generation but should
not migrate, bind it to the revision or generation and reject it with a typed,
actionable stale or incompatible outcome.

This clean-break authority does not automatically cover public documented
interfaces, user-owned persisted data, release or rollback paths, external
integrations, or any boundary whose components can actually run at different
revisions. Those surfaces still require an explicit migration, compatibility
decision, or superseding ADR. When revision coherence cannot be proved, fail
closed and treat compatibility as a deliberate product decision rather than an
accidental parser feature.

## GitHub IDEA Plugin Release Authority

The release tag owns the only production IDEA plugin build. The
`build-idea-plugin` release job builds one unsigned ZIP with
`:backend-idea:buildPlugin`, renders `packaging/jetbrains/updatePlugins.xml`
with the exact release tag and version, and uploads both files to the draft
GitHub release. It must not require Marketplace verification, signing inputs,
certificate enrollment, IDEA provenance, GitHub Pages, or immutable-release
configuration. Stable releases update the matching Homebrew CLI before making
the draft public so the IDE's `latest` feed cannot advertise an unavailable
CLI pair.

`install.sh` may delegate initial plugin installation to a closed IDE through
its supported `installPlugins` command and the installed CLI's
version-specific feed. That command must not be presented as an updater because
JetBrains skips an already-installed plugin. Existing updates remain IDE-owned.
The script must not write plugin directories or recreate profile links. Run
`.github/scripts/test-release-workflow-contract.sh` and
`.github/scripts/test-macos-installer-contract.sh` after changing this
boundary.

## Build, Test, and Development Commands

- `./gradlew test` runs the Kotlin/JVM test suite.
- `./gradlew :analysis-api:test` runs a focused module test; replace the module
  name for narrower checks.
- `./gradlew buildIdeaPlugin` builds the IDEA plugin zip.
- `./gradlew refreshDevelopmentLocal` builds and atomically activates the
  worktree-local headless authority without changing Homebrew or JetBrains.
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

Headless Gradle import settlement keeps its timeout and stability policy in
typed Kotlin with deterministic fake-time tests. Required pull-request
semantics use the small authored multi-module fixture and an already-prepared
generation; the full repository Kast-on-Kast workflow remains an integrated
canary.

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
Before each linked worker starts, open the exact worktree root as its own IDE project and run `kast agent verify --workspace-root "$PWD"` from that worktree.
Never reuse another worktree's Kast runtime, metadata, or semantic evidence.
Keep the IDE project open while active; close its exact IDE project or window before removing the worktree.
</kast>
