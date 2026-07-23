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

On macOS, install one supported host:

- IntelliJ IDEA 2026.2, build 262; or
- Android Studio 2026.1.2, build 261.

Recommended setup enables background project opening. Start Codex from the
exact project or worktree root. Kast then follows the matching path:

| Project state | Result |
| --- | --- |
| Exact root already open | Reuse that project without focusing or moving it. |
| Existing root closed | Open a new project frame in the sole running supported IDE. |
| New worktree | Background-open the root and let the plugin create its metadata. |
| No IDE running | Background-launch the sole supported installed app. |

Kast requests background launch without a new application process. The
developer's native macOS tab preference remains in force, and frame placement
follows the active IDEA project where public APIs permit it.

If installed plugin bytes already match, rerunning setup does not close a
running IDE. If they differ, noninteractive setup returns
`IDE_RESTART_REQUIRED`. The interactive installer asks before closing the sole
selected IDE and relaunches that app in the background after setup.

On Linux or a hosted agent, the bundle contains the headless backend. Keep the
task rooted at the Gradle workspace you intend to analyze.

Check readiness:

```console
kast ready --for kotlin
```

If the result is not ready, follow its reported next action or use
[Troubleshoot Kast](troubleshoot.md).

An `INDEXING` result is an expected early success: the exact runtime is
reachable while Gradle import, IDEA smart mode, Kotlin admission, or Kast's
reference index is still running. Semantic work begins after `READY`.
