# Native flow closure: independent oracle review

The initial synthetic oracles need stronger assertions before CP1 can pass.
This separate review examines the revision-3 specification and the initial `NativeFlowClosureTest` draft.
It does not establish a compiler result or an enterprise reproduction.

The source base is `b43a9ebc720d4aeaa5267a7d9c6cddb4f1d66ad1`.
The initial fixture digest is `d9a84806951a1838142d54482c419f50136d084ef0a5a857db2c2b41a66d7635`.
The review uses no enterprise source or private run identity.
Every reported case keeps enterprise status `REQUIRED / UNMAPPED / PENDING` until an authorized replay supplies its mapping.

## Sound starting oracles

- Named and anonymous callers differ by the synchronous source wrapper.
- Concrete and interface receivers use distinct source declarations.
- Outer `origin` and inner `convert` calls supply distinct producer anchors.
- The constant-call pair uses the upstream input and call result as separate seeds.
- Regular escapes, raw backslashes, and interpolated dollar literals use distinct lexical forms.
- Two reads use one fixture basis. The comparison includes frames, field paths, conditions, and work counts.

These source-derived expectations remain planned until their named assertions run.
The identity conversion isolates execution ownership. It does not reproduce an unknown enterprise transformation.

## Necessary oracle repairs

| Gap in the initial draft | Necessary assertion or disposition |
| --- | --- |
| A rejected seed aborts the first run | Capture both seed results before an assertion stops the case. Compare the finite rejection and its evidence. |
| Positive arrival alone accepts unresolved reads | Assert the fixed supported prefix and its qualified terminal. Reject unexpected resource caps and unexplained obligations. |
| Negative arrival alone accepts unsupported traversal | Prove callback entry or discarded input at the responsible owner. Assert the expected terminal or exact unresolved frontier. |
| Generic helper coverage starts before the helper | Add the helper-result seed. Prove RETURN and examine the first downstream failure separately. |
| I3 uses separate known implementations | Add one call with both admissible implementation alternatives. Forbid provenance from the discarded alternative. Keep each qualification. |
| Repeated entries only assert arrival | Assert two exact contribution occurrences and separate contribution counts from requested-site counts. |
| `String?` content only asserts arrival | Assert non-null contribution and the explained constant null construction, with their distinct conditions. |
| Getter coverage lacks the result seed | Add the getter-result positive. Keep the discarded-input negative. |
| Conversion coverage is absent | Add source conversion, discarded-input/result pairs, and an exact unresolved conversion frontier. Preserve independently supported synthesis. |
| Traversal ignores reviewed transfers | Traverse or explicitly account for reviewed alternatives. Do not silently drop acquired evidence. |
| Projection compares traversal order | Compare routes without enumeration order. Keep order within each witness, duplicate counts, basis, and proof differences. |
| Header and scope cases are absent | Freeze HRT-1, HBP-1, SCP-1, and SCP-2 separately. Keep FPD-1 pending until its failed stage is known. |

The current draft covers useful candidates, but it does not satisfy the entire control matrix.
Existing tests can supply unchanged obligations after their current assertions run.
Historical test source alone supplies no executed pass.

## D1 decision limits

`ModelValuePosition` exposes `Argument` and `Result`.
`ReviewedIterableMapCallable` admits the exact pinned `Iterable.map` callback contract.
Neither contract supplies a generic Pair/Map construction rule.
`QueryImpactReviewedConservationTest` also forbids a reviewed transfer from silently discharging `UNMODELED_CALL`.

The native route must still establish the actual selected `to` and `mapOf` declarations, overloads, and dependency identities.
A missing registry entry cannot erase independent source or compiler proof.
If the first unsupported join needs a new construction mechanism, CP0 selects the approved `QUALIFIED_LIBRARY_BOUNDARY` target.
That target needs the original token's supported prefix and the exact library frontier.
The header argument remains unproved from that seed.
HBP-1 cannot replace HRT-1.

No Pair/Map rule means X-33 through X-35 are inapplicable only to new-rule obligations.
Their disposition cannot remove an original reported case or waive enterprise replay.
A new D1 trigger after CP0 blocks that join until the owner supplies a decision.

## Initial review result

Independent source review: **complete, with unresolved oracle gaps**.
Compiler/native assertions: **NOT RUN by this reviewer**.
Enterprise replay: **NOT RUN by this reviewer**.
CP0 route packet: **PENDING exact native callable evidence**.
CP1: **OPEN** until the repaired oracles and B0/B1 evidence support its predicates.

