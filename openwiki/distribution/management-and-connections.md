---
type: System Explanation
title: Native management and harness connections
description: Explains the public native command, passive status evidence, connection
  directory admission, registration ownership, and Codex plugins.
tags:
- kast
- distribution
code_sources:
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
- path: packaging/codex-mcp-registration.py
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagementCliTest.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfigurationContractTest.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallationTest.kt
sources:
  - id: openwiki-source-75e91bf709db8b5e164b1b31
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
  - id: openwiki-source-b6ffac113b6a09198425b019
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/GuidanceInstallation.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - id: openwiki-source-b30c7b16cf2f49971ca55950
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgrade.kt
  - id: openwiki-source-d3ccbe593611586a04efc776
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
  - id: openwiki-source-1d4cdf342ee112e0d9d87924
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementReceiptAdmission.kt
  - id: openwiki-source-927dce901b5dbac4a4d84f48
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
  - id: openwiki-source-656a2bd4859ff8a64d431829
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt
generated: { by: "codex", at: "2026-10-30T23:25:02.193Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
---

# Native management and harness connections

## The public command owner

`KastManagementMain` parses the public native `kast` command.
Its finite command model selects query dispatch, status, versions, guidance installation, lifecycle operations, upgrades, disconnect, or uninstall.
The operational JVM command graph and transport launchers remain private payloads.
Their internal command families do not add public semantic subcommands.

Passive status reads installed receipts and available runtime evidence.
The output separates installed and loaded identities, update policy, pending recovery, registrations, workspaces, and in-flight requests.
Unavailable evidence stays unavailable with its reason.
Status does not start Java, prepare a workspace, or contact a release server to manufacture readiness.

## Guidance installation and ownership

`kast install codex`, `kast install copilot`, and `kast install pi` install CLI guidance by default.
The default bundle contains no automatic MCP server or hooks. It does not prepare a Python environment.
The explicit `--mcp` option selects the complete optional transport composition and its dependency preparation.
The retired `connect` and `plugin` setup syntax directs callers to `kast install`.
Legacy connection variants remain available for receipt admission and exact cleanup.

Codex and Copilot own plugin installation, configuration, and removal through their native commands.
Kast admits the manifest-owned bundle, exact marketplace identity, selected composition, and discovered resource bytes.
Equal plugin versions alone do not prove that the cache contains the selected resources.
A repeated setup repairs a stale owned cache and admits an unchanged selected bundle as `ALREADY_INSTALLED`.
After success, Kast removes only exact receipt-owned legacy registrations.
Foreign registrations and conflicting plugin sources reject before replacement.

Pi receives the canonical skill through native skill discovery and the optional extension only for `--mcp`.
Its receipt records exact owned paths and digests. Changed owned files and reserved foreign paths reject replacement.
`kast install skill --output <parent>` exports the complete canonical skill directory without provider configuration.
An identical directory succeeds without replacement. Extra, changed, or symbolic-link entries reject.
A fresh agent session is necessary after setup.

## Selection and passive status

`kast versions` lists the stable selection and five newest selectable published main builds.
Upgrade requests refine into one typed release selection and update policy before artifact effects.
An unqualified upgrade keeps its recorded tracking policy and rejects a pinned policy before release access.
Pending recovery takes precedence over selector resolution and invokes only the manifest-owned recovery bridge.
The wrapper preserves the child terminal result and does not select another release after recovery.

Management receipts preserve installed build identity, update policy, registrations, and guidance ownership.
Pinned policy needs the same recorded build identity. Tracking policy admits older receipts without a recorded build.
Guidance entries need unique harnesses and file paths, normalized destinations, and exact SHA-256 syntax.
Passive status reads the selected IDEA and profile evidence without Host preparation or release access.
Unavailable evidence keeps its finite reason.

Fixture tests establish finite parsing, ownership, and recovery outcomes.
Native adapter qualification needs separate process and provider evidence.
These mechanisms alone do not establish that a running IDEA loaded the selected Host or answered semantic queries.

## Focused tests

- [ManagementCliTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagementCliTest.kt)
- [ConnectionConfigurationContractTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfigurationContractTest.kt)
- [PluginInstallationTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallationTest.kt)

## Source owners

- [KastManagementMain.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt)
- [ManagementCommand.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ManagementStatus.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt)
- [ConnectionConfiguration.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt)
- [PluginInstallation.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt)
- [codex-mcp-registration.py](../../packaging/codex-mcp-registration.py)
- [ConfigurationSchemaDocument.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt)

## Read next

- [Direct tool processes](../transport/direct-mcp-and-tool-rpc.md)
- [Codex hosts](../transport/codex-hosts-and-framing.md)
- [Guarded reset](upgrades-shutdown-and-reset.md)
