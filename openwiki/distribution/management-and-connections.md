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
  - id: openwiki-source-5e92c81a46b459b558f8901e
    resource: repo://.github/scripts/release/product_candidate.py
  - id: openwiki-source-e61ce41a823368fa4e25eb84
    resource: repo://.github/scripts/release/product_producer.py
  - id: openwiki-source-f02c30e87f3db4d071a7be5b
    resource: repo://.github/scripts/release/publish_developer.py
  - id: openwiki-source-164e2da859b5277df81c7d94
    resource: repo://.github/workflows/ci.yml
  - id: openwiki-source-19c1ebaf58581a8fc0c5c10a
    resource: repo://cli/build.gradle.kts
  - id: openwiki-source-f2c689f11ca37c5559bce314
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/KastNativeMain.kt
  - id: openwiki-source-e1bc625b6dcbcb48924bf9b4
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHook.kt
  - id: openwiki-source-fb61cc58d2fa4809ff4d73bb
    resource: repo://cli/src/main/native-launchers/bin/kast-mcp
  - id: openwiki-source-75e91bf709db8b5e164b1b31
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
  - id: openwiki-source-97c3915cf78feeb35ecec04a
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerProgress.kt
  - id: openwiki-source-22e272ee16fcff0c22f636ab
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerStateAdmission.kt
  - id: openwiki-source-5640c4be5bb8760f0f77a90d
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/CopilotPluginInstallation.kt
  - id: openwiki-source-b6ffac113b6a09198425b019
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/GuidanceInstallation.kt
  - id: openwiki-source-fe95fd24c4ce348de98bb357
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/HarnessUninstall.kt
  - id: openwiki-source-4ea7092ecf62f475b11026f1
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstalledGuidanceRefresh.kt
  - id: openwiki-source-6ead538479055040a6fa54d0
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstalledProductRuntime.kt
  - id: openwiki-source-cfc838a1a4168e12e43d463d
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/IntegrationDisconnection.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - id: openwiki-source-b30c7b16cf2f49971ca55950
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgrade.kt
  - id: openwiki-source-8631d89978b8c4768c0c151b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceLifecycle.kt
  - id: openwiki-source-ecb21af09f4a49f9e40342dd
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
  - id: openwiki-source-7cea1ff036129ab5e1cebfe3
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRemoval.kt
  - id: openwiki-source-1d4cdf342ee112e0d9d87924
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementReceiptAdmission.kt
  - id: openwiki-source-a62e12ff14ead337deede61b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
  - id: openwiki-source-552a7d3faff1f8e990a51c96
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PiGuidanceInstallation.kt
  - id: openwiki-source-2353a5c8f9b33ce9b982a841
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PiSkillReplacement.kt
  - id: openwiki-source-927dce901b5dbac4a4d84f48
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
  - id: openwiki-source-55d373ae663a2c299fafa8fe
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ProductManagementReceipt.kt
  - id: openwiki-source-7391bd7fab188bde9fd1c847
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ProviderRegistration.kt
  - id: openwiki-source-dfb88490277ececa651be1f8
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicInstallationReset.kt
  - id: openwiki-source-e62300b4b70eef650b59f1db
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RejectedWorkspaceRemoval.kt
  - id: openwiki-source-656a2bd4859ff8a64d431829
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt
  - id: openwiki-source-d9ace9189c53b7c82c5ed60c
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/WorkspaceReclamation.kt
  - id: openwiki-source-d95d75ec0e14dda7faf63dbf
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgradeSelectionFileTest.kt
  - id: openwiki-source-245e3f9878cb2ef01a1fcbd1
    resource: repo://packaging/component_probe.py
  - id: openwiki-source-3af59f4285e4c5ca70150126
    resource: repo://packaging/composed_host_probe.py
  - id: openwiki-source-e08b876ca00219319cbb81aa
    resource: repo://packaging/composed_release.py
  - id: openwiki-source-611b757652f80d3a33875855
    resource: repo://packaging/container_probe.py
  - id: openwiki-source-1380068036b00bec817a6579
    resource: repo://packaging/managed_installation.py
  - id: openwiki-source-7f5792fd36f508c6577b3562
    resource: repo://packaging/managed_product_installation.py
  - id: openwiki-source-e7484ccb9a72cd71aa938a2f
    resource: repo://packaging/product_acceptance.py
  - id: openwiki-source-72a2e0c4bf1e54eeb4c8db6c
    resource: repo://packaging/product_lifecycle_probe.py
