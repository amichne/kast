---
type: Runtime Flow
title: Headless Gradle Sync
description: How the isolated host links the exact Gradle project and waits for stable compiler-ready model evidence.
tags: [internal, headless, gradle, project-model, indexing]
code_sources:
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/project/HeadlessProjectOpener.kt
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/gradle/bootstrap/HeadlessGradleProjectBootstrap.kt
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/gradle/settlement/HeadlessGradleModelSettlementAwaiter.kt
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/gradle/settlement/HeadlessGradleModelSettlementOutcome.kt
---

# Headless Gradle Sync

`HeadlessProjectOpener` opens the canonical root in the isolated VFS and passes
the detected workspace kind to `HeadlessGradleProjectBootstrap`. A non-Gradle
workspace has an explicit skipped result. A Gradle workspace must produce a
compiler-ready imported model.

```mermaid
flowchart TD
    open["Open exact root in isolated VFS"] --> kind{"Gradle workspace?"}
    kind -- "no" --> skipped["Typed skipped result"]
    kind -- "yes" --> configure["Configure headless Gradle import"]
    configure --> inspect["Inspect current model"]
    inspect --> linkable{"Exact root can link and refresh?"}
    linkable -- "no" --> fail["Typed model-unavailable failure"]
    linkable -- "yes" --> settle["Wait for stable import observation"]
    settle --> ready{"Compiler-ready?"}
    ready -- "yes" --> admit["Return ready module evidence"]
    ready -- "no" --> import["Link and import exact root"]
    import --> settleAgain["Wait and inspect within bounded policy"]
    settleAgain --> admit
    settleAgain -. "timeout, interrupt, or disposal" .-> fail
```

## Settlement evidence

The awaiter samples lifecycle, active import tasks, Gradle project paths, and
module readiness. It requires a configured number of stable observations.
`Settled`, `TimedOut`, `Interrupted`, and `ProjectDisposed` are distinct typed
outcomes and retain the last observation plus a bounded transition trace.

Submission of an import request is not completion. The runtime does not report
semantic `READY` until the imported Kotlin modules have coherent JDK, SDK,
library, order-entry, PSI, and compiler resolution.

Continue with [Indexing and generation](indexing-and-generation.md).
