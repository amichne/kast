# Local semantic replay comparison

## Production comparison, 2026-10-01

Two measured repetitions of each workload passed semantic equivalence with no
unavailable measurements. Dense references returned the same 137 compiler-confirmed
occurrences in 28 public calls instead of 45, a 37.8% reduction. Repeated model
capture counts also fell by 37.8%. Exact declaration/source and scoped enumeration
remained equivalent and used the same calls and native pages. The comparator
reports `evidenceLevel: NATIVE`, `repeatability: false`, `lessWork: true`.

The change tightens the conservative reference-row byte bound by accounting for
JSON spelling and inline-selector multiplicity. It preserves retained-state
charges, budgets, compiler proofs and provider behavior. Encoded-row tests cover
108 independently constructed ownership/signature/text combinations; the old bound
fails the dense-page packing assertion. Smaller bytes alone cannot qualify.

The workload is an immutable archive of real Kast source at
`da28841ac316a508df1a39dfd7eba004d122a3c0`, SHA-256
`8c780949d4a6c8cb32ee3c53b2b1c3f67621354ca7b78756a39d0a2588e4a914`.
It uses exact `QueryPlanSyntax` with source, references to `QueryStepSyntax`, and
scoped public functions in `kernel/src/main/kotlin` with filtering and continuations.
Both artifacts observe identical content, paths, requests, settings and policy.
Every trial starts a fresh RUN and drains its own continuations; opaque references
are never transferred between artifacts, hosts or epochs.

The installed public MCP/control path is the latest official v0.49.0. Baseline and
candidate hosts are private instrumented builds of that source plus the replay
changes, built sequentially with identical explicit version/JDK/options. The
baseline retains the old byte bound; the candidate changes that bound. Exactly one
of the 36 host JARs differs (`relation-contract`); all loaded JAR hashes match their
archive extractions. Native pins verify the changed class and diagnostic class.

| Artifact | Plugin archive SHA-256 |
| --- | --- |
| Baseline | `5be43fe1fbafc67b3f5baea4dbd93c6f65c640b5a7fa40384c2450129c755874` |
| Candidate | `f9e15ee123820369d92cc3fd0a5aa3fc964bd9ba261d33e2f3d4238bd1873822` |

IDEA is `IU-262.10315.125`, JBR `25.0.4+1-b508.27`, Kotlin plugin
`262.10315.125-IJ`, macOS arm64, normal UID 501/HOME/profile. Builds use GraalVM
25.0.2; the installed JVM adapter uses Temurin 25.0.2+10. Each run initializes one
installed MCP process before timing. Each workload has one fully drained warmup
and two measured repetitions, concurrency one, retained host caches, a 512-call
cap and 60-second harness timeout. RUN and every RESUME use the same fixed
10000 ms / 100000 work units / 20 results / 49152 returned-byte grant. Snapshot
Gradle import preparation and artifact builds occur outside workload clocks.
Native log capture waits at most 250 ms and reads at most 2 MiB per call.

| Workload/repetition/artifact | Rows | Calls | Discovery pages | Relation pages | UTF-8 wire bytes | First usable ms | Completion ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| exact/0 baseline | 1 | 1 | 1 | 0 | 5,674 | 45.14 | 45.14 |
| exact/0 candidate | 1 | 1 | 1 | 0 | 5,670 | 39.46 | 39.46 |
| exact/1 baseline | 1 | 1 | 1 | 0 | 5,671 | 37.19 | 37.19 |
| exact/1 candidate | 1 | 1 | 1 | 0 | 5,670 | 37.80 | 37.80 |
| references/0 baseline | 137 | 45 | 1 | 7 | 670,009 | 400.64 | 2,682.96 |
| references/0 candidate | 137 | 28 | 1 | 7 | 620,720 | 62.38 | 1,476.70 |
| references/1 baseline | 137 | 45 | 1 | 7 | 670,054 | 54.29 | 2,287.46 |
| references/1 candidate | 137 | 28 | 1 | 7 | 620,720 | 60.32 | 1,440.50 |
| scoped-all/0 baseline | 43 | 3 | 3 | 0 | 62,832 | 92.24 | 195.76 |
| scoped-all/0 candidate | 43 | 3 | 3 | 0 | 62,831 | 83.12 | 183.33 |
| scoped-all/1 baseline | 43 | 3 | 3 | 0 | 62,834 | 79.80 | 191.93 |
| scoped-all/1 candidate | 43 | 3 | 3 | 0 | 62,831 | 87.74 | 196.31 |

In both dense pairs, imported-project observations fall 2610→1624, IDEA-module
observations 6840→4256, selected Gradle modules 6615→4116 and source roots
4545→2828. Candidates, restored locators, K2-confirmed targets and relation facts
remain 137. Native relation pages remain seven. Completed locator-retention pipeline
attempts remain 255: retained/rejected outcomes move 2/253→4/251. The raw counts
are preserved; exclusive outcomes are not independent work quantities. Comparison
requires nonincreasing attempts and rejections, nondecreasing successes and a
strict reduction in another work quantity. Tests reject worse outcomes, missing
labels and outcome-only improvements. These counts do not prove unique stored
locators, allocation cost or future per-token revalidation availability.

Elapsed times are individual observations; they do not establish CPU cost or a
general latency distribution. Per-call phase and stage durations remain separate
raw vectors in the machine output and are never summed across overlapping clocks.
The claim is fewer measured operations for equivalent sufficient answers on this
source/workload/profile. Baseline-versus-baseline repeats also pass all six pairs
with `repeatability: true`, `lessWork: false`.

Machine outputs are `build/reports/semantic-replay-production.json` and
`build/reports/semantic-replay-production-repeatability.json`; the attempt inventory
is `build/reports/semantic-replay-production-attempts.json`. Private raw receipts,
pins, archive/class/JAR checks and build logs remain at
`/private/tmp/kast-performance-ue_u0qyy`. Earlier timeouts, project admission and
import movement, wrong artifact selection, incomplete log capture, and a build
output race remain retained. A candidate trial missing a receipt after the bounded
capture window cannot qualify; subsequent complete trials do not erase it.

Validation: 48 focused comparator tests; affected relation/query tests; four
diagnostic capability/cancellation tests; `productBuildGate` with explicit
`-Pversion=0.49.0 --max-workers=1`; JSON, architecture and knowledge guards.
The [documented reproduction command and contract](../../experiments/host-observation/SEMANTIC_REPRODUCTION.md#compare-complete-workloads-locally)
use the existing scripts, receipts and phase observations.

## Historical synthetic baseline

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
