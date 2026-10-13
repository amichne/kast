# Deferred Kotlin lazy callbacks

Kast now represents a reviewed Kotlin `lazy` initializer as a deferred supply.
The graph keeps the supplier, callback body, constructor reference, source sites, SEMANTIC BASIS, and binary identities.
The graph does not assert runtime activation or an invocation count.

## Cause

The former library rule accepted one matching `callsInPlace(..., EXACTLY_ONCE)` effect.
Kotlin `lazy` stores its initializer for later access and permits retries after an initializer failure.
That rule correctly rejected the missing effect with `DEPENDENCY_CONTRACT_EFFECT_UNPROVEN`.
The missing case needed a different proof.

Two more limits affected the caller request.
The binding reader accepted callable bodies as supplier owners, but a property supplied the initializer.
Graph admission also rejected a constructor reference whose requested target was a class declaration.

The new supplier type accepts a named callable or property after source containment succeeds.
A class-reference edge keeps the class identity and exact constructor occurrence.
The named-call policy still excludes the deferred callback from ordinary named callers.

## Binary model

The adapter reads the compiler-resolved library target and actual-to-formal mapping.
It admits `KOTLIN_LAZY_JVM_V1` only when six class digests match one reviewed set.
It reads those classes from the same archive and rejects an archive change.
It never analyzes a decompiled dependency body or uses a source attachment as proof.

The catalogue includes inspected stdlib artifacts for `1.9.24`, `2.0.21`, `2.1.20`, `2.2.20`, `2.3.20`, and `2.4.20`.
The `2.2.21` artifact has the same six digests as `2.2.20`.
Version text does not grant admission.
An unknown class set returns `DEPENDENCY_DEFERRED_MODEL_UNPROVEN`.
A missing or changed binary keeps its existing finite failure.

The six classes are `LazyKt`, `LazyKt__LazyJVMKt`, `SynchronizedLazyImpl`, `SafePublicationLazyImpl`, `UnsafeLazyImpl`, and `LazyKt__LazyJVMKt$WhenMappings`.
Their constructors store the callback, and their getters contain the callback invocation.
The model covers the default, mode, and lock overloads.
`PUBLICATION` retains the same deferred-supply representation without an exactly-once claim.
Anonymous suppliers, transported function values, and unmodeled libraries keep their existing rejections.

The wire binding uses `DEFERRED_DEPENDENCY` with a required model and six digests.
It has no `invocation_kind` field.
The serializer and document parser keep the source identities and reject malformed or contradictory records.

## Reproduction and evidence

The authored fixture uses `InterestRatesClientImpl`, a `CacheManager` dependency, and an `InterestRatesModule` property with `by kotlin.lazy`.
The public tracer uses a module-scoped `CALLERS` request for the client class.
It exercises real K2 ports, the canonical query runner, graph admission, retained evidence, and wire projection.
The expected ordinary caller set is empty because lexical containment does not grant a named-call edge.
The retained callback must preserve the exact property supplier and constructor occurrence.

The initial lambda assertion failed with `DEPENDENCY_CONTRACT_EFFECT_UNPROVEN` before the production change.
The property assertion then failed with `ANONYMOUS_IDENTITY_UNAVAILABLE` before the supplier change.
The public caller request then rejected the class target with `NonCallableTarget` before the graph change.
A separate negative control restored the former exactly-once rule and rejected the same public request.

The focused native command is:

```shell
mise exec -- ./gradlew \
  :relation:intellij:nativeFixtureTest \
  --tests '*NativeDependencyCallbackTest' \
  :runtime:hosted:nativeFixtureTest \
  --tests '*NativeScopedCacheManagerTraceTest' \
  -PnativeFixtureIdeaHome=/path/to/pinned/idea --console=plain
```

The pinned platform is IntelliJ `262.9437.185`.
The cases cover source attachments, every overload, formal positions, a same-name source function, and an altered implementation class.
The altered class keeps valid metadata but must reject the reviewed model.
Pure cases also reject each changed digest, invalid containment, a different target, and an incomplete scan.
Wire cases assert the encoded field set and reject invented invocations and inconsistent source sites.

This run uses an authored fixture, not the original project checkout.
It does not establish recurrence in that checkout, an installed Host result, runtime activation, or enterprise latency.

## Sources

- [Domain proof and catalogue](../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackDeferredDependency.kt)
- [Binary effect reader](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackDependencyContract.kt)
- [Class-content reader](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijKotlinLazyBinaryEvidence.kt)
- [Graph admission](../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteStaticCallbackGraph.kt)
- [Native caller fixture](../../runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeScopedCacheManagerTraceTest.kt)
- [Kotlin lazy source](https://github.com/JetBrains/kotlin/blob/v2.3.20/libraries/stdlib/jvm/src/kotlin/util/LazyJVM.kt)
