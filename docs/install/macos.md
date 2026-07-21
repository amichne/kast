---
type: How-to Guide
title: Install Kast on a Developer Workstation
description: Install Kast, IDEA integration, and the public Codex marketplace on macOS.
tags: [install, macos, idea, codex]
code_sources:
  - path: install.sh
  - path: cli-rs/src/machine.rs
    symbols: [activate, reconcile, reconcile_codex]
  - path: packaging/jetbrains/updatePlugins.xml
---

# Install Kast on a Developer Workstation

This guide installs Kast for a local Kotlin or Gradle project used through
Codex. You need macOS, Codex, and IntelliJ IDEA or Android Studio.

## 1. Close the IDE

Quit every IntelliJ IDEA and Android Studio process. The installer refuses to
replace a loaded plugin.

## 2. Run the installer

Run the single workstation entrypoint:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

Review the displayed plan and press Return. The installer taps the Kast
Homebrew repository, installs the CLI, downloads the release-matched IDEA
plugin, activates the machine bundle, fast-forwards
`amichne/kast-marketplace@main`, and selects `kast@kast` when Codex is
installed.

The completed bundle contains no global Kast skill, resident service, watcher,
or background JVM.

## 3. Add IDEA update discovery

In IDEA or Android Studio, open **Settings → Plugins → Manage Plugin
Repositories** and add:

```text
https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml
```

The feed lets the IDE discover Kast plugin releases through its native plugin
UI. The installer remains the authority for selecting a matched CLI and IDEA
bundle.

## 4. Open the exact root

Open the exact project or linked worktree you will give to Codex. The IDEA
plugin prepares compatibility metadata for that root and starts its semantic
runtime. A different checkout has different state and must be opened
separately.

## 5. Start a new Codex task

Start a new task after installation or update. Codex loads `kast@kast` at task
startup; an already-running task does not reload the new plugin generation.

Continue with [use Kast in Codex](../use/codex.md).

## Update the bundle

Quit the IDE and run:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update
```

Open the exact root again and start a new Codex task. If the IDE feed updates
the IDEA plugin independently, rerun this update before working so the CLI and
IDEA plugin return to one matched generation. The Codex plugin is updated
independently from the marketplace's `main` branch.
