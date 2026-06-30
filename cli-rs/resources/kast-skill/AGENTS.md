# Kast skill and command catalog guide

This file applies to `cli-rs/resources/kast-skill/` and descendants. This tree
is the authored skill source, command catalog, routing fixtures, and validation
material used by docs, `kast agent tools`, and generated LSP custom route
metadata. `kast agent setup skill` installs a thin skill entrypoint from this
tree, not the full source tree.

## Local purpose

- `SKILL.md` is the installed skill entrypoint for hosts that load skills.
- `references/commands.json` is the canonical machine-readable RPC and tool
  catalog used by the CLI, docs, tests, `kast agent tools`, and generated LSP
  metadata. It is not installed into skill hosts.
- `references/commands.yaml` and generated request schemas/samples are derived
  contract artifacts for source validation, not installed skill payload.
- `references/quickstart.md`, `references/runbook.md`, and
  `references/workflows.md` are source-only development references. Installed
  agents should use `SKILL.md`, `kast agent tools`, and `kast agent workflow`
  for progressive disclosure.
- `scripts/verify-kast-state.py` and `scripts/kast-agent-call.py` are
  source-tree test/development helpers. Do not teach them as installed package
  dependencies; common semantic and verification sequences belong to
  first-class `kast agent workflow` commands in the active binary.

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
- Prefer first-class CLI workflows for repeated verification or request
  exchange. Keep source helpers JSON-emitting, eager about input validation,
  and read-only unless a future command explicitly documents mutation.

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
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
```

Use `kast release validate --request-file <file>` for hand-authored request examples.
Run the source helper dry run after script edits and the native workflow dry
run after workflow edits:

```console
python3 cli-rs/resources/kast-skill/scripts/kast-agent-call.py symbol/query \
  --params-json '{"query":"Kast","limit":1}' --dry-run
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- agent workflow symbol \
  --dry-run --symbol Kast
```
