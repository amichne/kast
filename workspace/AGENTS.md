<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-12 | hash: 00f827a33b82 -->

# workspace

## Purpose

Defines canonical workspace identity and read evidence, and admits bounded semantic reads from projects already open in IntelliJ. IDEA owns project import, indexing, and lifetime.

## Key Files

- [CanonicalSemanticProjectRoot.kt](contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt) - canonical root identity.
- [HostedQueryExecutor.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt) - bounded read execution and outcome observation.
- [HostedReadTransaction.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransaction.kt) - freshness admission and revalidation.
- [HostedReadDiagnostics.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDiagnostics.kt) - typed stage and outcome evidence.
- [DetachedModelCapture.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt) - detached project model capture.

## Subdirectories

- `contract` - roots, identities, readiness and read evidence; historical publication contracts remain for compatibility.
- `intellij-read` - passive VFS/IDE model reads, read admission, and freshness listeners.

## Entry Points

- Gradle projects: `:workspace:contract`, `:workspace:intellij-read`.
- The isolated `service` and `intellij` import/runtime modules are retired.

## Navigation Hints

- Start with [workspace authority](../knowledge/modules/workspace.md).
- For stale or rejected reads, trace transaction checkpoints and diagnostics before opening platform adapters.
