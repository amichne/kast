---
type: Runtime Flow
title: Experimental hosted semantic query
description: One saved Kotlin class query resolves a direct supertype in an admitted open IDEA project and returns detached compiler evidence.
resource: file://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted
tags: [intellij, kotlin, semantic-query, lifecycle]
timestamp: 2026-09-10T00:00:00Z
code_sources:
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryService.kt
    symbols: [HostedQueryService]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/AdmittedHostedQuery.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/LiveHostedKotlinRead.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/LiveHostedClassIndex.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedClassLookup.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedSourceScope.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadCheckpoint.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryProbe.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryWire.kt
  - path: workspace/intellij-read/build.gradle.kts
  - path: experiments/host-observation/run_hosted_query.py
  - path: experiments/host-observation/hosted-query.kts.template
  - path: experiments/host-observation/hosted-plugin-unload.kts.template
  - path: experiments/host-observation/hosted-project-restoration.kts.template
  - path: experiments/host-observation/hosted-query.schema.json
---

# Experimental hosted semantic query

The project-level service retains one admitted existing Project, an owner-scoped
epoch source, and a terminal endpoint lifetime. It captures the cached Gradle
model without import, joins the same epoch to cancellable reads, and uses K2 to
prove that the selected class directly inherits its one explicit supertype.
Compiler identities and canonical signatures reuse symbol and protocol contracts.

Both endpoints must have uniquely owned supported source folders. Global dirty
documents, project-uncommitted PSI, indexing, changed stamps, and lost freshness
reject the request. The response explicitly describes saved IDE content and
cached source-folder provenance. This request-local snapshot does not establish
the stronger production [workspace publication](workspace-publication.md).

Semantic objects stay inside the read lifetime. The executor has one active
permit and a cooperative deadline; cancellation drains before releasing the
permit. Detachment invalidates the original endpoint, drains its requests, and
disposes its listener owner. No opener, importer, or isolated worker is used.

The optional plugin archive contains compiled adapter code and its contract
dependencies. The [manual runner](../../experiments/host-observation/HOSTED_QUERY.md)
loads that artifact into an existing native IDEA process and records project,
model, read-lock, result, and retirement evidence. Ordinary library builds have
no plugin descriptor. Opt-in checkpoints after semantic detachment qualify
restored edits, cancellation, and original-owner disposal before publication.
The project-close case explicitly restores the selected project after the old
request retires. The plugin-manager case uses the actual injected project
service and verifies dynamic unload. Unattended hosting and upgrades between
different plugin versions remain unqualified.

The controller refines acceptance evidence into typed verification or rejection
outcomes even under Python optimization. It records bounded carrier outcomes
separately from semantic results, so a script that never enters the host cannot
be mistaken for query completion or owner retirement.

Exact class-name discovery reads the existing Kotlin short-name stub index in
the same admitted project. Candidate collection ends before K2 resolves each
class and detaches its canonical compiler identity. The shared read boundary
checks saved content and one epoch before publication. Queries are restricted
to supported cached authored source folders, bounded to 32 candidates and 64 KiB
of output, and reject overflow. No index storage is copied or rebuilt by Kast.
