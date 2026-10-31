# Native query CLI evidence

The native client passes installed IDEA parity, retained/source reads, and Java-denying execution checks for the exercised commands.
The complete paired cohort passes equivalence checks; metadata and plans are faster, while semantic median timings are slightly slower.
Successful issued-token RESUME, rollback restoration, and clean-source admission remain unverified. This is not a fully accepted replacement.
This report follows the October 30, 2026 specification, AC01–AC17. Every criterion began as NOT RUN.
No release, push, merge, or personal installation replacement occurred. The changes remain uncommitted.

The user temporarily waived compressed and installed byte-size checks. No other acceptance requirement was waived.
Repository size limits remain unchanged. A local Gradle init script overrides only those two limits for this run.
Entry count, executable format, exact producer identity, resources, and installation checks remain enabled.

## Source and executable identities

- Examined specification baseline: `96e4c4bb3d1b9185869e4f0c73e9f3d7c95bfb6a`.
- Refreshed baseline and candidate HEAD: `1e3a654d3ea4f160d603a37e5c574d5470c9a543`.
- Candidate: `/Users/amichne/code/kast.feature-native-query-cli`, branch `feature/native-query-cli`, with uncommitted changes.
- Pristine comparator: `/Users/amichne/code/kast.feature-native-query-baseline`.
- Existing work in `/Users/amichne/code/kast` was preserved.
- Tested tracked-diff SHA-256: `012069b702fc43b98fcd953e395ee7a0ded142dd65014687292250e983987445`.
- `sha2/source-identity.json` records the exact bytes of 41 changed source files, including untracked implementation files.

Evidence paths below are relative to `.amp/in/artifacts/native-query/`.
The portable [evidence archive](native-query-cli/evidence.tar.gz) contains raw results, collectors, source snapshots, and a per-file SHA-256 inventory.
`sha2/previous-report.md` preserves the previous report.
Evidence under `size-waived/` identifies the earlier candidate without SHA2 acceleration unless stated otherwise.

| Final artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `kast-control-v0.0.0-macos-aarch64.tar.gz` | 78,166,915 | `4f70df81f0a1bded029201329400ba02118f957b2e7fea40a4a7d90a142c9597` |
| Native `bin/kast-query` | 63,354,520 | `c33d63944d9335109693dee494533ef60f2f8e91165aaf34941043573ba5a04a` |
| Management executable | 37,193,480 | `021549d488ce6c344350997f3b90a7248fcd159e7ae0baa2894e69b89e50ae1e` |
| Baseline JVM query launcher | See installation inventory | `d900861b69aa77c80dce1c2bfba3d40340ca40f370fb8a297729cb43244708c2` |
| Baseline management executable | See installation inventory | `54386c967c713a4288e529fed654bd5436ff163c3912caf2ca81dfd275d8724c` |

The archive's management executable is `share/kast/libexec/kast-management`.
Its `bin/kast` is the private JVM installer script; installation creates the public native dispatcher.
The archive contains 15,520 regular files, 15,658 entries, and 156,074,460 regular-file payload bytes.
Both byte limits are SKIP under the waiver: 67,108,864 compressed and 134,217,728 installed bytes.
The 16,384-entry limit remains enabled and passes.

The actual native installation contains 159,707,756 bytes across 15,529 regular files.
The baseline installation contains 96,331,456 bytes across the same file count.
These include installation-generated files and differ from the archive's staged payload measurement.
The public management executable is separate: 37,193,480 bytes native candidate versus 37,193,528 bytes baseline.
`sha2/measurements/ready-001.json` records these boundaries and build costs.

Oracle GraalVM 25.0.2+10.1, Java 25, `--no-fallback`, exact reachability metadata, and `-Os` produced the macOS arm64 query image.
The build also sets `fallback=false`. No wrapper supplies JVM fallback.
The query image took 63 seconds to generate, with 4.09 GB builder peak RSS.
The combined build command took 126 seconds. The management image took 56.9 seconds, with 2.64 GB builder peak RSS.
These are build costs, not query execution measurements.
Linux native and IDEA acceptance remain NOT RUN; this report qualifies only the exercised macOS arm64 target.

## Acceptance record

PASS requires executed assertions at the stated scope. FAIL records an observed contradiction.
NOT RUN means complete evidence is missing. SKIP records an explicit waiver or disabled suite, not a behavioral pass.

