# ADR 0012: Repo-native semantic story demo

Status: Accepted

Date: 2026-07-10

This ADR supersedes ADR 0006 and ADR 0008 only where those records classify
the interactive demo as a developer inspection surface. Their broader system,
agent-command, setup, runtime, output, distribution, and audit rules remain in
force.

## Decision

Kast exposes `kast demo` as a public, read-only evaluation workflow. It derives
a guided semantic story from the selected Kotlin repository, ranks concrete
symbols from available source-index evidence, and hands each demonstrated
capability back to the equivalent typed `kast agent` command.

The demo is interactive only in a human terminal. Captured invocations emit a
deterministic structured snapshot through the standard
`--output human|json` documentation boundary. The internal TOON renderer stays
available for agent capture. The snapshot states which evidence lanes
are available, which chapters are omitted, and which public commands reproduce
the result.

## Story Contract

The complete story progresses through compiler identity, a meaningful
lexical-versus-semantic comparison when one exists, relationships, impact,
diagnostics and safe mutation planning, then a command recap. An unavailable
backend or source index disables dependent chapters explicitly; it never
causes fixture data or inferred claims to be substituted.

The demo does not install repository resources, start or repair runtimes,
modify user code, or expose `--apply`. A mutation chapter may construct a
plan-first request only after the user supplies a hypothetical valid Kotlin
identifier.

## Public Interface

```console
kast demo [--workspace-root <path>] [--backend idea|headless] [--symbol <query>]
```

`--symbol` bypasses automatic candidate selection. Without it, candidate
ranking is deterministic: evidence count descending, then fully-qualified
name ascending, with at most one impact-hub, call-chain-hub, and semantic-
ambiguity story.

`kast developer inspect demo` is retired. Stale invocations must return
targeted `DEMO_COMMAND_MOVED` guidance instead of retaining a second demo
dialect.

## Source Of Truth

| Layer | Owner |
| --- | --- |
| Public flags and output selection | `cli-rs/src/cli/root.rs`, `cli-rs/src/cli/inspect_metrics_demo_rpc.rs` |
| Typed story, evidence, and state models | `cli-rs/src/demo/` |
| Source-index evidence | `cli-rs/src/demo/database.rs`, `cli-rs/src/metrics_database/` |
| Public compiler evidence | `cli-rs/src/agent/` |
| Public documentation | `README.md`, `docs/learn/repository-demo.md`, `docs/reference/commands.md` |

## Validation Commands

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked --test demo_smoke
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

End-to-end validation must compare repository file checksums before and after a
demo run and exercise full, index-only, backend-only, and unavailable evidence
states.

## Change Rule

Future demo chapters must use typed public evidence, remain non-mutating, state
degradation explicitly, and emit equivalent public command handoffs. Any demo
mutation or new public command family requires a superseding ADR.
