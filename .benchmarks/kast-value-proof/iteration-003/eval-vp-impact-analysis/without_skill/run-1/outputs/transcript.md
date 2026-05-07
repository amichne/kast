# Impact analysis without Kast skill

Workspace: `/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without`
Forbidden tools respected: no `kast_*`, no `kast` CLI, no IDE/LSP semantic tools.
Allowed discovery used: `grep` over Kotlin source files, plus manual reading of matching files.

## Commands / raw searches
### grep -RIn -C3 ContextualResolver -- *.kt
```
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-66-        override fun staticValueOrNull(): T = value
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-67-    }
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-68-
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:69:    private class ContextualResolver<T : Any, C : Context>(
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-70-        private val valueResolver: RuleValueResolver<C, T>,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-71-    ) : Resolver<T, C> {
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-72-        override fun resolve(
--
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-89-        internal fun <T : Any, C : Context> Rule<C>.targetedBy(
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-90-            valueResolver: RuleValueResolver<C, T>,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-91-        ): ConditionalValue<T, C> =
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:92:            ConditionalValue(this, ContextualResolver(valueResolver), SerializedRuleValueType.CONTEXTUAL)
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-93-
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-94-        internal fun <T : Any, C : Context> Rule<C>.targetedBySerialized(
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-95-            value: T,
```

### grep -RIn -C3 \.resolve( -- *.kt
```
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-162-
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-163-            if (isRampUpEligible(inputs.stableId, inputs.isFlagAllowlisted, candidate, computedBucket)) {
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-164-                Trace(
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:165:                    value = candidate.resolve(
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-166-                        context = inputs.context,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-167-                        registry = registry,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-168-                        ownerNamespace = feature.namespace,
--
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-36-        context: C,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-37-        registry: NamespaceRegistry,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-38-        ownerNamespace: Namespace,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:39:    ): T = resolver.resolve(
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-40-        context = context,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-41-        registry = registry,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-42-        ownerNamespace = ownerNamespace,
```

### grep -RIn -C3 resolver\.resolve( -- *.kt
```
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-36-        context: C,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-37-        registry: NamespaceRegistry,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-38-        ownerNamespace: Namespace,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:39:    ): T = resolver.resolve(
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-40-        context = context,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-41-        registry = registry,
konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt-42-        ownerNamespace = ownerNamespace,
```

### grep -RIn -C3 candidate\.resolve( -- *.kt
```
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-162-
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-163-            if (isRampUpEligible(inputs.stableId, inputs.isFlagAllowlisted, candidate, computedBucket)) {
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-164-                Trace(
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:165:                    value = candidate.resolve(
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-166-                        context = inputs.context,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-167-                        registry = registry,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-168-                        ownerNamespace = feature.namespace,
```

### grep -RIn -C3 evaluateCandidate -- *.kt
```
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-124-            val state = EvaluationState<T, C>()
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-125-            val matchedTrace =
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-126-                valuesByPrecedence.firstNotNullOfOrNull { candidate ->
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:127:                    evaluateCandidate(
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-128-                        candidate = candidate,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-129-                        inputs = inputs,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-130-                        registry = registry,
--
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-141-                )
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-142-        }
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-143-
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:144:    private fun evaluateCandidate(
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-145-        candidate: ConditionalValue<T, C>,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-146-        inputs: EvaluationInputs<C>,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-147-        registry: NamespaceRegistry,
```

### grep -RIn -C3 evaluateTrace -- *.kt
```
konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt-131-    mode: Metrics.Evaluation.EvaluationMode,
konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt-132-    definition: FlagDefinition<T, C, M>,
konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt-133-): EvaluationDiagnostics<T> {
konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:134:    val trace = definition.evaluateTrace(
konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt-135-        context = context,
konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt-136-        registry = registry,
konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt-137-    )
--
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-73-     */
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-74-    internal fun evaluate(context: C): T {
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-75-        return if (isActive) {
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:76:            evaluateTrace(context, feature.namespace).value
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-77-        } else {
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-78-            defaultValue
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-79-        }
--
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-99-        val isFlagAllowlisted: Boolean,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-100-    )
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-101-
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:102:    internal fun evaluateTrace(
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-103-        context: C,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-104-        registry: NamespaceRegistry,
konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt-105-    ): Trace<T, C> =
```

## Direct caller and depth-2 callers

| Depth | Caller | Relation | Location | Classification | Evidence |
|---:|---|---|---|---|---|
| 1 | `ConditionalValue.resolve(context, registry, ownerNamespace)` | direct dynamic dispatch caller of ContextualResolver.resolve() | `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:35-43` | production | line 39: ): T = resolver.resolve(...); resolver may be ContextualResolver or StaticResolver |
| 2 | `FlagDefinition.evaluateCandidate(candidate, inputs, registry, state)` | caller of ConditionalValue.resolve() | `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:144-180` | production | line 165: value = candidate.resolve(...) |
| 3 | `FlagDefinition.evaluateTrace(context, registry)` | caller of FlagDefinition.evaluateCandidate() (extra context from grep) | `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:102-142` | production | line 127: evaluateCandidate(...) inside valuesByPrecedence.firstNotNullOfOrNull |

