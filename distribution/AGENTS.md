<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-28 | hash: eac47d85f0bd -->

# distribution

## Purpose

Defines installation/runtime configuration contracts, the native management CLI, safe managed filesystem realization, and release assembly metadata.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt) - configuration model.
- [managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt](managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt) - owned installation tree.
- [release/plugin-release.gradle.kts](release/plugin-release.gradle.kts) - independent Control and Host release wiring and fresh-pair assembly.
- [release/component_release.py](release/component_release.py) and [release/component-release.schema.json](release/component-release.schema.json) - exact-source component records, owned asset inventories and checksum admission.

## Subdirectories

- `contract` - bootstrap, configuration, transport qualification, and runtime identity types.
- `cli` - native public management command, passive status, integration registration, and lifecycle entry points.
- `managed` - owned installation trees, recovery receipts, selected IDE discovery, and endpoint effects.
- `release` - Python/Gradle release and SBOM utilities.

## Entry Points

- Gradle projects: `:distribution:contract`, `:distribution:cli`, `:distribution:managed`.
- Release tasks are applied from `release/plugin-release.gradle.kts`.
- `assembleControlRelease` and `assembleHostRelease` package their selected component independently; `assembleRelease` composes a fresh-install pair.

## Navigation Hints

- Start with the [repository knowledge](../openwiki/modules/distribution.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For configuration meaning, begin in `contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration`; for filesystem effects, follow admitted values into `managed`.
- For the public executable, begin in `cli/src/main/kotlin/io/github/amichne/kast/distribution/cli`.
- For packaged layout failures, pair this map with [packaging instructions](../packaging/AGENTS.md).
