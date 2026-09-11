<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: a6576fd2edea -->

# runtime

## Purpose

Composes domain services and platform ports, dispatches typed protocol operations, serves requests, and emits bounded telemetry.

## Key Files

- [composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/bootstrap/InstalledKastRuntime.kt](composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/bootstrap/InstalledKastRuntime.kt) - installed runtime assembly.
- [composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/KastOperationHandlerFactory.kt](composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/KastOperationHandlerFactory.kt) - operation handler construction.
- [composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/core/CanonicalProtocolAuthority.kt](composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/core/CanonicalProtocolAuthority.kt) - protocol authority.
- [server/src/main/kotlin/io/github/amichne/kast/runtime/server/RuntimeServer.kt](server/src/main/kotlin/io/github/amichne/kast/runtime/server/RuntimeServer.kt) - runtime server.
- [server/src/main/kotlin/io/github/amichne/kast/runtime/server/ServerDispatch.kt](server/src/main/kotlin/io/github/amichne/kast/runtime/server/ServerDispatch.kt) - request dispatch.
- [telemetry/src/main/kotlin/io/github/amichne/kast/runtime/telemetry/OpenTelemetryKastObservability.kt](telemetry/src/main/kotlin/io/github/amichne/kast/runtime/telemetry/OpenTelemetryKastObservability.kt) - telemetry adapter.

- [hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt) - existing-project endpoint service.
- [hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt](hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt) - live semantic reads through shared query protocol.

## Subdirectories

- `composition` - installed assembly, platform adapters, protocol handlers, and semantic bootstrap.
- `server` - typed operation binding and dispatch.
- `hosted` - existing-IDE plugin and saved-content read endpoint; see [hosted queries](../knowledge/flows/hosted-query.md).
- `telemetry` - OpenTelemetry projection and forwarding.

## Entry Points

- Gradle projects: `:runtime:composition`, `:runtime:server`, `:runtime:hosted`, `:runtime:telemetry`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/runtime-hosts.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For end-to-end operation routing, begin at `ServerDispatch`, follow the typed binding into `composition/protocol`, then the owning service.
- For dependency wiring, begin in `bootstrap`; for emitted signals, begin in `telemetry` and the kernel observability port.