| Criterion | Result | Evidence and remaining scope |
| --- | --- | --- |
| AC01 | PASS | Final archive query matches its native producer. Format and no-fallback checks pass. Substitution tests reject changed bytes and a matching shell-script producer. |
| AC02 | NOT RUN | Final installed execution policy proves zero client JVM launches for 23 exercised calls, with direct/descendant absolute-JBR controls. Successful issued-token RESUME remains unverified. |
| AC03 | PASS | Parser, encoded-contract, catalog, flags, file/stdin plans, defaults, and native rejection checks pass. COMMON restrictions remain distinct. |
| AC04 | PASS | The final native matrix passes all 158 cases, including both recursive plan descriptors. Network and writes are denied; HOME is empty, PATH lacks Java, and stdin stays open. |
| AC05 | PASS | Shared-context tests and native peer installations cover markers, fences, spaces, unrelated directories, foreign/detached installations, and replaced payloads. Installed live invocation evidence remains part of AC12. |
| AC06 | PASS | Actual native peers reject wrong roots/hosts, incompatible platforms, replaced socket incarnations, malformed JSON, duplicate keys, and trailing documents. Exact failure categories and record cleanup pass. |
| AC07 | PASS | Typed output/exit fixtures, native output tests, and actual pagination pass. Real explicit 1 ms timeout selects native 124 versus baseline 6 with identical rejection bytes. |
| AC08 | PASS | Final installed bytes pass independent real IDEA declaration/caller/range/coverage and full parity checks, including absent-name and wrong-target controls. |
| AC09 | NOT RUN | Final bytes pass 30 retained reads and ten source reads in separate processes, including baseline-issued capabilities and no producer replay. Successful issued-token RESUME remains unverified. |
| AC10 | PASS | Final native process tests pass both signals at proven incomplete-input and active-socket states, with socket closure, exact child reaping, and no replay. |
| AC11 | PASS | Native sampled blocked writes stop without consumer drain. Broken stdout selects exit 5. Output-prefix and terminal-order checks pass. |
| AC12 | NOT RUN | Exact ownership tests pass. Earlier live status, request retirement, replacement rejection, and isolated reset pass. Rollback safely rejects, but restoration remains blocked by host-quiescence admission. |
| AC13 | PASS | Queue, connection, executor, backpressure, and read-accounting tests preserve active ownership until analysis drains. Client exit supplies no host-settlement claim. |
| AC14 | PASS | Shared JVM contracts, 43 task-service/transport tests, and final installed packaged stdio task-service/RPC acceptance pass. Malformed-child checks preserve rejection without retries. |
| AC15 | PASS | Final archive identity/layout and disposable installation pass; only the two byte checks are SKIP by user instruction. Processed resources and manifest-owned selection pass. No other platform is qualified. |
| AC16 | PASS | Final ten-start pilot and 310-start cohort pass every assertion and pair. Timings, resources, stage boundaries, and earlier failures are reported separately. Semantic timings are slightly slower; independent repeatability and a causal client-regression diagnosis remain unproved. No general semantic speedup is claimed. |
| AC17 | NOT RUN | Product and underlying source Gradle gates pass with the byte-only waiver. The source wrapper rejects the uncommitted tree before running; clean-source admission remains pending. Disabled suites are listed below. |

## Native resources and local effects

`sha2/metadata-native/run-001/` contains all 158 named commands, inputs, outputs, environments, and results for the final digest.
Each process used `-XX:MissingRegistrationReportingMode=Exit`.
A macOS execution sandbox denied all network operations and filesystem writes. HOME remained empty and its entries unchanged.
All stderr files were empty; no missing-registration failure occurred.

The matrix covers both catalogs, metadata sections, help, 37 authored examples through files/stdin, 19 invalid examples, malformed input, and catalog restrictions.
Both `describe query.plan --section output` schemas contain 35 definitions and 316 locally resolved references.
NetworkNT Draft 2020-12 admission passed 99 checks: 95 valid replies and four invalid controls for basis and nested request-name constraints.
Without a stdin request, stdin remains unread; local metadata and plans do not compose a host session.

The inventory verifies 37 owned resources against the final 263-entry embedded-resource report.
Names, origin JAR digests, and sizes match. The report does not expose hashes of the embedded bytes themselves.
Processed properties contain `ideBuild=262.10315.125` and `kotlinPluginBuild=262.10315.125-IJ`, not template placeholders.
IDEA/K2 semantic implementation remains outside the client image.
The reusable matrix driver and lock are archived beside these results.
The original temporary driver was unavailable, so this version explicitly reconstructs all 158 commands and request bytes from the earlier evidence.
It derives rejection expectations from the contract and recorded expected exits, not from recorded actual exits.

## Acceptance fixes and their checks

The baseline's recursive plan-output descriptor overflowed its stack in both catalogs.
The schema owner now supplies canonical output references and their transitive local definitions.
Two real-entry tests failed with `StackOverflowError` before the change and passed afterward.
The expanded metadata suite passed 38 tests; final native qualification passed both formerly failing cases.
Evidence: `size-waived/metadata/` and `size-waived/metadata-native/`.

The host can reject an explicit elapsed allowance before an operation document exists.
Its `BUDGET_EXCEEDED / detail.cause CALLER_TIMEOUT` document previously selected protocol exit 6.
The narrow typed projection now selects 124 with an explicit grant, or semantic exit 10 without one.
Unknown failure/cause combinations still reject as protocol failures. The regression failed with actual 6 versus expected 124, then passed.
This is an intentional repair to an inherited classification defect, not byte-for-byte baseline exit parity.
The actual installed 1 ms comparison passes: baseline exit 6, native exit 124, and byte-identical rejection documents.

