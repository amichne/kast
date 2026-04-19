---
title: Troubleshooting
description: Common issues and solutions when running Kast.
icon: lucide/life-buoy
---

# Troubleshooting

When something doesn't work, start here. Each section covers one
failure mode with symptoms, diagnostic steps, and a fix. Expand the
section that matches what you're seeing.

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

    The daemon indexes the entire workspace on first start. Large
    projects with many modules may take 30–60 seconds.

    - Run `kast workspace status` to monitor indexing progress.
    - Wait for `state: READY` before running analysis queries.
    - If indexing never completes, check that the Gradle wrapper in the
      project is functional (`./gradlew tasks` should succeed).
    - Pass `--accept-indexing=true` to `workspace ensure` if you can
      tolerate partial results while indexing finishes.

??? question "Shell can't find kast after install"

    Open a new shell session so the updated `PATH` takes effect. If
    that doesn't help:

    - Check whether the install root is on your `PATH`:
      `echo $PATH | tr ':' '\n' | grep kast`
    - Set `KAST_CLI_PATH` to point directly at the binary:
      `export KAST_CLI_PATH=/absolute/path/to/kast`

## Analysis results

??? question "Symbol not found"

    **Symptoms:** `kast resolve` returns an empty result or a
    `NOT_FOUND` error.

    - Confirm the file path is absolute and within the workspace root.
    - Confirm the offset points at an actual symbol (identifiers, not
      whitespace or comments).
    - Verify the daemon finished indexing (`kast workspace status`
      shows `state: READY`).
    - If the file was recently created, run `kast workspace refresh`
      to update the workspace index.

??? question "References return partial results"

    Kast scopes all analysis to the workspace root. References in
    files outside the workspace, in generated code, or in binary
    dependencies are not included.

    Check `searchScope.exhaustive` in the response:

    - `true` — every candidate file was searched. The list is
      complete.
    - `false` — the search was bounded. Compare
      `candidateFileCount` and `searchedFileCount` to see the gap.

    See [Behavioral model](architecture/behavioral-model.md) for
    details on workspace scoping and visibility rules.

??? question "Call hierarchy is truncated"

    Call hierarchy results are bounded by depth, fan-out, total edges,
    and timeout. Check the `stats` field in the response to see
    whether limits were hit.

    Adjust these parameters in the request:

    | Parameter | Default | What to change |
    |-----------|---------|----------------|
    | `depth` | 3 | Increase for deeper trees |
    | `maxTotalCalls` | 256 | Increase for wider graphs |
    | `maxChildrenPerNode` | 64 | Increase for highly-called functions |

    See [Behavioral model](architecture/behavioral-model.md#call-hierarchy-is-intentionally-bounded)
    for the full truncation model.

## Mutations

??? question "Rename fails with capability error"

    Both standalone and IntelliJ backends support rename. Run
    `kast capabilities` to verify.

    If the rename target is in a generated file or a read-only
    location, the operation may fail with a descriptive error.

??? question "Apply-edits rejects with conflict error"

    This means a file changed between when you planned the edit and
    when you applied it. The SHA-256 hash no longer matches.

    1. Re-run the `rename` command to get a fresh plan with updated
       hashes.
    2. Review the new plan.
    3. Apply it immediately before any other changes land.

## Transport and connectivity

??? question "Connection refused on stdio transport"

    When using stdio transport (for example, from an LLM agent):

    - Verify the daemon process is running and attached to
      stdin/stdout.
    - Confirm no other process is competing for the same stdio
      streams.
    - Verify JSON-RPC messages are line-delimited (one JSON object
      per line).

## Development and CI

??? question "Build or test failures after model changes"

    If you change `analysis-api` model classes, drift tests may fail.
    Run all three generation commands:

    ```console title="Regenerate all drift-tested artifacts"
    ./gradlew \
      :analysis-server:generateDocExamples \
      :analysis-api:generateDocPages \
      :analysis-api:generateOpenApiSpec
    ```

    | Test | What it checks |
    |------|---------------|
    | `AnalysisOpenApiDocumentTest` | OpenAPI spec matches models |
    | `AnalysisDocsDocumentTest` | Doc pages match models |
    | `DocExampleGeneratorTest` | Example JSON matches models |
    | `DocFieldCoverageTest` | All properties have `@DocField` |

## Getting help

If none of the above resolves your issue:

1. Run `kast health` and `kast workspace status` and include the
   output.
2. Check the daemon log output on stderr for stack traces.
3. Open an issue at
   [github.com/amichne/kast](https://github.com/amichne/kast/issues)
   with the diagnostic output.
