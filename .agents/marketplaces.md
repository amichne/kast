# Agent Marketplace

Kast's Codex plugin is externally authored and published. Installed payloads,
marketplace snapshots, and runtime caches are observations, not source of
truth for this repository.

| Marketplace | Source | Ref | Plugin |
|---|---|---|---|
| `kast` | `https://github.com/amichne/kast-marketplace` | `main` | `kast@kast` |

The plugin supplies Kast routing guidance plus `SessionStart` and
`PostToolUse` hook adapters. It does not embed a Kast runtime.

## Install or refresh

```console
codex plugin marketplace add amichne/kast-marketplace --ref main --json
codex plugin add kast@kast --json
```

Start a new Codex thread after installation. The active Kast CLI must expose
the typed semantic command surface:

```console
kast --help
kast symbol --help
```

Use the scoped workspace, symbol, relation, traversal, diagnostic, and change
commands for compiler-backed evidence.

## Validation

```console
codex plugin marketplace list
codex plugin list
kast --help
```
