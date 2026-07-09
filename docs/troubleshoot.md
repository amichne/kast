---
title: Troubleshoot
description: Diagnose install drift, backend state, indexing, semantic failures, and mutations.
icon: lucide/triangle-alert
---

# Troubleshoot

Start with read-only commands. They separate install drift, runtime state, and
semantic backend capability without changing the repository.

```console
kast --output json ready --for agent --workspace-root "$PWD"
kast --output json agent verify --workspace-root "$PWD"
kast --output json status --workspace-root "$PWD"
```

## Diagnostic Matrix

Use the matrix to choose the smallest check that proves the failing layer.

| Symptom | Likely cause | Read-only check | Fix path |
| --- | --- | --- | --- |
| Readiness reports managed-resource drift | Repository guidance or metadata is stale | `kast --output json ready --for agent --workspace-root "$PWD"` | Run `kast --output json repair --for agent --workspace-root "$PWD"`, review the plan, then rerun with `--apply` |
| `kast setup` fails on macOS | Workspace setup is plugin-owned on macOS | `kast ready --for agent --workspace-root "$PWD"` | Open the repository in IDEA or Android Studio with the Kast plugin enabled |
| `agent verify` reports missing backend | IDEA backend is unavailable or headless runtime is not started | `kast developer runtime status --workspace-root "$PWD"` | Start or restart the selected backend, then rerun `kast agent verify` |
| Verification reports indexing or incomplete source index | Gradle/project indexing is still in progress | `kast agent verify --workspace-root "$PWD"` | Wait for readiness or restart the runtime if the state is stale |
| Symbol lookup returns an unexpected target | Query is too broad or the file hint is missing | `kast agent symbol --query OrderService --workspace-root "$PWD"` | Refine with kind, file hint, or containing type before mutation |
| Diagnostics disagree with the file on disk | Backend file state is stale | `kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"` | Refresh runtime state and rerun diagnostics |
| Rename or mutation plan selects the wrong scope | The selector is too broad or the symbol identity is wrong | Rerun the mutation without `--apply` | Resolve identity first and use a narrower selector |

## Repair Install Drift

Repair is plan-first. Do not jump directly to `--apply` in automation.

```console
kast --output json repair --for agent --workspace-root "$PWD"
kast --output json repair --for agent --workspace-root "$PWD" --apply
```

Use [runtime and output modes](reference/runtime-and-output.md) when a script
needs stable JSON or backend selection.

## Restart A Backend

Use runtime commands when backend state is stale, upgraded, or pointed at the
wrong workspace.

```console
kast developer runtime status --workspace-root "$PWD"
kast developer runtime restart --backend=headless --workspace-root "$PWD"
kast agent verify --workspace-root "$PWD"
```

Restart is broader than a workspace refresh. Use it when runtime state,
backend upgrade state, or path resolution is suspect.

## Recheck Semantic Commands

Resolve identity and run diagnostics before retrying a mutation.

```console
kast --output json agent symbol --query OrderService --workspace-root "$PWD"
kast --output json agent diagnostics \
  --file-path "$PWD/src/main/kotlin/App.kt" \
  --workspace-root "$PWD"
```

For mutation commands, rerun without `--apply` first. Review the planned
request, selected scope, content file, and diagnostics before applying.
