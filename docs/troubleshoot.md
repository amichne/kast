---
title: Troubleshoot
description: Diagnose install issues, backend state, indexing, semantic failures, and mutations.
icon: lucide/triangle-alert
---

# Troubleshoot

Start with the visible symptom. Most readers do not need to run the underlying
checks themselves; the agent or support workflow can run the read-only command
sequence when needed.

## Diagnostic Matrix

| Symptom | Likely cause | Next action |
| --- | --- | --- |
| Kast CLI does not appear after install | Homebrew formula or CLI receipt repair did not complete | Rerun the installer, then run `kast ready --for machine` |
| Kast plugin does not appear | JetBrains did not install the release ZIP | Quit the IDE and rerun `install.sh install`, or use **Install Plugin from Disk** with the exact release ZIP |
| The IDE does not discover Kast updates | The GitHub Release feed is not enrolled | Add `https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml` as a custom plugin repository |
| A plugin update asks for restart | The IDE refused dynamic unload for this build or runtime state | Accept the JetBrains restart fallback; Kast does not force hot replacement |
| `kast@kast` does not appear in Codex | The extracted Kast marketplace is not configured, or the plugin was not installed from it | Add the marketplace root, run `codex plugin add kast@kast`, and start a new Codex task |
| Codex reports a Kast/plugin version mismatch | The marketplace archive and active Kast binary came from different releases | Install the matching CLI and Codex plugin release, reinstall `kast@kast`, and start a new task |
| A generic Kotlin edit is denied | The typed Kast mutation route has not produced target-bound fallback evidence | Let Codex try the corresponding typed mutation first; fall back only after its typed failure is recorded |
| Codex continues instead of stopping | Changed Kotlin lacks diagnostics for its current hash or an explicit typed blocker | Run diagnostics for every changed Kotlin file or report the typed blocker |
| A delegated task reports the wrong workspace | The task and semantic evidence refer to different linked worktrees | Open and prepare the exact delegated worktree, then start verification there |
| `~/.local/bin/kast` runs instead of Homebrew Kast | An older managed local shim precedes Homebrew on `PATH` | Run read-only machine readiness and use its cleanup command only when one is offered |
| Repair asks for the IDE to close | A recognized legacy Homebrew plugin symlink is ready for bounded removal | Close the affected IDE window and rerun `kast repair --for machine --apply` |
| The agent cannot use Kast in a macOS project | The project has not been opened with the Kast plugin active | Open the project in the IDE and let the plugin prepare it |
| `agent verify` reports `SEMANTIC_WORKSPACE_UNPREPARED` in a worktree or temporary checkout | That exact root has no admitted IDEA or headless semantic state | Prepare that exact root with the JetBrains plugin, or use an already installed supported headless distribution |
| `agent verify` reports `SEMANTIC_WORKSPACE_UNSUPPORTED` | The selected root is not a Kotlin Gradle workspace | Select the root containing `settings.gradle(.kts)` or `build.gradle(.kts)` |
| `agent verify` reports `SEMANTIC_BACKEND_AMBIGUOUS` | IDEA and headless are both ready for the exact root | Rerun with `--backend=idea` or `--backend=headless` after checking the candidate evidence |
| An applied command reports `SEMANTIC_MUTATION_AUTHORITY_REQUIRED` | The read-only headless route was selected without exact-root plugin preparation on macOS | Open that exact root with the JetBrains-installed plugin, verify it, then rerun the applied command |
| Hosted Linux agent cannot answer semantic questions | Headless bundle or backend is not active | Check the image/bootstrap flow and runtime state |
| Symbol lookup returns an unexpected target | The query is too broad | Narrow by kind, file, or containing type before editing |
| Diagnostics disagree with the file on disk | Backend source state may be stale | Refresh or restart the runtime before retrying |
| Rename or mutation plan selects the wrong scope | The selector or identity is too broad | Resolve identity first and use a narrower selector |

## Verify A Temporary Checkout

Use this path when an agent is running in a linked worktree, disposable clone,
or release-conflict checkout. Verification is read-only: it reports supported
next actions but does not run setup, copy metadata, launch an IDE, repair the
installation, start a headless runtime, or change global install authority.
It also preserves `daemons.json` exactly; use an explicit lifecycle command if
stale runtime state should be pruned.

