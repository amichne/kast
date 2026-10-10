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
static analysis. No visible IDE is launched.
Config, system and log files belong to `relation/intellij/build/native-test`.
Extraction always writes inside that directory. The ordinary `:relation:intellij:test`
task retains its existing pure/parser lane and does not run these fixtures.

An already extracted distribution of exactly the pinned build can be used read-only:

```sh
./gradlew :relation:intellij:nativeFixtureTest -PnativeFixtureIdeaHome=/path/to/pinned/distribution
```

The home must retain `product-info.json`, Platform libraries and bundled plugin
layout. It is not a daily IDE project or an installed Kast plugin. Java, Kotlin and
TOML libraries are included; Kotlin's enabled TOML reference-search extension
requires the latter when using the core classloader. Official transitive test
framework dependencies are verified by the checked Gradle checksum metadata.

`IndexedKotlinReferenceTest` extends JetBrains' externally supported
`NewLightKotlinCodeInsightFixtureTestCase`. It asserts actual K2 mode, persistent
VFS identities, an indexed named function, exact cross-file resolution, one real
reference-search result, an empty negative control and freshness after a committed
edit plus index readiness. It never creates a `KotlinCoreEnvironment`.

`HeavyKotlinScopeTest` uses `HeavyPlatformTestCase` with Java modules, an explicit
case-owned JDK and a library root. The consumer depends on the target. Same-name
noise modules also depend on the target but call their own functions. Only noise
widens from one to five modules. The fixed oracle is the consumer file at UTF-16
offset 38, resolving to the target declaration at offset 18. Narrow and workspace
queries must exhaust with that same result. Newly indexed noise PSI is checked
for cold AST state before the broad comparison warms it. The case also tests an
unused declaration, cancellation from an actual native callback and committed-edit
invalidation. There are no sleeps or timing thresholds.

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
