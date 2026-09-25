# Single IDEA backend read throughput

## Current path

The connected provider runs in Kast's persistent App Server. It prepares the selected workspace and calls the [IDE socket client](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeSocketClient.kt) directly. A hosted provider read therefore pays descriptor validation, one Unix socket exchange, and IDEA execution; it does not launch a JVM per invocation. The standalone CLI and MCP entry points have their own process lifetimes, so a benchmark that launches them for every call measures an additional cost that the provider path does not have.

The [endpoint listener](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt) admits concurrent, bounded frame exchanges. One mutex serializes semantic and mutation dispatch for each project endpoint. The [query lifetime](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryLifetime.kt) independently permits exactly one active invocation and would return `BUSY` if that mutex were simply removed. The default connection cap is 16 and the host query allowance is 4 seconds; queue admission reserves execution time and 100 ms for publication. Parallel calls can overlap client framing, but semantic throughput remains limited by one service lane. With `n` admitted calls and mean semantic service time `S`, the execution portion is approximately `n × S` in both serial and parallel modes. Parallel callers add queue wait of roughly their place in that lane, and enough callers reach the deadline or connection cap. This is a capacity model, not an observed semantic throughput claim.

## Transport-only observation

On 2026-09-25, a single local IDEA endpoint for this worktree answered 24 direct `DESCRIBE` socket exchanges in each mode. Each exchange opened a new Unix socket, wrote one length-framed request, and read the complete reply. One run gave:

| Mode | Calls | Wall time | Median call | 95th percentile call |
| --- | ---: | ---: | ---: | ---: |
| Serial | 24 | 22.54 ms | 0.56 ms | 2.92 ms |
| Eight parallel clients | 24 | 6.44 ms | 1.56 ms | 3.28 ms |

All replies were `KAST_IDE_HOST`. `DESCRIBE` does no symbol analysis. This observation shows that socket setup and framing can overlap and were submillisecond at the median in this one run. It does not establish query latency or warmed-index behavior. The installed endpoint was already running; no startup cost was included.

## Connected semantic observation

The already connected Kast tool queried the exact `QueryService` class in this worktree with the same directory, source-set, and return-field selection each time. The first call took 14.4 seconds; it included provider workspace preparation and is excluded from the table. Two subsequent serial and eight-client trials used eight calls each:

| Trial | Serial wall time | Parallel wall time | Parallel last reply | Result |
| --- | ---: | ---: | ---: | --- |
| 1 | 1,665 ms | 575 ms | 574 ms | All 16 calls complete |
| 2 | 1,933 ms | 1,854 ms | 1,853 ms | All 16 calls complete |

These are two short local observations, not stable throughput estimates. Individual serial calls ranged from 67 to 488 ms, so IDEA work and preparation varied during the run. Parallel replies arrived in a staircase, and the second trial's tail approached the serial wall time. That behavior is consistent with a single serialized semantic lane, though these end-to-end timings alone do not isolate how much time each call spent in the mutex. The installed provider did not yet expose the new `source` return field, so this measurement covers symbol search only.

## Decision and next measurement

Keep the persistent App Server and direct socket path. A shell RPC wrapper would change CLI startup costs, not the serialized IDEA service time. A GraalVM native image would add packaging and compatibility work without removing the IDEA mutex or compiler analysis cost; there is no measured need for one here.

For high-volume symbol retrieval, use a single bounded `query_symbols` pipeline with `return_fields: ["source"]` when source is needed. This performs one admitted source read per emitted exact symbol inside the same query and avoids a separate client exchange for each window. It does not make those source reads parallel inside IDEA. Returned text counts toward the query's output and checkpoint budgets; source failures retain finite causes and incomplete coverage.

The next controlled measurement should replay the same exact query with source selection against one warmed IDEA project through an installed build containing this change: serial calls, concurrent calls up to the connection cap, and a single pipeline returning the same number of symbols. Record end-to-end wall time, throughput, p50/p95 latency, `SEMANTIC_ADMISSION` wait, `EXECUTION` time, qualified results, and deadline/capacity rejections from the existing bounded [transport observations](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedTransportObservation.kt). Only consider separate read lanes after proving concurrent read authority, query-lifetime permits, continuation-store ownership, cancellation, and IDE behavior with native tests.
