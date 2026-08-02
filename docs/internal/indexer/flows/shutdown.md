---
type: Runtime Flow
title: Indexer Shutdown
description: How exact identity protects transport, workers, the writer lease, and the endpoint during close.
tags: [internal, indexer, lifecycle, shutdown, concurrency, sqlite]
code_sources:
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
  - path: cli-rs/src/execution/runtime/control/lease/runtime_binding.rs
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/transport/LocalRpcServer.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/IndexerServerRuntime.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/lifecycle/SourceIndexWriterLease.kt
---

# Indexer Shutdown

Only explicit Kast lifecycle demand stops an indexer. Foreground editor close,
crash, project, VFS, and application events do not enter this flow.

```mermaid
sequenceDiagram
    autonumber
    participant Demand as Explicit stop or owned lease close
    participant Admission as Exact-root admission
    participant Indexer as Exact process identity
    participant Transport as Local RPC server
    participant Worker as Index workers
    participant Store as SQLite store and lease
    participant Endpoint as Descriptor and socket

    Demand->>Admission: stop exact root and optional lease
    Admission->>Indexer: revalidate process and endpoint
    Indexer->>Transport: stop admission and close clients
    Indexer->>Worker: cancel and await termination
    Indexer->>Store: close store and release writer lease
    Indexer->>Endpoint: remove only if identity matches
    Indexer-->>Admission: stopped exact identity
```

## Ownership rules

- A lease can stop only the matching indexer instance it started.
- A borrowed indexer stays running when the borrower exits.
- PID equality alone is insufficient because a PID can be reused.
- Descriptor replacement does not authorize an old process to delete the new
  descriptor or socket.
- Concurrent close and start reuse a valid identity or return a typed conflict;
  they do not create two writers.

The server closes transport before continuation state and compiler resources.
Workers terminate before the store and writer lease close. Endpoint cleanup
compares live file identity before unlinking.

After a crash, the next explicit demand quarantines stale evidence and admits
or creates one replacement. Kast does not add a foreground watcher or an
unattended supervisor.