The lifecycle service selects JVM `user.home`, which the copied JBR does not derive from HOME.
The lifecycle environment now supplies exactly one quoted, generated `-Duser.home` property after filtering inherited options.
Controlled JBR execution confirmed HOME alone selected `/Users/amichne`; the generated property selected the disposable home.
Three tests passed, including quotes, newline, and option-looking text without property injection.
The private service entry and its Java-selection policy remain unchanged.

## Semantic fixture and independent oracle

The fixture root is `/private/tmp/knq-lifecycle-5VGhDa`.
The earlier candidate HOME was `h`; baseline HOME is `baseline-home`; the shared workspace is `workspace`.
The owned IDEA copy is `Owned IDEA.app/Contents`, with a distinct executable inode from personal IDEA.
Its project, configuration, system, logs, Gradle cache, and temporary files are disposable.
The first HOME path was shortened to fit the existing Unix-socket path limit.
Trust was configured only for this fixture. Personal IDEA PID 12733 was not stopped or modified.

The earlier host was PID 22176, started October 30, 2026 at 08:23:49 local time.
Its project identity is `2cd3f767-77b0-448c-b166-d9c526738a37`, with final accepted epoch 2, `SAVED_PSI_COMMITTED`, version 1.
The endpoint is `h/.kast/ide-hosted/a60116eb29f516005321b15d8a2a7edd/endpoint.json` beneath the fixture root.
An IDE-owned import produced one imported project and three selected IDEA modules.
The copied IDEA's phased Gradle import was disabled through its existing registry setting after the first import produced zero modules.
Both clients use that same imported model. No production import policy changed.

The runner is macOS 26.5.2, build 25F84, arm64. The actual IDEA build is `IU-262.10968.63`.
Its selected JBR is `25.0.4+1-b508.27`, `JBR-25.0.4+1-508.27-nomod`.
These runtime versions are distinct from the processed compatibility properties and the GraalVM build toolchain above.

The earlier candidate manifest SHA-256 is `45e892d90fa932beb305019512cca82d369965866971754308c18d902f9140f9`.
Its management receipt SHA-256 is `33a18b28d068c9555b85acc07dbddacfae8d234b832a242b310862a1f2b112eb`.
Both candidate and baseline passed independent verification of 15,525 manifest entries, with zero rejections.
`size-waived/lifecycle/ready-host-receipt.json` binds launch, endpoint, basis, installation, and setup corrections.
Its SHA-256 is `7f5259a0da2bd0a7f29fab02b6c1f5217fae404781cd2325597eeab32abda541`.
That first receipt records epoch 1. The first correctness attempt rejected the later epoch 2 instead of normalizing it away.
Two new sequential baseline/native probes agreed on epoch 2 while all other identities stayed unchanged.
The new receipt is `size-waived/semantic/ready-epoch2-20261030T122938.550199Z/ready-host-receipt.json`.
Its SHA-256 is `f9bcf8430a7dc9b253023b08bec41134f9d25085cf8634fa71895bea95389e89`.
Earlier logs contain VFS and PSI/INDEXING epoch signals; no specific cause of the advance is inferred.

The accelerated correctness fixture uses HOME `m`, copied IDEA PID 35613, and project host `7f6077b6-0643-4ea6-8c42-d6a0b85f2736`.
Its admitted basis is epoch 1, `SAVED_PSI_COMMITTED`, version 1.
The candidate manifest is `214ba1d179671192a4c6ccbb0df98b67f691f11115cf4149f6bc11f848856899`.
The management receipt is `c6700a59a45a772771bb27c26daf5d87441e60646657ecc5d865b846094cd6dc`.
The authoritative readiness receipt is `sha2/lifecycle/route-corrected-001/ready-host-receipt.json`, SHA-256 `84a93f58eab790f1a030b735b61a1f09452479389962a372275f591b4ecb0e5a`.
It records a baseline Java child with the baseline installation classpath and a native child at the exact candidate path.
Baseline HOME selects `baseline-home`; its JVM `user.home` selects `m` for the shared host.
Native HOME selects `m` and receives no Java options.
Four earlier probes incorrectly shared HOME and therefore failed route qualification; their original receipt remains preserved, not accepted as parity evidence.
The two corrected probes passed the independent declaration and full normalized comparison.
All six calls count toward this correctness host's lifecycle capacity.

The final correctness run, `sha2/semantic/run-001/`, passed 94 assertion entries across 65 captured outer starts.
It includes 30 retained reads, ten source reads, and a real packaged stdio task-service session with its RPC child.
All outer children were reaped; the MCP session closed.
The six nonzero exits were the expected direct/descendant Java denials, two unissued-RESUME rejections, and two explicit-timeout outcomes.
The host identity and complete basis stayed fixed.
Its lifecycle log records 67 completed preparations: six setup calls and 61 from correctness.
The separate measurement lifetime must start fresh; this lifetime has only 189 entries left, less than the planned 195-call measurement budget.

The saved [OrderService source](public-cli-v1/OrderService.fixture.txt) has SHA-256 `c0cb08aacfb8b7097ab074e253b710eae649c22e4443d0b5a747f72ee39e4859`.
The copied source must keep those bytes.