## Second source review

This pass examines local changes after `f5f14e4fcbef0c70707692ce40b6d851539d6167`.
The fixture digest is `2242b5afeae6101d20c8b69497764996ec86f856c03be7079a2e093ac291cd53`.
The reviewer ran no Gradle command and no native assertion during this pass.
Later source changes need a new examination of the affected findings.

The executor records `testHeaderSelectedLibraryOverloads` as **PASSED** on `B0-frozen`.
The selected declarations are `kotlin.to` and the single-pair `kotlin.collections.mapOf(pair)` overload.
The recorded Kotlin standard library SHA-256 is `0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e`.
The source assertions distinguish the non-vararg overload.
This reviewer did not repeat that native run or hash the archive.

CP0 selects the target `QUALIFIED_LIBRARY_BOUNDARY`.
The milestone remains `OPEN`.
This decision supplies no enterprise closure claim and no complete header-route claim.

### Facts that the new types preserve

`ValueStringContent` keeps template, entry, and embedded-result ranges.
Its transfer needs equal execution owners and call stacks, empty selected field paths, and one template result.
`Direct` cannot admit `STRING_CONTENT`.
The `String?` variant needs `StringContentNonNull` from the selected source and exact entry.
The canonical and protocol projections name that condition and the constant null alternative.
These source properties need pure rejection controls and independently expected encoded shapes before acceptance.

The callback changes keep exact frame selection, actual/formal positions, body identity, and fresh supplier binding comparisons.
`ValueExecutionOwner.represents` admits an anonymous body only through an existing execution context.
The selected scan receives the active frames instead of an unqualified summary.
No inspected change deletes the upstream frame-adjacency comparison.
These source facts support the design, but they do not prove the complete I1–I8 control population.

### Remaining concrete findings

| Finding | Scope and necessary evidence |
| --- | --- |
| Null-only construction loses its typed result | `NativeStringContent.read` discards `NullConstant` as `Unit`. Keep an explained null construction without a source-content edge. |
| Conversion refusal loses the operation identity | `UnresolvedConversion` maps to `UNSUPPORTED_EXPRESSION` at the entry. Preserve the actual conversion frontier and acquired callable evidence. |
| Source object conversion has no admitted route | The dispatcher accepts String types and refuses other types. X-21, X-26, and X-27 remain open until their distinct conversion oracles run. |
| Repeated entries lack an occurrence assertion | Arrival alone accepts one contribution. Assert both entry identities and one requested site. |
| `String?` content lacks a branch assertion | Assert the non-null condition, exact entry, null alternative, and no original-content edge on the null branch. |
| I3 still lacks both admissible alternatives | Separate `Good` and `Other` inputs do not prove one call with both alternatives. Keep qualification on the forwarding alternative only. |
| The header frontier remains loosely asserted | The test accepts an external refusal anywhere in `buildHeaders`. Assert the selected `to` occurrence and its exact failed position. |
| The forbidden header endpoint uses text matching | Replace the signature-text search with the exact compiler-selected `fetch` declaration and argument occurrence. |
| HBP-1 proves inventory but not exact argument arrival | Keep its exhausted empty inventory. Add the selected `fetch` argument identity and original downstream seed adjacency. |
| Pure constructor misuse controls remain absent | Reject wrong entry, source, owner, frame, basis, and condition combinations. Preserve the valid derived alternative. |
| Effective read grants differ from printed grants | The walk uses a larger byte allowance than `fixture.budget`. Record seed and read grants separately. Keep enterprise grants separate. |

The current harness captures two seed results before its admission assertion.
The connected cases now reject unresolved obligations.
The helper-result seed and header controls also exist.
Those changes resolve parts of the initial review, subject to their actual executions.
Traversal still ignores reviewed targets and compares whole traversal order.
Getter-result, conversion, scope, and both-alternative controls remain absent from this fixture snapshot.

### Callback proof risks

The same selected-supplier scan now serves direct and implementation calls.
Run named, anonymous, default-supplier, stored-supplier, returned-factory, and wrong-actual controls after this shared-owner change.
The explicit supplier path carries the existing invocation execution owner into local sites and argument construction.
The callback factory path still creates a declaration-owned invocation.
An anonymous caller with a factory-produced callback needs a distinct control before broader composition claims.

