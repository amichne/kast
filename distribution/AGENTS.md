---
type: Module Guide
title: "Kast distribution"
description: "Defines installation contracts, the public native management executable, managed filesystem effects, and independent component releases."
resource: "repo://distribution"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
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
<!-- generated: 2026-10-14 | hash: 130861782e6b -->

# distribution

## Purpose

Defines installation contracts, the public native management executable, managed filesystem effects, and independent component releases.

## Key Files

- [ConfigurationSchemaDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt) - typed installed configuration.
- [ManagedInstallationOwnedTree.kt](managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt) - the admitted installation tree.
- [KastManagementMain.kt](cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt) - the public native process entry.
- [plugin-release.gradle.kts](release/plugin-release.gradle.kts) - Control and Host release tasks.
- [component_release.py](release/component_release.py) - component asset records and checksum admission.

## Subdirectories

- [contract/](contract/) - configuration, bootstrap, transport, and runtime identities.
- [cli/](cli/AGENTS.md) - the public command and authored lifecycle rules.
- [managed/](managed/) - installation trees, recovery receipts, IDE discovery, and endpoint effects.
- [release/](release/) - release records, product gates, SBOMs, and archive assembly.

## Entry Points

- Gradle projects: `:distribution:contract`, `:distribution:cli`, and `:distribution:managed`.
- `assembleControlRelease` and `assembleHostRelease` package separate components. `assembleRelease` makes a fresh pair.

## Navigation Hints

- For public command behavior, read the nested native CLI guide.
- For configuration meaning, start in `contract`. For filesystem effects, trace admitted values into `managed`.
- For archive or installer failures, continue into the packaging guide.
- Component versions are independent. Hosted compatibility uses exact contract identities and platform admission.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
