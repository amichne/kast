---
type: How-to Guide
title: Use Kast in Codex
description: Ask Codex for Kotlin work backed by the open IDEA project.
tags: [codex, kotlin, workflow]
code_sources:
  - path: cli-rs/resources/codex-plugin/plugins/kast/skills/kast-codex/SKILL.md
  - path: cli-rs/resources/codex-plugin/plugins/kast/hooks/hooks.json
  - path: cli-rs/src/codex/hook.rs
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

The plugin resolves compiler identities before relationships and routes
supported writes through plan-first semantic mutations. Unsupported work can
fall back to Codex's normal tools.

## Review the result

Kast results distinguish exact compiler evidence from bounded or unavailable
evidence. A successful semantic mutation includes diagnostics for the resulting
contents.

Two advisory hooks add local context:

- Task startup asks Kast to open the exact root through the configured IDEA
  application.
- A successful Kotlin write requests diagnostics when that root has a healthy
  IDEA runtime.

Hook failures add context but do not deny an edit or stop the task. See the
[plugin reference](../reference/codex-plugin.md) for the fixed boundary.
