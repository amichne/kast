---
title: Command Surface
description: Curated reference for the public Kast CLI command groups.
icon: lucide/list-tree
---

# Command Surface

This page is the lookup reference for public command groups. It is curated from
the installed CLI help and the forward product surface, not generated from the
internal request catalog.

## Root Commands

`kast` is the root AXI command surface. Root commands are stable entry points
for setup, readiness, repair, runtime status, developer operations, and typed
agent work.

| Command | Role |
| --- | --- |
| `kast help` | Browse command-tree help |
| `kast version` | Print the packaged CLI version |
| `kast context` | Print compact workspace context for agents and hooks |
| `kast ready` | Verify that Kast is ready for a task |
| `kast repair` | Plan or apply safe repair of install state |
| `kast status` | Check current workspace status |
| `kast developer ...` | Run operator, inspection, machine, and release commands |
| `kast agent ...` | Run typed agent, semantic, and LSP commands |

The global output selector is `--output <human|json|toon>`. Use `json` for
scripts that need a stable parser contract.

## Public Command Groups

The groups below are the published workflow surface. Removed raw transport,
top-level runtime aliases, workflow helpers, and generated catalog calls are
not public reader paths.

| Group | Commands | Public role |
| --- | --- | --- |
| Context | `kast context` | Show compact workspace context and next-command hints |
| Setup | `kast setup` | Install non-macOS repository guidance |
| Readiness | `kast ready --for agent|kotlin|release|machine` | Report task readiness without mutation |
| Repair | `kast repair --for agent|kotlin|release|machine` | Plan by default and apply only with `--apply` |
| Status | `kast status` | Inspect workspace and runtime status |
| Runtime | `kast developer runtime up|status|stop|restart|capabilities` | Manage backend lifecycle |
| Inspection | `kast developer inspect paths|metrics|demo|catalog` | Inspect local state, source-index metrics, demos, and catalog samples |
| Machine | `kast developer machine ...` | Manage local developer-machine integrations |
| Release | `kast developer release package|activate|generate|validate` | Build, activate, regenerate, and validate release artifacts |
| Agent | `kast agent verify|symbol|impact|diagnostics|rename|add-file|add-declaration|add-implementation|add-statement|replace-declaration|lsp` | Run typed semantic and editor-adapter commands |

## Workspace And Backend Flags

Many commands accept `--workspace-root <path>` and `--backend <idea|headless>`.
`--workspace-root` should be an absolute repository root when automation needs
to avoid current-directory ambiguity.

```console
kast ready --for agent --workspace-root "$PWD"
kast status --backend=headless --workspace-root "$PWD"
kast agent verify --backend=idea --workspace-root "$PWD"
```

Backend selection pins a command to the selected runtime. It does not redefine
the semantic command dialect; both IDEA and headless runtimes serve the same
typed agent command surface.

## Setup Boundary

`kast setup` is the non-macOS repository guidance path. On macOS, repository
setup is owned by the IntelliJ IDEA or Android Studio plugin after the project
opens.

Setup installs only:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` guidance region in the selected context file

It does not install Copilot package files, portable instruction packages,
session hooks, generated catalog copies, workflow helper assets, or public raw
transport.

## Source And Validation

Command names are owned by `cli-rs/src/cli/` and the installed `kast --help`
tree. Published docs checks are:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```
