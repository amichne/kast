# Headless indexed Kotlin fixtures

Run the native lane explicitly:

```sh
./gradlew :relation:intellij:nativeFixtureTest
```

The task uses the catalog's `idea-platform-build` for the IDEA distribution,
Platform test framework, Java test framework and supported
`kotlin-base-test-framework`. JUnit Platform discovers its JUnit 3 fixture bases
through the matching Vintage engine. It checks the distribution build number and
uses its published JVM module-opening arguments. The task also runs native source
static analysis. A typed decoder rejects malformed metadata, a mismatched build,
an unsupported host OS or a missing host launch. No visible IDE is launched.
Config, system and log files belong to `relation/intellij/build/native-test`.
Extraction always writes inside that directory. The ordinary `:relation:intellij:test`
task retains its existing pure/parser lane and does not run these fixtures.

An already extracted distribution of exactly the pinned build can be used read-only:

```sh
./gradlew :relation:intellij:nativeFixtureTest -PnativeFixtureIdeaHome=/path/to/pinned/distribution
```

The home must retain `product-info.json`, Platform libraries and bundled plugin
layout. It is not a daily IDE project or an installed Kast plugin. Java, Kotlin and
TOML libraries are included; only Java, Kotlin and TOML plugins are enabled.
Kotlin's enabled TOML reference-search extension
requires the latter when using the core classloader. Official transitive test
framework dependencies are verified by the checked Gradle checksum metadata.

`IndexedKotlinReferenceTest` extends JetBrains' externally supported
`NewLightKotlinCodeInsightFixtureTestCase`. It asserts actual K2 mode, persistent
VFS identities, an indexed named function and exact cross-file resolution.
It checks one real reference-search result and an empty negative control.
After a committed edit and index readiness, it checks that the removed reference
stays absent. It never creates a `KotlinCoreEnvironment`.

`HeavyKotlinScopeTest` uses `HeavyPlatformTestCase` with Java modules, an explicit
case-owned JDK and a library root. The consumer depends on the target. Same-name
noise modules also depend on the target but call their own functions. Only noise
widens from one to five modules. The fixed oracle is the consumer file at UTF-16
offset 38, resolving to the target declaration at offset 18. Narrow and workspace
queries must exhaust with that same result. Newly indexed noise PSI is checked
for cold AST state before the broad comparison warms it. The case also tests an
unused declaration, cancellation from an actual native callback and committed-edit
invalidation. There are no sleeps or timing thresholds.

`NativeSearchCancellationTest` first proves a nonempty indexed reference search
with the same exact file and offsets. It then requests cancellation during a
production scope-membership call inside the executed native query. The fixture
does not call a cancellation checkpoint. The native SDK must throw cancellation
before the first reference callback, and the observed search must close once
with `CANCELLED`. Both callback counters must remain zero. This is native query
drainage within the headless fixture; it does not prove hosted read-action or
transport drainage.

`NativeCallbackExpiryTest` uses the same positive control. At the delivered native
callback, it advances the injected clock to the original semantic deadline.
Production reference inventory must stop before site admission or candidate
retention. The result must retain both `TIME_LIMIT_REACHED` and
`PARTITION_INVENTORY_UNAVAILABLE`, with zero examined semantic work and no facts.
The search closes after the callback returns the stop decision. This inventory
case uses an eligible native reference and proves qualified incompleteness.

The excluded-callback case narrows the production scope to the target file.
A deliberately broader SDK search supplies the real cross-file reference.
The current-time control must classify it as `SOURCE_DOMAIN_EXCLUDED`.
At the original deadline, `IntellijCallbackFlowContext.providerSite` must reject
it with `TIME_LIMIT_REACHED` before reading the supplied site. The exact callback
path, UTF-16 offset and resolved target are independent oracle checks.
This qualifies the production callback-admission rule on a real excluded SDK
reference. Production search retains its scope. The narrower reference inventory
remains qualified by the eligible case above. No case raises a deadline or uses
a sleep.

The work comparison calls the real production `IntellijRelationScopeCompiler`
and `forEachReference` boundary. Counters count scope predicate calls, live source
membership calls, executed native queries and delivered reference callbacks. They
are not distinct files, internal index visits or all semantic resolution calls.
The exact resolution assertion is an additional oracle check. Full Kast dependency
capture, bytes/hashes, semantic-fact retention and transport are not exercised;
missing counters for them must not be interpreted as zero work. This lane does
not qualify a separately loaded production optimization or installed plugin.

The frozen native-surrogate oracle and imported-IDE replay under
`experiments/host-observation` remain independent evidence. Passing this lane does
not requalify those 126 oracle checks or reproduce a private incident.

JetBrains documents [light and heavy fixtures](https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html)
and the supported [Kotlin test framework dependency](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-types.html#testframeworktype).
