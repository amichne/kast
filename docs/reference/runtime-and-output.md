---
title: Runtime And Output Modes
description: Reference for backend selection, readiness, repair, runtime lifecycle, and output modes.
icon: lucide/activity
---

# Runtime And Output Modes

Runtime commands describe how Kast reaches an IDEA or headless backend. Output
modes describe how the CLI renders results for humans, scripts, and agents.

## Output Modes

The global selector is:

```console
kast --output json ready --for agent --workspace-root "$PWD"
```

| Mode | Use |
| --- | --- |
| `human` | Readable operator output |
| `json` | Machine-readable JSON for scripts and contract checks |
| `toon` | Compact structured output for agent-heavy command streams |

Pass the output mode explicitly in automation. Do not rely on terminal
detection when a parser depends on the result shape.

## Readiness And Repair

`ready` is read-only. `repair` plans by default and mutates only with
`--apply`.

| Command | Role |
| --- | --- |
| `kast ready --for agent` | Check agent guidance and semantic-command readiness |
| `kast ready --for kotlin` | Check Kotlin semantic readiness |
| `kast ready --for release` | Check release-task readiness |
| `kast ready --for machine` | Check developer-machine readiness |
| `kast repair --for <target>` | Report planned install-state repairs |
| `kast repair --for <target> --apply` | Apply planned repair actions |

Both commands accept `--workspace-root <path>` and `--backend <idea|headless>`.
`repair` also accepts `--jetbrains-config-root <path>` for JetBrains profile
audits.

## Runtime Lifecycle

Runtime lifecycle lives under `developer runtime`.

| Command | Role |
| --- | --- |
| `kast developer runtime up` | Start or warm the workspace daemon |
| `kast developer runtime status` | Check running backends |
| `kast developer runtime stop` | Stop the workspace daemon |
| `kast developer runtime restart` | Stop matching runtime state and start again |
| `kast developer runtime capabilities` | Print advertised backend capabilities |

```console
kast developer runtime status --workspace-root "$PWD"
kast developer runtime up --backend=headless --workspace-root "$PWD"
kast developer runtime capabilities --backend=idea --workspace-root "$PWD"
```

Use `restart` when runtime state, backend version, or path resolution is
suspect. Use `agent verify` after lifecycle changes to prove semantic
capability.

## Backend Selection

Kast has two public runtime modes behind the same command surface.

| Backend | Typical host | Notes |
| --- | --- | --- |
| `idea` | macOS developer machines with IntelliJ IDEA or Android Studio open | The plugin prepares workspace setup and serves semantic analysis |
| `headless` | Linux CI, hosted agents, server images, and mirrored artifact stores | The bundle installs the CLI, manifest, and backend runtime together |

Backend selection does not change agent command names. It only selects which
backend should answer the request.

## Status And Verification

`kast status` reports current workspace status. `kast agent verify` checks the
semantic backend and should be the last read-only check before semantic work.

```console
kast --output json status --workspace-root "$PWD"
kast --output json agent verify --workspace-root "$PWD"
```

Use the [troubleshooting matrix](../troubleshoot.md) when these checks disagree
or identify a stale backend.
