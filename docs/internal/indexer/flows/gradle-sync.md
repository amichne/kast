---
type: Runtime Flow
title: Indexer Gradle Sync
description: How the indexer links the exact Gradle project and waits for stable compiler-ready model evidence.
tags: [internal, indexer, gradle, project-model, indexing]
code_sources:
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/project/ProjectOpener.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/gradle/bootstrap/GradleProjectBootstrap.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/gradle/settlement/GradleModelSettlementAwaiter.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/gradle/settlement/GradleModelSettlementOutcome.kt
---

# Indexer Gradle Sync

`ProjectOpener` opens the canonical root in the isolated VFS and passes
the detected workspace kind to `GradleProjectBootstrap`. A non-Gradle
workspace has an explicit skipped result. A Gradle workspace must produce a
compiler-ready imported model.

```mermaid
flowchart TD
    open["Open exact root in isolated VFS"] --> kind{"Gradle workspace?"}
    kind -- "no" --> skipped["Typed skipped result"]
    kind -- "yes" --> configure["Configure Gradle import"]
    configure --> inspect["Inspect current model"]
    inspect --> settle["Wait for stable observations"]
    settle --> ready{"Compiler-ready?"}
    ready -- "yes" --> admit["Return module evidence"]
    ready -- "no" --> import["Link and import exact root"]
    import --> settle
    settle -. "timeout, interrupt, disposal" .-> fail["Typed failure"]
```

The awaiter samples lifecycle, active import tasks, Gradle project paths, and
module readiness. `Settled`, `TimedOut`, `Interrupted`, and `ProjectDisposed`
are distinct outcomes and retain the last observation plus a bounded trace.

Submitting an import request is not completion. The indexer does not report
semantic `READY` until Kotlin modules have coherent JDK, SDK, library,
order-entry, PSI, and compiler resolution.

Continue with [Indexing and generation](indexing-and-generation.md).
