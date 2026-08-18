## Task 3 — Prove symbol discovery through the installed runtime

**Goal**

Prove the corrected discovery algorithm against the packaged Kast runtime and the real bundled Kotlin plugin, then prove direct discovery → exact-resolution composition.

**Depends on**

```text
Task 1 — Preserve exact symbol discovery qualifications
Task 2 — Replace scope-blind symbol-name enumeration
```

**Why**

Existing `:symbol:intellij` tests use fake contributors and fake items. They validate the collector and adapter contracts, but they do not prove behavior of the real Kotlin stub indexes or the packaged runtime.

The clean-slate delivery graph requires installed-system proof after the native read implementation is established.

**Allowed writes**

```text
installed-product test fixture sources
installedProductTest implementation
runtime/composition installed-runtime fixtures if already owned there
cli tests only if required to assert public projection
```

Do not introduce a second integration-test framework.

**Fixture**

Create an indexed Kotlin source containing:

```kotlin
package fixture

class HealthController

class ServerTimeController

fun healthCheck() = Unit

typealias ControllerAlias = HealthController
```

Generate enough unrelated Kotlin declarations to exceed the old work threshold for:

```text
--limit 10
```

That means the corpus must contain more unrelated indexed names than the previous effective `10 × 100` budget before the target would have been encountered under the old enumeration strategy.

**Installed execution**

Run the packaged product:

```shell
kast symbol discover \
  --query HealthController \
  --limit 10
```

and:

```shell
kast symbol discover \
  --query fixture.HealthController \
  --limit 10
```

Both must return a candidate for the same declaration.

Take each returned candidate selector verbatim and execute:

```shell
kast symbol resolve \
  --candidate-selector '<selector>'
```

Do not reconstruct identity from:

```text
name
path
offset
qualified name
display output
```

The returned exact selector from both journeys must be identical.

Also exercise:

```shell
kast symbol discover --query HealthCont --limit 10
kast symbol discover --query ControllerAlias --limit 10
```

**Assertions**

```text
simple query finds HealthController
qualified query finds HealthController
fuzzy query finds HealthController
typealias query finds ControllerAlias
simple + qualified candidate locations are identical
simple + qualified candidates resolve to the same exact selector
large unrelated index vocabulary does not produce WORK_LIMIT before the match
successful terminal enumeration does not return an incomplete qualification
```

**Failure-proof assertion**

Add at least one installed-path test showing that a genuine nonterminal provider condition is still projected as a specific public limitation rather than `evidence-incomplete`, where deterministic fault injection is available.

Do not weaken production code merely to make this injection possible.

**Validation**

```shell
./gradlew :symbol:intellij:test
./gradlew :runtime:composition:test
./gradlew :protocol:wire:test
./gradlew :cli:test
./gradlew verifyKastArchitecture --configuration-cache
./gradlew installedProductTest
```

**Architecture assertions**

The installed read must perform:

```text
zero Gradle imports
zero workspace refreshes
zero graph builds
zero SQLite writes
zero source mutations
```

Live values must remain request-local:

```text
Project
GotoSymbolModel2
DefaultChooseByNameItemProvider
NavigationItem
PsiElement
GlobalSearchScope
K2 analysis values
```

**Non-goals**

```text
Do not benchmark unrelated operations.
Do not introduce a graph/index persistence prerequisite.
Do not add a fallback discovery implementation.
Do not weaken exact selector refinement.
Do not use fake Kotlin contributors as the installed proof.
```

**Done when**

A clean installed Kast runtime discovers `HealthController` by simple, qualified, and fuzzy query through the real Kotlin plugin; the old unrelated-name threshold cannot hide the declaration; and both simple and qualified discovery results compose directly into the same compiler-grounded exact selector.
