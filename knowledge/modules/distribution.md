---
type: Kotlin Module Group
title: Distribution and packaging
description: Typed configuration and runtime identity contracts constrain managed installation effects, release assembly, and acceptance harnesses.
resource: file://distribution
tags: [distribution, configuration, packaging, release]
timestamp: 2026-09-14T00:00:00Z
code_sources:
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
    symbols: [ControlDistributionLimits]
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
    symbols: [ConfigurationSchemaDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/InstalledConfigurationSchema.kt
    symbols: [InstalledConfigurationSchema]
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
    symbols: [ManagedInstallationOwnedTree]
  - path: packaging/configuration-schema.json
  - path: packaging/installation-recovery.py
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/InstallationRecoveryReceipt.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ControlPayloadInventory.kt
  - path: install.sh
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
    symbols: [InstallationWorkflow, executeInstallationChild]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
    symbols: [InstallationRequest, AppServerTools]
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateControlMetadataTask.kt
  - path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - path: distribution/release/plugin-release.gradle.kts
  - path: packaging/test-installed-product.sh
  - path: .github/scripts/release/build-assets.sh
  - path: .github/scripts/release/publish-release.sh
---

# Distribution and packaging

Distribution contracts own configuration keys, defaults, owners, operational limits, network policy, runtime identity, and bootstrap outcomes. Managed adapters materialize only admitted archives, endpoints, trust stores, and owned installation trees.

Root and packaging scripts orchestrate checkout installation, persistent lifecycle, release layout, and acceptance. Stable releases and local checkout installations include a hosted-plugin ZIP named for the IDEA release line (`idea-262.zip`). Local installation builds the control product and matching hosted plugin before staging their checksums. The public installer verifies its checksum, release version, plugin identity, and descriptor release line before atomically replacing only the Kast directory under IDEA's user plugin root; dry-run validates the archive without writing plugin state. The checked [configuration schema](../../packaging/configuration-schema.json) is a public boundary and must remain aligned with the Kotlin catalogue.

Installation child processes emit `kast_installation` records by default with a closed stage and outcome. Prior admission, retirement, configuration validation, command qualification and App Server enablement retain distinct success, nonzero exit, deadline, I/O and interruption observations. Child admission remains authoritative; these records do not contain command arguments, environment values or filesystem paths.

`ControlDistributionLimits` owns the maximum verified control-product entry count and manifest size used by staged installation and runtime identity admission. The shell bootstrap, installed lifecycle, build verifier, and Kotlin owner are checked for the same entry limit, and release layout verification rejects a product outside that bound before publication. Upgrade admission uses the new, checksum-verified lifecycle implementation to inspect the prior installation, so a valid older product cannot block its own replacement because its validator predates the current product bounds. This retains the resource limits while preventing copied limits from drifting below the product that the build produced.

`ControlPayloadInventory` counts paths globally, including the three payload roots, before sorting or hashing. Installer and broker use that same admission; `verifyReleaseRuntimeAdmission` exercises the runtime identity owner on the actual staged control product. Limit diagnostics retain the resource and observed lower bound. The hosted-plugin ZIP has a separate archive budget.

Before activation, `prepareInstallationRecovery` saves a typed receipt and an offline Python bundle outside the payload. `installation-recovery.py detach` fences launches, detaches matching command links and receipted plugin directories, and retains previous plugin backups. It executes retirement only after full payload admission. Verified retired state is quarantined; uncertain processes and journals remain preserved and produce `DetachedWithUnresolvedState`. A missing plugin ownership witness cannot produce a clean result. The standalone `prepare` operation supports older installations without requiring their executable to run. Python and JVM installation transitions use compatible POSIX record locks.

The staged Kotlin installer selects default tools directly from the canonical agent catalog. The shell bootstrap preserves an explicit selection and supplies no copied default list. Explicit selections must contain current, unique tool names; installation retains their admitted definitions in canonical catalog order before writing configuration.

Read [configuration](../contracts/configuration.md) for ingress and ownership rules.

`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.

`assembleRelease` ships exactly the control tarball and matching hosted-plugin ZIP with checksums. `GenerateControlMetadataTask` derives `ide-host.json` from the actual plugin bytes and build identities; installation verifies the exact name, version, length and digest. No semantic-runtime manifest, isolated indexer payload or topology store is shipped. Installation manifests use schema 2 and `hostedPluginSha256`; lifecycle inspection still admits historical schema-1 records so old owned installations can be retired safely.

Release asset construction, checksums, SBOM inventory, and publication agree on the control and plugin pair. Native acceptance retains private homes and owned processes without creating isolated runtime caches or worker payload stores.

The [installed knowledge contract](../contracts/installed-knowledge.md) describes
`kast knowledge`, its isolated PSI extraction, verified module ownership and
scoped guide resources staged with the control product.
