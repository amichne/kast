---
type: API Contract
title: Codex Plugin Reference
description: The separately published kast@kast surface and runtime boundary.
tags: [codex, reference, hooks]
code_sources:
  - path: cli-rs/src/codex/hook.rs
  - path: cli-rs/src/machine.rs
---

# Codex Plugin Reference

`kast@kast` is the sole Kast agent-guidance surface. It is published from the
public [amichne/kast-marketplace](https://github.com/amichne/kast-marketplace)
repository and tracks its `main` branch independently from Kast releases.

## Installed components

| Component | Contract |
| --- | --- |
| Routing skill | Points Codex to the installed Kast CLI for Kotlin and Gradle semantic work. |
| `SessionStart` hook | Delegates startup and daemon awareness to the CLI for the task's exact root. |
| `PostToolUse` hook | Delegates post-write diagnostics to the CLI. |
| Launcher | Resolves `kast` from `PATH`, then `$HOME/.local/bin/kast`, and forwards the hook event. |

The plugin contains no MCP server, app connector, independent semantic
protocol, embedded CLI, or duplicate workspace guidance generator.

## Runtime boundary

IDEA owns compiler state and workspace indexing. The CLI owns daemon,
compatibility, command execution, and structured result concerns. The Codex
plugin only supplies hooks and invocation guidance.

Machine receipts hash the CLI and IDEA plugin only. Marketplace contents and
plugin versions are not coupled to Kast release digests. Reconciliation
re-registers `amichne/kast-marketplace --ref main` and installs `kast@kast`.

Kast never creates, migrates, or removes `AGENTS.md`, `AGENTS.local.md`, or
workspace skill files. Files left by older versions are ordinary user-visible
workspace content.
