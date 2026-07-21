---
type: Explanation
title: The Kast Workstation Model
description: Why installation, compiler state, and agent interaction have separate owners.
tags: [architecture, codex, idea]
code_sources:
  - path: install.sh
  - path: cli-rs/src/machine.rs
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginService.kt
  - path: cli-rs/src/codex/hook.rs
---

# The Kast Workstation Model

Kast separates machine installation, compiler state, and user interaction so
each concern has one owner.

```mermaid
flowchart LR
    install["Installer\nCLI and IDEA"] --> idea["IDEA plugin\ncompiler state"]
    marketplace["Public marketplace\ntracks main"] --> codex["Codex plugin\nagent interface"]
    idea --> root["Exact open root"]
    root --> codex["Codex plugin\nagent interface"]
```

## The installer owns machine identity

The installer selects one CLI and one IDEA plugin ZIP. Reconciliation is
synchronous, requires the IDE to be closed, and separately fast-forwards the
public Codex marketplace. Nothing watches the machine afterward, because
installation is an occasional transaction rather than continuous work.

The machine bundle does not project a global skill or embed a marketplace.
Codex's native plugin selection is the only workstation agent integration.

## IDEA owns semantic state

The Kotlin compiler already lives inside IDEA or Android Studio with the loaded
project model. The Kast IDEA plugin exposes that state for the exact open root
instead of starting another local JVM. This also means two worktrees are two
different semantic workspaces.

## Codex owns interaction

Developers describe outcomes to Codex. The independently published plugin
routes requests to the installed CLI and keeps installer, release, and
runtime-management commands outside the normal task surface.

This boundary keeps the visible workflow small without weakening semantic
identity, plan-first mutations, diagnostics, or compatibility admission.
