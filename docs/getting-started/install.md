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
cd /path/to/your/repository
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer is macOS-only. It uses Homebrew to install the global `kast`
binary, invokes the version-coupled IntelliJ IDEA or Android Studio plugin
installer, and asks you to open the repository so the plugin can prepare workspace metadata.
Run it from the repository root; the current directory is the default
workspace root. Like the Homebrew installer, mutating commands explain what they
will do and pause before they change the machine. Use `NONINTERACTIVE=1` only
when automation has already accepted that plan. The script fails before mutation
for unsupported hosts, unknown commands, invalid flags, invalid tap values,
invalid tap URLs, or missing workspace roots.

Use `update` when the Homebrew path is hidden behind the installer:

```console title="Refresh the developer-machine install"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update --workspace-root "$PWD"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- verify --workspace-root "$PWD"
```

The default Homebrew tap is `amichne/kast`. Pass both `--tap` and `--tap-url`
for mirrors on a custom Git host:

```console title="Install from an internal Homebrew tap"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- install \
  --tap internal/kast \
  --tap-url https://git.example.com/internal/homebrew-kast.git \
  --workspace-root "$PWD"
```

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

Use `kast ready` when you need the manifest-backed readiness view.

```console
kast ready --for agent --workspace-root "$PWD"
kast --output json ready --for agent --workspace-root "$PWD"
```

## IDE Plugin

Use the installer update path when local JetBrains profile links need repair:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update --workspace-root "$PWD"
```
