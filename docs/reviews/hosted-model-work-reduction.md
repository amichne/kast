# Retain the imported model within one live epoch

## Scope and selected work

The baseline is fetched `origin/main` at
`e913d091fea9284e5c9535b2e16eeabcd1322aa5`. Both artifacts query the same
immutable archive of that commit through the installed persistent public MCP
path. This is the existing `KAST_SOURCE` profile in
[`reproduce_semantic_queries.py`](../../experiments/host-observation/reproduce_semantic_queries.py),
not a claim about the frequency of all production requests.

The characterized flow reads exact `QueryPlanSyntax` with source, drains
workspace references to `QueryStepSyntax`, and drains public functions in
`kernel/src/main/kotlin`. Every trial starts a fresh query and uses only its own
issued references and continuations. The fixed grant is 10000 ms, 100000 work
units, 20 results and 49152 returned bytes per call, at concurrency one, with
one retained warmup and two measured repetitions per workload.

Two qualified baseline runs agree on all six measured semantic comparisons.
Each repetition observes:

| Workload | Public calls | Complete rows | IDEA modules enumerated | Source roots enumerated | Revalidation bytes hashed |
|---|---:|---:|---:|---:|---:|
| Exact source | 1 | 1 | 152 | 105 | 9565 |
| Dense references | 37 | 160 | 5624 | 3885 | 4556 |
| Scoped public functions | 3 | 43 | 456 | 315 | 47960 |

Dense references account for 37 of 41 public calls (90.24%). Each hosted call
repeated the same detached imported-model capture: 58 imported projects, 152
IDEA modules, 147 selected Gradle modules, five foreign modules and 105 source
roots. This repeated enumeration was selected because it appears on every call
and concentrates in the flow with the most observed calls. Byte counts,
enumeration counts, compiler refinements and stage durations are different units;
they do not establish a combined work total or CPU share.

## Production rule

[`HostedNamedGradleSourceScope`](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedNamedGradleSourceScope.kt)
owns one successfully admitted detached model per endpoint. Reuse requires
equality of the opaque `ProjectReadEpoch` and identity of the immutable
`ReadLimits` admission policy. Freshness is validated before every access and
again after each new capture. A changed or incomparable epoch, changed policy,
rejection or cancelled capture cannot publish the previous model as current.
Failures are not cached. A mutex covers capture and retention; evaluation holds
no model-retention lock.

[`HostedQueryService`](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryService.kt)
admits live epoch authority before model capture. Its existing validation before
and after semantic evaluation remains required. Existing epoch signals cover PSI,
scoped VFS, model/root changes, import identity/timestamps and indexing state.
Reuse explicitly records zero for the five existing model-enumeration counters.
Missing diagnostic receipts remain unavailable; the counter contract is unchanged.

## Native qualification

The qualified candidate run completes all three warmups and six measured trials.
Comparison against `baseline-b` reports six `EQUIVALENT` pairs, empty
incompatibility/unavailable lists and `lessWork=true`.
This includes every semantic row, order, compiler qualification, source payload,
scope, omission and terminal result. All successor checkpoints are drained.

The following counts apply independently to either measured repetition:

| Workload | Imported projects, baseline → candidate | IDEA modules | Selected Gradle modules | Foreign modules | Source roots |
|---|---:|---:|---:|---:|---:|
| Exact source | 58 → 0 | 152 → 0 | 147 → 0 | 5 → 0 | 105 → 0 |
| Dense references | 2146 → 0 | 5624 → 0 | 5439 → 0 | 185 → 0 | 3885 → 0 |
| Scoped public functions | 174 → 0 | 456 → 0 | 441 → 0 | 15 → 0 | 315 → 0 |

Across the three workloads this avoids repeated enumeration of 2378 imported
projects, 6232 IDEA modules, 6027 selected Gradle modules, 205 foreign modules
and 4305 source roots per repetition. These are separate counter totals, not
unique object counts. All other work counters in that comparison are unchanged
except epoch-read preemptions, which do not increase. Public calls, native discovery and
relation pages, encoded bytes, compiler refinements, revalidation hashing,
issued handles and locator outcomes are preserved.

The additional comparison against `baseline-d` also reports all six pairs
`EQUIVALENT`, with the same model-counter reductions and no missing evidence.
Its overall `lessWork` is false: dense-reference repetition one has one additional
epoch-read preemption. The strict comparator rejects any increasing work counter;
five individual pairs pass its reduction predicate. This preserves the scheduler
observation and limits the aggregate verdict to the qualified `baseline-b` pair.

