# Codex plugin source guide

This file applies to `cli-rs/resources/codex-plugin/` and descendants. This
tree is the repository marketplace source for the CLI-only `kast@kast` Codex
plugin. It is independent from the GitHub Copilot package at
`cli-rs/resources/plugin/` and the provider-neutral skill at
`cli-rs/resources/kast-skill/`.

## Authored sources

Edit these files directly when their behavior or presentation changes:

- `plugins/kast/skills/kast-codex/SKILL.md` owns the thin semantic routing
  rules;
- `plugins/kast/skills/kast-codex/agents/openai.yaml` owns skill presentation
  and implicit invocation;
- `plugins/kast/scripts/kast-codex-hook` owns only attested-launcher and sibling
  CLI resolution plus stdin forwarding; and
- `plugins/kast/assets/kast.svg` is the canonical copied brand asset.

The routing skill teaches only task begin, discovery through `kast agent` and
scoped help, task finish, and exact reporting of typed blockers. Do not add
command inventories, setup, repair, LSP, developer commands, raw RPC names, or
hook commands to the skill.

## Generated sources

Do not hand-edit these files to change the contract:

- `marketplace.json`;
- `.agents/plugins/marketplace.json`, the byte-identical Codex discovery copy;
- `plugins/kast/.codex-plugin/plugin.json`;
- `plugins/kast/hooks/hooks.json`;
- `plugins/kast/assets/codex-exposure.toon`;
- `plugins/kast/assets/hook-recovery-messages.toon`; and
- Codex package and routing fixtures named by the generator.

Change the Rust exposure types, descriptors, templates, or hook policy, then
regenerate. Generated output must be deterministic and must not contain a
timestamp, host path, or environment-specific value.

## Package boundary

The plugin contains one skill, default `hooks/hooks.json`, one launcher, and
generated metadata/assets. It must not contain `.mcp.json`, `.app.json`, MCP
server code, an app connector, a custom agent profile, raw RPC payloads, or a
copy of the internal command catalog.

The launcher accepts only the generated hook event. It resolves an absolute
`KAST_AGENT_TASK_LAUNCHER` override or the stable user launcher, verifies that
launcher and its sibling `kast` are executable, and executes the hidden Rust
hook entrypoint with stdin unchanged. It never searches `PATH` or build output.
It must not parse events, make workflow decisions, write session state, or
transform output.

Rust translates Codex host envelopes into the shared `kast agent task`
lifecycle. The task core owns receipts, baselines, diagnostics, Gradle proof,
and completion decisions; the Codex adapter retains only its typed-mutation
guardrail and host response mapping. Hooks never apply setup, repair, IDE,
installation, or source mutations.

## Metadata and release

The marketplace and plugin names are `kast`, so the install identity is
`kast@kast`. The manifest version is generated from the compiling binary. The
published website, privacy, and terms URLs are:

- `https://kast.michne.com/`;
- `https://kast.michne.com/privacy/`; and
- `https://kast.michne.com/terms/`.

The release archive is `kast-codex-plugin-<tag>.zip` with `marketplace.json`,
the byte-identical `.agents/plugins/marketplace.json` discovery manifest, and
`plugins/kast/` at its root. Local cachebusters preserve the base version and
use one `+codex.<token>` suffix.

## Verify

Run the focused generation and package gates after any change in this tree or
its Rust owners:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- \
  developer codex generate --check
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test codex_plugin_smoke
.github/scripts/test-codex-plugin-package-contract.sh
python3 /Users/amichne/.codex/skills/.system/plugin-creator/scripts/validate_plugin.py \
  cli-rs/resources/codex-plugin/plugins/kast
python3 /Users/amichne/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  cli-rs/resources/codex-plugin/plugins/kast/skills/kast-codex
```
