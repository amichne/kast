---
type: Reference
title: Agent Harness Resources
description: Release-matched Kast skills, hooks, and local marketplace manifests.
tags: [codex, claude, copilot, hooks, marketplace]
code_sources:
  - path: cli-rs/resources/kast
  - path: cli-rs/src/operations/install/agent_resources.rs
  - path: install.sh
---

# Agent Harness Resources

Kast embeds one agent skill and provider-native manifests for Codex, Claude,
and Copilot. `install.sh` materializes the selected resources under
`KAST_HOME`, registers that local directory with the harness, and installs
`kast@kast`.

Resources are version-bound to the Kast release. The installer never
fast-forwards an external marketplace repository.

## Activation compatibility

After a user trusts a bundled hook, each startup or resume checks the installed
CLI, provider plugin, and skill against the release version and complete
embedded-resource digest. This check includes the hook.
Any version or digest mismatch rejects harness activation.
Codex and Claude reject a mismatch at
`SessionStart`. Copilot reports the mismatch at `sessionStart` and denies the
first `preToolUse` event, which is its first blocking hook boundary. The
rejection reports the detected and expected values and this repair command,
with the active provider in place of `codex`:

```console
kast __internal resources install --harness codex
```

This check is not an install-time gate. An untrusted, skipped, disabled, or
tampered hook cannot enforce its own check.

Direct CLI use does not require agent harness resources.

## Selection

With no `--harness` option, the installer selects every harness executable it
detects. Select explicitly by repeating the option:

```console
./install.sh --harness codex --harness claude --harness copilot
```

Use `--harness none` to install no agent resources.

## Installed contract

| Component | Contract |
| --- | --- |
| `kast` skill | Routes Kotlin and Gradle discovery, refresh, navigation, graph analysis, diagnostics, and changes through the public CLI. |
| Agent activation hooks | Reject a trusted Codex or Claude session at startup, or deny Copilot's first tool use, when the CLI, plugin, hook, and skill do not match. |
| Local marketplace manifest | Lets the harness install the embedded `kast@kast` plugin without a network marketplace. |

The resources do not contain a compiler backend. Provider hooks use the
release-matched private KastCTL bridge for activation checks. Users perform
repository operations through the public `kast` entrypoint.

## Release assets

Each tag publishes deterministic `kast-codex-<tag>.tar`,
`kast-claude-<tag>.tar`, and `kast-copilot-<tag>.tar` archives. Each archive
has a SHA-256 checksum, and the set has one provenance document.
