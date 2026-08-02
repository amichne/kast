---
type: How-to Guide
title: How to Troubleshoot Kast
description: Diagnose setup, exact-root runtime, indexing, and semantic evidence failures without editing Kast state by hand.
tags: [troubleshooting, setup, headless, indexing, runtime]
code_sources:
  - path: cli-rs/src/operations/self_mgmt.rs
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
  - path: cli-rs/src/execution/runtime/backend/headless_authority.rs
---

# How to Troubleshoot Kast

Start with the visible phase that failed. Kast separates installation,
workspace routing, runtime readiness, and semantic evidence so one recovery
action does not have to guess at all four.

| Symptom | Check | Action |
| --- | --- | --- |
| `kast` is missing or the active release is invalid | Run `command -v kast`, then `kast` | Rerun the installer. |
| The wrong project is reported | Run `kast` from the intended root | Open or select the exact root; do not reuse another checkout's runtime. |
| `IDEA_SEMANTIC_BACKEND_RETIRED` | Check workspace runtime configuration | Run setup to migrate the legacy value to `headless`; do not recreate the old backend. |
| `IDEA_VERSION_UNSUPPORTED` | Check the installed runtime-source build | Use IntelliJ IDEA 2026.2/build 262 or Android Studio 2026.1.2/build 261. |
| `IDEA_HOST_AMBIGUOUS` | Check installed supported bundles | Configure one exact supported runtime source. Foreground state is irrelevant. |
| `HEADLESS_RUNTIME_CONFLICT` | Compare the reported exact-root identities | Stop only the stale Kast-owned runtime named by the typed result, then retry. |
| Headless runtime is unavailable | Run `kast` from the exact root | Run `kast up`; do not open or close a foreground IDE as a repair step. |
| Runtime reports indexing | Wait for headless Gradle, Kotlin, and Kast indexing | Retry `kast up`. |
| Runtime reports degraded | Read its single actionable cause | Repair the named Gradle, Kotlin admission, or reference-index failure. |
| Kotlin source modules are unavailable | Check the headless Gradle model and SDK | Repair the build model, then rerun `kast up`. |
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
kast
kast graph summary
```

The home result reports runtime readiness. The graph summary reports retained
coverage separately. A runtime can be ready while a graph result remains
incomplete. In that case, run `kast refresh` for the affected files and retain
the limitation until coverage is complete.

Kast progress and success are silent. It emits one deduplicated notification
for an actionable terminal Kast failure. Git, shallow-clone, IDE, and
third-party notifications remain owned by their source.

If the problem persists, include the workspace root, reported backend,
readiness limitation, and exact failed command.

For generation movement, repository coverage, continuation, label artifact,
or semantic-table failures, follow
[Maintain repository intelligence](maintain-repository-intelligence.md).