`represents` compares anonymous body identity, while frames and leases carry invocation and basis evidence.
Do not use that predicate alone as proof of call occurrence or freshness.
Negative controls must keep sibling bodies, supplier calls, field paths, conditions, implementation alternatives, and semantic bases distinct.
The reported NPA, CAC, HRC, and FPD families keep their original enterprise obligations.

Second review: **complete, with acceptance criteria OPEN**.
Final compiler assertions, pure controls, encoded shapes, and enterprise replay: **NOT RUN by this reviewer**.

## Third source and evidence review

This pass examines the uncommitted fixture split, path classification, and hosted query witness after `f5f14e4fcbef0c70707692ce40b6d851539d6167`.
The reviewed test digest is `feb380a815bdd368c5c8e004b55d4af3dee2ed694e45f61ee95ffe48be558875`.
The reviewed fixture digest is `7da8718b0ee177383ffd7712e1ecf0560878dad41e880d04e94f23a7b640c115`.
Concurrent changes after these snapshots need their own examination.
This reviewer ran no Gradle command and changed only this review document.

### Changes since the second pass

- The repeated-entry control now asserts two distinct entry ranges and one template-result identity.
- The `String?` control now asserts `NULLABLE_STRING`, the constant null alternative, and a `StringContentNonNull` condition.
- A separate getter-result seed now needs one content edge and an argument arrival. Its upstream-input negative remains separate.
- The I3 control supplies two conditional implementations at one call. It permits callback entry only through `Pass` and keeps `Drop` in dispatch evidence.
- HRT-1 now compares the refusal range with the exact `to` expression. Its forbidden endpoint uses the compiler identity of `RemoteApi.fetch`.
- The semantic manifest now declares 28 closure methods and the new hosted anonymous method. Actual accountant success remains a separate obligation.

These changes strengthen the oracles without a downgrade.
Their source assertions do not establish an executed pass.

### Remaining semantic and protocol gaps

`NativeStringContent` still discards `NullConstant` as `Unit`.
The `NULLABLE_STRING` enum explains a potential null alternative, but no null-only fixture proves a kept constant-construction result.
The `String?` assertion also lacks exact condition-origin and entry comparisons.
X-24 remains open until that result and its accounting survive retention and encoding without an original-content edge.

Object conversion still lacks an admitted source `toString` route and paired discarded-input/result controls.
The generic refusal still names the template entry instead of the actual conversion operation and its acquired callable evidence.
X-21, X-26, X-27, and the conditional X-36 obligation remain unresolved.
An exact unsupported conversion needs its established prefix and finite reason.
No fallback for Pair/Map construction waives these conversion obligations.

Path classification now handles every compiler, reviewed-library, representation-model, and `ModeledBoundary` alternative explicitly.
Both domain and protocol folds preserve derivation and stop at the first unproved correspondence.
The serializer rejects an encoded classification that disagrees with the retained steps.
The current new projection assertion covers a `ModeledBoundary` step and the emitted classification field.
It does not prove derived-then-exact persistence, every modeled alternative, matching fold results, or rejection of a forged encoded classification.
Those obligations need focused pure cases with independently expected shapes.

HBP-1 still asserts one `UNMODELED_CALL` obligation and an exhausted empty implementation inventory.
It does not assert the exact reached `fetch` argument, its position, or its adjacency from the fixed downstream seed.
Keep the inventory assertion and add those identities.
That downstream surrogate remains distinct from original-token HRT-1 and private Retrofit replay.

### Hosted composition witness

The new anonymous workload uses the real production query and retained wire evidence.
Its expected producer and observer currently use default declaration ownership.
Those sites execute inside the `invokeNow` callback, so declaration ownership is not the correct anonymous oracle.
The executor will keep exact anchors and declarations and get the expected owner from qualified callback facts.
That correction preserves oracle strength. It cannot substitute an unqualified owner wildcard.
The reviewer reports this source mismatch before the hosted assertions run.

The existing capture supplies no requested sites and requests value paths.
It therefore supplies no integrated requested-site accounting or compact FINDINGS witness.
The later-page guard compares flow reads, seed reads, and compiler counters before and after each retained page.
The first `READ_RESULT` currently guards flow reads only.
The proof counter also omits `revalidate` and `revalidateSite` entries.
Extend the same acquisition evidence to those calls before a claim that every retained read performs no semantic acquisition.

### Baseline and corpus comparison

