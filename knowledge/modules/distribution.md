---
type: Kotlin Module Group
title: Distribution and packaging
description: Typed configuration and runtime identity contracts constrain managed installation effects, release assembly, and acceptance harnesses.
resource: file://distribution
tags: [distribution, configuration, packaging, release]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
    symbols: [ConfigurationSchemaDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/InstalledConfigurationSchema.kt
    symbols: [InstalledConfigurationSchema]
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
    symbols: [ManagedInstallationOwnedTree]
  - path: packaging/configuration-schema.json
  - path: install.sh
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
    symbols: [InstallationWorkflow, executeInstallationChild]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
    symbols: [InstallationRequest, AppServerTools]
---

# Distribution and packaging

Distribution contracts own configuration keys, defaults, owners, operational limits, network policy, runtime identity, and bootstrap outcomes. Managed adapters materialize only admitted archives, endpoints, trust stores, and owned installation trees.

Root and packaging scripts orchestrate checkout installation, persistent lifecycle, release layout, and acceptance. Stable releases and local checkout installations include a hosted-plugin ZIP named for the IDEA release line (`idea-262.zip`). Local installation builds the control product, semantic runtime and matching hosted plugin before staging their checksums. The public installer verifies its checksum, release version, plugin identity, and descriptor release line before atomically replacing only the Kast directory under IDEA's user plugin root; dry-run validates the archive without writing plugin state. The checked [configuration schema](../../packaging/configuration-schema.json) is a public boundary and must remain aligned with the Kotlin catalogue.

Installation child processes emit `kast_installation` records by default with a closed stage and outcome. Prior admission, retirement, configuration validation, command qualification and App Server enablement retain distinct success, nonzero exit, deadline, I/O and interruption observations. Child admission remains authoritative; these records do not contain command arguments, environment values or filesystem paths.

The staged Kotlin installer selects default tools directly from the canonical agent catalog. The shell bootstrap preserves an explicit selection and supplies no copied default list. Explicit selections must contain current, unique tool names; installation retains their admitted definitions in canonical catalog order before writing configuration.

Read [configuration](../contracts/configuration.md) for ingress and ownership rules.

`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.
