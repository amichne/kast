# Build logic guide

`build-logic` is an included Gradle build. It owns the `kast.*` convention
plugins and reusable task types that configure every Kotlin module. Treat this
guide as a local delta from the repository guide: product behavior remains in
the consuming projects.

## Module map

- `src/main/kotlin/kast.*.gradle.kts` contains the precompiled convention
  plugins for Kotlin libraries, serialization, publishing, applications, and
  test fixtures.
- `support/publishing` owns Maven coordinates, metadata, signing, and target
  selection.
- `support/tasks` owns IDEA distribution extraction, test-tag selection,
  generated protocol versions, runtime library synchronization, classpath
  layout proof, indexer-version generation, and wrapper scripts.
- `src/test/kotlin` contains task and convention contract tests.

## Dependency boundary

- This build imports the repository version catalog from
  `../gradle/libs.versions.toml` and targets Java/Kotlin 21.
- It may configure main-build projects, but it must not depend on their product
  classes or encode workspace-specific runtime policy.
- Convention-plugin IDs and registered task names are consumed across project
  boundaries. Renaming or changing their output layout is a repository-wide
  contract change.
- `kast.runtime-app` provides generic application packaging. `indexer` owns the
  additional private-plugin/runtime split required by its IntelliJ host.

## Shared invariants

- `kast.kotlin-library` owns JUnit Platform setup and the default exclusion of
  `concurrency`, `performance`, and `parity`. Explicit
  `-PincludeTags=...` selects those suites; `-PexcludeTags=...` adds exclusions.
- `SyncRuntimeLibsTask` writes deterministic runtime jars and
  `classpath.txt`. `WriteWrapperScriptTask` atomically writes the launcher that
  resolves the application jar.
- `VerifyClasspathLayoutTask` proves class ownership, required entries,
  descriptor placement, and the absence of forbidden fat jars. Keep semantic
  class-entry checks stronger than filename conventions.
- `ExtractIdeaDistributionTask` rejects zip-slip paths and replaces a
  versioned extraction atomically.
- Protocol constants are generated from the three checked-in files under
  `cli-rs/protocol/`: `api-schema-version.txt`,
  `install-receipt-schema-version.txt`, and
  `source-index-schema-version.txt`. Never add a second literal authority.
- Publishing configuration must reject missing or blank artifact metadata and
  preserve explicit local, snapshot, release, and GitHub target behavior.

## Verification ladder

1. Run the focused task test, for example:
   `./gradlew -p build-logic test --tests DefaultTestTagSelectionTest`,
   `WriteProtocolSchemaVersionsTaskTest`,
   `WriteSourceIndexSchemaVersionTaskTest`, or
   `RuntimeClasspathAssertionsTest`.
2. Run `./gradlew -p build-logic test`.
3. Exercise the narrowest consumer task. Runtime-layout changes require
   `./gradlew :indexer:verifyPortableDistLayout :indexer:portableDistZip`;
   protocol-generation changes require the owning module generator.
4. Run `./gradlew build` when a convention plugin, toolchain, dependency
   bundle, test policy, publishing rule, or shared task contract changed.
