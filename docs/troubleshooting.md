---
title: Troubleshooting
description: Common issues and solutions when running Kast.
icon: lucide/life-buoy
---

# Troubleshooting

When something breaks, start here. Each section names one failure
mode, lists symptoms, and walks you to a fix. Open the section
that matches what you're seeing.

## Installation and startup

??? question "I need to know which Kast install is active"

    Run doctor in JSON mode first:

    ```console
    kast --output json doctor
    ```

    The payload reports `configuration.valid`, `canonicalDirectory`,
    `binary.runningBinary`, `binary.configuredBinary`, install metadata,
    issues, and warnings. `doctor` still emits this payload when
    `config.toml` is malformed, so configuration failures can be diagnosed
    without guessing which file or binary was active.

??? question "Daemon won't start"

    **Symptoms:** a `health` JSON-RPC request returns an error or
    hangs.

    1. Verify the workspace root exists and contains Kotlin sources:

        ```console
        kast rpc '{"jsonrpc":"2.0","id":1,"method":"health"}'
        ```

    2. Check that Java 21 or newer is available:

        ```console
        java -version
        ```

    3. Look for a stale socket file. If the daemon crashed without
       cleanup, the socket may still exist in the configured socket
       directory. By default, that is your platform temp directory, not
       always `/tmp`:

        ```console
        find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'kast-*.sock'
        ```

        On macOS, `TMPDIR` is often under `/var/folders/...`. Remove any
        stale sockets and retry.

??? question "Indexing takes too long"

    On first start, the daemon indexes the entire workspace. Big
    multi-module projects can take 30–60 seconds.

    - Run `kast status` to watch progress
    - Wait for `state: READY` before running queries
    - If indexing never finishes, check the project's Gradle
      wrapper works (`./gradlew tasks` should succeed)
    - If indexing never reaches READY, inspect the daemon log from
      `kast status --output json`

??? question "Shell can't find kast after install"

    Open a fresh shell so the updated `PATH` takes effect. If that doesn't
    help:

    - Check that `$HOME/.local/bin/kast` exists and is executable:
      `test -x "$HOME/.local/bin/kast"`
    - Check that `$HOME/.local/bin` is on `PATH`:
      `echo "$PATH" | tr ':' '\n' | grep "$HOME/.local/bin"`
    - If you keep config outside the default directory, set
      `KAST_CONFIG_HOME` to the directory that contains `config.toml`.
    - If the binary lives somewhere else, set `[cli] binaryPath` in
      `$HOME/.config/kast/config.toml` to the executable path.

??? question "IDEA or Android Studio can't find the Kast plugin"

    **Symptoms:** IDEA or Android Studio starts without Kast diagnostics, or
    `kast doctor` reports missing JetBrains profile links.

    Install or repair the Homebrew-managed plugin link from the CLI:

    ```console
    kast install plugin
    ```

    Then restart the IDE so it reloads installed plugins.

??? question "Kast does not open IDEA for a Copilot session"

    **Symptoms:** Copilot starts, but `kast` reports `IDEA_NOT_RUNNING` or
    `IDEA_PLUGIN_NOT_INSTALLED` instead of opening the IDE.

    `kast` only opens a GUI IDE when both the backend and launch policy are
    explicit. Configure the IDEA launch policy:

    ```toml title="$HOME/.config/kast/config.toml"
    [runtime]
    defaultBackend = "idea"

    [runtime.ideaLaunch]
    enabled = true
    command = "idea"
    waitTimeoutMillis = 90000
    requireInstalledPlugin = true
    ```

    Then configure Copilot to launch the packaged Kast LSP with
    `--backend=idea` when you want IDE-hosted analysis. If the plugin check
    fails, install or repair the profile link:

    ```console
    kast install plugin
    ```

??? question "Copilot LSP package files look stale"

    Reinstall the packaged files with `--force`. This replaces the managed
    LSP package files under `.github` and `.agents/skills`, records the
    running CLI version, and leaves unrelated repository content in place.

    ```console
    kast install copilot --force
    ```

## Analysis results

??? question "Symbol not found"

    **Symptoms:** `raw/resolve` returns empty or a `NOT_FOUND`
    error.

    - Confirm the file path is absolute and inside the workspace
      root
    - Confirm the offset lands on an actual identifier (not
      whitespace or a comment)
    - Confirm the daemon finished indexing
      (`kast status` shows `state: READY`)
    - If the file is brand new, run `raw/workspace-refresh` through
      `kast rpc` to
      update the index

??? question "References return partial results"

    `kast` scopes analysis to the workspace root. References in
    files outside the workspace, in generated code, or in binary
    dependencies don't appear.

    Read `searchScope.exhaustive` in the response:

    - `true` — every candidate file was searched. The list is
      complete.
    - `false` — the search was bounded. Compare
      `candidateFileCount` and `searchedFileCount` to see the
      gap.

    See [Limits and boundaries](architecture/behavioral-model.md)
    for workspace scoping and visibility rules.

??? question "Call hierarchy is truncated"

    Call hierarchy is bounded by depth, fan-out, total edges, and
    timeout. Read the `stats` field to see which limits hit.

    Adjust these in the request:

    | Parameter | Default | What to change |
    |-----------|---------|----------------|
    | `depth` | 3 | Increase for deeper trees |
    | `maxTotalCalls` | 256 | Increase for wider graphs |
    | `maxChildrenPerNode` | 64 | Increase for highly-called functions |

    See [Limits and boundaries](architecture/behavioral-model.md#call-hierarchy-is-intentionally-bounded)
    for the full truncation model.

??? question "Diagnostics return stale results"

    **Symptoms:** `raw/diagnostics` reports an error you already
    fixed, or misses a problem you just introduced.

    The daemon caches the last view of disk it observed. If you
    (or your agent, or `git checkout`) modified files outside its
    observation window, you'll get a stale answer. Refresh first:

    ```console
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/workspace-refresh","params":{}}'
    kast rpc '{"jsonrpc":"2.0","id":2,"method":"raw/diagnostics","params":{"filePaths":["/absolute/path/to/src/App.kt"]}}'
    ```

    Same fix applies to `raw/resolve`, `raw/references`,
    `raw/file-outline`, and any other read method that looks
    suspiciously out of date.

## Mutations

??? question "Rename fails with capability error"

    Both backends support rename. Run `kast capabilities` to
    confirm.

    If the rename target is in a generated file or a read-only
    location, the operation fails with a descriptive error.

??? question "Apply-edits rejects with conflict error"

    A file changed between plan and apply. The SHA-256 hash no
    longer matches.

    1. Re-run `raw/rename` for a fresh plan with updated hashes
    2. Review the new plan
    3. Apply it before any other changes land

## Transport and connectivity

??? question "Connection refused on stdio transport"

    Using stdio (for example, from an agent):

    - Verify the daemon process is running and attached to
      stdin/stdout
    - Make sure no other process is competing for the same
      streams
    - Make sure JSON-RPC messages are line-delimited (one JSON
      object per line)

## Getting help

If nothing here resolves it:

1. Run `health` through `kast rpc`, then run `kast status`, and capture the
   output
2. Check daemon stderr for stack traces
3. Open an issue in the
   [Kast issue tracker](https://github.com/amichne/kast/issues) with the
   diagnostic output
