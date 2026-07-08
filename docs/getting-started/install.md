---
title: Install
description: Install the Homebrew CLI and activate the IntelliJ plugin setup path.
icon: lucide/download
---

# Developer Machine Install

This page is the macOS developer-machine path. Linux CI, hosted agents, and
server images use the separate [Headless Linux server](headless-linux.md) path.

## Developer machine

```console
brew tap amichne/kast
brew install kast
kast developer machine plugin
```

Homebrew installs the global `kast` binary and matching IntelliJ plugin
artifact.
Restart IntelliJ IDEA or Android Studio after Homebrew links or refreshes the
plugin, then open the repository. The plugin prepares the workspace.

## Workspace Setup

On macOS, workspace setup is owned by the IntelliJ plugin. It writes:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` region in the selected repo context file
- `.kast/setup/workspace.json` with the plugin-prepared invocation metadata

The CLI does not install skill-only, runtime-only, Copilot package, portable
instruction, session hook, generated catalog, or workflow helper state on
macOS. If prior Kast-managed files are not required or recognized by the
incoming plugin version, the plugin backs them up and removes them from the
active setup path.

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

Use the developer-machine command when local JetBrains profile links need
repair:

```console
kast developer machine plugin
```
