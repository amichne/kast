---
title: Troubleshooting
description: Diagnose readiness, backend, and semantic command failures.
icon: lucide/triangle-alert
---

# Troubleshooting

Start with read-only commands. They separate install drift, runtime state, and
semantic backend capability without changing the repository.

```console
kast --output json ready --for agent --workspace-root "$PWD"
kast --output json agent verify --workspace-root "$PWD"
kast --output json status --workspace-root "$PWD"
```

## Install Drift

If readiness reports managed-resource or setup drift, plan repair before
applying it.

```console
kast --output json repair --for agent --workspace-root "$PWD"
kast --output json repair --for agent --workspace-root "$PWD" --apply
```

??? question "Why does setup fail on macOS?"
    On macOS, repository setup is owned by the IntelliJ IDEA or Android Studio
    plugin. Open the repository in the IDE with the Kast plugin enabled, then
    rerun `kast ready --for agent --workspace-root "$PWD"`.

## Backend Not Ready

If `agent verify` reports indexing or a missing backend, check the runtime
state and refresh the workspace before retrying the semantic command.

```console
kast developer runtime status --workspace-root "$PWD"
kast developer runtime refresh --workspace-root "$PWD"
kast agent verify --workspace-root "$PWD"
```

??? question "What if Gradle has not loaded yet?"
    The IDEA plugin can request a Gradle project load on project open when
    `projectOpen.gradleLoadEnabled` is true. The default is enabled. Kast starts
    the backend before the Gradle refresh so `agent verify` can report progress
    instead of blocking.

## Semantic Command Fails

Resolve identity and run diagnostics before retrying a mutation. This confirms
the symbol target and the file state the backend sees.

```console
kast --output json agent symbol --query OrderService --workspace-root "$PWD"
kast --output json agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
```

For mutation commands, rerun without `--apply` first. Review the planned
request, selected scope, content file, and diagnostics before applying.

## Stale Command Surface

Current binaries intentionally remove generic helper commands from the public
agent dialect. Use the targeted replacement in the structured error.

??? question "What replaced `kast agent tools`?"
    Use `kast`, `kast help agent`, and the installed Kast skill for command
    discovery. Agent automation should call typed commands such as
    `kast agent verify`, `kast agent symbol`, `kast agent diagnostics`, and the
    plan-first mutation commands.

??? question "What replaced `kast agent call` and `kast agent workflow`?"
    Use the public typed commands. The raw catalog remains an implementation
    detail for generated protocol assets and internal adapters, not the normal
    agent-facing CLI.
