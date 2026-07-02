# Kast skill and RPC catalog guide

This file applies to `cli-rs/resources/kast-skill/` and descendants. This tree
is the packaged skill and command catalog used by agents, docs, `kast agent
tools`, and generated LSP custom route metadata.

## Local purpose

- `SKILL.md` is the packaged skill entrypoint for hosts that load skills.
- `references/commands.json` is the canonical machine-readable RPC and tool
  catalog.
- `references/commands.yaml` and generated request schemas/samples are derived
  contract artifacts.
- `references/quickstart.md` and `references/runbook.md` are agent-facing
  lookup material.
- `references/workflows.md` owns install/config/package verification, project
  readiness, semantic workflow sequencing, and recovery ownership.
- `scripts/verify-kast-state.py` and `scripts/kast-agent-call.py` are packaged
  deterministic helpers for read-only state checks and file-backed requests.
  Common semantic sequences belong to first-class `kast agent workflow`
  commands in the active binary.

The durable decision record for package ownership, manifest-backed resource
trust, and active-binary workflow support is
`.agents/adr/0002-agent-resource-and-workflow-source-of-truth.md`.

## Edit rules

- Treat `references/commands.json` as the source catalog for methods, request
  fields, tool names, and flow grouping.
- Regenerate derived contract artifacts after catalog changes.
- Keep command and tool descriptions aligned with the current product story in
  `.agents/adr/0001-agent-first-install-and-docs-operating-model.md`.
- Do not add JVM-owned handlers for Rust-owned `database/*` or source-index
  query methods.
- Keep recovery guidance resolve-first and compiler-backed; do not route
  Kotlin symbol work through text search.
- Do not preserve workflow helpers solely for older binaries. If the active
  binary lacks `kast agent tools` or `kast agent workflow`, report the
  incompatibility and require upgrade or reinstall.
- Prefer scripts for repeated verification or request-exchange workflows. Keep
  them JSON-emitting, eager about input validation, and read-only unless a
  future command explicitly documents mutation.

## Downstream surfaces

Catalog changes can affect:

- `cli-rs/protocol/api-specification.md` generated summary block
- `cli-rs/src/lsp.rs` generated custom method list and dispatch metadata
- `docs/commands/agent.md`, `docs/commands/lsp.md`, and package tests when tool names
  or flow groups change

## Verify

Run the catalog and docs checks after catalog changes:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- release generate contract --check
python3 .github/scripts/render-rpc-contract-summary.py --check
.github/scripts/test-kast-routing-evals.sh
.github/scripts/run-kast-format-impact-report.sh
.github/scripts/run-kast-routing-format-impact-report.sh
.github/scripts/run-kast-skill-eval-format-comparison.sh
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
```

For skill-eval format experiments, set `KAST_SKILL_EVAL_AGENT_OUTPUT_SHAPE`
to `text`, `json`, or `toon` before running the comparison script. Suite-specific
overrides are `KAST_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE` and
`KAST_ROUTING_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE`; unset them to fall back to the
shared value, and set `json` to switch captured answer requests back to JSON.

Use `kast developer release validate --request-file <file>` for hand-authored request examples.
Run the packaged helper dry run after script or workflow edits:

```console
python3 cli-rs/resources/kast-skill/scripts/kast-agent-call.py symbol/query \
  --params-json '{"query":"Kast","limit":1}' --dry-run
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- agent workflow symbol \
  --dry-run --symbol Kast
```
