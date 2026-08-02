---
type: Runtime Flow
title: Headless Runtime Shutdown
description: How exact identity and ownership protect transport, workers, the writer lease, and the endpoint during close.
tags: [internal, headless, lifecycle, shutdown, concurrency, sqlite]
code_sources:
  - path: cli-rs/src/execution/runtime/backend/headless_authority.rs
  - path: cli-rs/src/execution/runtime/control/lease/runtime_binding.rs
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/transport/LocalRpcServer.kt
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcAnalysisDispatcher.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaBackendRuntime.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/lifecycle/SourceIndexWriterLease.kt
---

# Headless Runtime Shutdown

Only explicit Kast lifecycle demand stops a headless runtime. Foreground IDE
close, crash, project, VFS, and application events do not enter this flow.

```mermaid
sequenceDiagram
    autonumber
    participant Demand as Explicit stop or owned lease close
    participant Authority as Headless authority
    participant Runtime as Exact runtime identity
    participant Transport as Local RPC server
    participant Dispatcher as RPC dispatcher
    participant Worker as Index workers
    participant Store as SQLite store and writer lease
    participant Endpoint as Descriptor and socket

    Demand->>Authority: stop exact root and optional lease identity
    Authority->>Runtime: revalidate instance, process start, owner, and endpoint
    Runtime->>Transport: stop accepting and close client sockets
    Runtime->>Dispatcher: cancel and drain active jobs
    Runtime->>Worker: cancel and await termination
    Runtime->>Store: close read-write store and release writer lease
    Runtime->>Endpoint: remove only if file identity still matches
    Runtime-->>Authority: stopped exact identity
```

## Ownership rules

- A lease can stop only the matching runtime instance it started.
- A borrowed runtime stays running when the borrower exits.
- PID equality alone is insufficient because a PID can be reused.
- Descriptor replacement does not authorize the old process to delete the new
  descriptor or socket.
- Concurrent close and start either reuse the valid identity or return a typed
  conflict; they do not create two writers.

The analysis server closes transport before dispatcher continuation state and
backend resources. Index workers terminate before the store and its writer
lease close. Endpoint cleanup compares the live file identity before unlinking.

After a crash, the next explicit demand quarantines stale evidence and admits
or starts one replacement. Kast does not add a foreground watcher or an
unattended supervisor.
