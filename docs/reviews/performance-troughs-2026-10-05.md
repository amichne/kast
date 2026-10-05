# Performance trough investigation — 2026-10-05

The screenshots establish mixed performance and two incomplete-output problems. This host's controlled fixture measurements establish substantial one-shot process overhead. They do not reproduce the screenshots' 13–26 second discovery troughs. A proposed catalog optimization failed to demonstrate an improvement and was discarded.

## Images actually viewed

All three Library files were materialized into this task's own workspace, verified as readable JPEG bytes, and visually viewed:

| Authoritative filename | Bytes | Dimensions | Observation |
|---|---:|---|---|
| IMG_7193.jpeg | 471,778 | 1152 × 1536 | Capability timing table and functional validation |
| IMG_7194.jpeg | 463,610 | 1152 × 1536 | Troughs, continuation rejection, output qualification |
| IMG_7195.jpeg | 350,907 | 1152 × 1536 | A reported app-server installation repair |

The first two images show results against `/Users/amichne/code/mobile/m1-msl`, using saved, committed PSI. Their comparisons explicitly include preparation, queueing, semantic execution, transport, serialization, and caches and are described as uncontrolled.

| Screenshot observation | Current | Previous |
|---|---|---|
| Serial exact FinShipedRouter discovery | 13.172 / 13.788 / 13.742 s; median 13.742 s | Median 5.460 s |
| Three concurrent exact searches | Batch 7.793 s | Batch 15.199 s |
| shouldUseFeature with source and retention | 4.020 / 4.294 / 8.293 s; median 4.294 s | Median 5.496 s |
| Retained-result read | 147 ms | 8.903 s |
| Resume incomplete retained search | 11.520 s for remaining two declarations | No directly equivalent isolated result |
| FinShipedRouter REFERENCES | Eight pages, 51 occurrences, exhaustive; about 4.4 s | Eight pages, 51 occurrences; about 41.9 s |
| Scoped IDEA diagnostics | Initial enumeration 90 ms, resumed scan 12.926 s; eight files, exhaustive | 11.176 s; nine files |

A separate exact acquisition of `ECMRampUpProviderImpl.shouldUseFeature` took 26.535 s despite a complete result. A reference resume was immediately rejected, then an identical fresh operation drained all eight pages successfully without repair.

The CALLEES walk found three edges and four expanded nodes, with complete traversal coverage and no partial expansions. Repeated library-policy exclusions exhausted the 65,536-byte allowance. A resume returned no new rows but issued another continuation with the same limitation. The structured diagnostic result reported exhaustive coverage while its human-readable preamble said coverage unavailable. These are separate correctness/presentation symptoms; screenshots alone do not identify a CPU bottleneck.

## Launch observations and identities

**The launch screenshot contains no original error text.** It states that a standalone Codex daemon had owned the canonical discovery socket, that Kast was enabled using IntelliJ's Java 25 runtime, and that a native initialize handshake returned Codex 0.160.0. It cites installed build `d1305fe51`. Git verifies that `d1305fe51e35855e65a1024ce88d094a90e8a341` is #923 and descends from the #920 default discovery change and #922 rejected-startup ownership repair. This verifies source ancestry, not that screenshot's installed bytes.

Passive observations on this task's host:

