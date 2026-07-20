---
title: Codex Plugin Contract
description: Reference for Kast's task lifecycle, hooks, state, and package boundary in Codex.
icon: lucide/file-lock-2
---

# Codex Plugin Contract

The `kast@kast` plugin is a local CLI adapter. Rust command types own the
exposure contract, and the plugin does not contain a second semantic service.

## Package Surface

| Surface | Contract |
| --- | --- |
| Skill | One compact `kast-codex` skill for task admission, command discovery, and completion |
| Hooks | `SessionStart`, `PreToolUse`, `PostToolUse`, and `Stop` through one local launcher |
| Binary | Resolves an attested `kast-agent-task` and sibling `kast`; no binary is bundled |
| Semantic transport | The shared task core plus typed `kast agent` commands and TOON results |
| Generated material | Manifest, marketplace metadata, hook configuration, exposure metadata, and fixtures |
| Excluded material | MCP configuration or server, app connector, custom agent profile, raw RPC surface, and copied command catalog |

The plugin release version matches the Kast CLI release version. The IDEA
plugin and backend still use their typed runtime compatibility contract; a
shared version string does not bypass exact-worktree admission.

## Agent-Visible Commands

The skill teaches only `kast-agent-task begin`, `kast agent` discovery, scoped
`--help`, and `kast-agent-task finish`. Rust still classifies the typed semantic
surface deliberately, but generated tutorials and copied command inventories
are not part of the plugin. See the [agent command
reference](agent-commands.md) for lifecycle and semantic contracts.

## Hook Events

| Event | Input purpose | Possible control effect |
| --- | --- | --- |
| `SessionStart` | Begin or recover the exact-root task | Adds the typed task receipt as context |
| `PreToolUse` | Check task ownership and examine a proposed generic Kotlin mutation | Denies generic Kotlin writes; applied edits must use the typed synchronous route |
| `PostToolUse` | Refresh task status after a tool call | Adds current task evidence as context |
| `Stop` | Finish through the shared diagnostics and Gradle proof core | Continues the turn with the typed blocker when completion proof is missing |

The launcher forwards standard input to one hidden Rust hook entrypoint. Rust
owns input parsing, decisions, session state, and the JSON hook control
envelope.

## Local Session State

State is stored atomically with owner-only permissions at:

```text
$PLUGIN_DATA/sessions/<session-id>.json
```

The provider record contains only its schema, session ID, and exact workspace
root. In-flight mutation identity, blockers, baselines, diagnostics, Gradle
policy, task outcomes, reports, and final hashes belong to the shared task
receipt, not provider state.

Provider state is guardrail evidence. It is not a source index, task receipt,
copy of the workspace, installation receipt, or remote synchronization store.
See the [privacy notice](../privacy.md) for data handling.

## Ownership And Regeneration

Rust owns the hook translation and exposure metadata. `SKILL.md`, skill
presentation metadata, the launcher, and the canonical logo are authored
plugin sources. Marketplace and plugin metadata, hook configuration, exposure
assets, recovery messages, and contract fixtures are generator-owned.

Published archives are named `kast-codex-plugin-<tag>.zip` and contain root
`marketplace.json`, its byte-identical `.agents/plugins/marketplace.json`
discovery projection, and `plugins/kast/`. Release verification checks the
archive, aggregate checksum, build ledger, and provenance together.
