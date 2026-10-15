---
type: Module Guide
title: "Kast runtime"
description: "Composes semantic services inside IntelliJ IDEA and owns hosted transport, query publication, diagnostics, and source changes."
resource: "repo://runtime"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: runtime/hosted/build.gradle.kts
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeResources.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEpochStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryContinuations.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceStateStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedTransportObservation.kt
  - path: runtime/hosted/src/main/resources/META-INF/plugin.xml
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationProjectionTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryRetainedPresentationTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 9b6ab7c209f5 -->

# runtime

## Purpose

Composes semantic services inside IntelliJ IDEA and owns hosted transport, query publication, diagnostics, and source changes.

## Key Files

- [HostedEndpointService.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt) - project endpoint ownership.
- [plugin.xml](hosted/src/main/resources/META-INF/plugin.xml) - IntelliJ service registration.
- [HostedSemanticServices.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt) - native semantic service composition.
- [HostedCanonicalQuery.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt) - canonical query dispatch and output fitting.
- [HostedEpochStore.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEpochStore.kt) - read epoch ownership and retirement.
- [HostedQueryPublicationSession.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt) - allocation, encoded output, commit, and cancellation release.
- [HostedQueryContinuations.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryContinuations.kt) - project-owned bounded retained query state.
- [HostedSourceStateStore.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceStateStore.kt) - source output and replay under one quota.
- [HostedChangeResources.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeResources.kt) - shared durable mutation stores.
- [HostedTransportObservation.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedTransportObservation.kt) - bounded connection stages and read correlation.

## Subdirectories

- [hosted/](hosted/) - the single active runtime module, native fixtures, and plugin resources.
- [hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/) - explicit asynchronous workspace refresh.
- [hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/) - IDE project lifecycle coordination.

## Entry Points

- Gradle project: `:runtime:hosted`.
- Read `hosted/NATIVE_FIXTURES.md` and `hosted/native/README.md` before native qualification.
- [HostedQueryPublicationProjectionTest.kt](hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationProjectionTest.kt) - publication and projection cases.
- [HostedQueryRetainedPresentationTest.kt](hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryRetainedPresentationTest.kt) - retained output cases.

## Navigation Hints

- Trace requests from the endpoint into canonical dispatch and the domain protocol owner.
- For read admission, cancellation, and freshness, continue into `workspace/intellij-read`.
- For callback reuse, examine the topology fact store and relation readmission. The runtime owns their project lifetime.
- IDEA owns project import and indexing. The isolated sidecar runtime is retired.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
