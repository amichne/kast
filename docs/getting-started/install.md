---
title: Install
description: Install the Homebrew CLI and add minimal repository agent guidance.
icon: lucide/download
---

# Developer Machine Install

This page is the macOS developer-machine path. Linux CI, hosted agents, and
server images use the separate [Headless Linux server](headless-linux.md) path.

## Developer machine

```console
brew tap amichne/kast
brew install kast

cd /path/to/your/repository
kast setup --workspace-root "$PWD"
```

Homebrew installs the global `kast` binary and the version-coupled
`kast-plugin` cask. Repository setup is separate and intentionally small.

## Repository Agent Guidance

`kast setup` installs or repairs only two v1 assets:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` region in the selected repo context file

The default context target is the first existing file from `AGENTS.md`,
`CODEX.md`, `CLAUDE.md`, `.github/copilot-instructions.md`, or
`AGENTS.local.md`. If none exists, setup creates ignored `AGENTS.local.md`.

```console title="Plan and install repository guidance"
kast setup --dry-run --workspace-root "$PWD"
kast setup --workspace-root "$PWD"
kast setup --workspace-root "$PWD" --context-file "$PWD/cli-rs/AGENTS.md" --force
```

Use `--context-file` for additional explicit guidance targets. Use
`--no-auto-exclude-git` only when generated local guidance should remain visible
to Git.

Setup does not install Copilot package files, portable Markdown instruction
packages, session hooks, generated catalog copies, or workflow helper assets in
v1. Enterprise environments that cannot use public GitHub still only need to
mirror the Kast binary/runtime artifacts plus this skill and managed guidance
region; the setup flow is not MCP-dependent.

## Readiness And Repair

Readiness is read-only:

```console
kast ready --for agent --workspace-root "$PWD"
kast ready --for kotlin --workspace-root "$PWD"
```

Repair is explicit and plan-first:

```console
kast repair --for agent --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD" --apply
```

Use `kast developer inspect paths` when you need the manifest-backed path model.

```console
kast developer inspect paths
kast --output json developer inspect paths
```

## IDE Plugin

Use the Homebrew-managed cask or developer-machine command when local JetBrains
profile links need repair:

```console
brew reinstall --cask kast-plugin
kast developer machine plugin
```