| Independent expected declaration or occurrence | UTF-16 range, end-exclusive |
| --- | --- |
| `example.OrderService` | [17,94) |
| `example.OrderService.submitOrder` | [42,92) |
| `example.placeOrder` | [96,174) |
| `submitOrder` call in `placeOrder` | [152,163) |

The caller edge is exactly `example.placeOrder → example.OrderService.submitOrder`.
Direction is `SOURCE_TO_TARGET`, provenance `k2-authored-source`, and coverage `exact-compiler-confirmed`.
Absent-name and wrong-target controls must reject broader answers.
Fresh comparisons normalize only row IDs and consistently linked result references.
They keep host, workspace, basis, source, selected fields, completion, and coverage.
Retained reads use issued token bytes without normalization and must not replay the producer.

The earlier report incorrectly said transport/read joins were absent.
Raw logs contain `kast_transport_read`; the current collector requires exact connection-ID/read-ID joins.
Retained receipts must show RETENTION and zero producer/search/discovery/relation entries.
This is stronger than assigning one receipt to an arbitrary time interval.

Successful issued-token RESUME remains NOT RUN.
Source investigation found a consumer but no established supported public producer for this fixture.
Public automatic RUN uses retained result/cursor presentation; reducing output grants did not produce an output continuation.
The literal token in `HostedQueryRetainedPresentationTest` is not live authority.
No capability was fabricated or issued through a private owner to claim public acceptance.
Exact source paths, line ranges, and earlier attempted inputs are in `size-waived/measurements/resume-findings.md`.

## Complete client execution policy

The final correctness run applied a macOS sandbox policy to the installed public command and every descendant.
The policy denied process execution except for exact installed dispatcher, query-wrapper, and native-payload paths, plus `/bin/sh`, `/bin/bash`, and `/usr/bin/dirname`.
It contained no Java executable or directory allowance. Both installations and the copied JBR were digest-checked before execution.
The existing IDEA JVM was outside the client process tree and remained the semantic host.

Four controls tested the absolute copied-JBR path with the same policy mechanism.
Direct Java and shell-descendant Java succeeded when that exact executable was added to the allowance.
Without that allowance, direct execution exited 71 and shell-descendant execution exited 126, both with `Operation not permitted`.
The 23 installed native invocations then passed their document, exit, host/basis, and transport-to-read assertions under the Java-denying policy.
This proves zero client JVM launches for those invocations; it does not count native launches or qualify an unexercised successful RESUME.
Final raw policies, controls, commands, and receipts are in `sha2/semantic/run-001/`.
The summary SHA-256 is `458df24478fefcba7f0a01dfc9aeaa076aea6fccc6890b9e4721d75eca56e3e0`.
Earlier candidate evidence remains in `size-waived/semantic/run-002-corrected/` and is not substituted for final bytes.

## Paired collection contract

`collection-decision.md` remains frozen. `size-waived/measurements/collection-amendment.md` records only the user-authorized size waiver.
The protocol requires a passing ten-process pilot, excluded from measurements.
Each workload receives one warm-up per route and 30 alternating baseline/native fresh-process pairs.
The order is retained read, metadata, request-only plan, discovery, then callers: 300 measured starts plus ten warm-ups.
Retained reads use one pre-existing issued result; producer execution stays outside every timed read.
The baseline and candidate use the same ready host, workspace, source, basis, scope, fields, catalog, and grants.
Builds and other fixture clients must stay idle during the cohort.

The collector records each start before assertions, exact argv/environment/input, exit, stdout/stderr, output EOF, and monotonic duration.
It preserves log slices, decoded receipts, load averages, and `wait4` CPU/RSS accounting for the waited public child.
That accounting is not full descendant telemetry or summed concurrent process-tree RSS.
Missing execution counts remain UNAVAILABLE, not zero.
Complete execution-policy proof is separate from latency collection.

The report keeps every started sample, including failures, with count, median, empirical p95, range, and paired differences.
Percentiles describe this cohort; they are not a statistical power claim.
Process time minus hosted time is not JVM startup time.
No general semantic-query speedup claim is made.

The first complete cohort, `size-waived/measurements/cohort-001/`, is FAIL: 310 starts and 58 failed sample assertions.
Discovery sample 191 exited 0 but lost its receipt when the host log rotated at offset 9,948,695.
Caller samples 253–309 exited 4 with `daemon-operation-lifecycle-capacity` before semantic execution.
The collector preserved all samples and marked every population ineligible for a latency claim.
Its summary contains the raw descriptive statistics, including the slower native metadata and retained-read observations.
No failed sample was removed or converted into a successful comparison.

The corrected collector accepts one verified log rename, recording original and successor inode/offset spans and their hashes.
It still rejects missing, truncated, or ambiguous log generations. Twelve deterministic rotation checks pass.
`size-waived/measurements/rotation-capacity-amendment.md` preserves the correction, failed evidence, and lifetime budget.