Interpretation: the only direct call site that can dispatch to `ConditionalValue.ContextualResolver.resolve()` is `ConditionalValue.resolve()` calling the private `resolver.resolve(...)`. Grepping `.resolve(` also finds `candidate.resolve(...)`, which is the next caller up (`FlagDefinition.evaluateCandidate`) and not a direct call to the private nested resolver implementation.

## Test vs production classification

- Direct/depth-2 call chain entries above are all production code (`src/main/kotlin`).
- Broader `.evaluate(` text grep (entry points that can eventually reach this path through public/internal evaluation) found 3 production lines and 33 test lines. Because this run intentionally lacks semantic tools, these are textual matches and include overloaded/extension `evaluate` calls.

### Broader `.evaluate(` matches
| Classification | Location | Text |
|---|---|---|
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:24` | `assertTrue(SerializableFlags.enabled.evaluate(context))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:25` | `assertEquals(Theme.DARK, SerializableFlags.theme.evaluate(context))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:26` | `assertEquals(RetryPolicy(mode = "ios"), SerializableFlags.uiConfig.evaluate(context))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:32` | `assertTrue(SerializableFlags.enabled.evaluate(context))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:33` | `assertEquals(Theme.DARK, SerializableFlags.theme.evaluate(context))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:34` | `assertEquals(RetryPolicy(mode = "ios"), SerializableFlags.uiConfig.evaluate(context))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:62` | `assertEquals(RetryPolicy(mode = "ios"), SerializableFlags.uiConfig.evaluate(TestContext(platform = Platform.IOS)))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:63` | `assertFalse(SecondaryFlags.enabled.evaluate(TestContext(platform = Platform.IOS)))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:54` | `assertEquals(true, namespace.enabled.evaluate(TestContext()))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:55` | `assertEquals(Theme.DARK, namespace.theme.evaluate(TestContext()))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:56` | `assertEquals(RetryPolicy(maxAttempts = 7, backoffMs = 250.0, enabled = false, mode = "linear"), namespace.retryPolicy.evaluate(TestContext()))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:65` | `val previous = namespace.enabled.evaluate(TestContext())` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:81` | `assertEquals(previous, namespace.enabled.evaluate(TestContext()))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:115` | `assertEquals(RetryPolicy(), namespace.retryPolicy.evaluate(TestContext()))` |
| test | `konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:127` | `assertEquals(false, namespace.enabled.evaluate(TestContext()))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/core/NamespaceBehaviorTest.kt:20` | `val results = (1..50).map { CheckoutFlags.enabled.evaluate(context) }` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/core/NamespaceBehaviorTest.kt:29` | `assertTrue(CheckoutFlags.enabled.evaluate(context))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/core/NamespaceBehaviorTest.kt:30` | `assertFalse(BillingFlags.enabled.evaluate(context))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/core/NamespaceBehaviorTest.kt:38` | `assertTrue(AxisFlags.prodOnly.evaluate(prod))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/core/NamespaceBehaviorTest.kt:39` | `assertFalse(AxisFlags.prodOnly.evaluate(stage))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:29` | `assertTrue(namespace.flag.evaluate(TestContext()))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:33` | `assertFalse(namespace.flag.evaluate(TestContext()))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:37` | `assertFalse(namespace.flag.evaluate(TestContext()))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:39` | `assertTrue(namespace.flag.evaluate(TestContext()))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:38` | `assertTrue(namespace.enabled.evaluate(context))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:42` | `assertFalse(namespace.enabled.evaluate(context))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:46` | `assertTrue(namespace.enabled.evaluate(context))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:72` | `assertEquals(7, namespaceA.number.evaluate(TestContext()))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:73` | `assertEquals(2, namespaceB.number.evaluate(TestContext()))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:75` | `assertEquals(5, namespaceA.number.evaluate(TestContext()))` |
| test | `konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:76` | `assertEquals(2, namespaceB.number.evaluate(TestContext()))` |
| production | `konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt:34` | `fun <T : Any, M : Namespace> Feature<T, C, M>.evaluate(): T =` |
| production | `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:35` | `* val enabled = AppFlags.darkMode.evaluate(context)` |
| production | `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:44` | `fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.evaluate(` |
| test | `smoke-test/src/test/kotlin/SmokeTest.kt:31` | `assertTrue(Flags.enabled.evaluate(context))` |
| test | `smoke-test/src/test/kotlin/SmokeTest.kt:37` | `assertTrue(Flags.enabled.evaluate(context))` |

## Answer

Every direct caller found:

1. `ConditionalValue.resolve(...)` in `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:35-43` (production) directly calls `resolver.resolve(...)`, which dispatches to `ContextualResolver.resolve()` when `resolver` is a `ContextualResolver`.

Depth 2 from that caller:

1. `FlagDefinition.evaluateCandidate(...)` in `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:144-180` (production) calls `candidate.resolve(...)` at line 165.

No direct or depth-2 callers found in test files. Test files contain `.evaluate(...)` calls that may exercise the path indirectly, listed above.
