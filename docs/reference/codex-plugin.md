---
title: Codex Plugin Contract
description: Reference for Kast's fixed Codex command exposure, hooks, state, and package boundary.
icon: lucide/file-lock-2
---

# Codex Plugin Contract

The `kast@kast` plugin is a local CLI adapter. Rust command types own the
exposure contract, and the plugin does not contain a second semantic service.

## Package Surface

| Surface | Contract |
| --- | --- |
| Skill | One implicitly invokable `kast-codex` skill for semantic Kotlin work |
| Hooks | Default `hooks/hooks.json` discovery with one local launcher |
| Binary | Resolves the active `kast` installation; no binary is bundled |
| Semantic transport | Typed `kast agent` commands and structured CLI results |
| Generated material | Manifest, marketplace metadata, command reference, examples, hook messages, and fixtures |
| Excluded material | MCP configuration or server, app connector, custom agent profile, raw RPC surface, and copied command catalog |

The plugin release version matches the Kast CLI release version. The IDEA
plugin and backend still use their typed runtime compatibility contract; a
shared version string does not bypass exact-worktree admission.

## Agent-Visible Commands

The following list is closed. A new Kast command is unavailable to Codex until
Rust classifies it deliberately and regeneration updates the package.

| Family | Commands | Behavior |
| --- | --- | --- |
| Workspace | `workspace-files` | Read-only, bounded discovery for the exact root |
| Identity | `symbol` | Read-only exact symbol resolution |
| Relationships | `references`, `callers`, `callees`, `implementations`, `hierarchy`, `impact` | Read-only semantic navigation and impact evidence |
| Diagnostics | `diagnostics` | Read-only diagnostics bound to the analyzed file state |
| Mutation | `rename`, `add-file`, `add-declaration`, `add-implementation`, `add-statement`, `replace-declaration` | Plan by default; apply only through each typed command's explicit gate and stable idempotency key |
| Operation | `operation status`, `operation cancel` | Observe or request cancellation of an admitted mutation |

Setup, readiness, repair, verification, LSP, runtime management, developer
commands, and retired catalog/workflow calls are absent from normal
`kast-codex` routing. Internal hooks may use the read-only or plan-only subset
needed to establish readiness and recovery evidence.

## Hook Events

| Event | Input purpose | Possible control effect |
| --- | --- | --- |
| `SessionStart` | Establish or recover one session and exact workspace baseline | Adds readiness, coherence, or recovery context |
| `SubagentStart` | Bind delegated work to its exact root and linked worktree | Adds verification context |
| `PreToolUse` | Examine a proposed generic Kotlin mutation | Denies only when the typed target route has not produced fallback evidence |
| `PostToolUse` | Examine the completed tool request and result | Records typed command and diagnostics evidence |
| `Stop` | Compare current Kotlin changes with stored proof | Continues the turn when current diagnostics or a reported blocker is missing |

The launcher forwards standard input to one hidden Rust hook entrypoint. Rust
owns input parsing, decisions, session state, and the JSON hook control
envelope.

## Local Session State

State is stored atomically with owner-only permissions at:

```text
$PLUGIN_DATA/sessions/<session-id>.json
```

One session record may contain:

- schema, plugin, and Kast versions;
- resolved binary path;
- canonical workspace root, Git common directory, linked worktree, and commit;
- baseline dirty Kotlin paths and SHA-256 fingerprints;
- typed command attempts, outcomes, affected paths, and operation IDs;
- typed failures and path-scoped fallback eligibility; and
- diagnostics evidence tied to current file hashes and explicitly reported
  blockers.

The state is recovery and guardrail evidence. It is not a source index, a copy
of the workspace, an installation receipt, or a remote synchronization store.
See the [privacy notice](../privacy.md) for data handling.

## Ownership And Regeneration

The Rust exposure types and descriptors are the authority for generated
command material. `SKILL.md`, skill presentation metadata, the launcher, and
the canonical logo are authored plugin sources. Marketplace and plugin
metadata, hook configuration, command examples, exposure assets, recovery
messages, and contract fixtures are generator-owned.

Published archives are named `kast-codex-plugin-<tag>.zip` and contain root
`marketplace.json`, its byte-identical `.agents/plugins/marketplace.json`
discovery projection, and `plugins/kast/`. Release verification checks the
archive, aggregate checksum, build ledger, and provenance together.
