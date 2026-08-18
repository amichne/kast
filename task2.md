## Task 2 — Replace scope-blind symbol-name enumeration with IntelliJ Choose-by-Name

**Goal**

Replace manual global-name enumeration for `SymbolDiscoveryKind.SYMBOL` with IntelliJ's productized `GotoSymbolModel2` + `DefaultChooseByNameItemProvider` path.

**Baseline**

`amichne/kast@d0f94a985cb023f9069e06b6313afded7e31529b`

**Why**

`IntellijNativeDiscoveryQuery` currently calls `collector.admitWork()` for every value emitted from `ChooseByNameContributorEx.processNames()` before testing whether the name matches the query.

The public handler creates that budget as:

```text
requested limit × 100
```

so `--limit 10` admits only 1,000 work units. A target after 1,000 unrelated index keys can therefore never be inspected.

The current adapter also admits Kotlin providers by exact implementation class name.

JetBrains' current symbol-search implementation instead uses `GotoSymbolModel2`, computes a local pattern, constructs `FindSymbolParameters.withLocalPattern`, and streams matched output through `DefaultChooseByNameItemProvider.filterElementsWithWeights`.

**Allowed writes**

```text
symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijNativeDiscoveryQuery.kt
symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijNativeDiscoveryAdapter.kt
symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijDiscoveryProjection.kt
symbol/intellij/src/test/**
symbol/intellij/AGENTS.md
```

Only modify `IntellijDiscoveryProjection.kt` if item admission naturally belongs there.

**Required adapter**

Introduce a narrow request-local provider boundary approximately equivalent to:

```kotlin
internal fun interface IntellijChooseByNameSymbolProvider {
    fun search(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        processor: Processor<NavigationItem>,
    ): IntellijChooseByNameCompletion
}
```

No IntelliJ type may escape `:symbol:intellij`.

**Implementation**

For `SymbolDiscoveryKind.SYMBOL`:

```text
GotoSymbolModel2
→ transform complete query
→ derive local pattern from model separators
→ FindSymbolParameters
→ DefaultChooseByNameItemProvider.filterElementsWithWeights
→ item admission
→ existing detached candidate projection
```

Construct parameters using the equivalent of:

```kotlin
val completePattern = viewModel.transformPattern(request.pattern.value)
val localPattern = compileLocalPattern(model, completePattern)

val parameters = FindSymbolParameters
    .wrap(completePattern, compiledScope.nativeScope)
    .withLocalPattern(localPattern)
```

Then stream matched items:

```kotlin
provider.filterElementsWithWeights(
    viewModel,
    parameters,
    indicator,
    Processor { descriptor ->
        val item = descriptor.item as? NavigationItem
            ?: return@Processor true

        collector.accept(item)
    },
)
```

The work budget applies to provider-delivered matched items, not global short-name index keys.

Keep these existing bounds:

```text
result limit
byte limit
work limit
elapsed-time limit
cancellation
workspace generation
environment state
```

Provider early termination must produce a qualification. It must never produce `Complete`.

**Item admission**

Remove the contributor implementation-class allowlist.

Admission occurs after the provider returns a `NavigationItem`:

```text
NavigationItem
→ unwrap PsiElementNavigationItem.targetElement when present
→ non-Kotlin item                   => FILTERED
→ supported KtNamedDeclaration     => ADMITTED
→ unsupported Kotlin declaration   => UNSUPPORTED
```

Non-Kotlin results are `FILTERED`, not `UNSUPPORTED`.

The admitted Kotlin declaration set must cover declarations that `IntellijKotlinCompilerSymbolLookup` can subsequently ground through K2, including:

```text
classes / class-like declarations
functions
properties
type aliases
```

Discovery still returns detached candidates. Exact identity remains the responsibility of `symbol.resolve`.

**Qualified names**

A query such as:

```text
fixture.HealthController
```

must compile a local pattern of:

```text
HealthController
```

rather than comparing the complete FQN directly against Kotlin short-name index keys.

**Update repository instructions**

`symbol/intellij/AGENTS.md` currently requires:

```text
ChooseByNameContributorEx
collect matching names before requesting elements
```

That guidance must be replaced because it would directly contradict the corrected implementation.

**RED**

```shell
./gradlew :symbol:intellij:test --tests '*SymbolDiscovery*'
```

Add failing fixtures for:

```text
target after > workUnitLimit unrelated names
simple query: HealthController
qualified query: fixture.HealthController
fuzzy query: HealthCont
same-name declarations in two Kotlin files
same-name non-Kotlin declaration
typealias ControllerAlias
provider stops before terminal enumeration
provider throws RuntimeException
```

The first fixture must demonstrate the existing defect:

```text
target exists
target appears after the old work threshold
result has zero candidates
qualification contains WORK_LIMIT_REACHED
```

**GREEN**

```text
HealthController finds the intended Kotlin declaration.
fixture.HealthController finds the same declaration.
HealthCont finds the intended declaration.
Simple and qualified queries return the same file and offset.
Same-name Kotlin declarations remain distinct.
Same-name non-Kotlin results are filtered.
Type aliases are discoverable.
Provider early termination is qualified.
Provider RuntimeException is qualified as PROVIDER_FAILURE.
No exact Kotlin contributor implementation class remains in production code.
```

**Non-goals**

```text
Do not increase SYMBOL_DISCOVERY_WORK_MULTIPLIER.
Do not remove the work budget.
Do not add contributor class names.
Do not perform exact selector resolution during discovery.
Do not add repository scanning or grep fallback.
Do not refresh the workspace.
Do not import Gradle.
Do not persist evidence.
```

**Done when**

Repository vocabulary size and provider iteration order cannot prevent a matching symbol from being considered merely because unrelated names exhausted the request work budget.

This directly completes the existing bounded native discovery requirement.  The IntelliJ substrate program already requires native symbol discovery without scope-blind name enumeration that drops valid matches.

