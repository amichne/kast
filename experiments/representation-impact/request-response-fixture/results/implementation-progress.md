# Implementation progress snapshot

M1, M2, and M3 are **NOT PASSED** at this snapshot.
Segment 1 is **GREEN** for native seed admission, execution ownership, invocation selection, and formal positions.
Segment 2 has two real missing-transfer **RED** results.
No new library models implement the packet operations yet.
Implementation continues after this report.

## Source and evidence identity

The snapshot time is `2026-10-20T01:15:53Z`.
The implementation base is `d04b765c1805722c1aab4a216ecaf66ae302a681`.
The frozen-input commit and current `HEAD` are `543509a7f4d22d6ec850f52b70c898bca478c37d`.
The native commands used later, uncommitted implementation changes.
The commit identifies frozen inputs, but it does not identify every executed implementation byte.
The XML receipts contain no complete immutable identity for that implementation worktree.

The [upstream manifest](../upstream-inputs.sha256) records the supplied packet and audit digests.
The [initial manifest](../initial-freeze.sha256) preserves the original composite, V0 body, and corrected oracle.
The [derived manifest](../derived-sources.sha256) preserves V1–V8 and pure controls.
The [runner manifest](../runner-inputs.sha256) records the standalone harness and evaluator inputs.
The frozen files remain unchanged.

The copied XML receipts have these SHA-256 digests:

| Receipt | SHA-256 |
| --- | --- |
| `native-baseline-initial.xml` | `cac55ac2c2f1714b3adf09a30317204a886ffd546b1eac2759322b26b67843e4` |
| `native-stage1-first-green.xml` | `844fd3254c1dc404be40d4fbd8a3291931c83c53cc8302e6255a215662407594` |
| `native-stage1-position-green.xml` | `1278fcb9d337856da116626e1f9e3d773c593d0bad07bc8231d11b3c00f4f587` |
| `native-selector-red.xml` | `91d7d187ae0e9a531632f43cbf27d5bcc7f4b2db1491d93b2afcffc1be5f8420` |
| `native-selector-green.xml` | `19e4b1283d3ed2910812bab622b60609049a1758efcc155f6044f1b2c42baa0e` |
| `native-source-transfer-red.xml` | `189f8cd42fd4a7e6f474428d8e77343d3553c8cdfc27b35a4a63988d2650c3ac` |
| `native-stage1-final-flow-green.xml` | `1fd57ee0cf1da2a5d33251e1b245dce614a36c8a144964f8f101986e9d6ef5a5` |
| `native-stage1-final-position-green.xml` | `76242ddec6c28efe7af8385c4add54e89d0f3e45bf428e4202cc4f2424018745` |

## Native stages

The native fixtures use real K2 resolution and the production seed and one-hop adapters.
They copy the frozen files into case-owned native projects.
They do not use installed public Tool RPC capture.