This reviewer parsed the preserved XML without a new test execution.
`b0-frozen` declares 26 methods: 11 pass, 15 fail, zero error, and zero skip.
`b1` declares the same 26 names: 12 pass, 14 fail, zero error, and zero skip.
The changed result is the diagnostic for the callback without invocation. It supplies no admission improvement claim.

The current fixture adds disjoint controls and stronger assertions after the historical baseline.
Those changes alter fixture identity, so the two added controls cannot silently enter the old comparison population.
The executor plans the same final fixture and semantic oracles against unchanged production.
That comparison remains pending until its source, compiler, dependency, request, grants, and method identities are recorded.
Keep the historical B0/B1 artifacts and name the new comparison separately.
Keep actual basis differences. Do not erase them to produce equality.

The existing text capture prints ordinary frame and transfer-evidence objects through `toString`.
Those classes lack a complete textual projection, so some proof fields appear only as object references.
Within-run object equality compares real evidence, but those text records alone cannot support a complete cross-run semantic comparison.
Keep canonical evidence or qualify the comparison to its available fields and method outcomes.
A limited comparison does not satisfy the full semantic-corpus criterion.
Compare alternative witnesses without traversal order while preserving adjacency, duplicate counts, conditions, implementations, and basis.

The original incident families still need their reported failure discriminators.
A passing helper-result baseline does not reproduce a post-RETURN stop.
A generic-factory callback-entry refusal does not reproduce FPD-1's distinct unsupported-expression failure.
The concrete NPA candidate's baseline pass does not account for both reported seed rejections.
SCP-1 and SCP-2 still need their frozen domain comparisons.
Only the owner can supply a D5 waiver for a reported case without reproduction.

Third review: **complete, with milestone OPEN and target QUALIFIED_LIBRARY_BOUNDARY**.
Current final test execution, source gates, accountant results, and enterprise replay remain outside this review's executed evidence.

## Fourth source review: null construction and implicit conversion

This pass examines the new `ValueStringConstruction`, `StringConversionResult`, implicit member selection, and matched-return reuse.
The reviewed conversion digest is `00290fbba8735380830508b3f6a1299580b8d0d0219d91999596d5ed418feaaa`.
The reviewed return-owner digest is `b985afafb1a73741661daec57acb6e51dbdd351b2375322e12b3d57f8a612ad7`.
The reviewed null-record digest is `c1b86d280f9d74e62738e319f49ef98a9ba6e0b20553463171917a23977f2641`.
This reviewer ran no Gradle command.

### New preserved facts

The null-only branch now emits a typed construction record without a source-content transfer.
The record keeps its source, template, entry, and input ranges.
`ValueFlowStep` admits records against its exact source and original result/byte grants.
Supported-end projections now include these records in both value paths and findings.
The executor reports that the native null control passed.
That result supersedes the earlier source finding that the adapter discarded every null construction.

Implicit conversion now selects a member with no parameters through the receiver type's member scope.
The selected member must be `Any.toString` or an override of that member.
Extension functions cannot enter this selection.
A final class or final member supplies the direct-dispatch premise.
The source body returns a non-null String before content contribution starts.
`StringConversionResult` keeps that returned value distinct from the object expression at the same source range.

Matched-return reuse still reacquires the exact caller expression, selected callable, execution owner, and invocation.
It reconstructs the frame and compares it with the retained frame before the stack exit.
Reusing an admitted direct frame after those comparisons preserves stronger earlier dispatch evidence.
The source change does not authorize a new direct frame from a method name alone.

### Concrete unsafe gaps at this snapshot

`ValueStringConstruction.constantNullFromCompiler` does not constrain the template range to the selected execution owner.
A contribution edge receives that constraint through its target `ValueSite`, but a constant record has no target site.
The null constructor must reject an enclosing template outside `source.executionOwner.range`.
A pure negative can supply nested parsed ranges that contain the source but exceed its execution scope.

The reviewed `SOURCE_CALL_RETURN` role admission still excludes `StringConversionResult`.
The source-transfer proof accepts that role, so the separate role gate rejects the intended result.
Both owners must admit the same legal result while preserving invocation, owner, stack, field, and condition checks.

The inspected `/tmp/kast-native-closure-object-return.log` ended at compilation.
An inferred `Refinement.Refined` made its `Rejected` branch invalid.
That invocation ran no object-return assertion and supplies no product RED or GREEN result.
The executor's earlier object run reached a real member FIELD_READ and RETURN, then stopped at `UNMODELED_CALL`.
This reviewer treats the new return behavior as pending until its corrected native assertion runs.

