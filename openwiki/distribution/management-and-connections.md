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
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementExecution.kt
- path: packaging/composed_bundle.py
- path: packaging/isolated_runtime.py
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
- path: packaging/codex-mcp-registration.py
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagementCliTest.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfigurationContractTest.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallationTest.kt
sources:
  - id: openwiki-source-20e85ee434d9dedec13e85f7
    resource: repo://build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - id: openwiki-source-19c1ebaf58581a8fc0c5c10a
    resource: repo://cli/build.gradle.kts
  - id: openwiki-source-f2c689f11ca37c5559bce314
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/KastNativeMain.kt
  - id: openwiki-source-fb61cc58d2fa4809ff4d73bb
    resource: repo://cli/src/main/native-launchers/bin/kast-mcp
  - id: openwiki-source-75e91bf709db8b5e164b1b31
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
  - id: openwiki-source-395b154f68f83030a7178a3d
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerCommands.kt
  - id: openwiki-source-f05f898b365cdf39b2dad20e
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerManagement.kt
  - id: openwiki-source-fb0010731e53bb0391a312a7
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerQueryEntry.kt
  - id: openwiki-source-a3d400e2149c937d7b2dafff
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerQueryLease.kt
  - id: openwiki-source-167d1a669da1a6f9cf901888
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerQueryRouting.kt
  - id: openwiki-source-dc8df2b070b8c6ba7da7a8f9
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerStore.kt
  - id: openwiki-source-b6ffac113b6a09198425b019
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/GuidanceInstallation.kt
  - id: openwiki-source-4ea7092ecf62f475b11026f1
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstalledGuidanceRefresh.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - id: openwiki-source-b30c7b16cf2f49971ca55950
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgrade.kt
  - id: openwiki-source-d3ccbe593611586a04efc776
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
  - id: openwiki-source-1d4cdf342ee112e0d9d87924
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementReceiptAdmission.kt
  - id: openwiki-source-552a7d3faff1f8e990a51c96
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PiGuidanceInstallation.kt
  - id: openwiki-source-2353a5c8f9b33ce9b982a841
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PiSkillReplacement.kt
  - id: openwiki-source-927dce901b5dbac4a4d84f48
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
  - id: openwiki-source-656a2bd4859ff8a64d431829
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt
  - id: openwiki-source-d95d75ec0e14dda7faf63dbf
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgradeSelectionFileTest.kt
  - id: openwiki-source-03ffc32a0ca502ab67c54b25
    resource: repo://install.sh
  - id: openwiki-source-d53edde1666c585216ff3c8d
    resource: repo://packaging/composed_bundle.py
  - id: openwiki-source-8e1c0e02a6021c5adc87ccac
    resource: repo://packaging/isolated_runtime.py
  - id: openwiki-source-1380068036b00bec817a6579
    resource: repo://packaging/managed_installation.py
generated: { by: "pi", at: "2026-11-11T11:08:36.331Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-11-11T11:08:36.331Z
---

# Native management and harness connections

## The public command owner

`KastManagementMain` parses the public native `kast` command.
Its finite command model selects query dispatch, workspace containers, status, versions, guidance installation, lifecycle operations, upgrades, disconnect, or uninstall.
The `cli` module composes management and the private transport entry points in one native executable.
Thin private launchers select their existing command and lifecycle owners without a JVM.
Their internal command families do not add public semantic commands.

Passive status reads installed receipts and available runtime evidence.
The output separates installed and loaded identities, update policy, pending recovery, registrations, workspaces, and in-flight requests.
Unavailable evidence stays unavailable with its reason.
Status does not start Java, prepare a workspace, or contact a release server to manufacture readiness.

## Private workspace commands

`kast container` lists workspace containers. `create`, `resume`, `stop`, and `rm` manage their private Hosts.
Creation selects `--root` or the current directory and grants trust to that exact workspace.
Overlapping workspace roots reject before runtime acquisition.
Each record has private settings, indexes, logs, and a Gradle home; only runtime bytes are shared.
An explicit `--gradle-home` selects an existing external directory instead.
The store is the `kast-workspaces` sibling of the Control management root, outside its replaceable installation.

Queries select a record from their current directory and prepare its Host on demand.
They never create records or grant trust.
Request-only `query plan` bypasses that routing and preparation.
Stop writes a sticky fence before shutdown admission; an active query returns `BUSY` without lifting that fence.
Resume removes the fence and attempts preparation.
Parent and query-child reader leases protect private state until query completion, without holding the registry lock through semantic execution.

`rm` requires the record's ID, stops the Host, and requires idle reader admission.
It atomically detaches the record before removing private state without following links.
The repository, shared runtime, and explicit external Gradle home remain outside that removal scope.
These are process and profile boundaries, not an OS sandbox.

The older archive entry `R/bin/kast-task WORKSPACE -- COMMAND [ARGS...]` grants or reuses trust for one exact workspace in a private installation.
A conflicting grant rejects before command execution.
The launcher starts the command in that workspace with the private environment and preserves its arguments and exit status.
Ordinary queries do not grant workspace trust.

## Guidance installation and ownership

`kast install codex`, `kast install copilot`, and `kast install pi` install CLI guidance by default.
The default bundle includes CLI guidance and preparation hooks without an automatic MCP server.
It does not prepare a Python environment.
The explicit `--mcp` option selects the complete optional transport composition and its dependency preparation.
The retired `connect` and `plugin` setup syntax directs callers to `kast install`.
Legacy connection variants remain available for receipt admission and exact cleanup.

Codex and Copilot own plugin installation, configuration, and removal through their native commands.
Kast admits the manifest-owned bundle, exact marketplace identity, selected composition, and discovered resource bytes.
Equal plugin versions alone do not prove that the cache contains the selected resources.
A repeated setup repairs a stale owned cache and admits an unchanged selected bundle as `ALREADY_INSTALLED`.
After success, Kast removes only exact receipt-owned legacy registrations.
Foreign registrations and conflicting plugin sources reject before replacement.

Pi receives the canonical skill through native skill discovery.
CLI-only composition installs its preparation extension. Explicit MCP composition selects the complete MCP extension.
The Pi receipt selects the owned skill directory.
Kast replaces that directory as a unit and rejects symbolic links.
Foreign paths outside that directory remain protected.
After the binary replacement commits, the installer refreshes admitted provider plugins and recorded Pi guidance.
`kast install skill --output <parent>` exports the complete canonical skill directory without provider configuration.
An identical directory succeeds without replacement. Extra, changed, or symbolic-link entries reject.
A fresh agent session is necessary after setup.

## Selection and passive status

`kast versions` lists the stable selection and five newest selectable published main builds.
Upgrade requests refine into one typed release selection and update policy before artifact effects.
The wrapper writes the captured selection in a physical temporary directory before invoking the installer bridge.
An aliased temporary-directory setting therefore still produces a physical record path.
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
- [ManagedUpgradeSelectionFileTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgradeSelectionFileTest.kt)

## Source owners

- [`isolated_runtime.py`](../../packaging/isolated_runtime.py)

- [KastManagementMain.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt)
- [ManagementCommand.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ManagementStatus.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt)
- [ConnectionConfiguration.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt)
- [PluginInstallation.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt)
- [`codex-mcp-registration.py`](../../packaging/codex-mcp-registration.py)
- [ConfigurationSchemaDocument.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt)

## Read next

- [Direct tool processes](../transport/direct-mcp-and-tool-rpc.md)
- [Codex hosts](../transport/codex-hosts-and-framing.md)
- [Guarded reset](upgrades-shutdown-and-reset.md)