The final measurement lifetime uses copied IDEA PID 42151, started October 30 at 09:08:57 local time.
Its semantic host is `840ec047-ba9e-4085-a454-c500ff7a4c8d`, epoch 1, `SAVED_PSI_COMMITTED`, version 1.
The previous correctness host was stopped and reaped with exit 143 before this launch.
Two readiness probes and one fresh baseline producer completed before the ten-process pilot.
The pilot passed all ten sample assertions and all five paired comparisons; it contributes no reported timings.
Its summary SHA-256 is `c9529e0254a49b10baa364584c00527312ef50915f1af1b035c89ba4dd58db09`.
The readiness receipt is `sha2/measurements/ready-host-receipt.json`, SHA-256 `0e9234cf5fcda32bb1f1aac011bd4d29916689598ad76f80607c7a616e8dd2c5`.
The pre-existing retained token is `result:v1:dd6b7151-3a91-4e2a-91f8-9c17784d0743`; its baseline producer was reaped before any timed read.
No correctness token or lifecycle history was reused.

Post-collection checks found exactly 195 unique preparations, all complete: three setup, six pilot, and 186 cohort preparations.
All 195 semantic joins retained the admitted host and basis; 61 of 256 lifecycle entries remained available.
The verifier preserved 22 final log files and rechecked 165 pinned identities, 41 source files, and both installations' 15,525 manifest-listed files.
The existing fixture owner then stopped and reaped IDEA PID 42151 with exit 143; its owner processes also exited.
This was harness cleanup, not product retirement or further AC12 evidence. Personal IDEA and the protected `/h` fence remained unchanged.
No further product call, installation change, rollback attempt, or build occurred.
The host is no longer live, although its endpoint files remain on disk.
Evidence is in `sha2/measurements/post-collection-001/`.
The cleanup receipt SHA-256 is `916ac0c712e737c7734576f51078e76ce8f4bc454e5d58fa1f2f130563307131`.
The lifetime-accounting SHA-256 is `ca104f1fed271c2e528f9d8b4aa783aa839670b7b33732d5a7d9efe993eca7bc`.

## Paired measurements preserve all samples

`sha2/measurements/cohort-001/` is PASS for collection and equivalence: 310 starts, zero failed sample assertions, and 155 passing paired comparisons.
The ten warmups are excluded below; each workload has 30 measured pairs.
All children exited 0, were reaped, and reached output EOF before the next invocation.
One log rotation occurred during caller baseline pair 18; the collector preserved both verified spans.
The summary SHA-256 is `d4b2a7d3ebfdaccf8069ca16d41c7c4bc1e914111d91c68da931f988bac43c0b`.
Raw directories keep every command, environment, document, receipt, resource observation, and duration.

Public duration runs from process start through output completion, in milliseconds:

| Workload | Route | Count | Median | Empirical p95 | Minimum–maximum |
| --- | --- | ---: | ---: | ---: | ---: |
| Retained read | Baseline | 30 | 1,768.162 | 1,838.143 | 1,694.358–3,218.847 |
| Retained read | Native | 30 | 1,789.566 | 1,859.138 | 1,732.457–1,860.275 |
| Metadata | Baseline | 30 | 220.707 | 230.433 | 216.177–244.873 |
| Metadata | Native | 30 | 93.038 | 97.995 | 91.787–101.527 |
| Request-only plan | Baseline | 30 | 519.066 | 546.904 | 507.746–552.180 |
| Request-only plan | Native | 30 | 114.670 | 120.696 | 112.928–121.317 |
| Discovery | Baseline | 30 | 1,784.412 | 1,858.135 | 1,723.089–1,862.877 |
| Discovery | Native | 30 | 1,813.071 | 1,857.070 | 1,758.703–1,866.697 |
| Callers | Baseline | 30 | 2,099.122 | 2,140.485 | 2,007.590–2,168.968 |
| Callers | Native | 30 | 2,110.178 | 2,177.604 | 2,008.146–2,190.474 |

Each paired difference is native minus baseline, in milliseconds. Negative values favor native.
These are medians of differences, not differences between medians.

| Workload | Pairs | Median difference | Empirical p95 | Minimum–maximum | Native slower pairs |
| --- | ---: | ---: | ---: | ---: | ---: |
| Retained read | 30 | +32.774 | +124.381 | −1,359.708–+154.328 | 21 |
| Metadata | 30 | −128.293 | −122.476 | −151.216–−121.434 | 0 |
| Request-only plan | 30 | −404.385 | −395.755 | −437.800–−393.483 | 0 |
| Discovery | 30 | +36.299 | +124.722 | −78.419–+129.109 | 21 |
| Callers | 30 | +8.833 | +90.880 | −104.423–+120.417 | 19 |

Every metadata and plan pair favored native. Semantic pairs show a small slowdown in this cohort, not a speedup.
The 3,218.847 ms baseline retained-read sample remains included; no outlier was removed.
Both routes emitted identical byte counts per workload: 2,287 retained, 52,103 metadata, 628 plan, 2,418 discovery, and 5,210 callers.
Identical lengths supplement the full-document and independent semantic comparisons; they do not replace them.
These results do not establish a latency improvement for other workspaces or hardware.

