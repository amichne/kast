---
type: Kotlin Module Group
title: Runtime and process hosts
description: Runtime composition connects domain operations to adapters, while server, indexer, App Server, and CLI own distinct transport and process responsibilities.
resource: file://runtime
tags: [kotlin, runtime, server, indexer, cli]
timestamp: 2026-09-11T00:00:00Z
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: runtime/server/src/main/kotlin/io/github/amichne/kast/runtime/server/ServerDispatch.kt
    symbols: [ServerDispatch]
  - path: runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/bootstrap/InstalledKastRuntime.kt
    symbols: [InstalledKastRuntimeConstruction]
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerMain.kt
    symbols: [KastIndexerMain]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt
    symbols: [InstalledCoordinator]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
    symbols: [KastCliMain]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/OwnedHostedEndpoint.kt
  - path: runtime/hosted/build.gradle.kts
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/compatibility/IdeHostCompatibility.kt
    symbols: [IdeHostCompatibilityPolicy, IdeReleaseLine]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointProtocol.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedPeerCancellation.kt
    symbols: [HostedPeerTermination, dispatchUntilPeerTermination]
  - path: runtime/composition/build.gradle.kts
  - path: query/protocol/build.gradle.kts
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeSocketClient.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeQuery.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/ide/IdeCommands.kt
---

# Runtime and process hosts

`runtime:composition` constructs the production graph from explicit ports and exports dispatch capability. `runtime:server` decodes and dispatches typed operations. `runtime:telemetry` owns bounded OpenTelemetry/file effects.

The indexer runs the semantic runtime inside the admitted IntelliJ environment. The App Server owns persistent coordination, frontend sessions, workspace worker admission, and host protocol projection. The CLI owns human command parsing, configuration ingress, installation workflows, and output projection.

These hosts communicate through protocol and distribution contracts; reachability alone does not establish readiness or semantic authority.

`runtime:hosted` is a separate IDEA plugin for existing-project reads.
Its release archive carries the Kast release version and IDEA release line in
its name. The descriptor permits `since-build="262"` through `until-build="262.*"`;
the public installer validates that line before activation. Runtime admission
accepts IDEA and bundled Kotlin builds in the baseline's `262` release line while
retaining their exact observed identities. Product, protocol, registry, schema,
and capability checks remain exact. This policy accepts patch-build compatibility
risk; the build baseline and recorded native acceptance remain `262.10315.125`.
Its project service owns a bounded local socket and delegates saved-content
semantic reads to `workspace:intellij-read`. It has no project-opening, Gradle
import, or worker-launch authority and is excluded from isolated runtime
composition. Its generalized evaluator composition consumes an admitted read
context, pure semantic services, project-bound adapters, and the shared
[`query:protocol`](query-protocol.md) module. All seven canonical read handlers
and their existing-IDE client decoders serve the default semantic command path.
Peer termination cancels and joins owned work; the semantic executor retains its
permit until cleanup. See [hosted queries](../flows/hosted-query.md) and the
[native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
for the final CLI/provider results and their bounded coverage.

The primary `kast index` commands and compatible `kast ide` spelling project
the same local Clikt implementation before
isolated-product bootstrap. Its only runtime capability is an existing-IDE
client; it cannot start a worker. The socket adapter validates the shared
hosted schemas and exact root/name correlation before producing CLI output.
IDEA owns incremental index maintenance for this path. The internal `INDEX_SYNC`
operation remains absent from the public command graph.

`selectCliRuntimePath` now also routes `query`, `symbol`, `source`, `relation`,
`traversal`, and `diagnostic` before installed bootstrap in `KastCliMain`. Their
seven canonical reads use the admitted existing host and reject a missing host
without fallback. The provider requires App Server projection version 9, whose
read schemas preserve published/live evidence and semantic qualification. It
qualifies and invokes its configured real CLI; manual production provider dispatch
through the final distribution completed an exact `Child` query with evidence
matching direct CLI execution. This establishes provider/process invocation,
while full Codex WebSocket and multi-client acceptance remain separate.

Read [request dispatch](../flows/request-dispatch.md) for the cross-host path.

Hosted project services retain an immutable read-limit policy admitted from the IDE process environment and JVM properties. The CLI/provider retain the corresponding policy from their configuration boundary, including saved installation values. Invalid configuration rejects before native execution. Default hosted logs include the selected capacities and provenance. See [configuration](../../docs/hosted-read-configuration.md).

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Three ordinary searches and deferred `query_symbols` share `query.run`; `check_diagnostics` shares `diagnostic.check`. Private admission retains each tool's schema identity and typed syntax through its exact CLI binding. The `tool` command family uses the existing-IDE read path. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.