On macOS, open the exact checkout root in IntelliJ IDEA or Android Studio with
the JetBrains-installed Kast plugin enabled. After the plugin has prepared that
root, rerun verification and the read-only semantic commands against the same
absolute path.

```console
kast --output json agent verify --backend=idea --workspace-root "$PWD"
kast --output json agent symbol --query <name> --workspace-root "$PWD"
kast --output json agent diagnostics \
  --file-path "$PWD/path/to/File.kt" \
  --workspace-root "$PWD"
```

On a host with the supported headless distribution already installed and an
exact-root runtime already ready, select the headless backend explicitly.
Verification reuses that runtime and never starts, installs, or repairs a
headless backend. Subsequent read-only symbol and diagnostics commands may use
the installed distribution's normal lifecycle path.

```console
kast --output json agent verify --backend=headless --workspace-root "$PWD"
kast --output json agent symbol \
  --backend=headless \
  --query <name> \
  --workspace-root "$PWD"
kast --output json agent diagnostics \
  --backend=headless \
  --file-path "$PWD/path/to/File.kt" \
  --workspace-root "$PWD"
```

Check the returned backend, workspace root, source modules, limitations, and
evidence quality before consuming symbol or diagnostics results. If the root
does not match the temporary checkout, stop; Kast must not reuse that state.
If automatic verification reports two ready candidates, choose one explicitly.
If only one backend kind is ready, automatic verification selects it regardless
of the host fallback.
Do not use the unprepared headless route for applied mutations on macOS; plugin
preparation for the exact root remains mandatory and is checked before runtime
descriptor discovery.

## Recover The Codex Plugin

Use this sequence when Codex does not load the Kast skill or hooks, or when it
reports a release mismatch.

1. Confirm that the active `kast` binary came from the intended release.
2. Confirm that the configured marketplace root contains `marketplace.json`
   and `plugins/kast/.codex-plugin/plugin.json` from that same release.
3. Reinstall and inspect the plugin:

    ```console
    codex plugin add kast@kast
    codex plugin list
    ```

4. Start a new Codex task. Do not use the task that observed the older plugin
   generation.
5. Open or prepare the exact linked worktree if session-start evidence reports
   another root.

The plugin does not bundle Kast and cannot repair a missing CLI. It also cannot
apply a setup or repair plan from a hook.

## Handle A Legacy Global Kast Skill

The provider-neutral repository or workspace `.agents/skills/kast` remains
supported and should not be removed. A separate global
`~/.codex/skills/kast` may predate the plugin marketplace.

Ask Kast for a plan before changing the global copy:

```console
kast repair --for agent --workspace-root "$PWD"
```

Apply only when the plan proves that a Kast receipt owns the legacy target and
the user has authorized the cleanup. The repair backs up the recognized copy
before removal. If ownership is unknown, leave the directory unchanged and
report it; do not delete it manually to make plugin discovery pass.

## Keep Fixes Plan-First

Repair and mutation flows should report what they intend to change before they
write. If a plan points at the wrong symbol, scope, or file, refine the request
instead of applying it.

??? info "Read-only checks for agents and support"
    These commands separate install readiness, backend state, and semantic
    capability without changing source files.

    ```console
    kast --output json ready --for agent --workspace-root "$PWD"
    kast --output json agent verify --workspace-root "$PWD"
    kast --output json status --workspace-root "$PWD"
    ```

    When agent readiness fails, inspect `agentEnvironment.skills.candidates`
    and `agentEnvironment.guidance`. Their state and source path distinguish a
    stale selectable skill from missing, modified, user-owned, managed, or
    foreign guidance. A `repairCommand` is a direct, recoverable next step;
    readiness itself does not execute it or overwrite user content.

    On macOS, machine readiness also reports the active install authority, the
    exact Homebrew binary from the trusted receipt, and any legacy PATH shadow.

    ```console
    kast --output human ready --for machine
    ```

    A cleanup command is intentionally absent unless Kast proves that the
    shadow is its own writable legacy shim and that Homebrew is the next PATH
    candidate. Unknown or administrator-owned files are left unchanged.

    If runtime state is stale, agents or support workflows can restart the
    selected backend and verify again.

    ```console
    kast developer runtime status --workspace-root "$PWD"
    kast developer runtime restart --backend=headless --workspace-root "$PWD"
    kast agent verify --workspace-root "$PWD"
    ```
