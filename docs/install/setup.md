---
type: How-to Guide
title: Install or Update Kast
description: Activate one complete, verified Kast release on macOS or Linux.
tags: [install, update, macos, linux, idea, codex]
code_sources:
  - path: install.sh
  - path: cli-rs/src/install/bundle_install.rs
  - path: cli-rs/src/install/bundle_validation.rs
---

# Install or Update Kast

Use the same operation for a first install, an update, a downgrade, or recovery.

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The bootstrap downloads the bundle for the current macOS or Linux platform and
delegates to `kast setup`. Success means the CLI, headless backend, IDEA plugin,
skill, guidance, config, and receipt all resolve from one verified release under
`KAST_HOME` (default `~/.local/share/kast`).

To install a downloaded or locally built bundle instead:

```console
./install.sh --source /path/to/kast-platform-vX.Y.Z.tar.gz
```

For local development, build and activate one bundle with:

```console
./gradlew refreshDevelopmentMachine
```

After setup succeeds, use `~/.local/share/kast/current/bin/kast`. On a
workstation, open the exact project or worktree in IntelliJ IDEA or Android
Studio so the bundled plugin can prepare that semantic workspace.

Rerun the same command whenever readiness fails. Setup serializes concurrent
runs, discards stale staging, replaces Kast-owned projections, and restores the
prior active release if final verification fails.