### Necessary conversion and record controls

| Control | Decisive oracle |
| --- | --- |
| Member versus extension | Assert the exact selected override and its body/field route. Forbid the extension declaration in the witness. |
| Constant conversion, upstream seed | A constant member result receives no provenance from its discarded input. |
| Constant conversion, result seed | The separately anchored member-call result contributes its own String content. |
| Two conversion occurrences | Each member RETURN reaches its own invocation and template entry. No exchanged result is admitted. |
| Unproven virtual dispatch | An open member gains no unqualified direct frame. Keep its finite unresolved result or existing qualified proof. |
| Inherited or synthesized member | Preserve independent compiler/source support. Synthesis or a missing reviewed rule alone proves no opacity. |
| Conversion readmission | A different callable, occurrence, owner, basis, frame, or result role cannot consume the retained conversion proof. |
| Null record shape and scope | Reject an outside-owner template, wrong source, invalid nested ranges, and foreign basis. Keep the valid constant record. |
| Null record capacity | Original output and byte grants account for the record. A rejected append keeps the original step unchanged. |
| Encoded null evidence | Assert exact record kind and ranges in findings and value paths. A round trip alone supplies no independent shape oracle. |

The current null assertion counts records through the baseline-compatible `getStringConstructions` method.
It does not assert their source identity, exact ranges, or encoded representation.
The new object workload includes a same-name extension, but arrival alone does not prove the exact member witness.
These stronger assertions complete the named obligations without a new collection model or a change to D1.

Fourth review: **complete, with the matched-return result and stated controls pending**.
The milestone remains `OPEN`; the selected target remains `QUALIFIED_LIBRARY_BOUNDARY`.
No enterprise replay or complete original-token-to-header claim follows from this source pass.

### Fourth-pass correction after the role control

The executor restored the direct-dispatch guard in `NativeValueSourceReturns.matched`.
The object control then passed with `StringConversionResult` admitted by `SOURCE_CALL_RETURN`.
The missing role case was the demonstrated cause. The direct-frame reuse hypothesis is withdrawn.
The reviewer examined the restored source and `/tmp/kast-native-closure-object-role.log`, which reports `BUILD SUCCESSFUL`.
The current XML contains one executed object control, one pass, zero failure, zero error, and zero skip.
The null constructor now rejects a template outside the selected execution owner.
The remaining paired-seed, identity, occurrence, frontier, and encoded-negative controls remain necessary before their closure claims.

## Final focused source review

This pass examines the new construction/conversion projection tests and `NativeStringContentQueryTest`.
The hosted test digest is `ad3ec815b0d9e352678ae2858f7bfcb3045bac3954bff5aa5f576f03f196b5b1`.
The construction-test digest is `e32a92b1b9294eddb831d3d600863fa36d2c5c158caf87f21fbddb6fd839cab3`.
This reviewer ran no Gradle command and changed only this document.

The new pure construction tests assert literal encoded keys, record kind, and template range.
They also assert no contribution, unchanged result count, scope/source rejection, byte rejection, and preserved conversion identities for every declared origin.
The derived-path tests assert persistence through a later exact argument edge and rejection of forged, unknown, or missing classifications.
These assertions resolve the earlier missing shape and classification controls, subject to their actual executions.

The new hosted repeated-entry case supplies an exact requested argument and its declaration inventory.
It asserts two contribution occurrences, one result identity, requested-site arrival, and derived compact findings.
The null case compares kept construction records across retained paths and native-read witnesses without a content edge.
The capture now guards the first retained read and later reads with flow, proof, and compiler counters.
The proof counter now includes declaration and site readmission.

Two narrow issues arose during this pass, and the executor changed both source owners:

| Issue | Current source disposition |
| --- | --- |
| Conversion failure admission accepted more signature shapes than the domain constructor | `ImpactStringConversionValidation.kt:8` now needs a function with empty value/context parameters before identity comparison. A negative control must exercise rejection. |
| The hosted conversion case claimed encoding but inspected only typed documents | `NativeStringContentQueryTest.kt:63` now asserts the exact member identity, encodes/decodes retained paths, and needs the literal `STRING_CONVERSION_RESULT` discriminator. Its rerun remains necessary. |

