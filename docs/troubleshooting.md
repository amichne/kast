---
title: Troubleshooting
description: Diagnose common Kast CLI install, backend, agent, and LSP issues.
icon: lucide/life-buoy
---

# Troubleshooting

Start with commands that report active state instead of guessing. `kast ready`
checks managed install state, `kast developer inspect paths` explains filesystem resolution, and
`kast developer runtime status` reports backend state for the current workspace.

## Install state

Use these checks when the wrong binary, stale repository files, or missing
plugin links are suspected.

??? question "Which Kast install is active?"

    Run readiness in JSON mode first. The payload includes config validity,
    install manifest state, canonical paths, binary linkage, issues, and
    warnings.

    ```console
    kast --output json ready --for machine
    kast developer inspect paths
    ```

    If readiness reports repairable managed state, run the repair command once and
    inspect again.

    ```console
    kast ready --fix
    kast ready
    ```

??? question "The shell cannot find `kast`"

    Open a fresh shell so the updated `PATH` takes effect. If that does not
    help, install shell integration and verify the active shim path.

    ```console
    kast developer machine shell --shell zsh
    kast developer inspect paths
    command -v kast
    ```

    Use `--shell bash` for Bash profiles. If no `kast` binary is reachable,
    rerun the platform install path from the install guide.

??? question "IDEA or Android Studio does not load the plugin"

    The macOS developer install includes the Homebrew-managed plugin cask. Use
    the direct repair path, then restart the IDE.

    ```console
    brew reinstall --cask kast-plugin
    kast developer machine plugin
    kast ready
    ```

    `kast ready` should report the managed plugin and JetBrains profile links.
    If it does not, inspect the profile root shown by `kast developer inspect paths --idea`.

??? question "Repository Copilot files look stale"

    Reinstall the repository-local package with the active binary. This updates
    managed files under `.github` and refreshes resource checksums in the
    install manifest.

    ```console
    cd /path/to/your/repository
    kast setup --force
    kast ready
    ```

## Backend state

Use lifecycle commands when semantic commands fail, hang, or return stale
results.

??? question "The backend will not start"

    Start from the visible lifecycle commands. Use JSON status if you need log
    paths or machine-readable details.

    ```console
    kast ready --for kotlin
    kast developer runtime up --backend=headless
    kast developer runtime status --backend=headless
    kast --output json developer runtime status --backend=headless
    ```

    Check Java 21 or newer for the headless backend:

    ```console
    java -version
    ```

    If the IDEA backend is selected, confirm the project is open in IDEA or
    Android Studio and the Kast plugin is installed.

??? question "Indexing takes too long"

    First starts can be slow on large multi-module projects. Watch status until
    the backend reaches a servable state.

    ```console
    kast developer runtime status
    kast --output json developer runtime status
    ```

    If indexing never converges, verify the Gradle project itself works, then
    inspect the daemon log path from the JSON status payload.

??? question "Results look stale after file changes"

    Refresh the files that changed outside the backend, then rerun the semantic
    command.

    ```console
    APP_FILE="$PWD/src/main/kotlin/App.kt"

    kast agent call raw/workspace-refresh --params "{\"filePaths\":[\"$APP_FILE\"]}"
    kast agent call raw/diagnostics --params "{\"filePaths\":[\"$APP_FILE\"]}"
    ```

    The same refresh pattern applies before resolve, references, file outline,
    or code action calls when disk changed outside Kast's observation window.

## Semantic results

Use the response metadata before deciding whether a result is complete,
bounded, or failed.

??? question "A symbol is not found"

    Confirm the file path is absolute, inside the workspace, and points at a
    Kotlin identifier. Use `workspace-symbol` when you only know the name.

    ```console
    kast agent call raw/workspace-symbol --params '{"pattern":"OrderService","maxResults":20}'
    kast agent call raw/resolve --params "{\"position\":{\"filePath\":\"$PWD/src/main/kotlin/App.kt\",\"offset\":42}}"
    ```

    If the file is new or recently edited, refresh it first.

??? question "References return partial results"

    Read `result.searchScope.exhaustive` in the `kast agent call raw/references`
    envelope.

    ```console
    kast agent call raw/references \
      --params "{\"position\":{\"filePath\":\"$PWD/src/main/kotlin/App.kt\",\"offset\":42},\"includeDeclaration\":true}"
    ```

    `true` means every candidate file was searched. `false` means the search
    was bounded; compare candidate and searched file counts before making a
    completeness claim.

??? question "Call hierarchy is truncated"

    Call hierarchy is intentionally bounded by depth, fan-out, total nodes, and
    timeout. Increase limits only when the larger tree is useful.

    ```console
    kast agent call raw/call-hierarchy \
      --params "{\"position\":{\"filePath\":\"$PWD/src/main/kotlin/App.kt\",\"offset\":42},\"direction\":\"INCOMING\",\"depth\":5}"
    ```

    Read `result.stats` to identify which bound stopped traversal.

## Mutations

Plan mutations before writing files. Kast rejects apply steps when a file hash
no longer matches the planned state.

??? question "Rename planning fails"

    Check that the backend advertises rename support and that the target is not
    generated or read-only.

    ```console
    kast developer runtime capabilities
    kast agent call raw/rename \
      --params "{\"position\":{\"filePath\":\"$PWD/src/main/kotlin/App.kt\",\"offset\":42},\"newName\":\"newName\",\"dryRun\":true}"
    ```

??? question "Apply-edits rejects with a conflict"

    A file changed between plan and apply. Recreate the plan from the current
    file state, review it, and apply the fresh plan.

    ```console
    kast agent call raw/rename \
      --params "{\"position\":{\"filePath\":\"$PWD/src/main/kotlin/App.kt\",\"offset\":42},\"newName\":\"newName\",\"dryRun\":true}" > rename-plan.json
    ```

## LSP hosts

LSP failures are usually command path, workspace root, backend, or stdio
framing problems. Prove the same workspace outside the host before debugging
host logs.

??? question "The LSP host cannot start Kast"

    Verify the repository package and backend from a normal shell.

    ```console
    kast ready
    kast developer runtime status --workspace-root "$PWD"
    kast agent call health --params '{}' --workspace-root "$PWD"
    ```

    If those commands pass, inspect the host logs for the exact
    `kast agent lsp --stdio` command, working directory, and environment.

## Getting help

When opening an issue, include command output that proves the active state.
Prefer JSON where the command supports it.

```console
kast --output json ready
kast --output json developer runtime status
kast developer inspect paths
```
