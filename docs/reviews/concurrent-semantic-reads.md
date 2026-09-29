# Concurrent semantic reads: qualification and capacity

2026-09-29. Default **two semantic readers**, configurable with
`KAST_READ_HOST_READERS` or `kast.read.host.readers`. The transport continues to
admit 16 connections. A saturated reader owner returns `BUSY`; there is no
whole-query lock or waiting semantic queue. An invocation keeps its unique permit
until its computation drains, including cancellation and timeout cleanup.

## Authority and retained state

Admission reobserves the request's original native epoch inside a short owner
transition, with native read access acquired first. An older capture cannot
advance the owner after a newer epoch has been admitted. Retained state asks that
same semantic owner whether an authority is current; numeric revision ordering
is not authorization. Each store retains one current partition. A stale request
cannot replace or clear it. Replaced and retired stores reject issuance and
restoration through escaped references as well as new admission.

Canonical dispatch uses `SINGLE_EVALUATION`. IntelliJ can restart its own read
actions while validating the original basis, but query evaluation cannot rebind
to a new epoch. A moved basis rejects with rerun guidance. Snapshot stability is
optimistic: publication validates the original epoch, and a later edit can
invalidate delivered references. This does not provide historical snapshots.
Mutation coordination and native write exclusion remain unchanged.

## Why serialization existed

`5ebfe28a0` introduced the single-reader lifetime with the first existing-IDE
semantic feasibility endpoint on September 10. `6f65e16de` later added concurrent
frame admission and the dispatch mutex in the AR-01 connection-reliability work.
The mutex let transport requests queue in front of that single-reader owner.
These are ownership and admission constraints in Kast, not evidence that K2
requires whole-query serialization. The native barrier below establishes that
two real analysis sessions can overlap on this backend.

## Native measurement boundary

The candidate archive was loaded with the existing Kotlin plugin classloader in
the already running IDEA **262.10315.125**, PID **26040**, against this exact
imported Kast worktree. The installed plugin was not replaced or unloaded.
The published module/AGENTS context came from release
`0.20260928.121700`, source `40bc04be96cd39f4b5d58788ff2de801b627e1f3`.

The workload resolves `Refinement.Refined` and its explicit direct supertype
through real K2, using the production hosted executor, model capture, native read,
and final freshness validation. Eight warmups precede three differently ordered
rounds of 32 requests per reader count. Reader count is the bounded number of
concurrent clients. The exploratory candidate allowed up to 16 readers, so the
1/2/4/8 client counts exercised those actual concurrency levels without saturation.

This is native semantic evidence for the candidate on the installed IDE backend.
It is not a benchmark of canonical `query.run`, provider startup, MCP framing, or
an installed-release replacement. The separate 156-client socket test remains
transport evidence and no longer asserts that active dispatch equals one.

### Capacity comparison

All 96 requests per row published successfully; no qualifications, timeouts or
capacity rejections occurred in the throughput phases. Latency includes the
probe dispatch and detached response. Execution comes from the production
per-invocation diagnostic receipt, including model capture and revalidation.
Times are milliseconds; throughput counts successful publications only.

| Concurrent readers | Success/sec | Latency p50 / p95 | Execution p50 / p95 | Empty-write + EDT p50 / p95 |
| --- | ---: | ---: | ---: | ---: |
| 1 | 12.35 | 69.00 / 148.32 | 67.91 / 145.14 | 4.49 / 15.75 |
| 2 | 15.53 | 119.68 / 209.63 | 113.76 / 204.03 | 6.18 / 26.48 |
| 4 | 22.41 | 158.97 / 266.32 | 153.83 / 255.61 | 12.79 / 64.77 |
| 8 | 18.51 | 328.41 / 1067.12 | 320.30 / 898.15 | 17.91 / 25.04 |

Separate responsiveness phases issue eight EDT/empty-write probes per row while
reads are outstanding. They produced `READ_EPOCH_REJECTED/READ_PREEMPTED` for
1/8, 0/16, 0/32 and 8/64 reads respectively; every other read published. This
proves bounded observed write access, not a general UI responsiveness guarantee.
The reversible content edit described below separately proves epoch invalidation.

Model capture dominates this workload: p50 capture times were 65, 110, 149 and
313 ms. No model caching or refresh-coalescing change is included.

### Confirmation and default choice

