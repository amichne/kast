# Kotlin 2.3.20 library-model review

This review records source, bytecode, and compiler evidence through October 20, 2026.
`ReviewedValueModel.FirstOrNull` now implements the exact reviewed `List<T>.firstOrNull()` operation.
`ValueModelApplication.CollectionSelection` carries its conditional selection separately from supplied representation rules.
`ReviewedValueModel.Let` supplies exact receiver admission and a detached callback contract.
Native callback transport and the other reviewed applications remain incomplete.
M2 and M3 remain incomplete.

## Artifact identity

The native standard-library jar matches the cached Maven artifact byte-for-byte.
The [native identity receipt](../evidence/native-stdlib-identity.tsv) records its location and manifest metadata.
Its implementation version is `2.3.20-release-208` under IDEA build `262.9437.185`.
The standalone fixture runtime uses Kotlin `2.4.21`.
That runtime result does not qualify the native `2.3.20` library models.

| Artifact | Maven coordinates | SHA-256 |
| --- | --- | --- |
| Native and cached binary | `org.jetbrains.kotlin:kotlin-stdlib:2.3.20` | `0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e` |
| Primary source jar | `org.jetbrains.kotlin:kotlin-stdlib:2.3.20:sources` | `5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661` |

The primary artifacts are the [binary jar](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar) and [source jar](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20-sources.jar).
The source jar resides at `/tmp/kast-kotlin-stdlib-2.3.20-sources.jar` for this review.
Source entries and line numbers in this report refer to those exact bytes.

The current catalog uses `id = kotlin-stdlib-value-flow` and `version = 1`.
Its exact rules are `collections.list.firstOrNull.select-zero` and `scope.let.invoke-block-value`.
Its review provenance is `source-sha256:5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661`.

`ModelVersion` names the model revision, not the library version.
The provenance identifier names the reviewed source bytes.
A matching review identifier alone supplies no live binary proof.
Admission still needs the current compiler declaration, exact overload, semantic basis, and matching binary identity.
The fixture logger needs separate source provenance from its frozen [source manifest](../upstream-inputs.sha256).

## Reviewed source entries

| Exact source entry and lines | Callable or declaration | Permitted meaning |
| --- | --- | --- |
| `commonMain/kotlin/collections/Collections.kt:71-98` | `emptyList<T>()`, `listOf<T>(vararg elements: T)`, `listOf<T>(element: T)` | An empty list supplies no element. Each admitted element occupies its corresponding result element position. |
| `jvmMain/kotlin/collections/CollectionsJVM.kt:17-21` | `listOf<T>(element: T)` | The single element enters `Collections.singletonList`. |
| `commonMain/generated/_Collections.kt:199-224` | `Iterable<T>.first()`, `List<T>.first()` | The normal result selects the first element. An empty receiver throws. |
| `commonMain/generated/_Collections.kt:264-290` | `Iterable<T>.firstOrNull()`, `List<T>.firstOrNull()` | A nonempty receiver supplies its first element. An empty receiver returns null without element provenance. |
| `commonMain/kotlin/util/Standard.kt:112-119` | `T.let(block: (T) -> R)` | The receiver enters the supplied block's argument. The block's normal result supplies the call result. |
| `commonMain/kotlin/util/Tuples.kt:20-44` | `Pair<A, B>`, `A.to(that: B)` | The receiver enters `Pair.first`. Argument zero enters `Pair.second`. |
| `commonMain/kotlin/collections/Maps.kt:42-66` | `mapOf<K, V>(vararg pairs: Pair<K, V>)`, `mapOf<K, V>(pair: Pair<K, V>)` | Pair keys and values remain separate. A later duplicate key replaces the earlier value. |
| `commonMain/kotlin/collections/Maps.kt:620-637` | `Array<out Pair<K, V>>.toMap` overloads | Empty input yields no entry. Other inputs pass pairs to map storage. |
| `jvmMain/kotlin/collections/MapsJVM.kt:17-26` | `mapOf<K, V>(pair: Pair<K, V>)` | The single pair supplies the key and value of `Collections.singletonMap`. |
| `commonMain/kotlin/Library.kt:10-19`, `jvmMain/kotlin/Library.kt:13-22` | `String?.plus(other: Any?)` | The declaration establishes string concatenation. It does not establish arbitrary object conversion provenance. |
| `commonMain/kotlin/String.kt:18-22`, `jvmMain/kotlin/String.kt:21-25` | `String.plus(other: Any?)` | The built-in member establishes string concatenation, separate from value-preserving transport. |
| `jvmMain/kotlin/io/Console.kt:77-81` | `println(message: Any?)` | The message passes to `System.out.println`. This supplies no return-value payload. |

