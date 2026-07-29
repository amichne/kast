---
type: How-to Guide
title: How to Install or Update Kast
description: Install one verified Kast release and connect the agent harnesses you use.
tags: [install, update, macos, linux, idea, headless, agents]
code_sources:
  - path: install.sh
  - path: cli-rs/src/operations/install/bundle_install.rs
  - path: cli-rs/src/operations/install/agent_resources.rs
---

# How to Install or Update Kast

Use the same transaction for a first install, update, downgrade, or repair:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer verifies the complete release before switching `current`. A
failed install leaves the prior release usable.

It also detects installed Codex, Claude, and Copilot executables and installs
Kast's release-matched local resources for each one. Select harnesses
explicitly when needed:

```console
./install.sh --harness codex --harness copilot
./install.sh --harness none
```

No remote marketplace checkout is required. Each tagged release also publishes
provider-specific resource archives, checksums, and provenance.

## Installed entrypoints

The default installation root is `~/.local/share/kast`.

| Path | Purpose |
| --- | --- |
| `~/.local/bin/kast` | Public agent interface. |
| `~/.local/bin/_kastctl` | Internal setup and maintenance control plane. |
| `~/.local/share/kast/current/bin/kast` | Release-bound public entrypoint. |
| `~/.local/share/kast/current/bin/_kastctl` | Release-bound internal entrypoint. |

Both entrypoints contain identical bytes; the invoked name selects the command
surface.

## Install a local or pinned bundle

```console
./install.sh --source /path/to/kast-platform-vX.Y.Z.tar.gz
```

For development from this checkout:

```console
./gradlew refreshDevelopmentMachine
```

## Prepare a workspace

On macOS, install one supported host:

- IntelliJ IDEA 2026.2, build 262; or
- Android Studio 2026.1.2, build 261.

Start an agent from the exact project or worktree root and run:

```console
kast
kast up
```

`kast up` reuses the exact open root or uses the supported background-open
path. It returns only after usable semantic evidence is available, or reports
the typed blocker to act on.

Refresh after source changes:

```console
kast refresh
kast refresh src/main/kotlin/App.kt
```

Inspect persisted evidence separately from runtime readiness:

```console
kast graph summary
```

A ready runtime and a non-empty graph do not prove exhaustive coverage. Use
the limitations returned by the operation you intend to act on.
