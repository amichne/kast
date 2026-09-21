# Pre-v1 configuration and setup audit

Reviewed 2026-09-21. Scope: build configuration, dependency declarations, installed
configuration admission, retired runtime setup, and their direct consumers.

## Removed or corrected

| Finding | Change and evidence |
| --- | --- |
| Contributor setup omitted the Python dependencies required by the Gradle gate. | [Development setup](../development.md) now creates a Python 3.12 virtual environment and installs the same pinned requirements as CI. The broad gate exposed the missing `jsonschema` dependency. |
| Dependency verification was explicitly disabled for all builds and IDE sync. | [Gradle properties](../../gradle.properties) now require strict verification. Missing checksums were compared with independently fetched Maven Central artifacts and published SHA-256 files where available; provenance is recorded in [verification metadata](../../gradle/verification-metadata.xml). |
| Retired isolated-runtime adapters still shipped download, extraction, cache, trust-store, and network configuration effects. | Removed the uncalled managed runtime store/downloader/extractor, network bootstrap and trust materializer, heap observer, and their tests. Removed the orphaned semantic runtime manifest model. Retained installation ownership, recovery, endpoint, and selected-IDE adapters. |
| The configuration catalogue advertised settings and JVM proof inputs for removed owners. | Removed eight setup selectors, derived indexer/importer properties, and indexer transport limits. [Admission tests](../../distribution/contract/src/test/kotlin/io/github/amichne/kast/distribution/contract/configuration/RetiredRuntimeConfigurationTest.kt) check all four input sources and bounded `UNKNOWN_KEY` failures. [CLI tests](../../cli/src/test/kotlin/io/github/amichne/kast/cli/ConfigurationInspectionTest.kt) check the actual exported schema. |
| Unused Maven publishing conventions loaded a plugin and contained deprecated APIs. | Removed both publishing conventions, extension/helper code, their isolated test, plugin dependency, duplicate plugin pin, and unused POM properties. No shipped module applied these conventions. GitHub release assembly remains authoritative. |
| Delegated Gradle task/configuration/source-set declarations produced Gradle 10 deprecations. | Replaced them with explicit registration/creation; task providers remain lazy. |
| Identical IDEA distribution/platform pins could drift independently. | Consolidated compilation/distribution selection on `idea-platform-build`. The independently selected packaged host retains `ide-host-build`. |
| Obsolete and unused dependency declarations remained. | Removed the redundant coroutines `jdk8` dependency and unused JUnit 4 and Gradle Tooling API catalogue aliases. The selected core constraint is unchanged. Coroutines [merged Java 8 integration into core in 1.7](https://github.com/Kotlin/kotlinx.coroutines/blob/master/CHANGES.md#version-170). |

Remove the eight retired assignments listed in the [configuration contract](../../knowledge/contracts/configuration.md)
from saved files or the calling environment. They now reject; they are not silently
ignored or translated into unrelated IDEA settings. This is an intentional pre-v1
configuration break.

## Remaining v1 removal targets

1. **Retired worker configuration and delegated import environment.**
   [ResolvedKastConfiguration](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/KastConfigurationResolution.kt)
   still admits heap, worker capacity, runtime store/cache selectors, and Gradle
   environment forwarding. Production caller search found no consumers of the
   resolved heap/capacity/path getters; child projections still retain these
   assignments in launch/configuration identity. Remove the settings together with
   saved-configuration migration and installed upgrade fixtures. In particular,
   eliminate credential forwarding once the delegated import settings leave the
   contract. Keeping no-op user knobs would misrepresent control over IDEA.
2. **Retired CLI and wire lifecycle vocabulary.**
   [KastCli](../../cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt)
   still carries start/stop rejection branches, while
   [CoordinatorControl](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt)
   serves zero-worker status. Remove this vocabulary as a coordinated public
   command/schema change. Preserve rejection and ownership checks for old worker
   receipts until the supported upgrade floor excludes those installations.
3. **Bootstrap observation compatibility.**
   The retained bootstrap contract still depends on Gradle JVM observation types.
   Compiler checking proved that dependency. Remove or replace the entire
   [bootstrap projection](../../cli/src/main/kotlin/io/github/amichne/kast/cli/InstalledBootstrapSchemas.kt)
   before deleting those types; deleting only their declaration breaks the
   serialization contract.
4. **Kotlin migration scaffolding.**
   The compiler reports redundant `ConsistentCopyVisibility` annotations across
   domain models. Remove the annotations and the old migration flag in the shared
   Kotlin convention together, with compiler checks proving constructor/copy
   visibility. This audit does not weaken those constructors.

Historical recovery paths are not interchangeable with dead setup paths. They
retain ownership proofs for installed files and processes, so their removal needs
an explicit supported-upgrade floor and original-release upgrade acceptance.

## Verification boundary

The focused retirement test first failed because removed settings were accepted
and retired owners remained in the catalogue, then passed after the change.
Distribution and CLI tests exercise the configuration and consumer boundaries.
Build/architecture checks do not establish installed upgrade compatibility or
native IDEA/Codex behavior; those require the separately documented acceptance
runs. This audit makes no native-runtime qualification claim.

Verified after cleanup with the documented Python environment active:

- `./gradlew --no-daemon productBuildGate --max-workers=4 --console=plain`: passed; 535 tasks, including architecture, module checks, packaging, and installed-product acceptance.
- `./gradlew knowledgeImpact verifyKnowledgeBase verifyJsonContracts verifyConfigurationIngress --max-workers=4 --console=plain`: passed; six impacted concepts reviewed, zero knowledge issues, zero JSON guard violations, and no ingress findings.
- `./gradlew help --no-configuration-cache --warning-mode fail --console=plain`: passed.
- `git diff --check`: passed.

The Codex installed-schema qualifier was skipped when its opt-in inputs were absent;
this is not native Codex/IDEA release qualification.