The semantic slowdown remains positive in both alternating-order groups; it is not dismissed as an ordering artifact or noise.
One cohort establishes this association, not independent repeatability or a client-specific cause.
AC16's PASS covers the measurement contract. It does not authorize replacement or establish that a repeatable latency regression has been ruled out.

## Accounted resources fall, but elapsed time is not partitioned

The following results use 30 measured pairs per population, excluding warmups.
CPU is the sum of user and system time reported by `wait4(public PID)`, in milliseconds.
It is not wall-clock stage duration or IDEA CPU. Complete descendant accounting remains unavailable.

| Population | Baseline CPU median [range] | Native CPU median [range] | Paired median difference |
| --- | ---: | ---: | ---: |
| Retained | 4,466.445 [4,361.066–4,823.486] | 1,621.824 [1,578.795–1,685.427] | −2,837.640 |
| Discovery | 4,476.973 [4,331.819–4,715.535] | 1,624.181 [1,601.126–1,652.500] | −2,848.054 |
| Callers | 4,451.287 [4,324.394–4,618.968] | 1,601.115 [1,581.080–1,672.409] | −2,850.501 |

Maximum RSS uses the same OS accounting boundary, in MiB. It is not summed simultaneous process-tree memory.

| Population | Baseline maximum RSS median [range] | Native maximum RSS median [range] |
| --- | ---: | ---: |
| Retained | 592.375 [580.406–613.984] | 154.492 [152.781–164.188] |
| Discovery | 606.352 [594.406–622.609] | 154.531 [152.812–165.875] |
| Callers | 592.078 [545.109–614.500] | 162.438 [161.141–171.797] |

Accounted user CPU, system CPU, and maximum RSS decreased in every semantic pair.
Client preparation also shortened in every pair. These observations do not isolate JVM startup.

| Population / instrumented span | Baseline median ms | Native median ms | Paired median difference ms |
| --- | ---: | ---: | ---: |
| Retained / client preparation | 562.500 | 166.000 | −381.500 |
| Discovery / client preparation | 552.500 | 181.500 | −371.000 |
| Callers / client preparation | 562.500 | 186.500 | −369.500 |
| Retained / host semantic read | 2.626 | 4.280 | +1.597 |
| Discovery / host semantic read | 4.179 | 7.472 | +3.227 |
| Callers / host semantic read | 299.719 | 312.774 | +16.952 |
| Retained / joined transport execution | 4.910 | 7.241 | +2.312 |
| Discovery / joined transport execution | 6.060 | 10.639 | +4.371 |
| Callers / joined transport execution | 301.444 | 314.668 | +17.950 |

Each semantic invocation has one schema-version-16 read receipt and one exact connection-to-read join on the same host and basis.
Host-side preparation has a 3 ms median for both routes in every population.
Client preparation spans its owner through completion in [WorkspacePreparations](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt).
Transport execution uses the joined connection in [HostedTransportObservation](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedTransportObservation.kt).
The ACCEPT timer starts before listener acceptance and can include earlier idle time; it is excluded from attribution.
Semantic, transport, and nested dependency spans overlap. They must not be added together or subtracted from medians to invent missing stages.

Every retained read recorded zero K2 entries, searches, relation attempts, declaration scans, and discovery-page publications.
Discovery recorded one K2 entry, one exact-symbol resolution, one scoped-file search, one declaration scan, and 85 PSI nodes visited.
Callers recorded four K2 entries and one relation attempt, with exactly 139,347,253 dependency bytes hashed and 27,657 completed hashes per invocation.
The native-call count fingerprints matched throughout, with EXACT qualification.
Counter vectors matched in all retained/discovery pairs and 29 caller pairs; caller pair 1 differed only in revalidation-locator disposition.
Retained-entry gauges grew between queries. Balanced route order does not eliminate evolving-host-state effects.

The same host recorded somewhat longer semantic spans for native-route requests without more counted work.
Receipts do not separately time all client admission, hashing, schema initialization, decoding, projection, and output work.
The remaining elapsed time has no assigned cause. Neither resource reductions nor subtraction proves a JVM-startup duration or a general latency improvement.

## Native integrity checks use hardware acceleration

The first candidate made native metadata slower through the installed route, despite fast direct execution.
Three diagnostic pairs recorded native public calls at 435–439 ms and direct native payload calls at 10.9–11.7 ms.
These diagnostics are not a substitute for the paired cohort or an attribution of all remaining time.
Sampling found 277 of 308 dispatcher main-thread samples in scalar SHA-256 schedule and round code.
The dispatcher verifies the full native payload; the client separately verifies its admitted payload.
Both integrity checks remain unchanged.

GraalVM 25's generic `armv8.1-a` target omits SHA2. Its AArch64 implementation does not provide the advertised AMD64 runtime-feature dispatch.
Both macOS-specific argument files now add only `-H:CPUFeatures=SHA2`.
Apple XNU, OpenJDK 25.0.2, and Go 1.25.2 treat SHA2 as part of the Apple-silicon macOS baseline.
The option is absent from portable/Linux arguments; it does not select all features of the build machine.
Pinned sources, commands, target scope, and profile offsets are in `size-waived/measurements/admission-diagnosis/research.md`.

