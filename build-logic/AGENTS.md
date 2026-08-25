# Build logic guide

`build-logic` is an included Gradle build. It owns the `kast.*` convention
plugins and reusable task types that configure every Kotlin module. Treat this
guide as a local delta from the repository guide: product behavior remains in
the consuming projects.

## Module map

- `src/main/kotlin/*.gradle.kts` and `src/main/kotlin/conventions/*.gradle.kts` contain the
  precompiled convention plugins for Kotlin libraries, serialization, publishing, applications,
  and test fixtures. A `package kast` declaration preserves the public `kast.*` plugin ID for
  scripts grouped under `conventions/`.
- `support/publishing` owns Maven coordinates, metadata, signing, and target
  selection.
- `support/tasks` owns IDEA distribution extraction, test-tag selection,
  runtime library synchronization, classpath layout proof, indexer-version
  generation, generated-serialization source guards, and wrapper scripts.
- `support/pr633` owns reusable exact-head, bytecode, API, and gate-evidence task types.
- `support/architecture` owns the typed clean-slate module graph, effect policy,
  and checked-in architecture projection.
- `support/delivery/model` owns the exact-head VFS-passive delivery graph and typed authority
  refinement. `support/delivery/tasks` owns projection, Git, source-read, serialization, generation,
  and verification boundaries.
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
- `WriteJavaProcessOutputTask` atomically captures a typed JVM projection as one generated build
  resource without moving filesystem authority into product contract modules.
- `WriteProcessOutputTask` atomically captures a declared executable projection while keeping
  process configuration compatible with Gradle's configuration cache.
- `VerifyClasspathLayoutTask` proves class ownership, required entries,
  descriptor placement, and the absence of forbidden fat jars. Keep semantic
  class-entry checks stronger than filename conventions.
- `VerifyGeneratedSerializationSourcesTask` and `verifyGeneratedBuildLogicSerialization` reject
  hand-written JSON assembly at configured closed schemas. Treat this fast source guard as a
  complement to compiler and round-trip proof, not as a replacement for either.
- `ExtractIdeaDistributionTask` rejects zip-slip paths and replaces a
  versioned extraction atomically.
- Add every direct project dependency to the typed architecture policy in the
  same change. Regenerate the checked-in projection from that policy.
- Exclusive-effect validation keeps topology-build authority in `:topology:build` and topology
  publication in `:evidence:sqlite`; effect scanning proves their concrete bytecode ownership.
- Publishing configuration must reject missing or blank artifact metadata and
  preserve explicit local, snapshot, release, and GitHub target behavior.
- `kast.vfs-passive-delivery` verifies checked-in program projections without rewriting them. Its
  explicit projection-generation task replaces both projections atomically. The KVP-001 GREEN path
  separately generates exact-head authority, contradiction, and verification evidence under
  `build/reports/delivery` from digest-admitted repository authority sources before verifying them.

## Verification ladder

1. Run the focused task test, for example:
   `./gradlew -p build-logic test --tests DefaultTestTagSelectionTest`,
   `RuntimeClasspathAssertionsTest`.
2. Run `./gradlew -p build-logic test`.
3. Exercise the narrowest consumer task. Runtime-layout changes require
   `./gradlew :indexer:verifyPortableDistLayout :indexer:portableDistZip`;
   shared-task changes require the owning module consumer.
4. Run `./gradlew build` when a convention plugin, toolchain, dependency
   bundle, test policy, publishing rule, or shared task contract changed.
