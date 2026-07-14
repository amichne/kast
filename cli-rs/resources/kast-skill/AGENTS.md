# Kast skill and internal catalog guide

This file applies to `cli-rs/resources/kast-skill/` and descendants. This tree
contains the packaged skill entrypoint plus internal catalog material used by
docs, backend contracts, release checks, and generated LSP custom route
metadata.

## Local purpose

- `SKILL.md` is the packaged skill source installed by repository setup.
- `references/commands.json` is the internal machine-readable command catalog.
- `references/commands.yaml` and generated request schemas/samples are derived
  contract artifacts.
- `references/quickstart.md` and `references/runbook.md` are agent-facing
  lookup material.
- `references/workflows.md` owns install/config/package verification, project
  readiness, semantic workflow sequencing, and recovery ownership.
- `scripts/verify-kast-state.py` is the internal deterministic helper for
  read-only state checks.

The current source-of-truth contract for the public product surface, workflows,
AXI command dialect, and validation gates is
`.agents/adr/0006-forward-system-definition-and-audit-scope.md`.

## Edit rules

- Treat `references/commands.json` as the source catalog for internal methods,
  request fields, tool names, and flow grouping.
- Regenerate derived contract artifacts after catalog changes.
- Keep command and tool descriptions aligned with ADR 0006.
- Rust CLI modules own operational source-index reads; JVM handlers own
  API-backed semantic work.
- Keep recovery guidance resolve-first and compiler-backed.
- Skill guidance routes agents to `kast ready`, `kast repair`, and typed
  `kast agent` commands.
- Skill guidance routes workspace discovery through public
  `kast agent workspace-files`, including typed source/script filters,
  generation/query-bound public continuation, kind-relevant partial coverage,
  build-qualified Gradle ownership, and direct `filePath` composition with
  diagnostics and exact symbol lookup. It must not teach
  `raw/workspace-files` as a public workflow.
- Keep `.kts` source-index limitations and cross-source coherence limitations
  explicit. `.kt` index progress is irrelevant to a script-only request and
  #340, but relevant source/mixed partitions must remain partial. Never describe
  a partial or pending relevant candidate lane as exhaustive.
- The packaged script is an internal verification helper. Keep it
  JSON-emitting, eager about input validation, and read-only by default.

## Downstream surfaces

Internal catalog changes can affect:

- `cli-rs/protocol/api-specification.md` generated summary block
- `cli-rs/src/lsp.rs` generated custom method list and dispatch metadata
- `docs/commands/agent.md`, `docs/commands/lsp.md`, and package tests when
  internal method names or flow groups change
- public workspace-file routing, paging examples, and installed skill content
  when the typed command or continuation contract changes

## Verify

For any workspace-files routing, packaged guidance, or internal catalog change,
run all package, LSP, routing, and generated-contract checks below:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
python3 .github/scripts/render-rpc-contract-summary.py --check
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-kast-routing-evals.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
./gradlew test --no-daemon
./gradlew buildIdeaPlugin --no-daemon
```

The format-impact reports remain optional experiments. When running them, set
`KAST_SKILL_EVAL_AGENT_OUTPUT_SHAPE`
to `text`, `json`, or `toon` before running the comparison script. Suite-specific
overrides are `KAST_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE` and
`KAST_ROUTING_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE`; unset them to fall back to the
shared value, and set `json` to switch captured answer requests back to JSON.

Use `kast developer release validate --request-file <file>` for hand-authored request examples.
Run the packaged verifier after script edits:

```console
python3 cli-rs/resources/kast-skill/scripts/verify-kast-state.py --workspace-root "$PWD"
```