Predicate overloads of `first` and `firstOrNull` are outside this initial review.
Their callback conditions need separate evidence.
No reviewed entry authorizes a source-defined callable with the same spelling.

## Actual binary examination

`javap -c -p` examined the native jar, rather than the fixture runtime jar.
The selected methods agree with their primary source entries.

| Exact class and method | Bytecode evidence |
| --- | --- |
| `kotlin.collections.CollectionsKt__CollectionsKt.listOf(Object[])` | The method branches on array length, then calls `ArraysKt.asList` or `emptyList`. |
| `kotlin.collections.CollectionsKt__CollectionsJVMKt.listOf(Object)` | The method passes its argument to `java.util.Collections.singletonList`. |
| `kotlin.collections.CollectionsKt___CollectionsKt.first(List)` | The method rejects an empty receiver, then returns `List.get(0)`. |
| `kotlin.collections.CollectionsKt___CollectionsKt.firstOrNull(List)` | The method returns null for an empty receiver and `List.get(0)` otherwise. |
| `kotlin.StandardKt__StandardKt.let(Object, Function1)` | The method passes its receiver to `Function1.invoke`, then returns that invocation's result. |
| `kotlin.TuplesKt.to(Object, Object)` | The method passes both positions to the `Pair` constructor in order. |
| `kotlin.Pair.<init>(Object, Object)` | Constructor positions zero and one enter distinct `first` and `second` fields. Their getters read those exact fields. |
| `kotlin.collections.MapsKt__MapsJVMKt.mapOf(Pair)` | The method reads `Pair.first` and `Pair.second`, then calls `Collections.singletonMap`. |
| `kotlin.collections.MapsKt__MapsKt.mapOf(Pair[])` | The method branches on array length and delegates nonempty pairs to `toMap`. |
| `kotlin.collections.MapsKt__MapsKt.putAll(Map, Pair[])` | Each pair supplies separate arguments to `Map.put` in input order. |
| `kotlin.io.ConsoleKt.println(Object)` | The method reads `System.out`, calls `PrintStream.println(Object)`, and returns void. |

These facts qualify the reviewed library operations.
They do not establish which overload K2 resolves at each fixture call.
Native call capture must supply that exact binding before model application.
The extension on `String?` and the built-in string member have no reviewed ordinary implementation body in these source entries.
String-template treatment also needs native expression and operand evidence.
This review does not infer a hidden `String.plus` call from template text.

## Exact model positions

This table describes semantic positions.
Only the exact `List.firstOrNull` selection passed native application assertions at this snapshot.
These positions do not define public request syntax.
`path` denotes the incoming field or element path.
Every application keeps the exact invocation, execution owner, call stack, model reference, and semantic basis.

