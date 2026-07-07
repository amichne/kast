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
- `scripts/verify-kast-state.py` is an internal deterministic helper for
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
- Scripts are internal verification helpers. Keep them JSON-emitting, eager
  about input validation, and read-only by default.

## Downstream surfaces

Internal catalog changes can affect:

- `cli-rs/protocol/api-specification.md` generated summary block
- `cli-rs/src/lsp.rs` generated custom method list and dispatch metadata
- `docs/commands/agent.md`, `docs/commands/lsp.md`, and package tests when
  internal method names or flow groups change

## Verify

Run the catalog and docs checks after internal catalog changes:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- release generate contract --check
python3 .github/scripts/render-rpc-contract-summary.py --check
.github/scripts/test-kast-routing-evals.sh
.github/scripts/run-kast-format-impact-report.sh
.github/scripts/run-kast-routing-format-impact-report.sh
.github/scripts/run-kast-skill-eval-format-comparison.sh
.github/scripts/test-lsp-pivot-gates.sh
```

For skill-eval format experiments, set `KAST_SKILL_EVAL_AGENT_OUTPUT_SHAPE`
to `text`, `json`, or `toon` before running the comparison script. Suite-specific
overrides are `KAST_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE` and
`KAST_ROUTING_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE`; unset them to fall back to the
shared value, and set `json` to switch captured answer requests back to JSON.

Use `kast developer release validate --request-file <file>` for hand-authored request examples.
Run the packaged verifier after script edits:

```console
python3 cli-rs/resources/kast-skill/scripts/verify-kast-state.py --workspace-root "$PWD"
```
