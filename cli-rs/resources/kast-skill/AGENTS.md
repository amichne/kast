# Provider-neutral Kast skill guide

This file applies to `cli-rs/resources/kast-skill/` and descendants. This tree
is the small provider-neutral skill installed by repository setup. It is not
an internal protocol workspace.

## Authored sources

- `SKILL.md` owns the compact Kotlin and Gradle routing loop.
- `references/quickstart.md` owns typed first-use examples.
- `references/runbook.md` owns concise semantic recovery guidance.
- `references/workflows.md` owns readiness, exact-worktree, and validation
  sequencing that is useful across agent providers.

Keep the skill focused on public `kast` and `kast agent` commands. Do not place
raw RPC catalogs, generated schemas, request fixtures, maintenance evaluation
packs, provider hooks, package manifests, or verification scripts in this
directory. Internal catalog truth and generated request fixtures live under
`cli-rs/protocol/source/`; routing and format evaluation material lives under
`cli-rs/protocol/maintenance/`; the internal read-only verifier lives at
`scripts/verify-kast-state.py`.

## Edit rules

- Keep the main skill below 70 lines and defer only genuinely useful detail.
- Route Kotlin discovery through `kast agent workspace-files` and preserve its
  exact coverage and continuation evidence.
- Preserve compiler-resolved symbol identity across relationship and mutation
  commands.
- Keep mutations plan-first, applied with stable idempotency keys, and followed
  by diagnostics for current file contents.
- Keep readiness and repair guidance read-only by default. Applying repair
  requires explicit user authority.
- Acquire one exact-root lease before semantic work, pass its opaque ID and
  selected backend to later semantic commands, and release the same lease when
  the worker finishes. Do not teach direct developer runtime lifecycle as the
  agent startup path.
- Do not teach retired `tools`, `call`, or `workflow` commands, raw RPC names,
  LSP internals, developer commands, package files, or hooks as the normal
  semantic surface.

## Verify

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
.github/scripts/test-kast-routing-evals.sh
.github/scripts/test-docs-content-contract.sh
python3 scripts/verify-kast-state.py --workspace-root "$PWD"
```
