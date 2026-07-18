---
title: Choose A Command
description: Pick the Kast command family that matches the reader job.
icon: lucide/list-tree
---

# Choose A Command

Use this guide when you know the job but not the Kast command family. The public
shape should stay simple: developers install and open projects; agents and CI
use typed command families when they need semantic evidence.

## Start With The Job

| Reader job | Start with | Why |
| --- | --- | --- |
| Install on a macOS developer machine | Install | Installs the Homebrew CLI and initial release-matched JetBrains plugin |
| Prepare Linux CI or hosted agents | Headless install | Installs the self-contained CLI and backend bundle |
| Understand what an agent will do | Agent commands | Explains the typed semantic capabilities |
| Inspect Kotlin safely | Semantic inspection | Resolves identity before relying on usage evidence |
| Plan a Kotlin edit | Safe edits | Reviews target, diagnostics, conflicts, and write set first |
| Build or mirror artifacts | Distribution | Packages and validates release artifacts |

Use [command surface reference](../reference/commands.md) when you need the
curated command group list.

??? info "Agent and operator command families"
    The exact command names are useful for agent authors, CI, and support.

    | Need | Command family |
    | --- | --- |
    | Check task readiness | `kast ready` |
    | Repair managed state | `kast repair` |
    | Inspect runtime state | `kast status` and `kast developer runtime ...` |
    | Inspect Kotlin semantically | `kast agent symbol`, `diagnostics`, or `impact` |
    | Plan Kotlin edits | `kast agent rename` and mutation commands |
    | Package release artifacts | `kast developer release ...` |

Prefer typed agent commands over raw transport, generated catalog lookup, byte
offset selectors, or implementation class names.
