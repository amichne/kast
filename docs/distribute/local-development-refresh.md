---
title: Refresh a development machine
description: Activate one processless Kast CLI, IDEA plugin, and agent-resource bundle from a checkout.
---

# Refresh a development machine

Use this workflow when developing Kast itself on macOS. It selects one machine
bundle from the current checkout. It does not publish a release, create a
worktree-local installation, or start a headless IntelliJ JVM.

## Before you start

Close IntelliJ IDEA and Android Studio. Plugin reconciliation replaces the
installed Kast plugin and deliberately refuses to mutate a loaded plugin.

## Refresh

From the checkout you want to make authoritative, run:

```console
./gradlew refreshDevelopmentMachine
```

The task builds the Rust CLI and IDEA plugin, then performs two synchronous
steps:

1. `kast machine activate` stages and atomically selects a strict bundle of the
   CLI, task launcher, IDEA plugin ZIP, provider-neutral skill, task proof
   resources, and Codex and Copilot adapters.
2. `kast machine reconcile` verifies every selected digest, replaces the
   closed IDE's Kast plugin, selects the global Kast skill, and asks Codex's
   native plugin command to select the bundled adapter when Codex is installed.

The command returns only after reconciliation succeeds. The refresh installs no LaunchAgent,
plist, socket, watcher, or background process.

## Verify the machine authority

```console
kast --output toon machine status
```

A healthy result reports the selected machine bundle as active. After opening
the exact project in IDEA, verify its plugin-hosted backend:

```console
kast agent verify --workspace-root "$PWD"
```

Each worktree must be opened as its own IDEA project. The plugin writes only
`.kast/setup/workspace.json`; skills, Codex files, and CLI versions are
machine resources, not worktree projections.

## Worktree leases

Acquire one exact-root lease after the worktree's IDEA project is ready:

```console
kast agent lease acquire --workspace-root "$PWD"
```

The lease always borrows that IDEA plugin instance. There is no backend
selector and release never shuts down IntelliJ. Close the exact project window
before removing a worktree.

## Why there is no launchd service

Machine reconciliation occurs only after installation or an explicit refresh,
and plugin replacement requires IDEA to be closed. No command needs a resident
socket or event stream. A LaunchAgent would add restart policy, logs,
permissions, and upgrade state without making the bounded transaction safer.
If status later reports drift, close IDEA and run:

```console
kast machine reconcile
```

## Headless scope

macOS rejects local headless runtime requests with
`HEADLESS_LOCAL_UNSUPPORTED`. This prevents large IntelliJ JVMs from being
duplicated across worktrees. Linux CI, hosted-agent, and server distributions
retain the separate release headless runtime.