| Operation | Input position | Output position | Necessary condition |
| --- | --- | --- | --- |
| Single-element `listOf` | Argument zero at `path` | Result at `Element(0) + path` | Exact single-element overload |
| Multiple-element `listOf` | Compiler-mapped actual element at `path` | Result at `Element(index) + path` | Native actual-element order and spread evidence |
| `List.first` | Extension receiver at `Element(0) + path` or `AnyElement + path` | Result at `path` | The same receiver has an element at index zero |
| `List.firstOrNull` | Extension receiver at `Whole`, `Element(0) + path`, or `AnyElement + path` | Conditional result at `Whole` or `path` | The same receiver has an element at index zero |
| `T.let` | Extension receiver at `path` | Supplied block argument zero at `path` | Exact supplied block and invocation proof |
| `T.let` | Supplied block's normal result at `path` | Result at `path` | The same supplied block and matched invocation |
| `A.to` | Extension receiver at `path` | Result at `Field(Pair.first) + path` | Exact resolved property identity |
| `A.to` | Argument zero at `path` | Result at `Field(Pair.second) + path` | Exact resolved property identity |
| Single-pair `mapOf` | Argument zero at `Field(Pair.second) + path` | Result at the map-value position for that pair's key, then `path` | Native key proof and exact overload |
| String concatenation | Exact string receiver or string argument | Derived string result | Reviewed operator and native operand binding |
| String template | Native embedded value | Derived string expression result | Compiler proof for the template and that embedded value |
| Logger terminal | Exact `Logger.info` or `Logger.warn` argument zero | No value result | Reviewed source declaration and sink interpretation |

Collection selection uses `ValueAccessPath.select` for its existing exact or abstract element semantics.
The application keeps `SelectedElement` as a condition on the exact receiver, index zero, and result occurrence.
That condition keeps the invocation, receiver kind, execution owner, semantic basis, and call context.
A source-known empty list permits no satisfiable element witness.
The application does not add a non-null or nonempty fact.
The later non-null filter needs evidence for the returned value or a proved alias on the same path.

`let` supplies no direct receiver-to-result identity rule.
A constant block supplies no receiver provenance to its result.
The reviewed body calls the block once, but its result needs a separate payload chain.
`let` also accepts a null receiver.
The safe call in `requireNotNull` supplies the receiver's non-null condition, independently of the library body.
Callback failure and unsupported execution keep their existing obligations.

The first map model can cover the fixture's single-pair overload.
Its key proof comes from the actual pair's first value.
The model does not use a fixed `Authorization` label.
The current path vocabulary cannot express a map value associated with a key.
A typed map-value position must preserve that association and keep key payload separate from value payload.
Multiple pairs with unresolved duplicate keys remain unsupported until overwrite conditions receive typed evidence.

String derivation records influence on a new string.
It does not assert equal values or copy a known representation state automatically.
Constant template text supplies no payload edge.
Opaque `Any?.toString` behavior needs its own reviewed or source-proven conversion.

## Implemented owners and remaining extensions

[ReviewedValueModel.kt](../../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ReviewedValueModel.kt) owns reviewed artifacts and exact compiler bindings.
`ReviewedLibraryBinding` admits the current basis, full signature, binary digest, artifact version, library origin, and declaration membership.
Its invocation comparison preserves enclosing, callable, and execution proofs beside ordinary invocation identity.

[ValueModelApplication.kt](../../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueModelApplication.kt) owns the conditional selection and separate callback applications.
The application preserves the path suffix, scoped predicates, exact call, and matched frames.
It supplies no `NonNull` or nonempty fact.
`RepresentationHistory` records this application without a supplied representation `Origin`.
A transformation does not copy a known representation automatically.

`NativeValueReviewedCall` uses the actual K2 library module and declaration membership.
`NativeValueArtifactRead` reads bounded chunks under the same native allowance.
It rejects changed content or physical identity, unavailable metadata, and refused work.
A reviewed metadata exception preserves the original source exclusions.
It grants no permission to scan a compiled body.

`QueryImpactTasks`, its existing ledger, and retained presentation preserve the complete application.
Typed projections preserve its source, target, condition, exact call, model, artifact, and compiler proofs.
Detached assertions separately reject substituted application branches and preserve strict completion.
These assertions do not establish native callback transport or full packet capability.

The detached callback contract passed seven cases for exact entry, matched normal return, state preservation, and rejected substitutions.
Its native implementation still needs separate receiver-to-block and block-result-to-call evidence.
The `let` source review supplies no direct receiver-to-result rule.
Native safe-call qualification passed for the checked receiver and scoped `NonNull` subject.
A later alias needs its own matched transfers.

