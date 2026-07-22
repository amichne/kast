---
type: Reference
title: Codex Plugin Reference
description: Components and ownership boundaries of the separately published kast@kast plugin.
tags: [codex, reference, hooks, marketplace]
code_sources:
  - path: cli-rs/src/codex/hook.rs
  - path: install.sh
---

# Codex Plugin Reference

`kast@kast` is Kast's Codex guidance surface. It is published from the
public [amichne/kast-marketplace](https://github.com/amichne/kast-marketplace)
repository and tracks its `main` branch independently from Kast releases.

## Installed components

| Component | Contract |
| --- | --- |
| Routing skill | Tells Codex when to use the installed Kast CLI for Kotlin and Gradle semantic work. |
| `SessionStart` hook | Delegates startup and daemon awareness to the CLI for the task's exact root. |
| `PostToolUse` hook | Delegates post-write diagnostics to the CLI. |
| Launcher | Resolves `kast` from `PATH`, then `$HOME/.local/bin/kast`, and forwards the hook event. |

The plugin does not embed the Kast runtime. The matched CLI and compiler
backend come from the active setup release.

## Runtime boundary

IDEA owns compiler state and workspace indexing on macOS. The packaged
headless backend owns compiler state on supported non-IDE hosts. The CLI owns
exact-root routing, compatibility, command execution, and result projection.
The Codex plugin supplies hooks and invocation guidance.

Setup receipts hash the CLI and IDEA plugin only. Marketplace contents and
plugin versions are not coupled to Kast release digests. Reconciliation
re-registers `amichne/kast-marketplace --ref main` and installs `kast@kast`.

The plugin's two hooks are advisory. A hook failure can add task context, but
it does not silently turn an unprepared workspace into compiler-backed
evidence.