| Stage | Expected result | Observed result | Status and evidence |
| --- | --- | --- | --- |
| Initial V0 constructor | Admit the original constructor and produce its first transfer | `UNSUPPORTED_INVOCATION`; transfer not reached | **FAILED**, [baseline table](../evidence/baseline-admission.tsv), [XML](../evidence/native-baseline-initial.xml) |
| Initial S1 nested constructor | Admit the original constructor inside `sequential` | `OWNER_MISMATCH`; transfer not reached | **FAILED**, same baseline receipts |
| Initial S2 qualified invocation | Admit the seed and produce its first transfer | Seed admitted; transfers empty; `UNSUPPORTED_EXPRESSION` | **FAILED**, same baseline receipts |
| Segment 1 original seeds | Admit V0 and S1 with `LOCAL_BINDING`; give S2 its first transfer | Seven assertions passed, including wrong-callable, wrong-owner, and stale-basis controls | **PASSED**, [XML](../evidence/native-stage1-first-green.xml) |
| Segment 1 formal positions | Preserve constructor `destination[1]` and `send.request[0]` | Both positions passed with their parameter names | **PASSED**, [XML](../evidence/native-stage1-position-green.xml) |
| Segment 1 execution owner | Restore the anonymous execution owner; reject the lexical host | Both controls passed | **PASSED**, same position XML |
| Segment 1 receiver | Reach the next call receiver without inventing its result | `RECEIVER` reached `map`; its next read kept `UNMODELED_CALL` | **PASSED**, same position XML |
| Selector before correction | Normalize the full and selector ranges to one invocation | Selector admission returned `ANCHOR_MISMATCH`; wrong-callable result also mismatched | **FAILED**, [RED XML](../evidence/native-selector-red.xml) |
| Selector after correction | Preserve one invocation identity and selected syntax | Full `769:954` and selector `898:954` shared canonical range `769:954` | **PASSED**, [selector table](../evidence/selector-admission.tsv), [GREEN XML](../evidence/native-selector-green.xml) |
| Selector rejection controls | Reject the wrong callable and partial callee | `CALLABLE_MISMATCH` and `UNSUPPORTED_INVOCATION` | **PASSED**, same selector receipts |
| Segment 2 V0 outgoing | Transfer the actual argument into the concrete `fetch.request` formal | Actual `960:973` reached argument 0; expected formal `531:553` did not appear; `UNMODELED_CALL` | **FAILED**, [transfer table](../evidence/source-transfer-red.tsv), [XML](../evidence/native-source-transfer-red.xml) |
| Segment 2 V0 incoming | Project the resolved `wireResponse.link` field | Local read `717:729` appeared; projection `717:734` did not appear; `UNSUPPORTED_EXPRESSION` | **FAILED**, same transfer receipts |

The Segment 2 seed assertions passed before the missing-transfer assertions failed.
Both failures therefore establish product RED at their stated stages.
Neither case reached matched-return composition.

The initial native suite recorded seven assertions with three failures and no errors or skips.
The first Segment 1 suite recorded seven assertions with no failures, errors, or skips.
The position suite recorded six assertions with no failures, errors, or skips.
The selector suite changed from two failures among three assertions to no failures among the same three assertions.
The Segment 2 suite recorded two assertions with two failures and no errors or skips.
These receipts describe separate runs and do not form one aggregate suite result.
The final combined Segment 1 run passed nine flow assertions and six position assertions with no failures, errors, or skips.
Its copied receipts are [flow XML](../evidence/native-stage1-final-flow-green.xml) and [position XML](../evidence/native-stage1-final-position-green.xml).

## Exact procedures

The [baseline command record](../evidence/baseline-commands.tsv) preserves the native baseline procedure and setup failures.
The baseline command was:

```sh
mise exec -- ./gradlew :relation:intellij:nativeFixtureTest --tests '*NativePacketValueFlowTest' -x :relation:intellij:detektNativeTest
```

The [selector command record](../evidence/selector-command.tsv) preserves this identical RED/GREEN procedure:

```sh
mise exec -- ./gradlew --no-daemon --max-workers=1 :relation:intellij:nativeFixtureTest --tests '*NativePacketValueFlowTest.testS2SelectorOnlyRangeSharesTheCanonicalInvocation' --tests '*NativePacketValueFlowTest.testS2PartialCalleeRangeRejects' --tests '*NativePacketValueFlowTest.testS2SelectorWithWrongCallableRejects'
```

The selector GREEN command exited `0` and passed static analysis.
The final Segment 1 command also exited `0`:

```sh
mise exec -- ./gradlew --no-daemon --max-workers=1 :relation:intellij:nativeFixtureTest --tests '*NativePacketValueFlowTest' --tests '*NativePacketValuePositionTest'
```

The [source-transfer command record](../evidence/source-transfer-commands.tsv) preserves this Segment 2 procedure:

```sh
mise exec -- ./gradlew :relation:intellij:nativeFixtureTest --tests '*NativePacketSourceTransferTest' -x :relation:intellij:detektNativeTest
```

