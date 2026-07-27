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
both native repository surfaces:

```console
kast agent repository --help
kast agent graph --help
```

Use `kast agent repository` for bounded natural-language identity, path,
impact, architecture, and context questions. Use `kast agent graph` for
persisted compiler-backed topology.

## Validation

```console
codex plugin marketplace list
codex plugin list
kast agent --help
```
