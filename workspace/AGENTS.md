---
type: Module Guide
title: "Kast workspace"
description: "Defines workspace identity and read authority, then admits bounded semantic reads from projects open in IntelliJ IDEA."
resource: "repo://workspace"
tags: [kast, navigation]
timestamp: "2026-10-19T00:21:06Z"
source_revision: 50f064f1779433c49f3d513919b3474b7cc3ed0e
code_sources:
  - path: workspace/contract/build.gradle.kts
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
  - path: workspace/intellij-read/build.gradle.kts
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijLocalCompilerOwner.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijReadObservation.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationEffect.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransaction.kt
  - path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutorTest.kt
  - path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransactionTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: ba0248f76aa7 -->

# workspace

## Purpose

Defines workspace identity and read authority, then admits bounded semantic reads from projects open in IntelliJ IDEA.

## Key Files

- [CanonicalSemanticProjectRoot.kt](contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt) - canonical root identity.
- [SemanticReadAuthority.kt](contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt) - published and live read authority.
- [HostedQueryExecutor.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt) - admission, cancellation, deadlines, and final publication.
- [HostedReadTransaction.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransaction.kt) - freshness admission and revalidation.
- [HostedReadPublicationAdmission.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt) - response capacity admission before provider work.
- [HostedReadPublicationEffect.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationEffect.kt) - the admitted publication effect and rejection evidence.
- [DetachedModelCapture.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt) - capture of the existing imported project model.
- [IntellijLocalCompilerOwner.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijLocalCompilerOwner.kt) - shared K2 selection of named owners for local declarations.
- [IntellijReadObservation.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijReadObservation.kt) - bounded read stages and counters.

## Subdirectories

- [contract/](contract/) - roots, model identities, epochs, and read evidence.
- [intellij-read/](intellij-read/) - IDE model capture, freshness listeners, read admission, and diagnostics.

## Entry Points

- Gradle projects: `:workspace:contract` and `:workspace:intellij-read`.
- [HostedReadTransactionTest.kt](intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransactionTest.kt) - freshness transitions.
- [HostedQueryExecutorTest.kt](intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutorTest.kt) - execution and cancellation outcomes.

## Navigation Hints

- For stale reads, start with `HostedReadTransaction` and the reported execution stage.
- For cancellation or output capacity, start with `HostedQueryExecutor` and the publication owners.
- IDEA owns project import, indexing, and project lifetime. Explicit refresh orchestration lives in `runtime/hosted`.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
