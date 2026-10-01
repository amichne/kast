# Local semantic replay baseline

Two installed native runs passed the comparator on 2026-10-01: all three pairs
are `EQUIVALENT`, with complete authored oracles and no unavailable measurements.
The earlier management `runtime_unavailable` result did not establish query-path
unavailability; direct public calls reached the normal running IDEA process.

Implementation starts at fetched `origin/main`
`7581cab635870edff345b8176cd207a7ced81b94`. The observed composition used installed
v0.48.1 `kast-mcp-complete` and a temporarily loaded, locally built v0.49.0 host
with diagnostic schema 6. Both runs pinned identical executable/JAR/loaded-class
hashes, fixture content, requests, grants and settings. All 36 loaded host JAR
hashes matched the extracted build archive. IDEA was `IU-262.10315.125`, JBR
`25.0.4+1-b508.27`, Kotlin plugin `262.10315.125-IJ`, on macOS arm64 in the normal
UID 501 profile. This is a local instrumented baseline, not a released v0.49.0
qualification.

The installed MCP executable SHA-256 was
`b6b8cfbb395d53bb18d65919d07762a186a0dbfa35a7bf4245d0ea56a7ddc2ad`;
the host archive SHA-256 was
`b696c5b1e56d17c8fd39ea508cc60d798e2c0378206f61b07ba23b5a73f24428`.
Complete artifact identities, settings and native raw receipts accompany the
machine-readable comparison.

Observed policy: one measured repetition per run, zero warmups, concurrency one,
512-call harness cap, 60-second per-call timeout. Every RUN and RESUME retained
2000 ms / 100000 work units / 20 results / 49152 returned-byte grants. Existing
host caches were retained; preliminary attempts had already exercised the host.
Each run owned one installed MCP process, initialized/listed before the workload
clock. Every trial reacquired references with a fresh RUN, then used only its own
successor checkpoint through terminal completion.

| Workload/run | Rows | Public calls | Discovery pages | Relation pages | UTF-8 wire bytes | First usable ms | Completion ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| exact-source A | 1 | 1 | 1 | 0 | 5,292 | 1280.48 | 1280.48 |
| dense-references A | 1001 | 334 | 1 | 51 | 4,683,804 | 688.18 | 39615.12 |
| scoped-all A | 74 | 4 | 4 | 0 | 91,992 | 90.18 | 233.73 |
| exact-source B | 1 | 1 | 1 | 0 | 5,288 | 312.80 | 312.80 |
| dense-references B | 1001 | 334 | 1 | 51 | 4,683,800 | 123.37 | 37856.51 |
| scoped-all B | 74 | 4 | 4 | 0 | 91,988 | 55.46 | 184.82 |

The exact-source runs each observed one compiler refinement; scoped-all each
observed 75 refinements and 75 structural tasks before returning 74 public
functions. Dense-reference runs each observed 1001 relation candidates, confirmed
targets and facts, across 51 native relation pages and 334 public calls. All
counter differences were zero except dense epoch-read preemptions (2 versus 7).
Wire lengths differed by four bytes per workload; smaller output is not a work
reduction. Native phase and stage durations remain separate per-call vectors;
they overlap and are not summed. Elapsed time is not CPU cost.

Retained unsuccessful attempts include an opening/import freshness rejection,
a lifecycle-host rejection during overlapping unload, missing application-owner
startup after dynamic load, a deliberately canceled 256-cap capture, and a
one-shot lifecycle-capacity rejection after 303 reference rows. These observations
are not averaged away or substituted for completion. The reproduction runner now
uses the installed persistent MCP path, starts the existing application owner
on dynamic load, and records bounded pin-rejection stages. No native lifecycle
capacity or semantic grant was increased, and no backend optimization was made.

Machine-readable comparison (all counters and duration vectors):
`build/reports/semantic-replay-baseline.json`. Native repeatability output is also
at `build/reports/semantic-replay-repeatability.json`; retained attempt references
are at `build/reports/semantic-replay-attempts.json`. Private raw runs and build /
restoration receipts are retained at
`/private/tmp/kast-replay-native-retry-k_a2k0f6`.

The installed v0.48.1 host was restored, all 36 files matched its installed release
archive, the original `/Users/amichne/code/kast` project was reopened, and a fresh
public query completed. Only the owned fixture project was closed; its files and
raw evidence remain available.

The fixture is synthetic and both compared artifacts are identical:
`repeatability: true`, `evidenceLevel: NATIVE`, `lessWork: false`. No efficiency
improvement is claimed. A subsequent optimization must use a representative
workload, distinct pinned artifacts, identical policy and equivalent sufficient
evidence before counters can establish reduced work. See the
[reproduction command and comparison contract](../../experiments/host-observation/SEMANTIC_REPRODUCTION.md#compare-complete-workloads-locally).

`kast.txt` is not a tracked repository file and has no role in this workflow.