The native declaration selector still excludes extensions through member-scope and `Any.toString` ancestry.
The existing body selector supplies final-callable or final-dispatch proof before direct entry.
An open conversion keeps its unproved dispatch, and an external conversion keeps its selected identity and finite cause.
No further incorrect authority claim or demonstrated identity loss appears in this reviewed slice.

Source references are:

- `query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/ImpactStringConstructionProjectionTest.kt:40`.
- `query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/ImpactStringContentProjectionTest.kt:33`.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactStringConstructionValidation.kt:3`.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactStringConversionValidation.kt:8`.
- `runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeStringContentQueryTest.kt:27`.

The executor reports a passed 37-case native cohort, passed selected conversions, a passed anonymous production query, and four passed construction tests.
The new hosted cohort initially reached `MISSING_DECLARATION` for two cases.
That admission failure supplies no semantic reproduction.
The fixture now supplies the exact requested-site declarations without a change to the expected site or producer.
Its corrected rerun, complete current gates, and exact execution accountant remain pending in this review.

A green code delivery remains distinct from incident closure.
The complete original incident mapping, matched enterprise replay, and unresolved reported-case reproduction criteria still control the milestone state.
This review grants no D5 waiver and no complete header-route claim.

## Existing computed-getter control

The `computedRead` correction preserves the original five inputs and the original constant-getter source.
`ComputedValue.value` still returns literal `0` and does not read `stored`.
The changed assertion proves an exact getter invocation and source-call entry, then no outgoing transfer or unresolved obligation.
It also needs `SupportedDomainExhausted` at the admitted getter receiver.
The shared `next` helper needs exactly one transfer of each requested kind.

This is an intended stronger oracle for the new getter capability, not an oracle downgrade.
A receiver-to-getter entry does not assert a stored-field alias or dependence of the constant result on the receiver.
The final empty transfer set forbids that fabricated dependence.
The four other unsupported-read assertions remain unchanged.

The source route resolves the actual property, explicit dispatch receiver, final property or owner, and authored getter body.
It does not bypass the getter through the property's name.
Source references are `NativeConstructorPropertyStorageTest.kt:72`, `NativeNamedGetter.kt:22`, and `NativeValueGetterReceiver.kt:13` within `relation/intellij`.
The assertion needs a new execution before a pass claim.
The earlier broad run used the original assertion and keeps its actual failure status.

HRT-1 and HBP-1 now use the same two-run comparison helper.
HRT-1 needs one `EXTERNAL_CALL` obligation at the exact `to` expression and forbids the selected `fetch` argument.
HBP-1 now needs the exact `fetch` callable, argument position zero, and the original downstream producer range.
Its exhausted empty implementation inventory remains separate from the original-token route.
These changes strengthen the frozen identities and do not waive enterprise replay.

## Schema size and compatibility correction

The schema correction preserves canonical owners and the existing byte limits.
`CanonicalSchemaReferences` now names the actual `ImpactCallbackInputFailureDocument` and `ImpactFlowDomainDocument` serializer descriptors.
`InstalledServerProjectionDocuments` supplies both definitions from those same `KSerializer` instances through the existing local-reference mechanism.
No field, constraint, failure variant, or evidence qualifier was removed.

`ImplementationDispatchSchemaReuseTest` adds both definitions to its independent shape comparisons.
The independent generator still uses only its original recursion anchors, not the installed factoring map.
The local-reference and malformed-proof assertions remain in place.
Thus the expected-schema change preserves its independent oracle.

The reviewer parsed the current generated Pi result schema without a Gradle invocation.
It contains five references to `impactImplementationDispatch`, eleven to `impactCallbackInputFailure`, and six to `impactFlowDomain`.
Both new definitions exist. Every reference in the three regenerated Pi schemas resolves locally.
The fifth dispatch reference belongs to the new canonical callback-failure position.
The count change from four to five therefore reflects the actual factored schema, not a relaxed inequality.

The executor reports that repeated inline schemas exceeded the catalog cap, and failure-only factoring remained too large.
Reusing the six identical flow-domain shapes preserves their canonical contract.
The packaged limit remains 1,048,576 bytes.
The installed-schema assertion still reserves 4,096 bytes within that limit.
A smaller individual result schema does not itself prove that the complete catalog fits.
The final packaged and installed byte assertions still need their executed results.

