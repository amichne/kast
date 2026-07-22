---
type: How-to Guide
title: Install or Update Kast
description: Activate one verified Kast release on macOS or Linux and prepare a workspace for compiler-backed tasks.
tags: [install, update, macos, linux, idea, headless]
code_sources:
  - path: install.sh
  - path: cli-rs/src/install/bundle_install.rs
  - path: cli-rs/src/install/bundle_validation.rs
  - path: cli-rs/src/manifest.rs
---

# Install or Update Kast

Use the same setup transaction for a first install, an update, a downgrade, or
recovery.

## Install the current release

On macOS or Linux, run:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The bootstrap selects the platform bundle and delegates activation to
`kast setup`. Kast stages and verifies the complete release before switching
`current`. If final verification fails, the prior active release remains
usable.

The default installation root is `~/.local/share/kast`. The active executable
is:

```console
~/.local/share/kast/current/bin/kast version
```

When Codex is installed, setup also fast-forwards
`amichne/kast-marketplace` and installs `kast@kast` from that independent
marketplace.

## Install a local or pinned bundle

Pass the archive or extracted bundle to the bootstrap:

```console
./install.sh --source /path/to/kast-platform-vX.Y.Z.tar.gz
```

For development from this checkout, build and activate one matched bundle:

```console
./gradlew refreshDevelopmentMachine
```

## Prepare the compiler runtime

On macOS, open the exact project or worktree root in IntelliJ IDEA or Android
Studio. The matched plugin publishes runtime metadata for that root after the
project model and Kotlin analysis are usable.

On Linux or a hosted agent, the bundle contains the headless backend. Keep the
task rooted at the Gradle workspace you intend to analyze.

Check readiness:

```console
kast ready --for kotlin
```

If the result is not ready, follow its reported next action or use
[Troubleshoot Kast](troubleshoot.md).
