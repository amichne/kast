---
type: API Contract
title: Codex Plugin Reference
description: The installed kast@kast surface, lifecycle, settings, and boundaries.
tags: [codex, reference, hooks]
code_sources:
  - path: cli-rs/resources/codex-plugin/plugins/kast/.codex-plugin/plugin.json
  - path: cli-rs/resources/codex-plugin/plugins/kast/hooks/hooks.json
  - path: cli-rs/src/codex/exposure.rs
  - path: cli-rs/src/codex/hook.rs
---

# Codex Plugin Reference

`kast@kast` is the sole agent-facing component in the macOS workstation bundle.
It contains one routing skill, two advisory hooks, generated command metadata,
and a launcher for the selected Kast binary.

## Installed components

| Component | Contract |
| --- | --- |
| Routing skill | Exposes compiler-backed inspection, relationships, impact, diagnostics, and plan-first Kotlin mutations to Codex. |
| `SessionStart` hook | Requests the configured IDEA application for the task's exact root and reports launch failures as context. |
| `PostToolUse` hook | Requests diagnostics after successful Kotlin writes when the exact-root IDEA runtime is healthy. |
| Launcher | Resolves the selected Kast binary and forwards the hook event without maintaining its own state. |

The plugin contains no MCP server, app connector, independent semantic
protocol, or global Kast skill.

## Hook settings

The IntelliJ Kast settings page controls the hooks globally. Both hooks are
enabled by default. The master switch disables both; each event can also be
disabled independently.

## Runtime boundary

IDEA owns compiler state and workspace indexing. The plugin uses only the exact
root supplied by the Codex task. Another clone or linked worktree is not a
compatible substitute.

Hooks are advisory. Typed semantic mutations remain plan-first and return only
after the resulting diagnostics reach a terminal outcome.

## Release identity

The installer selects CLI, IDEA plugin, and Codex plugin artifacts from one
release. The IDEA update feed can discover a newer plugin independently, but
runtime compatibility—not matching display text—decides whether a pair may
operate. Rerunning the installer restores the matched generation.
