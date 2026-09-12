<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-12 | hash: cd8dbff354a1 -->

# runtime

## Purpose

Composes semantic services inside an existing IntelliJ project and retains typed outcomes through endpoint transport, diagnostics, planning, and recovery.

## Key Files

- [HostedEndpointService.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt) - project endpoint ownership.
- [HostedSemanticServices.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt) - request-scoped semantic service composition.
- [HostedCanonicalQuery.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt) - bounded canonical read dispatch.
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
