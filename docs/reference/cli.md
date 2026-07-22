---
type: Reference
title: CLI Reference
description: Public Kast command families, shared options, and intended audiences.
tags: [cli, reference, commands, output]
code_sources:
  - path: cli-rs/src/cli/root.rs
  - path: cli-rs/src/main.rs
  - path: cli-rs/protocol/source/commands.yaml
---

# CLI Reference

`kast` is the installed control plane for setup, workspace readiness, guided
inspection, and agent-facing semantic operations.

## Command families

| Command | Purpose | Primary audience |
| --- | --- | --- |
| `kast help [topic...]` | Browse the command tree and scoped help. | Everyone |
| `kast version` | Print the packaged CLI version. | Everyone |
| `kast context` | Print compact context for the current workspace. | Agents and diagnostics |
| `kast setup` | Install or refresh one verified release from a bundle. | Installer and release tooling |
| `kast ready` | Verify readiness for an agent, Kotlin, release, or machine task. | Users and agents |
| `kast status` | Report current workspace runtime status. | Users and diagnostics |
| `kast demo` | Explore a guided semantic story in a Kotlin repository. | Evaluators and developers |
| `kast agent` | Run typed, pipe-friendly semantic operations. | Codex and other agent tooling |
| `kast developer` | Run development and release-engineering commands. | Kast contributors |

Use scoped help as the option authority:

```console
kast help setup
kast ready --help
kast demo --help
```

## Shared workspace options

Commands that operate on a workspace can accept:

| Option | Meaning |
| --- | --- |
| `--workspace-root <path>` | Absolute root whose runtime and compiler evidence are requested. |
| `--backend idea\|headless` | Select a backend instead of automatic routing. |

Some agent operations also accept an opaque exact-root lease. Leases are
backend-bound capabilities; they are not portable workspace identifiers.

## Output modes

Commands with `--output` expose these values:

| Value | Contract |
| --- | --- |
| `human` | Readable terminal output. |
| `toon` | Compact typed output used by current agent workflows. |
| `json` | Deprecated compatibility projection for existing consumers. |

The default depends on the command surface. Do not parse human output as a
stable machine protocol.

## Setup

`kast setup` accepts a complete extracted bundle or bundle archive through
`--source`. The public installer selects that source and invokes setup. There
are no separate public update, repair, or uninstall command families.

## Readiness targets

`kast ready --for <target>` accepts `agent`, `kotlin`, `release`, or `machine`.
The default is `agent`. A readiness response can include exact-root evidence,
limitations, and next actions; it is stronger than checking whether a process
exists.
