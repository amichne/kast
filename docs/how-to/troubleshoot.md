---
type: How-to Guide
title: Troubleshoot Kast
description: Diagnose setup, exact-root runtime, indexing, and semantic evidence failures without editing Kast state by hand.
tags: [troubleshooting, setup, idea, indexing, runtime]
code_sources:
  - path: cli-rs/src/self_mgmt.rs
  - path: cli-rs/src/runtime/workspace_admission.rs
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaIndexSemanticAdmission.kt
---

# Troubleshoot Kast

Start with the visible phase that failed. Kast separates installation,
workspace routing, runtime readiness, and semantic evidence so one recovery
action does not have to guess at all four.

| Symptom | Check | Action |
| --- | --- | --- |
| `kast` is missing or the active release is invalid | `~/.local/share/kast/current/bin/kast version` | Rerun the installer. |
| The wrong project is reported | `kast status` from the intended root | Open or select the exact root; do not reuse another checkout's runtime. |
| `IDEA_PLUGIN_UPDATE_REQUIRED` | Rerun setup and read its typed result | Close only the selected IDE if setup returns `IDE_RESTART_REQUIRED`, then retry. |
| `IDEA_VERSION_UNSUPPORTED` | Check the product build | Use IntelliJ IDEA 2026.2/build 262 or Android Studio 2026.1.2/build 261. |
| `IDEA_HOST_AMBIGUOUS` | Check running processes and installed bundles | Set `runtime.ideaLaunch.command` to the exact supported app. |
| `IDE_PROFILE_AMBIGUOUS` | Check supported JetBrains profiles | Rerun setup with `--idea-plugins-dir` for the selected host profile. |
| IDEA runtime is unavailable | Run `kast ready --for kotlin` from the exact root | Let Kast background-open the project; do not start a duplicate IDE process. |
| Runtime reports indexing | Wait for Gradle, IDEA/Kotlin, and Kast indexing | Retry `kast ready --for kotlin`. |
| Runtime reports degraded | Read its single actionable cause | Repair the named Gradle, Kotlin admission, or reference-index failure. |
| Kotlin source modules are unavailable | Check the IDE project model and SDK | Repair the IDE/Gradle model, then reopen the project. |
| Relationships are limited | Read the result's coverage and next action | Resume or narrow the query; do not treat a partial result as exhaustive. |
| A mutation is rejected | Check exact-root readiness and target identity | Prepare the workspace and resolve one exact declaration before retrying. |

## Recover setup

Rerun the same setup operation:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

For a pinned bundle:

```console
./install.sh --source /path/to/kast-platform-vX.Y.Z.tar.gz
```

Setup reports the failed phase and rerun command. A failed final verification
restores the prior verified release. Do not edit `current`, receipts, sockets,
or installed artifacts by hand.

## Inspect runtime state

Run these read-only checks from the intended workspace:

```console
kast status
kast ready --for kotlin
```

`status` describes the current workspace runtime. `ready` evaluates whether
that runtime is suitable for the requested task and reports typed limitations
such as indexing, missing source modules, or an unprepared workspace.

Kast progress and success are silent. It emits one deduplicated notification
for an actionable terminal Kast failure. Git, shallow-clone, IDE, and
third-party notifications remain owned by their source.

If the problem persists, include the workspace root, backend name, Kast
version, readiness limitation, and the exact failed command when reporting it.