The finite failure oracle now explicitly includes `CLASSIFICATION_MISMATCH` and its serialized example.
The compact-finding key oracle now includes the authored `classification` field.
The public catalog assertions now expect contract 18, matching the authored schema and `PUBLIC_TOOL_CONTRACT_VERSION`.
Other schema/version owners remain independent.
The Pi exporter reads the canonical `KSerializer` instances and installed semantic-schema owner. It does not hand-edit the three generated shapes.
Their final parity assertion remains necessary.

Source review found no oracle downgrade or loss of schema authority in this correction.
This reviewer changed only this document and ran no Gradle command.

## Storage-capacity oracle correction

The revised `QueryInvocationStorageWorkTest` preserves the original capacity and rollback oracle.
The fixture still contains two observations and uses the original policy and fixture budgets.
Each observation adds one reference to the already shared empty construction list.
The graph charges 8 bytes per reference with its unchanged factor of eight, so those references add 128 semantic bytes.

The reviewer separately counted the literal encoded fields.
The exact classification adds 63 UTF-8 bytes. The empty construction field adds 25 bytes.
The resulting expected components are 354,424 semantic bytes, 2,816 preview bytes, and 256 row-reference bytes.
Their sum is 357,496 bytes. The expectations do not read their answer from production measurement.

The test keeps the original 357,280-byte grant and expects `RETAINED_BYTES_LIMIT` with empty accounting and no admitted row shape.
It separately rejects 357,495 bytes, then accepts exactly 357,496 bytes on the same instance.
The final estimate must equal the fresh accepted estimate, so the retry still examines rollback of graph accounting.
This is an explicit capacity control, not an increased grant for a positive semantic workload.
The source review found no oracle downgrade.

Source owners are `QueryInvocationStorageWorkTest.kt`, `QueryImpactRelationStorage.kt`, and `QueryImpactRetainedGraph.kt` within `query`.
The focused execution remains pending in this review.
This reviewer ran no Gradle command and changed only this document.

## Final focused execution disposition

The current XML at `build/reports/native-flow-closure/final-focused/` records the corrected hosted cohort and signature controls.
The hosted cohort passes 3/3. The signature/range cohort passes 3/3. Both have zero failures, errors, and skips.
The native closure cohort passes 37/37. The full callback-return file passes 30/30.
These results resolve the two narrow issues from the last source pass.
The complete gate and execution accountant still need their own current results.
No enterprise or complete-header claim follows from these synthetic executions.

The corrected getter and two-run header selector then passed 3/3, with zero failures, errors, and skips.
The command log is `/tmp/kast-native-closure-corpus-repair.log`.
Its XML remains in `build/reports/native-flow-closure/corpus-repair/`.
This execution resolves the focused pending result. The complete source gate remains separate.

The final catalog encodes 1,021,086 bytes under the unchanged 1,048,576-byte cap.
The installed projection passes its unchanged 4096-byte headroom assertion.
The catalog/serializer cohort passes 16/16. The final RPC/parity cohort passes 26/26.
The host-observation cohort passes 332/332. These cohorts have zero failures, errors, and skips.
The Node adapter process keeps its two existing platform skips. Its current count remains separate from the JUnit wrapper.

## Diagnostic-constructor review

`ValueCallbackInputFailure.ImplementationIncomplete` now has a private constructor and a finite admission result.
Its factory accepts incomplete coverage and rejects exhausted coverage with `EXHAUSTED_DISPATCH`.
The native caller consumes that one decision. It does not repeat the coverage examination.
The existing matched basis and original inventory remain intact. Private copy visibility prevents an unchecked replacement.
The six `ValueImplementationObligationOracleTest` assertions pass, including both coverage states and a foreign basis.
This refinement preserves the existing production decision. It supplies no new incident reproduction claim.

The focused storage cohort then passed 4/4 with zero failures, errors, and skips.
The command log is `/tmp/kast-native-closure-storage-consumer.log`.

## Native termination review

The unchanged supplier distinctness assertion fails with 28 causes and 23 termination labels.
Five supplier-stage causes collapse into the older argument-mapping label.
The workspace owner now declares five distinct bounded termination codes. The relation adapter maps each matching cause explicitly.
The original distinctness assertion remains. A new assertion also preserves each of the five exact code names.
The focused supplier assertion passes 1/1. The workspace suite passes 316/316, with zero failures, errors, and skips.
No source payload or new semantic effect enters this diagnostic change.