- Source baseline: `c2582dbf0c3bbee83acc7db4f426d5836ece0710` (#927), freshly fetched and verified against origin/main at task start.
- Original checkout: main at `374308a46`, with existing untracked user review work; preserved.
- Isolated worktree: `/Users/amichne/Documents/Codex/2026-10-05/task-3/kast`, branch `perf/characterize-troughs`.
- Installed and running Control: `0.20261005.32000`.
- Loaded Host: `0.20261005.132150`, IDEA PID 81367; passive status reports compatibility.
- Control installation payload identity: `sha256:959551deeb2fbdac5b852beedd9fc7386ff3869452a9973dd99a1ab04ccaeabd`.
- Canonical Codex socket currently points to a Codex-daemon socket owned by PID 52488; Kast's private socket is served by Java PID 27821 through launchd.
- The screenshot workspace `/Users/amichne/code/mobile/m1-msl` is absent.
- The previously supplied source abbreviation `6fd2a38d` is unavailable in this repository's object database; loaded source ancestry is unverified.

The likely launch boundary is public discovery/socket ownership admission, before semantic execution. Current private Kast readiness does not establish ownership of the canonical Codex route. The screenshot's historical error cannot be reconstructed from the image. No active app was restarted, no incumbent process was stopped, and no global setting or installation was changed.

## Controlled baseline

The already-open fixture was `/Users/amichne/.codex/worktrees/19ce/kast/build/callback-native-fixture`, host `13839cb2-b299-4389-90b6-5f9a60245e5a`, epoch 1. A health call verified the exact root, READY, INDEXED, and SAVED_PSI_COMMITTED.

Two queries alternated through the installed one-shot Tool RPC and one case-owned MCP process. There was one excluded warm-up pair and ten measured pairs per query and transport; transport order alternated. No build ran concurrently with measurement.

Both queries used exact names, exact kinds, package `repro.logging` without subpackages, source set main, and NAME/LOCATION/SIGNATURE/SOURCE output. The fixed requested budget was 30,000 ms, 50,000 work units, 20 results, and 65,536 returned bytes.

Every measured result had complete status, exhaustive coverage, no failures or omissions, the same host/epoch, unchanged locations/signatures/source, and the independently expected identities: one FixtureLogger and five IdentityOne/Two/Three/Four/Five.sharedOperation declarations. Every call matched exactly one native semantic receipt. Opaque references were required to be issued exact:v5 values; they were not treated as stable identity equality.

| Query | RPC median wall | Warm MCP median wall | RPC native median | MCP native median |
|---|---:|---:|---:|---:|
| FixtureLogger | 1,576.085 ms | 19.379 ms | 5.523 ms | 4.058 ms |
| sharedOperation | 1,605.763 ms | 22.991 ms | 9.055 ms | 8.019 ms |

MCP initialization was 1,185.284 ms and is excluded from warm call latency. RPC ranges were 1,544–1,642 ms and 1,544–1,793 ms respectively. Native model-capture medians were 0.061/0.073 ms for RPC and 0.045/0.059 ms for MCP. Returned RPC envelopes were 3,313 and 6,905–6,906 bytes.

Native counters proved one discovery page, one/five collected and projected candidates, one/five compiler refinements, one file hashed per query, and 331/282 bytes hashed. These counts are consistent with the scoped fixture and do not establish behavior on m1-msl.

Fresh import, cold indexing, and a cold model build were not timed. Preparation checks, scheduling, process bootstrap, and client work remain in wall time outside the correlated native receipt. The gap isolates a transport/process-lifecycle concern on this fixture, not an explanation of the screenshots' slower serial discovery. Existing persistent MCP amortizes its initial process cost; no new persistent watcher was introduced.

## Optimization trial and rejection

A startup JFR catalog profile contained 47 execution samples, dominated by Kotlin serialization/JSON work and schema construction. The tested candidate constructed the direct catalog from canonical agent definitions and avoided hosted output schemas that the direct projection immediately replaces. A fresh equality test proved every direct registration's name, description, input schema, output schema, and read-only effect matched the prior projection and rejected hosted-only definitions. The full source-built Tool RPC catalog also equaled the installed catalog.

Baseline and candidate were built from the same source baseline, with identical explicit version, dependencies, Java 25 runtime, request, fixture, scope, and budgets. The candidate changed only direct catalog construction. CLI jar SHA-256 values were:

- Baseline: `b8414e05087e2395c630035d7aa62ca71f597778641b130a43c3c0ddf5edfe5e`.
- Candidate: `28edc5f1b53287379d57bd27435ec2c42e2035a283b9350998c14015c41400e1`.

The first ten-pair source comparison was inconsistent: FixtureLogger wall medians were 2,264.440 → 2,169.697 ms, but sharedOperation was 2,219.446 → 2,529.760 ms. A repeat stopped after six complete pairs because a correlated native receipt was unavailable when IDEA rotated its 10 MB log. That incomplete run is not counted as evidence of improvement.

The harness was corrected to retain partial records and identify the original log by inode, and case-owned payloads were copied to /tmp. Background fileproviderd and bird were observed consuming about 129% and 94% CPU; no attempt was made to change those services.

The completed /tmp repeat, again with ten measured pairs per query, recorded child CPU time:

| Query | Baseline wall median | Candidate wall median | Baseline child CPU median | Candidate child CPU median | Pairs faster |
|---|---:|---:|---:|---:|---:|
| FixtureLogger | 1,922.017 ms | 1,940.302 ms | 4,145.031 ms | 4,198.827 ms | 6/10 |
| sharedOperation | 1,765.153 ms | 1,885.395 ms | 4,157.563 ms | 4,208.785 ms | 6/10 |

CPU time sums all child threads and can exceed wall time. Native medians also varied slightly. This does not demonstrate a repeatable performance gain. **All three candidate source/test edits were restored; no product optimization is retained.**

## Validation and evidence

- Baseline source build and two characterization classes: BUILD SUCCESSFUL, 28 s.
- Candidate source build and schema equality check: BUILD SUCCESSFUL, 6 s; eight tests executed and passed.
- After restoring the candidate, the CLI test task restored 325 passing cases from Gradle cache. This is cached evidence.
- Thirty-one app-server startup/socket/discovery cases freshly executed and passed, including real JDK socket/path probes in case-owned temporary directories and preservation of retained discovery ownership on rejected startup.
- The final combined Gradle command completed successfully in 13 s. No skips or failures appeared in the reported suites.
- No release, push, PR, or merge was performed.

Build commands used Java 25 from IntelliJ and `-Pversion=0.20261005.32000 --console=plain`:

```sh
./gradlew :cli:installDist :cli:test \
  --tests io.github.amichne.kast.cli.rpc.KastToolRpcBridgeTest \
  --tests io.github.amichne.kast.cli.PackagedProviderCatalogTest
./gradlew :cli:test :app-server:test \
  --tests io.github.amichne.kast.cli.rpc.KastToolRpcBridgeTest \
  --tests io.github.amichne.kast.cli.PackagedProviderCatalogTest \
  --tests io.github.amichne.kast.cli.mcp.KastMcpServerTest \
  --tests io.github.amichne.kast.cli.mcp.McpStructuredResultsTest \
  --tests io.github.amichne.kast.appserver.DesktopDiscoveryRejectionTest \
  --tests io.github.amichne.kast.appserver.DesktopDaemonDiscoveryTest \
  --tests io.github.amichne.kast.appserver.DesktopDiscoveryActivationTest \
  --tests io.github.amichne.kast.appserver.JdkBrokerSocketProbeTest
```

Task-local evidence includes `transport-baseline.json`, `candidate-measurement.json`, `candidate-measurement-tmp.json`, request/catalog documents, source build logs, startup JFR samples, and measurement scripts. The supporting archive contains these records and the viewed screenshots, not installation credentials or signed transfer URLs.

## Remaining blocker and next highest-value step

Access to the exact m1-msl checkout and its live loaded identities is required before claiming the screenshot troughs are reproduced or fixed. The launch screenshot's original error is also missing.

The next useful run is two fixed serial exact queries in m1-msl, one explicit warm-up, and 10–20 alternating repetitions with fixed scope/output/budgets and current canonical results. Correlate preparation, queueing, native model capture, compiler refinement, content revalidation, output fitting, and transport. Then drain the 51-occurrence reference result and the three-edge walk while preserving source basis, exclusions, completeness, and continuation ownership. Isolate cold import/index/model build in a separately owned configuration only when its contribution remains uncertain. Optimize the largest measured stage one change at a time, retaining only a paired improvement with those invariants preserved.
