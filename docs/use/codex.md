---
type: How-to Guide
title: Use Kast in Codex
description: Ask Codex for Kotlin work backed by the open IDEA project.
tags: [codex, kotlin, workflow]
code_sources:
  - path: cli-rs/src/codex/hook.rs
  - path: install.sh
---

# Use Kast in Codex

This guide shows how to use the installed Kast plugin without operating Kast
directly.

## Open the project first

Open the exact project or worktree in IntelliJ IDEA or Android Studio. Wait for
project loading and indexing to settle, then start a new Codex task rooted at
the same directory.

## Describe the work

Ask Codex for the Kotlin outcome you need. For example:

```text
Find the callers of OrderService.submit and explain which one owns retries.
```

```text
Rename this Kotlin declaration and verify the resulting files.
```

The plugin delegates semantic discovery, mutations, daemon state, and
compatibility decisions to the installed Kast CLI. Unsupported work can fall
back to Codex's normal tools.

## Refresh the plugin

Close the IDE and rerun the workstation installer. Reconciliation
fast-forwards `amichne/kast-marketplace@main`, installs `kast@kast`, and leaves
old workspace guidance or skill files untouched. Start a new Codex task so the
updated plugin is discovered.

Two advisory hooks add local context: task startup asks Kast to prepare the
exact root, and a successful Kotlin write requests diagnostics when that root
has a healthy IDEA runtime. Hook failures add context but do not deny an edit
or stop the task.

See the [plugin reference](../reference/codex-plugin.md) for the fixed boundary.
