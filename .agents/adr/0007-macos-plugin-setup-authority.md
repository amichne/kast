# ADR 0007: macOS plugin setup authority

Status: Accepted

Date: 2026-07-07

This ADR supersedes the macOS developer-machine setup portions of ADR 0006. All
non-macOS headless/server distribution rules from ADR 0006 remain in force.

## Decision

On macOS, Homebrew is the distribution mechanism and the IntelliJ plugin is the
developer workstation setup authority. The Homebrew package provides the Kast
binary and version-coupled IDEA or Android Studio plugin artifact together. The
Rust CLI is the execution surface after the workspace has already been prepared
by the plugin.

macOS workspace setup is valid only when the active workspace contains
plugin-prepared metadata, skill guidance, invocation details, and recognized
artifacts for the incoming version. Skill-only, runtime-only, resource-only, and
CLI-authored partial setup states are not supported.

The IntelliJ plugin owns project-open bootstrap:

- detect a Gradle workspace;
- verify the expected local Kast binary and plugin version;
- write the skill-facing instruction entrypoint and managed guidance region;
- write workspace metadata with the exact invocation and IDEA backend socket;
- back up and remove prior active Kast-managed artifacts that are not required
  or tolerated by the incoming version.

Delegated agents that use linked Git worktrees apply the same authority to each
exact worktree root. The coordinating agent must open every worker worktree as
its own IntelliJ IDEA or Android Studio project, wait for plugin-prepared
metadata, and run `kast agent verify --workspace-root "$PWD"` from that root
before the worker starts. A worker must not reuse another worktree's runtime,
metadata, or semantic evidence.

The IDE project remains open while its worker and worktree are active. Before a
worktree is retired or removed, the coordinating agent closes that exact IDE
project or window first. The plugin-installed skill and managed guidance region
must teach this setup and teardown lifecycle to every prepared workspace.

Unknown prior state is unmanaged state. Every incoming version must explicitly
recognize, require, tolerate, migrate, or remove prior managed artifacts. If a
backup, removal, metadata write, version check, or binary check fails, activation
fails closed and the IDEA backend must not start.

## Public Surface

The macOS user path is:

```console
brew install amichne/kast/kast
kast developer machine plugin
```

Then the user opens the workspace in IntelliJ IDEA or Android Studio with the
Kast plugin enabled. The plugin prepares the workspace; `kast agent verify` and
other typed agent commands run only after that valid setup exists.

On macOS, `kast setup`, hidden `kast agent setup ...`, and direct skill,
instruction, or Copilot resource install commands fail closed with replacement
guidance. They must not write skill-only or resource-only state.

## Source Owners

- Plugin workspace bootstrap: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastProjectOpenProfileAutoInit.kt`
- Plugin activation gate: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastProjectOpenAutoIndexing.kt`
- macOS CLI setup/resource refusal: `cli-rs/src/main.rs` and `cli-rs/src/cli/root.rs`
- macOS readiness metadata check: `cli-rs/src/self_mgmt.rs`
- Homebrew plugin install/repair: `cli-rs/src/install/`
- Public docs and skill guidance: `README.md`, `docs/`, and `cli-rs/resources/kast-skill/`

`PluginWorkspaceBootstrap` is the source of truth for the thin repo-local Kast
skill and managed guidance installed by the plugin. Its contract tests must
prove the delegated-worktree setup, isolation, lifetime, and teardown rules are
present in both outputs.

## Validation Gates

At minimum, changes to this surface must run:

```console
./gradlew :backend-idea:test
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_setup_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test ready_repair_smoke
.github/scripts/test-docs-content-contract.sh
```

Broaden to full `cargo test --manifest-path cli-rs/Cargo.toml --locked`,
`cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings`,
and docs rendering when shared install, output, or docs contracts move.