That command exited `1` after both native assertions failed.
The explicit exclusion bypassed an unrelated static-analysis crash.
Earlier setup and static-analysis failures did not establish product RED.
Their command records identify the failed stages and mark product assertions `NOT_RUN`.

## Runtime and capture status

The [runtime report](fixture-runtime.md) records this passed fixture procedure:

```sh
./gradlew -p experiments/representation-impact/request-response-fixture assertFixture --console=plain
```

That command used Kotlin `2.4.21`, JDK `25`, and `-Werror`.
It passed exact runtime results for nonempty success, empty success, backend error, headers, request fields, and logger messages.
It also passed the echo, constant-mapper, and independent-call controls.
The typed evaluator rejected missing positive evidence, missing non-null guards, and forbidden argument-field witnesses.
Those evaluator controls used supplied records rather than native packet witnesses.

| Evidence layer | Status | Meaning |
| --- | --- | --- |
| Exact source extraction and manifests | **PASSED** | Five composite blocks and the V0 body match the packet |
| Standalone fixture compilation and runtime | **PASSED** | The authored fixture produces its expected runtime fields |
| Segment 1 native admission and positions | **PASSED** | The stated K2 assertions ran and passed |
| Segment 2 native transfers | **FAILED** | Two necessary transfers remain absent at their asserted stages |
| Full native packet routes | **NOT RUN** | No complete positive row result follows from the stage assertions |
| Installed public request capture | **NOT RUN** | No installed request, schema, executable, grants, or public-result receipt exists here |
| New library-model implementation | **NOT RUN** | The source review supplies design evidence only |
| Final repository delivery gates | **NOT RUN** | This snapshot is not a final delivery report |

## Oracle coverage and milestones

A passed seed or first transfer does not satisfy its later destination row.
A forbidden-witness assertion needs emitted semantic witnesses from the stated source policy and implementation.
An unmatched requested site remains `RELATIONSHIP_UNPROVEN`.
It does not prove absence.

| Oracle rows | Positive capability | Forbidden emitted witnesses |
| --- | --- | --- |
| V0 outgoing and incoming final destinations | **NOT RUN**; Segment 2 failed before those destinations | **NOT RUN** |
| V1, V2, V3, V4, V5, V6, V7, V8 final destinations | **NOT RUN** | **NOT RUN** |
| C1.1, C1.2, C1.3, C1.4, C1.5 | **NOT RUN** | Not a forbidden row |
| C1.N1, C1.N2 | Not a positive row | **NOT RUN** |
| C2.1, C2.2, C2.3, C2.4, C2.5 | **NOT RUN** | Not a forbidden row |
| C2.6, C2.7, C2.8, C2.9, C2.10 | **NOT RUN** | Not a forbidden row |
| C2.N1, C2.N2 | Not a positive row | **NOT RUN** |
| C3.1, C3.2, C3.3 | **NOT RUN** | Not a forbidden row |
| C3.N1 | Not a positive row | **NOT RUN** |
| T1.COMBINED and T1.PRODUCTION | **NOT RUN** | **NOT RUN** for the production-route prohibition |

| Milestone | Status | Remaining acceptance |
| --- | --- | --- |
| M1 | **NOT PASSED** | Complete V0–V3 and V8 paths, invocation isolation, and forbidden-witness assertions |
| M2 | **NOT PASSED** | Complete corrected composite success rows, including C2.9, source-policy routes, and guarded empty-list control |
| M3 | **NOT PASSED** | Complete every corrected positive row, reviewed external interpretations, error propagation, logging, and function-value origins |

No query-completion claim follows from this snapshot.
The next implementation work starts at the two established Segment 2 failures.

## Prose assessment

The pinned assessment reports `ASSESSED` and keeps a grade advisory at line 1.
Exact diagnostics, stage names, and evidence limits keep their technical terms.
Reader comprehension remains `NOT_ASSESSED`.