Disassembly finds 56 SHA-256 instructions in each new executable and none in either previous executable.
The first diagnostic matcher missed the `.4s` mnemonic suffix and reported zero even for the new files.
That failed observer is preserved; corrected observations bind all four binary digests in `sha2/*instruction-evidence-corrected.json`.
The final resource matrix and seven actual-native process checks pass for the accelerated bytes.
Installed end-to-end measurements, rather than instruction presence, determine the latency claim.

## Live ownership passed; rollback restoration remains blocked

This live lifecycle evidence uses the earlier query digest `7998c949066841104fb393d76ea3edab39eae8825b47dfd8e5931ade33adb7f0`.
Its management digest is `f5c86b3c1ec27d676c4c19392213e7455d3b34d4e1a8ef3457a04b80a910ae01`.
Their lifecycle source matches the accelerated build; the evidence does not claim a rerun against different executable bytes.
Full records are in `size-waived/lifecycle/live-acceptance-summary.json` and its per-case directories.

The real native worker was PID 33116, start epoch milliseconds 1793364647558, with a product-created invocation record.
Ordinary status admitted it as VERIFIED 1. Independent verification checked all 15,525 manifest files.
The frozen Java-only baseline installer was invoked first, while this native worker remained held.
It exited 9 with `REGISTRY_REJECTED`, `PRIOR_ADMISSION_EXIT_REJECTED`, successful prior recovery, and `ROLLED_BACK`.
The native incarnation, record, candidate payload, manifest, receipt, and file identities remained unchanged.

This safe rejection did not reach the planned current-owner REQUESTS boundary.
The baseline installer instead calls prior private `kast-service disable`, then its incoming prior-state inspector rejects a still-live recorded PID.
It did not restore a Java-only owner. That observation is not a completed mixed-version recovery proof.

Separate ordinary stop and reinstall calls rejected with `REQUESTS_DID_NOT_RETIRE`, after 32.25 and 31.69 seconds respectively.
Current-owner force stop completed REQUESTS retirement; the exact native child was reaped with exit −9 and `harnessCleanup=false`.
The overall command still rejected at `HOST_RESTART_REQUIRED`. Client retirement did not imply host settlement.
The copied IDEA was then stopped through its fixture owner and reaped with exit 143.

Two subsequent public stops rejected `HOST_OBSERVATION_REJECTED`.
GraalVM reported unrelated same-user zombie PID 78850 alive with no command, which the existing conservative host guard rejects.
The task did not signal that process, reap another owner's child, weaken the guard, or remove the product fence.
Baseline installer retry, restored manifest/launcher checks, and restored JVM admission therefore remain NOT RUN.

An independent reset under `/r` retired native stdin waiter PID 34122 and returned REMOVED with exit 0.
The product reaped that exact child with exit −9; an outside-scope sentinel and the other installation identities remained unchanged.
The planned restored-JVM foreign-root control remains unverified because restoration was blocked.
Personal IDEA was untouched. The old `/h` candidate remains preserved behind its product shutdown fence.

## Focused checks and repository gates

| Check | Cases | Result |
| --- | ---: | --- |
| Final CLI JVM suite | 557 | PASS; zero failures/skips |
| Final management JVM suite | 284 | PASS; zero failures/skips |
| Final actual-native process suite | 7 | PASS; zero failures/skips |
| Final JVM Unix-socket suite, `:cli:udsTest` | 4 | PASS; zero failures/skips |
| Hosted queue/connection/retention owners | 21 | PASS |
| Hosted executor/backpressure/read accounting | 19 | PASS |
| Retained-row admission | 10 | PASS |
| Python task-service/transport | 43 | PASS |
| Source-gate consumer tests | 13 | PASS |
| Native artifact substitution | 1 | PASS; altered bytes and script producer both reject |

Final XML and counts are in `sha2/gate-tests/`; earlier supporting suites remain under `size-waived/`.
`verifyJsonContracts`, architecture, verification graph, knowledge, formatting, and `Detekt` checks passed in the product gate.
Generated graph and provenance files changed through their owners.
The GraalVM plugin reserves `nativeTest`; the existing JVM socket suite was renamed `udsTest` with its CI consumers.

The native tests use actual candidate bytes, not the former JVM fixture launcher.
Blocked-output tests require an exact native worker stack and kernel write frame, rather than a delay or a live PID.
They interrupt without draining the consumer and preserve the terminal-event and single-document rules.
Real Unix peers prove transport/admission behavior only, not compiler semantics.
Host queue/executor tests separately prove that cancellation does not retire active analysis before it drains.

