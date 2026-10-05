# Direct tool startup measurements, 2026-10-05

## Result and scope

Defer discovery catalog projection until catalog discovery. A one-shot `query_symbols` invocation uses compiled `AgentToolName` registrations and the unchanged canonical `PublicToolContract` argument admission. MCP/RPC catalogs retain the complete identical schema. Native scope, source authority, budgets, qualification, ownership, shutdown fencing and request limits are unchanged.

The frozen lazy candidate reduced end-to-end wall time in all 31 completed paired cases. The full repeat had 10 pairs for each of two synthetic fixture queries; a second repeat stopped at a finite lifecycle-capacity rejection. This establishes a local startup improvement on an already indexed small fixture. It does not establish an enterprise repository query or import improvement.

The timed source was based on `c2582dbf0c3bbee83acc7db4f426d5836ece0710` (#927). The PR branch starts from `faa385db9` (#928), whose only intervening changes are release-pin documentation. The measured live Control was `0.20261005.32000`; loaded Host was `0.20261005.132150`. The previously reported abbreviated Host source `6fd2a38d` could not be resolved locally. Versions are observations, not proof that the loaded Host matches this source tree. No installation, active IDE, global setting, or live service changed.

## Paired measurements

One excluded warmup pair per query per repeat; measured binary order alternates each pair. Requests use exact synthetic declarations in package `repro.logging`, source set `main`, and compiler identities independently expected to number one and five. Every successful sample retained source, location and signature, complete/exhaustive coverage, no failure/omission, the same root and Host epoch, and requested budgets of 30,000 ms / 50,000 work units / 20 results / 65,536 returned bytes. One correlated Host semantic receipt is required for every measured call. Correlation handles log rotation by retaining its inode. Process CPU is child user+system time across JVM threads.

| Repeat | Query | Paired n | Baseline median wall ms | Lazy median wall ms | Median paired reduction | Baseline/lazy median child CPU ms | Baseline/lazy median native ms |
|---|---|---:|---:|---:|---:|---:|---:|
| Complete 0 | FixtureLogger | 10 | 1542.549 | 1008.321 | 34.49% | 3885.599 / 2467.977 | 3.292 / 32.759 |
| Complete 0 | sharedOperation | 10 | 1543.435 | 999.181 | 35.23% | 3919.987 / 2473.919 | 3.981 / 19.102 |
| Partial 1 | FixtureLogger | 6 | 1527.004 | 984.217 | 36.57% | 3864.881 / 2415.352 | 3.601 / 31.913 |
| Partial 1 | sharedOperation | 5 | 1530.346 | 976.264 | 36.39% | 3896.917 / 2370.985 | 4.144 / 7.623 |

Native latency increased in each summary. No native optimization is claimed; the cause of that increase was not isolated. Candidate/refinement counts remain one/five. Improvements in total wall and child CPU survive that increase.

There were 71 successful calls: 8 warmups, 62 paired measurements, and one unpaired baseline. Call 72 (repeat 1, pair 6, sharedOperation, candidate) returned `daemon-operation-lifecycle-capacity`. Its wall duration was not captured before the original assertion failed, and no native semantic receipt exists. Those fields remain null. The repeat is marked incomplete; no replacement timings or increased limits conceal it.

Frozen baseline CLI/app-server SHA-256:
- `8807baf2388382ca824896081b2fcba28e93fb2955afde520e7c0370ee8ca8f1`
- `3adc362bd1be168a5c72f5dba7d7eb43a3ab96467f810e7dc55cbfe1fd2ac2ef`

Frozen candidate CLI/app-server SHA-256:
- `03a1266adf484de4ca8587d1aa38bd304117555b6cdda84e83d7f6a581f32b34`
- `0b8f324bb058e3c05dc03966d7c282a7e13b020328f2123c77a67185dc3342f4`

The final source groups the registration names and lazy producer into a typed `DirectToolRegistration` owner and extracts compatibility admission to satisfy quality gates. Functional tests verify the same path. Native capacity prevents a new successful full semantic-query paired trial of those final bytes; the hashes above identify the earlier timed variant. The separate final-byte startup trial below is complete.

## Exact final-byte startup trial

Rebuilt the unchanged final code commit `968b3e75fcabdc69b1271da2c3397ee8e402ab44` with the selected IDEA JBR and staged its CLI/app-server JARs in an owned complete payload. Each call starts a fresh JVM against the same indexed synthetic fixture and loaded Host. Two repeats each exclude one warmup pair and alternate order across six measured pairs. The fixed request is passive verbose health; all 28 calls return complete health with READY, INDEXED and SAVED_PSI_COMMITTED evidence.

| Repeat | Paired n | Baseline median wall ms | Final median wall ms | Median paired reduction | Baseline/final median child CPU ms |
|---|---:|---:|---:|---:|---:|
| 0 | 6 | 1264.521 | 578.373 | 54.53% | 3468.016 / 1760.188 |
| 1 | 6 | 1277.305 | 580.518 | 54.73% | 3435.818 / 1764.047 |

All 12 measured pairs improved. Final CLI SHA-256 is `dbacc5e96df4607b4bdfb05b396cd35302fb9abba2ca2ba5ec5a2ed52e250e60`; app-server SHA-256 is `32869025815f349f6a21b460bcd086bd47097c0e2d1934fa1cd5e08a41edb53f`. Git source state did not change during the trial. Later Claim/report commits change documentation only.

This measures one-shot startup and passive health, with no native semantic query or correlated semantic receipt. Preparation diagnostics still contain the lifecycle-capacity rejection; passive health remains available. The bounded history belongs to the loaded application's `IdeLifecycleApplication` service, so a fresh CLI or Control process reaches the same history. A scripted fresh lifecycle fixture would prove policy only. Fresh native authority would require a separately qualified graphical IDEA application and imported/indexed fixture; neither resetting the active application nor loosening capacity is used here.

## Why the earlier candidate was discarded

The initial candidate replaced one hosted schema projection with direct metadata construction, but still constructed all direct input/output discovery schemas before an invocation. Its completed confirmation repeat regressed medians from 1922 to 1940 ms and 1765 to 1885 ms; child CPU also increased. It was discarded. An earlier interrupted correlation run was excluded and preserved, not counted as successful.

The retained variant is different: both hosted and direct discovery projections are lazy, and registration membership is a compiled typed fact. Full schemas are still generated for discovery and canonical argument admission remains mandatory for invocation. A test supplies a discovery producer that throws if invoked, then independently checks unknown/malformed rejection, a valid dispatch and exactly one preparation; this proves less work rather than a schema cache or relaxed validation.

## Stage attribution

Opt-in JFR events cover named CLI and exact-IDE boundaries. Event fields contain only closed stage/outcome names, with no request, path, symbol, response or exception text. Recording is not started by ordinary invocation. Events preserve typed returned rejections and thrown exception identity. Raw JFR files can contain JVM arguments and paths and remain private.

Across 10 recorded baseline calls after one warmup, median wall was 1656.614 ms, JVM startup before main 163.572 ms, and session composition 970.978 ms. Hosted catalog composition was 726.675 ms and direct catalog projection 170.510 ms within composition. Admission was 156.255 ms, invocation 282.137 ms, model capture about 0.04–0.07 ms in earlier live receipts, native semantic read 3.634 ms, result projection 0.321 ms and output serialization 1.192 ms. The lazy variant avoids the two catalog blocks on invocation. The observed total saving is about 534 ms, not the sum of those two medians: first-use work moves to other stages, and nested/asynchronous timings overlap.

The socket operation stage includes client encoding, connect/write/read, Host work and decoding; it is not pure transport. A compatibility status exchange occurs before the semantic operation. No index/import/cold model claim follows from this already indexed fixture. Disabled-observer calibration had noise (baseline 1689.449 vs instrumented 1554.771 ms); it is not an instrumentation speedup claim.

## Validation and followups

Functional tests prove invocation does not evaluate a throwing discovery producer, unknown/malformed tools reject without preparation, valid dispatch prepares exactly once, and discovery preserves complete MCP/RPC catalogs and schema equality. Observer tests preserve typed outcomes and original exception identity. Canonical admission, native budgets, scope and ownership remain mandatory.

The investigation's full CLI suite and affected app-server suites passed, along with JSON contracts, architecture, formatting and file-length guards. Detekt findings were compared by rule, normalized source URI and message against an untouched source archive; no introduced findings remain. The final PR-branch aggregate run is recorded in the PR validation artifact.

The second repeat's finite lifecycle-capacity rejection and the native latency increase are retained limitations. Lifecycle retirement policy, output continuation, socket recovery and enterprise import/index/model measurements are deferred followups. The private repository and its live basis were unavailable. Final source has functional/JFR evidence of avoiding catalog work and a completed final-byte startup/health trial; the native semantic paired timings identify the earlier frozen variant. No active IDE/service restart, limit change, global setting or installation was used to manufacture a successful trial. Raw recordings, screenshots and full logs remain private and are excluded from this PR.
