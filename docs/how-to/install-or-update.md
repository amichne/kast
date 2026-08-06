---
type: How-to Guide
title: How to Install or Update Kast
description: Install one verified Kast release and connect the agent harnesses you use.
tags: [install, update, macos, linux, indexer, agents]
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
| `~/.local/share/kast/current/bin/kast` | Release-bound public entrypoint. |
| `~/.local/share/kast/current/libexec/kastctl` | Private release-bound setup and maintenance control plane; not on `PATH` for standard or snapshot installs. |

Both entrypoints contain identical bytes; the invoked name selects the command
surface.

## Force installation replacement

```console
./install.sh --force
```

Forced setup replaces validated Kast-owned installation state and managed user
commands before reinstalling. It preserves workspace indexes, source
checkouts, and unrelated IDE extensions.

## Install a local or pinned bundle

```console
./install.sh --source /path/to/kast-platform-vX.Y.Z.tar.gz
```

For development from this checkout:

```console
./install.sh --development
```

The typed development setup profile builds this checkout and projects both
`kast` and `kastctl` into `~/.local/bin`. The receipt records the exact command,
path, and target for each projection. Development setup does not replace an
unmanaged `kastctl`. A later standard or snapshot setup removes the projection
only when the prior receipt and current symlink both prove Kast ownership.
Internally, this profile runs `./gradlew refreshDevelopmentMachine` through the
same verified setup transaction.

## Prepare a workspace

On macOS, install one supported IntelliJ runtime source:

- IntelliJ IDEA 2026.2, build 262; or
- Android Studio 2026.1.2, build 261.

Start an agent from the exact project or worktree root and run:

```console
kast
kast up
```

`kast up` reuses an eligible exact-root indexer. If none exists, it creates one
isolated process for the root. It returns only after usable semantic evidence
is available, or reports the typed blocker to act on.

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