The accelerated product gate completed in 2m 27s: 645 actionable tasks, 115 executed and 530 up-to-date.
Native process tests executed again. Unchanged JVM suites keep their prior passing XML; task reuse is not a fresh execution claim.
Its `sourceValidationGate` passed. The clean-source wrapper separately rejected uncommitted changes before tests.
Semantic fixture suites were SKIP because `KAST_SEMANTIC_FIXTURES` was unset.
Three opt-in Codex suites were SKIP: `CodexProtocolQualifierTest`, `ManagedCodexUpstreamTest`, and `NativeCatalogAdmissionTest`.
These omissions supply no semantic or adapter pass. Live IDEA and packaged stdio checks supply only their exercised assertions.
The standalone documentation verification command passed again; its latest complete output is `sha2/documentation-check.log`.
The release-aware `run_product_gate.py` wrapper was not run for the final artifact.
It selects the latest published release version; the recorded command instead runs its `productBuildGate` target with the frozen comparison version `0.0.0`.

## Exact commands and source owners

Commands ran from the candidate checkout unless stated otherwise. Raw logs keep all failed attempts.
These are recorded executions, not commands that can reuse an expired host identity or token.
A repeat needs a new owned fixture, readiness receipt, and issued retained result before collection.

```sh
# Baseline checkout; numeric, immutable comparison product.
mise exec -- ./gradlew -Pversion=0.0.0 assembleKastControlDist generateHostReleaseRecord \
  -PhostedIdeaHome='/Users/amichne/Applications/IntelliJ IDEA.app/Contents' --console=plain

# Recursive descriptor RED and GREEN, using the same selector.
mise exec -- ./gradlew -Pversion=0.0.0 :cli:test \
  --tests '*PublicCliProcessTest.real plan output descriptor*' --console=plain

# Final product/source gate; only byte limits overridden by this local init script.
mise exec -- ./gradlew -Pversion=0.0.0 -I .amp/in/size-waiver.init.gradle \
  -Dorg.gradle.unsafe.isolated-projects=false --no-configuration-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx5g productBuildGate --console=plain

# Separate clean-source admission: rejected before tests because changes are uncommitted.
mise exec -- python3 .github/scripts/ci/source_validation.py

# Final installed correctness, retained/source reads, execution policy, and MCP/RPC.
S=.amp/in/artifacts/native-query/sha2/semantic
uv run --no-project --offline --locked --script "$S/acceptance.py" --check "$S/ready.json"
uv run --no-project --offline --locked --script "$S/acceptance.py" \
  --run "$S/ready.json" --authorize-ready-host --output "$S/run-001"

# New pilot and the frozen paired cohort use the fresh measurement lifetime.
M=.amp/in/artifacts/native-query/sha2/measurements
uv run --no-project --offline --locked --script "$M/collector.py" \
  --pilot "$M/ready-001.json" --authorize-ready-host --output "$M/pilot-001"
uv run --no-project --offline --locked --script "$M/collector.py" \
  --run "$M/cohort-ready-001.json" --authorize-ready-host --output "$M/cohort-001"

# Repository documentation checks, with raw output preserved.
mise exec -- python3 .github/scripts/ci/verify_documentation.py
```

`size-waived/size-waiver.init.gradle` preserves the exact waiver script.
`sha2/metadata-native/commands.txt` records the final matrix command; its run directory keeps every native argv and sandbox setting.
Lifecycle records preserve exact installer and IDEA launch environments, ownership identities, and manifest checks.
Semantic and measurement scripts use locked, dependency-free uv execution; their readiness checks reject incomplete configurations before product execution.

| Invariant | Source owner |
| --- | --- |
| One admitted runtime installation | [InstalledClientContext](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/InstalledClientContext.kt) |
| Shared session/record context | [KastDirectToolSession](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt), [OneShotInvocationRecord](../../cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/OneShotInvocationRecord.kt) |
| Query-only Java removal | [InstallationWorkflow](../../cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt) |
| Exact native/JVM ownership | [LifecycleProcesses](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/LifecycleProcesses.kt) |
| Shared status/retirement admission | [OneShotObservation](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/OneShotObservation.kt), [InstallationRequestRetirement](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationRequestRetirement.kt) |
| Selected lifecycle home | [InstallationLifecycle](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationLifecycle.kt) |
| Recursive output schema | [InstalledServerProjectionDocuments](../../cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt) |
| Public timeout classification | [PublicShellExit](../../cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicShellExit.kt) |
| Sole producer and assembly | [CLI build](../../cli/build.gradle.kts), [root build](../../build.gradle.kts) |
| Payload identity and limits | [VerifyDistributionTasks](../../build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt) |

The evidence archive excludes product binaries, HTML compiler reports, and bytecode caches.
It retains raw commands/results, source identity, execution policies, manifests, resource inventories, failed attempts, and collection code.
An archived source snapshot proves which bytes were tested; it does not turn an uncommitted tree into admitted release source.

Prose assessment uses pinned Vale 3.24.0 and the repository glossary.
The report is ASSESSED, not clean: the grade advisory remains because the required compiler, installation, and measurement terms are kept.
The final findings and input hashes are in `sha2/report-vale-final.json` and `sha2/report-assessment-final.json`.
This historical review lies outside the glossary's replacement scope. Reader comprehension was not independently assessed.
