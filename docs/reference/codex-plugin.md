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
| Session-start hook | Runs `~/.local/bin/kast` to report exact-root readiness and the next action. |
| Local marketplace manifest | Lets the harness install the embedded `kast@kast` plugin without a network marketplace. |

The resources do not contain a compiler backend and do not expose `_kastctl`.
They always invoke the active public `kast` entrypoint.

## Release assets

Each tag publishes deterministic `kast-codex-<tag>.tar`,
`kast-claude-<tag>.tar`, and `kast-copilot-<tag>.tar` archives. Each archive
has a SHA-256 checksum, and the set has one provenance document.