`ValueAccessPath`, `ValueField`, and `ValueElementIndex` remain the field and element owners.
The map extension needs a typed association between each key and its value.
Unresolved overwrite behavior must keep its obligation.
String derivation needs exact compiler operand evidence and a reviewed conversion where the value is opaque.

The [terminal-model owner](../../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueBoundary.kt) already supports `REVIEWED_EXTERNAL_SINK` at exact native positions.
Logger argument derivation and reviewed sink interpretation remain separate obligations.
The fixture logger declarations reside at `src/main/kotlin/fixture/wire/OffersApi.kt:13-16`.
No logger argument supplies a result payload.

## Negative controls and remaining proof

- A same-name source function or unreviewed overload does not match the reviewed compiler declaration.
- A changed jar or class digest does not reuse the review identity.
- Another invocation of the same callable cannot supply the result for this call.
- Another semantic basis cannot supply fields, positions, predicates, or library proof.
- Another list element cannot satisfy index-zero selection.
- An empty list cannot supply an element or a non-null payload.
- A pair's key cannot become its value, and a sibling field cannot substitute for `Pair.second`.
- An earlier duplicate map value cannot survive an established later overwrite.
- An unknown overwrite keeps an obligation instead of a value-preserving edge.
- Constant string text and a constant mapper cannot inherit an input payload.
- A derived logger message does not authorize a request-to-response relationship.
- A logger terminal does not establish runtime execution or a result payload.

Source and bytecode examination passed for the entries in this report.
The procedures and immutable receipts reside in [the integration log](../evidence/segment6-integration-events.tsv).

| Procedure | Expected evidence | Result and limit |
| --- | --- | --- |
| `*NativePacketLibraryBindingTest` | Six exact K2 signatures and actual artifact affiliation | **PASSED**, six cases. Model application remains separate. |
| `*NativePacketReviewedReceiverAdmissionTest` | Original EXCLUDE receiver admission and unreviewed refusal | **PASSED**, two cases after the intended admission failure. |
| `*NativePacketFirstOrNullApplicationTest` | Element-zero and abstract-element applications, with wrong-element and SOURCE controls | **PASSED**, four supplemental INCLUDE cases. |
| `*NativeValueArtifactReadTest` | Bounded artifact effects and explicit failures | **PASSED**, eight detached cases. These cases do not prove K2 affiliation. |
| `*RetainedModeledValueOracleTest` | Full modeled proof, substitutions, and retained presentation without semantic reads | **PASSED**, two detached cases. |
| `*NativePacketCompositeQueryTest` | Original complete S2, C2.4, and retained selection prefix | **FAILED**, five of seven cases passed. The original EXCLUDE selection prefix passed. |
| `*NativePacketSafeCallReceiverTest` | Exact reviewed Let receiver and scoped guard, with foreign-subject and direct-result controls | **PASSED**, three cases. Callback transport remains separate. |
| `*NativePacketThisReadTest`, `*NativePacketThisRestorationTest` | Exact extension reads, full-expression restoration, and ordinary-read controls | **PASSED**, six cases. |
| `*ValueLetCallbackOracleTest` | Detached callback composition, exact state, wrong call, wrong slot, and modeled provenance | **PASSED**, seven cases. Assumed compiler facts do not prove native execution. |
| `*NativePacketS3QueryTest` | Original matched return, both log branches, and separate forbidden HTTP arguments | **FAILED**, two of four cases passed. Both log routes remain incomplete. |

The latest original composite run reached the reviewed Let extension receiver at `1622:1626` after 73 steps.
Its continuation ended at `EXTERNAL_CALL`.
C2.4 still ended at a named capacity refusal.
These failures do not satisfy C2.8, C2.9, or the remaining packet rows.
The original S3 capture kept `CALLBACK_FLOW_UNPROVEN` at the supplied callback occurrence `793:799`.
The six binding captures do not prove the unimplemented models.
Live installed capture and representative latency measurements remain **NOT RUN**.

## Prose assessment

The pinned assessment report and repository Vale report accompany this file.
Their status describes prose rules, not source truth or reader comprehension.