This proves reduction of the observed model-enumeration work within one stable
epoch after warmup. A new endpoint or epoch still pays for capture: the first
successful candidate capture, retained in `candidate-b`, observes the original
152 modules and 105 roots. That run is excluded from the qualified comparison
because its earlier exact-source warmup rejected. The qualified `candidate-c`
uses the same endpoint's ordinary retained state; no cache invalidation or grant
change was used. No latency, CPU reduction or cold-start improvement is claimed.

## Artifact correspondence

Both Host artifacts use `-Pversion=0.20261004.20000` and the same IDEA contents.
The latest-main Control artifact remains byte-identical throughout. Unpacked
Host JAR inventories differ only in `workspace-intellij-read`:

| Artifact | Workspace read JAR SHA-256 |
|---|---|
| Baseline | `7fb03177b27b209502e1915109273bca7998632cf40997cfdf4b6a20f933cd64` |
| Candidate | `230f5a0a5f81422751db7ae602e02b1588536b84ab4e0ecd7ca74489bf13d6d1` |

The packaged and actually loaded candidate class hashes match the compiled
classes: `HostedQueryService`
`2413491ecc550ac766cc60e662009d6d73f1ddb9c0dc2f4c7870078f1578a18a`, and
`HostedNamedGradleSourceScope`
`9320bba20e87230d42a46b3a86244041acdc18e5ccafe7d7a947183fa45081c7`.
The candidate is the baseline plus the local source diff, not a published release.

Private raw receipts are retained under the owned `kast-main-work-92u5imz5`
directory. They include source paths and issued opaque handles and are excluded
from Git. `baseline-a` retains a freshness-rejected warmup; `baseline-c` retains
a missing native diagnostic receipt. Neither establishes performance proof.
`baseline-b` and `baseline-d` are qualified and repeatable. `candidate-a` retains
an unavailable native pin during plugin-triggered indexing, before any trial.
`candidate-ready-pin` also records indexing. `candidate-b` retains the rejected
first warmup during ordinary workspace preparation; its measured trials cannot
repair that qualification. `candidate-c` is the qualified run.
Setup, import, plugin loading, pinning and artifact assembly are outside workload
measurements.

The owned source snapshot was closed and the original plugin restored in the
same IDEA process. The final probe matches the original open-project list and
unsaved-document state. The final smart-mode pin matches the original plugin
path/version, every pinned loaded class, plugin JAR inventory and user Control
artifact. IDEA displayed its dynamic-unload restart advisory during the swaps;
restoration proves the selected active artifact, not reclamation of every old
classloader. No IDE restart or installed-file replacement was performed.

The private proof receipts are `comparison-b-c.json` (SHA-256
`2916f54a1d66b3fb297e170b81350e0e1738b55c57b0517c74442a8e4668f76a`)
and `comparison-d-c.json` (SHA-256
`cadc9e382fe2e2a2338c0033f4af5bdcd6ed68a4dcd48201251e0ce3ce3f459b`);
their retained manifests locate every raw request,
response, pin, native observation and continuation. The exact local receipt
digests should be checked against the retained files when reviewing this result.

## Verification and reproduction

The focused production-rule test first failed with three captures instead of
one. All eight final
[`HostedNamedGradleSourceScopeTest`](../../workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedNamedGradleSourceScopeTest.kt)
cases pass, including unchanged epoch, movement/incomparability, policy identity,
failed capture, freshness rejection, explicit zero observations, concurrent
readers and cancellation recovery. These tests inject only capture/freshness
effects and execute the real retention rule with domain types.

Passed commands:

```sh
./gradlew :workspace:intellij-read:test --tests '*HostedNamedGradleSourceScopeTest'
./gradlew :workspace:intellij-read:check :runtime:hosted:test hostObservationTest \
  verifyJsonContracts verifyArchitecture knowledgeImpact verifyKnowledgeBase
JAVA_HOME=/path/to/graalvm-25 GRAALVM_HOME=/path/to/graalvm-25 \
  ./gradlew productBuildGate knowledgeImpact verifyKnowledgeBase \
  -Pversion=0.20261004.20000
```

The product build gate verifies deterministic contracts, architecture, tests and
packaging; it does not itself establish native runtime behavior. Use the existing
[complete-workload procedure](../../experiments/host-observation/SEMANTIC_REPRODUCTION.md#compare-complete-workloads-locally)
with `--workload-profile KAST_SOURCE`, the same immutable archive/root/client and
separate baseline/candidate pins. Drain every trial, retain all warmups and
rejections, and compare through the existing `compare` command. Accept a work
reduction only after complete semantic equivalence and all required native
receipts. Restore the original loaded plugin after the temporary experiment.
