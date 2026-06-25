---
title: Commands
description: The Kast CLI command groups and when to use each one.
icon: lucide/list-tree
---

# Commands

Kast keeps the public CLI small. Human operator commands default to readable
text and accept `--output json` when scripts need structured payloads. Advanced
agent commands emit one JSON object on stdout so they can be chained in tools.

## Command groups

Start with the group that matches the job in front of you. Run `kast help` or
`kast help <command>` locally for the exact flags supported by your installed
binary.

| Group | Commands | Use when |
|-------|----------|----------|
| Lifecycle | `up`, `status`, `restart`, `stop`, `capabilities` | Start, inspect, refresh, or stop the workspace backend |
| Install and repair | `install ...`, `doctor`, `paths` | Install repository resources, repair managed files, or inspect path resolution |
| Agent automation | `agent ...`, `agent workflow ...` | Script semantic reads and file-backed workflows through a JSON envelope |
| Metrics | `metrics ...`, `agent metrics` | Query the local SQLite source index for fan-in, fan-out, coupling, impact, and search |
| LSP | `lsp --stdio` | Start the Language Server Protocol adapter for editors and Copilot packages |
| Distribution | `package ubuntu-debian-bundle`, `install activate-bundle` | Build or activate the Linux headless bundle |

## Output modes

Operator commands are designed for humans first. They render readable summaries
in terminals and plain text in captured logs. Add `--output json` to preserve
the structured payload for automation.

```console title="Readable by default, JSON when requested"
kast status
kast --output json status
```

`kast agent` is different by design. It always emits a single JSON envelope
with `ok`, `method`, `request`, and either `result` or `error`. Use it when a
script, agent, or CI step needs stable machine output.

## Workspace selection

Most commands default to the current workspace. When run below a project root,
Kast walks upward to a Gradle marker or `.kast` directory. Pass
`--workspace-root` only when the command should target a different repository.

Backend selection is explicit when it matters:

```console title="Select the backend"
kast up --backend=headless
kast status --backend=idea
kast agent health --workspace-root "$PWD" --backend=headless
```

## Debug escape hatch

Raw `kast rpc` still exists for low-level debugging and compatibility. The
published command docs teach `kast agent` first because it normalizes inputs,
wraps results consistently, and works better for scripts.
