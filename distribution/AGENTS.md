<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: c3352bddaf38 -->

# distribution

## Purpose

Defines installation/runtime configuration contracts, safe managed filesystem realization, and release assembly metadata.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/SemanticRuntimeContract.kt](contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/SemanticRuntimeContract.kt) - semantic runtime contract.
- [contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt) - configuration model.
- [contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/IndexerHeapSize.kt](contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/IndexerHeapSize.kt) - admitted heap sizing.
- [managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt](managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt) - owned installation tree.
- [managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SafeRuntimeArchive.kt](managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SafeRuntimeArchive.kt) - archive admission and extraction.
- [release/sidecar-release.gradle.kts](release/sidecar-release.gradle.kts) - release assembly wiring.

## Subdirectories

- `contract` - bootstrap, configuration, network, transport, and runtime identity types.
- `managed` - filesystem, endpoint, archive, network, and runtime-store effects.
- `release` - Python/Gradle release and SBOM utilities.

## Entry Points

- Gradle projects: `:distribution:contract`, `:distribution:managed`.
- Release tasks are applied from `release/sidecar-release.gradle.kts`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/distribution.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For configuration meaning, begin in `contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration`; for filesystem effects, follow admitted values into `managed`.
- For packaged layout failures, pair this map with [packaging instructions](../packaging/AGENTS.md).
