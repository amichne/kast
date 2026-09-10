---
type: Kotlin Module Group
title: Runtime and process hosts
description: Runtime composition connects domain operations to adapters, while server, indexer, App Server, and CLI own distinct transport and process responsibilities.
resource: file://runtime
tags: [kotlin, runtime, server, indexer, cli]
timestamp: 2026-09-09T00:00:00Z
code_sources:
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
---

# Runtime and process hosts

`runtime:composition` constructs the production graph from explicit ports and exports dispatch capability. `runtime:server` decodes and dispatches typed operations. `runtime:telemetry` owns bounded OpenTelemetry/file effects.

The indexer runs the semantic runtime inside the admitted IntelliJ environment. The App Server owns persistent coordination, frontend sessions, workspace worker admission, and host protocol projection. The CLI owns human command parsing, configuration ingress, installation workflows, and output projection.

These hosts communicate through protocol and distribution contracts; reachability alone does not establish readiness or semantic authority.

Read [request dispatch](../flows/request-dispatch.md) for the cross-host path.
