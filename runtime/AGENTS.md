<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-14 | hash: 9b6ab7c209f5 -->

# runtime

## Purpose

Composes semantic services inside an existing IntelliJ project and retains typed outcomes through endpoint transport, diagnostics, planning, and recovery.

## Key Files

- [HostedConnectionAdmission.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt) - bounded connection ownership and post-release drain observations.
- [HostedQueryContinuations.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryContinuations.kt) - project-owned execution and encoded-output continuation stores.
- [HostedReferenceStore.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt) - project-owned bounded compact reference lookup.

- [HostedEndpointService.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt) - project endpoint ownership.
- [HostedSemanticServices.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt) - request-scoped semantic service composition.
- [HostedCanonicalQuery.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt) - bounded canonical read dispatch.
- [HostedReadBudgetAdmission.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadBudgetAdmission.kt) - necessary response-byte admission before semantic dispatch.
- [HostedResponse.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedResponse.kt) - original semantic outcomes retained through encoding and transport.
- [HostedChangeResources.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeResources.kt) - shared durable mutation stores.
- [HostedChangeFailure.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeFailure.kt) - closed storage failures and bounded observations.

## Entry Points

- Gradle project: `:runtime:hosted`.
- `hosted/src/main/resources/META-INF/plugin.xml` registers the project service.

## Navigation Hints

- Start with [runtime hosts](../knowledge/modules/runtime-hosts.md) and [hosted queries](../knowledge/flows/hosted-query.md).
- Follow canonical dispatch into `query/protocol`; follow change planning into `change/protocol` and the phase owners.
- The isolated composition, server, and telemetry modules are retired. IDEA owns project import and indexing.
