# Provider-neutral Kast skill guide

This file applies to `cli-rs/resources/kast-skill/` and descendants. This tree
is the small provider-neutral skill installed by repository setup. It is not
an internal protocol workspace.

## Authored sources

- `SKILL.md` owns the complete compact Kotlin and Gradle task lifecycle.

Keep the skill focused on public `kast` and `kast agent` commands. Do not place
raw RPC catalogs, generated schemas, request fixtures, maintenance evaluation
packs, provider hooks, package manifests, or verification scripts in this
directory. Internal catalog truth and generated request fixtures live under
`cli-rs/protocol/source/`; routing and format evaluation material lives under
`cli-rs/protocol/maintenance/`.

## Edit rules

- Keep the main skill below 40 lines and do not add reference inventories.
- Teach only when Kast triggers, task begin, discovery through `kast agent` and
  scoped help, task finish, and exact reporting of typed blockers.
- Let the task core own workspace resolution, leases, diagnostics, Gradle proof,
  and completion. Do not reproduce those policies in prose.
- Do not teach retired `tools`, `call`, or `workflow` commands, raw RPC names,
  LSP internals, developer commands, package files, or hooks as the normal
  semantic surface.

## Verify

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
.github/scripts/test-kast-routing-evals.sh
```
