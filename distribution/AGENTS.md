---
type: Module Guide
title: "Kast distribution"
description: "Defines installation contracts, the management command graph, managed filesystem effects, and independent component releases."
resource: "repo://distribution"
tags: [kast, navigation]
timestamp: "2026-11-10T06:13:00Z"
source_revision: 85d30d515a1d777c5a83781f40813e32e69fa2e6
code_sources:
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedHeadlessInstallation.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedHeadlessLaunch.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/KastConfigurationCatalogue.kt
  - path: distribution/cli/build.gradle.kts
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: distribution/contract/build.gradle.kts
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
  - path: distribution/managed/build.gradle.kts
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
  - path: distribution/release/component_release.py
  - path: distribution/release/plugin-release.gradle.kts
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-11-06 | hash: f65ee7251f7d -->

# distribution

## Purpose

Defines installation contracts, the management command graph, managed filesystem effects, and independent component releases.

## Key Files

- [ConfigurationSchemaDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt) - typed installed configuration.
- [ManagedInstallationOwnedTree.kt](managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt) - the admitted installation tree.
- `cli` composes the single native Control executable. This module supplies management commands.
- [KastManagementMain.kt](cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt) - the management process entry.
- [`plugin-release.gradle.kts`](release/plugin-release.gradle.kts) - Control and Host release tasks.
- [component_release.py](release/component_release.py) - component asset records and checksum admission.

## Subdirectories

- [contract/](contract/) - configuration, bootstrap, transport, and runtime identities.
- [cli/](cli/AGENTS.md) - the public command and authored lifecycle rules.
- [managed/](managed/) - installation trees, recovery receipts, IDE discovery, and endpoint effects.
- [release/](release/) - release records, product gates, SBOM files, and archive assembly.

## Entry Points

- Gradle projects: `:distribution:contract`, `:distribution:cli`, and `:distribution:managed`.
- `assembleControlRelease` and `assembleHostRelease` package separate components. `assembleRelease` makes a fresh pair.

## Navigation Hints

- For public command behavior, read the nested native CLI guide.
- For configuration meaning, start in `contract`. For filesystem effects, trace admitted values into `managed`.
- `KAST_MANAGED_HEADLESS_STATE` selects private state through the configuration contract.
- `ManagedHeadlessInstallation` admits the selected runtime, private paths, bootstrap digest, and bundled JBR.
- `ManagedHeadlessLaunch` owns process arguments and environment. Installation admission grants no project trust or workspace readiness.
- For archive or installer failures, continue into the packaging guide.
- Component versions are independent. Hosted compatibility uses exact contract identities and platform admission.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
