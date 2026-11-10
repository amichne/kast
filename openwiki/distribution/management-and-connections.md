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
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerDispatch.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerExecution.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerRuntime.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerPlan.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerWorkspace.kt
- path: packaging/oci_task.py
- path: packaging/composed_bundle.py
- path: packaging/container/Dockerfile
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
  - id: openwiki-source-96cd9ece37ebf3517499a8c3
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerDispatch.kt
  - id: openwiki-source-81b23626299785d4b6b3cdfc
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerExecution.kt
  - id: openwiki-source-7054083325bc329560cbbed1
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerPlan.kt
  - id: openwiki-source-0e50d1b18d1925b709ede5d3
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerRuntime.kt
  - id: openwiki-source-53bfd9d570ccfafcba1fca7b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerWorkspace.kt
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
  - id: openwiki-source-e1eccbe769b8e78a5354c5ca
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementExecution.kt
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
  - id: openwiki-source-ef7a40843a617c29102e725f
    resource: repo://packaging/composition_platform.py
  - id: openwiki-source-47d417bba7f934fb0315bd88
    resource: repo://packaging/container/Dockerfile
  - id: openwiki-source-92f253dc3dcfce3b1698c227
    resource: repo://packaging/dev_container.py
  - id: openwiki-source-621afc296584eb4217553339
    resource: repo://packaging/managed_headless.py
  - id: openwiki-source-1380068036b00bec817a6579
    resource: repo://packaging/managed_installation.py
  - id: openwiki-source-438eaae27874148a8609a1fb
    resource: repo://packaging/oci_task.py
generated: { by: "codex", at: "2026-11-10T17:49:52.067Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-11-10T17:49:52.067Z
---

# Native management and harness connections

## The public command owner

`KastManagementMain` parses the public native `kast` command.
Its finite command model selects query dispatch, container tasks, status, versions, guidance installation, lifecycle operations, upgrades, disconnect, or uninstall.
The `cli` module composes management and the private transport entry points in one native executable.
Thin private launchers select their existing command and lifecycle owners without a JVM.
Their internal command families do not add public semantic commands.

Passive status reads installed receipts and available runtime evidence.
The output separates installed and loaded identities, update policy, pending recovery, registrations, workspaces, and in-flight requests.
Unavailable evidence stays unavailable with its reason.
Status does not start Java, prepare a workspace, or contact a release server to manufacture readiness.

## Native container tasks

`kast container` opens a shell in the current Git worktree.
`kast container run -- COMMAND [ARGS]` runs a task with literal arguments.
`--workspace` selects another worktree. `--name` selects private runtime state and caches for that worktree.
The native command dispatches before installed-root admission and needs no selected Kast installation.

The supported host is native ARM64 or x86_64 macOS with Colima and its Lima dependency.
Normal use needs no host Docker CLI, uv, Python, Java, or Git executable.
Colima's bundled `nerdctl` manages containerd in the `kast-native` profile.
Task transport uses Lima and `ctr` to preserve the guest exit status.
The prepared image supplies the guest tools and self-contained IDEA runtime.

The worktree and linked Git metadata keep the same absolute paths inside the container.
Kast discovers these paths from Git metadata files without a host Git process.
Writable mounts expose edits and build outputs directly in the local worktree.
The retained container keeps its IDEA profile and indexes.
A named volume at `/opt/kast/state/home/.gradle` keeps Gradle dependencies, wrapper distributions, and enabled build-cache entries.
IDEA imports and shell builds share that volume.

The canonical worktree, name, and architecture select the container and Gradle volume.
Different names keep separate runtime state and caches but share the worktree's source files and build outputs.
The host Gradle cache is not mounted. Cache reuse alone does not establish a startup or build-time improvement.

Image admission needs Linux, the native architecture, the `developer` user, and the `io.kast.dev-container=3` label.
`--image` accepts a local SHA-256 identifier or a registry reference with an immutable digest.
The first unqualified call selects local `kast-dev:arm64` or `kast-dev:amd64`.
Later unqualified calls keep the retained container's image.
Normal use never builds an image. Source builds use the separate checkout builder.

Each invocation admits the retained identity, image, mounts, user, and network before task dispatch.
The guest helper uses `--reuse` to admit only the exact selected workspace trust grant.
Conflicting trust or retained inputs reject before the task starts.
`--offline` selects a container without network access. A retained container needs the same network selection.
`kast container status` reads existing state without a VM or container start.

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
- [ContainerCommandTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ContainerCommandTest.kt)
- [ContainerPlanTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ContainerPlanTest.kt)
- [ContainerRuntimeTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ContainerRuntimeTest.kt)
- [test_composition_oci_task.py](../../packaging/test_composition_oci_task.py)

## Source owners

- [KastManagementMain.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt)
- [ManagementCommand.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ContainerExecution.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerExecution.kt)
- [ContainerRuntime.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerRuntime.kt)
- [ContainerPlan.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerPlan.kt)
- [ContainerWorkspace.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerWorkspace.kt)
- [ManagementStatus.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt)
- [ConnectionConfiguration.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt)
- [PluginInstallation.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt)
- [`codex-mcp-registration.py`](../../packaging/codex-mcp-registration.py)
- [ConfigurationSchemaDocument.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt)

## Read next

- [Direct tool processes](../transport/direct-mcp-and-tool-rpc.md)
- [Codex hosts](../transport/codex-hosts-and-framing.md)
- [Guarded reset](upgrades-shutdown-and-reset.md)
