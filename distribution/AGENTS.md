<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-14 | hash: c3352bddaf38 -->

# distribution

## Purpose

Defines installation/runtime configuration contracts, safe managed filesystem realization, and release assembly metadata.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt) - configuration model.
- [contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/IndexerHeapSize.kt](contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/IndexerHeapSize.kt) - historical heap input admission.
- [managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt](managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt) - owned installation tree.
- [release/plugin-release.gradle.kts](release/plugin-release.gradle.kts) - control distribution and IDEA plugin release wiring.

## Subdirectories

- `contract` - bootstrap, configuration, transport qualification, and runtime identity types.
- `managed` - owned installation trees, recovery receipts, selected IDE discovery, and endpoint effects.
- `release` - Python/Gradle release and SBOM utilities.

## Entry Points

- Gradle projects: `:distribution:contract`, `:distribution:managed`.
- Release tasks are applied from `release/plugin-release.gradle.kts`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/distribution.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For configuration meaning, begin in `contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration`; for filesystem effects, follow admitted values into `managed`.
- For packaged layout failures, pair this map with [packaging instructions](../packaging/AGENTS.md).
