# ADR 0026: Codex CLI plugin and Rust exposure authority

Status: Accepted

Date: 2026-07-17

This ADR records the accepted Codex integration contract for Kast. It
supersedes the Codex-facing portions of ADR 0006 and ADR 0023 where those
records treated hooks or provider packages as hidden implementation detail or
did not name a Codex release artifact. Their compiler-backed product,
plan-first mutation, exact-worktree, signed IDEA distribution, typed runtime
compatibility, and immutable release requirements remain in force.

## Decision

Kast ships one Codex plugin named `kast` from one repository marketplace named
`kast`. Its install identity is `kast@kast`. The plugin is a CLI adapter, not a
new semantic service.

The plugin contains exactly these functional surfaces:

- one thin `kast-codex` routing skill;
- default-discovered command hooks in `hooks/hooks.json`;
- one launcher that resolves the active `kast` executable and forwards the
  hook event and standard input; and
- generated metadata, command references, examples, recovery messages, and
  contract fixtures.

It contains no MCP manifest or server, app connector, custom agent profile,
raw RPC surface, copied command catalog, or provider-specific semantic
protocol. Kotlin semantic work continues to cross the typed Rust CLI and the
existing backend contract. Codex hook responses use only the hook control
envelope required by Codex; they are not a second semantic result dialect.

The provider-neutral `cli-rs/resources/kast-skill/SKILL.md` remains the owner
of installation, readiness, repair, and exact-worktree preparation guidance.
The Codex plugin skill teaches only compiler-backed inspection and plan-first
semantic mutation. The GitHub Copilot package in `cli-rs/resources/plugin/`
remains an independent distribution surface.

## Exhaustive exposure contract

Rust owns the complete decision about which existing CLI commands Codex may
route. The contract is modeled as:

```rust
enum CodexExposure {
    AgentVisible(CodexSemanticCommand),
    HookOnly(CodexHookCommand),
    NotExposed,
}
```

The classifiers over `Command`, `AgentCommand`, `AgentOperationCommand`, and
developer subcommands use exhaustive matches without wildcard arms. Adding a
CLI variant therefore causes a compiler error until its Codex exposure is
chosen deliberately.

`CodexSemanticCommand` contains exactly:

- `workspace-files`;
- `symbol`;
- `references`, `callers`, `callees`, `implementations`, `hierarchy`, and
  `impact`;
- `diagnostics`;
- `rename`, `add-file`, `add-declaration`, `add-implementation`,
  `add-statement`, and `replace-declaration`; and
- `operation status` and `operation cancel`.

Typed descriptors bind every visible command to its command path, read or
mutation mode, plan/apply behavior, required evidence, and examples. Those
descriptors generate the plugin reference; Clap help text and an authored JSON
catalog are not Codex contract authorities.

The hook-only surface contains version, context, readiness, plan-only repair,
status, semantic verification, and `developer codex hook <event>`. Setup,
repair application, LSP, runtime management, demos, compatibility aliases,
unrelated developer and release commands, retired catalog/workflow calls, and
the generator itself are not available to normal Codex routing.

## Generated plugin contract

`kast developer codex generate` renders the marketplace and plugin contract
from the compiling binary. `--check` renders to an isolated location and
fails on any byte-level drift from committed generated files. Release
generation uses the release-built binary's `KAST_VERSION`; it accepts no
caller-provided version override.

Generation is deterministic: output is sorted, UTF-8, LF-terminated, and free
of timestamps, host paths, and environment-specific content. It owns:

- `cli-rs/resources/codex-plugin/marketplace.json`;
- the byte-identical `.agents/plugins/marketplace.json` discovery manifest
  required when the extracted directory is registered with Codex;
- the plugin manifest and `hooks/hooks.json`;
- the skill command reference and examples;
- exposure and recovery-message assets; and
- package and routing fixtures.

The manifest omits `hooks` and relies on default `hooks/hooks.json` discovery.
It also omits `mcpServers` and `apps`. The plugin and CLI release versions are
identical. That equality proves release identity; it does not replace ADR
0023's typed CLI/IDE/backend compatibility admission.

## Hook execution and state

The launcher accepts one generated event name, resolves an executable absolute
`KAST_CODEX_BINARY` override for tests or otherwise resolves `kast` from
`PATH`, and executes `kast developer codex hook <event>`. It does not parse
hook input, make policy decisions, store state, or transform output.

Rust supports these event names:

- `session-start`;
- `subagent-start`;
- `pre-tool-use`;
- `post-tool-use`; and
- `stop`.

Hook state is atomic, owner-readable JSON under
`$PLUGIN_DATA/sessions/<session-id>.json`. It records the schema and release
identity, resolved binary, canonical workspace and linked-worktree identity,
baseline Kotlin hashes, typed command attempts and outcomes, affected files,
operation IDs, target-bound fallback eligibility, current-hash diagnostics,
and explicit blockers. Pre-existing Kotlin dirt is baseline evidence, not a
change made by the current task.

