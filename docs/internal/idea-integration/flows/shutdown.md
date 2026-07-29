---
type: Runtime Flow
title: IDEA Runtime Shutdown
description: How the IDEA service stops transport admission, closes request state, terminates indexing, and releases the shared store.
tags: [internal, idea, lifecycle, shutdown, concurrency, sqlite]
code_sources:
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastPluginService.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastPluginBackendLifecycle.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastDynamicPluginVetoer.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaBackendRuntime.kt
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/transport/RunningAnalysisServer.kt
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/transport/LocalRpcServer.kt
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcAnalysisDispatcher.kt
---

# IDEA Runtime Shutdown

`KastPluginService` is the project-level lifecycle owner.
`KastPluginBackendLifecycle` serializes start, stop, restart, and
configuration-driven transitions. Stop records `STOPPING` and retains the
backend close future until every drain phase completes. Only then can the
state become `STOPPED` or start a queued replacement.

The close path separates request ownership from index-store ownership. The
analysis server owns transport, dispatcher state, and the backend. The project
indexer shares the SQLite store, so the store must remain open until that
worker has terminated.

```mermaid
sequenceDiagram
    autonumber
    participant Trigger as Project dispose or lifecycle request
    participant Service as KastPluginService
    participant Running as RunningKastIdeaBackend
    participant Index as Project index worker
    participant Transport as Local RPC server
    participant Client as Active client handlers
    participant Dispatcher as RPC dispatcher
    participant Backend as Observed IDEA backend
    participant Store as SQLite source index

    Trigger->>Service: stopServer()
    Service->>Service: transition RUNNING to STOPPING
    Service->>Running: closeAsync()
    Running->>Index: cancel()
    alt caller is IDEA dispatch thread
        Running-->>Running: transfer remaining close phases to daemon closer
    end
    Running->>Transport: close()
    Transport->>Transport: close listening channel
    Transport->>Client: close accepted sockets
    Transport->>Client: join handler threads within close deadline
    Running->>Dispatcher: stop admission and cancel active jobs
    Dispatcher->>Dispatcher: await active-job quiescence
    Running->>Dispatcher: close continuation state
    Running->>Backend: close backend resources
    Running->>Index: awaitTermination()
    Running->>Store: close()
    Running-->>Service: complete close future or aggregated failure
    Service->>Service: transition to STOPPED or STOP_FAILED
```

## Server close order

`RunningAnalysisServer.close()` is idempotent and executes four phases in
order:

1. Close the local transport. This closes the listening channel, prevents new
   accepted clients, closes active client sockets, and waits for their handler
   threads within the transport deadline.
2. Close the dispatcher. It rejects new work, cancels every admitted request,
   waits until those request jobs leave the backend, and then releases
   server-held continuation state.
3. Close the backend.
4. Remove the matching daemon descriptor.

The implementation retains the first close failure and adds later failures as
suppressed causes. One failed cleanup phase therefore does not skip the
remaining owned resources.

Lifecycle requests follow the same path after their success response is
flushed. The transport invokes the one-shot after-response action only after it
writes the response, so a shutdown request cannot cut off its own
acknowledgement.

## Index-worker close order

`RunningKastIdeaBackend.close()` cancels the index worker before it closes the
analysis server. Cancellation sets an atomic flag and interrupts the worker.
The worker signals a latch from its final block.

The source-index close then waits for that latch. If stop runs on IDEA's event
dispatch thread, Kast moves the complete blocking sequence—transport close,
request drain, backend close, index wait, and store close—to a dedicated daemon
thread. That avoids freezing the user interface while preserving the rule that
no admitted request or index worker can use a closed dependency.

```mermaid
flowchart TD
    close["close requested"] --> first{"First close?"}
    first -- "no" --> done["Return"]
    first -- "yes" --> cancel["Set index cancellation and interrupt worker"]
    cancel --> edt{"On IDEA dispatch thread?"}
    edt -- "yes" --> async["Start dedicated backend-closer thread"]
    edt -- "no" --> server["Close transport and drain dispatcher"]
    async --> server
    server --> backend["Close backend and descriptor"]
    backend --> wait["Wait for index termination"]
    wait --> store["Close SQLite store"]
    store --> errors{"Cleanup failures?"}
    errors -- "yes" --> report["Throw first with suppressed failures"]
    errors -- "no" --> done
```

## Restart

Restart records one replacement request while the current backend drains. The
replacement retains the current Gradle admission and starts only after the
close future succeeds. At most one server and one index worker can therefore
be registered as the service's active runtime.

Dynamic plugin unload uses IDEA's veto extension. The first unload request
starts every open Kast project drain and returns a clear retry message. Unload
remains vetoed while any service is `RUNNING`, `STOPPING`, or `STOP_FAILED`; a
later retry succeeds only when every service owns no live backend resources.