generated: { by: "amp", at: "2026-11-13T00:20:29.143Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-13T03:20:11.225Z
---

# Native management and harness connections

## The public command owner

`KastManagementMain` parses the public native `kast` command.
Its six management verbs are `install`, `status`, `stop`, `versions`, `upgrade`, and `uninstall`.
Semantic command groups dispatch separately. Public container, resume, disconnect, and reinstall commands are absent.
The `cli` module composes management and the private transport entry points in one native executable.
Thin private launchers select their existing command and lifecycle owners without a JVM.
Their internal command families do not add public semantic commands.

Passive status reads installed receipts and available runtime evidence.
The output separates installed and loaded identities, update policy, pending recovery, registrations, workspaces, and in-flight requests.
Unavailable evidence stays unavailable with its reason.
Status does not start Java, prepare a workspace, or contact a release server to manufacture readiness.

## Managed workspace admission

`ManagedWorkspaceQuery` admits the installed product locally for each explicit semantic request.
The first preparation request creates a trust grant for its exact `CanonicalRoot` and private Host state.
Overlapping roots reject. Retained reads require an existing record; discovery and passive hooks do not grant trust.
The `kast-workspaces` sibling of the Control management root stores profiles outside the replaceable installation.
Each generation has private settings, indexes, logs, HOME, and default Gradle state.
Saved configuration can select an existing external Gradle home. Inherited credentials remain available; this is not an OS sandbox.

Durable record admission preserves the configured Gradle path even if that directory disappears.
Launch binding checks its current availability and physical identity, returning `INVALID_GRADLE_HOME` when those facts cannot be established.
An ordinary user-owned `.DS_Store` does not count as a workspace entry; linked or foreign entries still reject admission.
Global lifecycle rejection names the workspace entry and its finite cause.
Explicit uninstall can remove an inactive rejected record under its exclusive lease after private-state and mutation checks.
That recovery does not permit retirement of an unproven live Host or deletion outside the private profile.

A request owns its reader lease, rather than an entire MCP or RPC session.
Installed product identity is rechecked before generation publication and binding.
Queries neither acquire release assets nor fall back to a desktop installation.
`stop` selects the current workspace, an explicit workspace, or all recorded workspaces.
It holds exclusive idle leases while retiring selected Hosts, but writes no persistent stop fence.
The next explicit semantic request can start them again.

Admission opportunistically reclaims vanished-root profiles only after exact generation retirement and private evidence checks.
An exterior `.removing-ID.json` receipt survives interrupted deletion.
Unreadable cleanup candidates produce bounded rejection evidence rather than failing an unrelated semantic request.
See [retirement and recovery](upgrades-shutdown-and-reset.md) for the deletion boundary and remaining qualification gaps.

## Guidance installation and ownership

`kast install codex`, `kast install copilot`, and `kast install pi` install CLI guidance by default.
The default bundle includes CLI guidance and passive session hooks without an automatic MCP server.
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
CLI-only composition installs its context extension. Explicit MCP composition selects the complete MCP extension.
The Pi receipt selects the owned skill directory.
Kast replaces that directory as a unit and rejects symbolic links.
Foreign paths outside that directory remain protected.
Installer completion refreshes admitted provider plugins and recorded Pi guidance for both legacy and managed product results.
`ProductInstalled` carries notices and enters the same capability-marked refresh branch.
A failed or interrupted refresh preserves `HARNESS_REFRESH_REQUIRED`; successful refresh clears that notice without discarding other notices.
These source paths do not establish live provider refresh success.
`kast install skill --output <parent>` exports the complete canonical skill directory without provider configuration.
An identical directory succeeds without replacement. Extra, changed, or symbolic-link entries reject.
A fresh agent session is necessary after setup.

## Selection and passive status

`kast versions` lists the stable selection and five newest selectable published main builds.
Upgrade requests refine into one typed release selection and update policy before artifact effects.
Preview and stable select complete product records, not independently mixed Control and Host versions.
Exact repair reads the recorded product selection even when runtime bytes need repair; ordinary requests additionally require an admitted runtime.
The wrapper writes the captured selection in a physical temporary directory before invoking the installer bridge.
An aliased temporary-directory setting therefore still produces a physical record path.
An unqualified upgrade keeps its recorded tracking policy and rejects a pinned policy before release access.
Pending recovery takes precedence over selector resolution and invokes only the manifest-owned recovery bridge.
The wrapper preserves the child terminal result and does not select another release after recovery.

The parser admits `uninstall --force [--json]` for legacy state only, without a harness selector.
Under an exterior workspace lifetime lease, either managed store or `product-selection.json` rejects force detachment with `MANAGED_STATE_REQUIRES_UNINSTALL`.
Managed products use ordinary uninstall with its recovery journal. It removes receipted Pi guidance while preserving foreign siblings.
`ProviderRegistration` records only CODEX/COPILOT routing intent before provider mutation; current provider inventories still prove ownership.
Ordinary uninstall removes those recorded provider resources before Host retirement and rejects conflicting resources without retiring the Host.
It rechecks for new registrations under the registration lock and holds that lock through payload removal.
Explicit harness removal forgets only the selected routing record after verified cleanup.
Kotlin schema-2/schema-3 receipts and Python replacement preserve these records.

Qualification separates component execution from installed-product acceptance.
`composed_release._qualify_release` uses a test-native component harness, not retired public standalone or container commands.
The producer seals both component-qualified targets into one immutable build record before installed-product acceptance.
Acceptance receipts remain detached from that record. Publication admission requires both receipts and preserves the sealed bytes.
The acceptance baseline needs sealed component qualification, not prior publication or installed acceptance.
It can share the candidate's source revision, but each target needs a different actual bundle/runtime/Control composition.
Exact repair cannot stand in for upgrade evidence. CI admits the baseline's exact retained record and successful sealing job before the native acceptance matrix.
This staged source contract replaces the earlier circular setup route, but no successful native Host acceptance is established here.
See [distribution qualification](../modules/distribution.md) for the stage owners and evidence limits.

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
