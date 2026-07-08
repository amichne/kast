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
curl --fail --location --remote-name https://raw.githubusercontent.com/amichne/kast/main/install.sh
chmod +x install.sh
./install.sh install --workspace-root "$PWD"
```

The installer is macOS-only. It uses Homebrew to install the global `kast`
binary, invokes the version-coupled IDEA plugin installer, and asks you to open
the repository so the plugin can prepare workspace metadata. It fails before
mutation for unsupported hosts, unknown commands, invalid flags, invalid tap
values, invalid tap URLs, or missing workspace roots.

Use `update` when the Homebrew path is hidden behind the installer:

```console title="Refresh the developer-machine install"
./install.sh update --workspace-root "$PWD"
./install.sh verify --workspace-root "$PWD"
```

The default Homebrew tap is `amichne/kast`. Pass both `--tap` and `--tap-url`
for mirrors on a custom Git host:

```console title="Install from an internal Homebrew tap"
./install.sh install \
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
./install.sh update --workspace-root "$PWD"
```