`SessionStart` checks version coherence, readiness, exact-worktree preparation,
and the baseline. A compact recovery rehydrates the same session and preserves
the original baseline. `SubagentStart` emits the exact root and linked-worktree
context. `PreToolUse` denies a known generic Kotlin mutation until the matching
typed route reports an unsupported or typed-failure outcome for that target.
`PostToolUse` records structured Kast outcomes. `Stop` continues the turn when
newly changed Kotlin lacks diagnostics for its current hash and lacks an
explicitly reported typed blocker.

Hooks may perform read-only readiness checks and produce repair plans. They
must never apply setup, repair, source, IDE, or installation mutations.

## Distribution and release authority

The Codex plugin is produced from the same release tag as the CLI and IDEA
plugin. The `build-codex-plugin` job generates and validates
`kast-codex-plugin-<tag>.zip`, records `platformId: codex-plugin` provenance in
`build-ledger-codex-plugin.json`, and uploads the asset through the immutable
release uploader. The archive and its digest are included in `SHA256SUMS` and
the release provenance.

The archive root contains `marketplace.json`, a byte-identical
`.agents/plugins/marketplace.json` discovery manifest, and `plugins/kast/`.
Package validation proves the two marketplace projections are identical,
manifest and marketplace version parity, executable launcher permissions,
expected generated files, and absence of MCP, app, agent-profile, raw-catalog,
and RPC payloads. Release publication must prove one version across the CLI,
IDEA artifact, Codex manifest, exposure asset, and provenance before making
the release final.

Local development may append one `+codex.<token>` cachebuster while preserving
the binary's base semantic version. A cachebuster is not a release version and
must not accumulate suffixes.

## Installation and migration

The release archive is a non-default local marketplace and must be registered
before `codex plugin add kast@kast`. A new Codex task is required after an
install or update so hook and skill discovery use one plugin generation.

The repository or workspace `kast` skill remains supported. Repair may plan
cleanup of a legacy global `~/.codex/skills/kast` only when a Kast receipt
proves ownership. Applying that plan requires explicit user authority and
backs up the recognized legacy copy before removal. Unknown user-owned copies
are reported and left unchanged. Hooks may report this repair plan but cannot
apply it.

## Privacy and terms

The plugin executes the locally selected Kast binary and does not contain a
remote connector. It stores only the bounded session evidence needed for hook
recovery and diagnostics enforcement under `$PLUGIN_DATA`. The public privacy
notice is owned by `docs/privacy.md`; the public terms, including the MIT
license and warranty boundary, are owned by `docs/terms.md`. Their published
URLs are generated into the plugin manifest.

## Source ownership

| Contract | Authored owner | Generated or consuming surface |
| --- | --- | --- |
| Exposure classification and descriptors | `cli-rs/src/codex/exposure.rs` | Generated command reference, examples, and exposure asset |
| Developer CLI and generator | `cli-rs/src/cli/codex.rs` and `cli-rs/src/codex/generate.rs` | Generated marketplace, plugin payload, and fixtures |
| Hook parsing, policy, state, and control output | `cli-rs/src/codex/hook.rs` | Local hook responses and `$PLUGIN_DATA` session records |
| Thin routing behavior | `cli-rs/resources/codex-plugin/plugins/kast/skills/kast-codex/SKILL.md` | Installed `kast-codex` skill |
| Skill presentation metadata | `cli-rs/resources/codex-plugin/plugins/kast/skills/kast-codex/agents/openai.yaml` | Codex skill discovery |
| Binary resolution | `cli-rs/resources/codex-plugin/plugins/kast/scripts/kast-codex-hook` | Hook command execution |
| Brand source | `cli-rs/resources/codex-plugin/plugins/kast/assets/kast.svg` | Plugin presentation |
| Release archive and provenance | `.github/workflows/release.yml`, `.github/scripts/verify-codex-plugin-package.py`, and release ledger/provenance scripts | Immutable release asset, checksums, and provenance |
| Public installation, operation, privacy, and terms | `docs/install/codex.md`, `docs/use/codex.md`, `docs/reference/codex-plugin.md`, `docs/privacy.md`, and `docs/terms.md` | Published Zensical site |

Generated files are never edited to change behavior. Change the Rust contract
or the named authored source, regenerate, and review the resulting diff.

## Validation

The contract is accepted only when focused Rust tests prove exhaustive
classification, deterministic generation, all hook events, compaction,
worktree isolation, baseline handling, target-bound fallback, current-hash
diagnostics, and the no-mutation hook boundary.

The distribution and docs gates are:

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked \
  --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- \
  developer codex generate --check
.github/scripts/test-codex-plugin-package-contract.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

Any future Codex-visible command, hook mutation authority, provider transport,
or version-skew policy requires a superseding ADR before implementation.
