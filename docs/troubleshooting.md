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

??? question "Daemon won't start"

    **Symptoms:** `kast health` returns an error or hangs.

    1. Verify the workspace root exists and contains Kotlin sources:

        ```console
        kast health --workspace-root=/path/to/project
        ```

    2. Check that Java 21 or newer is available:

        ```console
        java -version
        ```

    3. Look for a stale socket file. If the daemon crashed without
       cleanup, the socket may still exist:

        ```console
        ls /tmp/kast-*.sock
        ```

        Remove any stale sockets and retry.

??? question "Indexing takes too long"

    On first start, the daemon indexes the entire workspace. Big
    multi-module projects can take 30–60 seconds.

    - Run `kast workspace status` to watch progress
    - Wait for `state: READY` before running queries
    - If indexing never finishes, check the project's Gradle
      wrapper works (`./gradlew tasks` should succeed)
    - Pass `--accept-indexing=true` to `workspace ensure` if you
      can live with partial results while indexing finishes

??? question "Shell can't find kast after install"

    Open a fresh shell so the updated `PATH` takes effect. If
    that doesn't help:

    - Check the install root is on `PATH`:
      `echo $PATH | tr ':' '\n' | grep kast`
    - Point `KAST_CLI_PATH` at the binary:
      `export KAST_CLI_PATH=/absolute/path/to/kast`

## Analysis results

??? question "Symbol not found"

    **Symptoms:** `kast resolve` returns empty or a `NOT_FOUND`
    error.

    - Confirm the file path is absolute and inside the workspace
      root
    - Confirm the offset lands on an actual identifier (not
      whitespace or a comment)
    - Confirm the daemon finished indexing
      (`kast workspace status` shows `state: READY`)
    - If the file is brand new, run `kast workspace refresh` to
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

    **Symptoms:** `kast diagnostics` reports an error you already
    fixed, or misses a problem you just introduced.

    The daemon caches the last view of disk it observed. If you
    (or your agent, or `git checkout`) modified files outside its
    observation window, you'll get a stale answer. Refresh first:

    ```console
    kast workspace refresh --workspace-root=$(pwd)
    kast diagnostics --workspace-root=$(pwd) --file-paths=$(pwd)/src/App.kt
    ```

    Same fix applies to `resolve`, `references`, `outline`, and
    any other read command that looks suspiciously out of date.

## Mutations

??? question "Rename fails with capability error"

    Both backends support rename. Run `kast capabilities` to
    confirm.

    If the rename target is in a generated file or a read-only
    location, the operation fails with a descriptive error.

??? question "Apply-edits rejects with conflict error"

    A file changed between plan and apply. The SHA-256 hash no
    longer matches.

    1. Re-run `rename` for a fresh plan with updated hashes
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

1. Run `kast health` and `kast workspace status` and capture the
   output
2. Check daemon stderr for stack traces
3. Open an issue at
   [github.com/amichne/kast](https://github.com/amichne/kast/issues)
   with the diagnostic output