A second candidate used the provisional four-reader limit and moved diagnostic
start before admission, so `REQUEST_ADMISSION` now includes the short permit
transition and coroutine scheduling. The actual semantic queue wait is zero by
policy: saturation rejects immediately. These measured admission durations are
an upper bound on monitor wait, not an isolated lock-contention measurement.

| Concurrent readers | Success/sec | Latency p50 / p95 | Admission + scheduling p50 / p95 | Execution p50 / p95 | Empty-write + EDT p50 / p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 15.33 | 58.58 / 100.07 | 0.051 / 0.190 | 57.28 / 97.80 | 0.70 / 6.97 |
| 2 | 21.26 | 84.59 / 141.62 | 0.047 / 0.154 | 82.71 / 139.40 | 6.13 / 9.83 |
| 4 | 20.38 | 163.84 / 319.11 | 0.051 / 0.088 | 160.66 / 318.43 | 9.86 / 20.82 |

Again all 96 throughput requests per row succeeded. Separate write probes
preempted 3/8, 2/16 and 3/32 reads respectively, each with the finite cause above.
Four readers' throughput advantage did not reproduce; two improved throughput
over one in both runs, with consistently lower tail latency than four or eight.
Therefore the final default is two. Larger capacities remain an explicit
configuration choice. These short local trials are not universal performance
claims, and eight is not justified as a default by this evidence.

## Concurrency regressions

Both native runs also passed the same deterministic barriers:

- Two invocations simultaneously held native read access **inside K2 analysis**;
  both subsequently published.
- Cancelling A returned `CANCELLED`; B still published.
- Retiring the original owner with both reads admitted returned `RETIRED` for
  both, then confirmed drainage.
- A reversible document edit after semantic detachment returned
  `FRESHNESS_REJECTED/MOVED`. The source bytes and saved document were restored.

Owner-local tests additionally prove that cancellation keeps capacity occupied
until cleanup finishes; duplicate and foreign completion cannot release another
permit; retirement waits for both independent cleanup barriers; late E cannot
replace/clear E+1 state or regain authority; and escaped retired stores cannot
issue or restore retained state. A moved-basis transaction never re-evaluates.

## Reproduction and retained evidence

Build with `:runtime:hosted:hostedPlugin -PhostedIdeaHome=<exact IDEA Contents>`.
Run `experiments/host-observation/run_concurrent_reads.py` with `--idea-contents`,
`--artifact`, `--project`, `--file` and `--offset`. This opt-in carrier requires
that exact project already open in the original IDE; it never opens/imports a
project or replaces a plugin. The file must name a saved Kotlin declaration
with one explicit source supertype. It performs and restores one document edit.
Use `--capacities 1 2` for the final default; the 1/2/4/8 comparison requires an
explicit eight-or-higher reader configuration in the candidate service.

The complete JSON reports, typed diagnostic receipts, input pins and carrier
logs remain in the local evidence directories:

- `kast-concurrent-reads-9em6nte2`: comparison archive SHA-256
  `4aababdbc70a3a8f5a30d4066b0df4d5b8d700efad39554c9b95295b556e1cc2`;
  report SHA-256 `b62b32f3408b765df497b96a1b36849219a715602a12f8b4fcd62fb65b5e07f1`.
- `kast-concurrent-reads-3mhg2x9y`: confirmation archive SHA-256
  `4d4622cf46b3b1c650e2fbbb6a174047e58a09aba41f54c4a242ec0d31d8b581`.

Both are under `/private/var/folders/zj/xhhqh2nd6qxc_m2tgcl_scw00000gn/T/`.
Archive hashes identify the measured code, including uncommitted changes; the
embedded version label alone does not identify those changes. The final capacity
reduction from four to two is covered by the same bounded-owner tests; the
confirmation's two-client row ran the otherwise identical executor.

## Local verification

Passed module `check` tasks for `kernel`, `distribution:contract`,
`workspace:contract`, `workspace:intellij-read`, `query:protocol`,
`runtime:hosted`, and `protocol:contract`, plus
`:cli:test --tests '*HostedFailureBudgetSchemaTest'`: 593 tests, zero failures.
Also passed `verifyKastArchitecture`, `verifyJsonContracts`,
`verifyConfigurationIngress`, `knowledgeImpact`, `verifyKnowledgeBase`, and
`:cli:verifyMintlifyCallableReference`. Configuration and callable-reference
snapshots were regenerated from their owners. Native candidate assembly and
both opt-in qualifications passed. The entire product/release gate and a fresh
installed-release deployment were not run.
