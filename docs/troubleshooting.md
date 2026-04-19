---
title: Troubleshooting
description: Common issues and solutions when using the Kast analysis daemon.
icon: lucide/life-buoy
---

# Troubleshooting

This page covers the most common issues encountered when running Kast, along
with diagnostic steps and solutions.

## Daemon won't start

**Symptoms:** `kast health` returns an error or hangs.

1. Verify the workspace root exists and contains Kotlin sources:
   ```bash
   kast health --workspace-root=/path/to/project
   ```
2. Check that the JDK is available. Kast requires JDK 21+:
   ```bash
   java -version
   ```
3. Look for a stale socket file. If the daemon crashed without cleanup, the
   socket may still exist:
   ```bash
   ls /tmp/kast-*.sock
   ```
   Remove any stale sockets and retry.

## Indexing takes too long

The daemon indexes the entire workspace on first connect. Large projects with
many modules may take 30–60 seconds.

- Use `kast runtime-status` to monitor indexing progress.
- Wait for `state: READY` before dispatching analysis queries.
- If indexing never completes, check that the Gradle wrapper in the project is
  functional (`./gradlew tasks` should succeed).

## Symbol not found

**Symptoms:** `kast resolve` returns an empty result or `NOT_FOUND` error.

- Confirm the file path is absolute and within the workspace root.
- Confirm the line/column position points to an actual symbol (identifiers,
  not whitespace or comments).
- Verify the daemon has finished indexing (`kast runtime-status` shows
  `state: READY`).
- If the file was recently created, call `kast refresh` to update the
  workspace index.

## References return partial results

Kast scopes all analysis to the workspace root. References in files outside
the workspace, in generated code, or in binary dependencies are not included.
See [Things to know](things-to-know.md) for details on workspace scoping.

## Call hierarchy is truncated

Call hierarchy results are bounded by depth, max total calls, and max children
per node. Check the `stats` field in the response to see whether limits were
hit. You can adjust `maxDepth`, `maxTotalCalls`, and `maxChildrenPerNode` in
the query. See [Things to know](things-to-know.md#call-hierarchy-is-intentionally-bounded)
for the default limits.

## Rename fails with capability error

The `rename` operation requires the `RENAME` capability. Check `kast capabilities`
to verify the backend supports it. Both standalone and IntelliJ backends
support rename.

If the rename target is in a generated file or a read-only location, the
operation may fail with a descriptive error message.

## Connection refused on stdio transport

When using stdio transport (e.g., from an LLM agent), ensure:

- The daemon process is running and attached to stdin/stdout.
- No other process is competing for the same stdio streams.
- The JSON-RPC messages are line-delimited (one JSON object per line).

## Build or test failures after model changes

If you change analysis-api model classes, several drift tests may fail:

| Test | Regeneration command |
| --- | --- |
| `AnalysisOpenApiDocumentTest` | `./gradlew :analysis-api:generateOpenApiSpec` |
| `AnalysisDocsDocumentTest` | `./gradlew :analysis-api:generateDocPages` |
| `DocExampleGeneratorTest` | `./gradlew :analysis-server:generateDocExamples` |
| `DocFieldCoverageTest` | Add `@DocField` annotations to new properties |

Run all three generation commands after any model change:

```bash
./gradlew :analysis-server:generateDocExamples :analysis-api:generateDocPages :analysis-api:generateOpenApiSpec
```

## Getting help

If none of the above resolves your issue:

1. Run `kast health` and `kast runtime-status` and include the output.
2. Check the daemon log output for stack traces.
3. Open an issue at [github.com/amichne/kast](https://github.com/amichne/kast/issues)
   with the diagnostic output.
