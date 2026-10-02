---
type: Kotlin Module Group
title: Distribution and packaging
description: Typed configuration and runtime identity contracts constrain managed installation effects, release assembly, and acceptance harnesses.
resource: file://distribution
tags: [distribution, configuration, packaging, release]
code_sources:
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
    symbols: [HostedQueryProgress]
  - path: runtime/hosted/native/README.md
  - path: runtime/hosted/native/mixed_version_acceptance.py
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
    symbols: [PluginInstallOutcome, PluginInstallFailure, installCodexPlugin]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginBundle.kt
    symbols: [AdmittedPluginBundle]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/IntegrationRegistration.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RegistrationPayload.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementLifecycle.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
    symbols: [ForceResetRoot]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetExecution.kt
    symbols: [forceResetInstallation, ForceResetExecution]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
    symbols: [FencedReset, QuiescentReset, ErasedReset, StagedReset, ActiveReset]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ReadyReset.kt
    symbols: [ReadyReset]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ResetProcessObservation.kt
    symbols: [ResetProcessObservation]
  - path: distribution/contract/src/main/resources/management/reset-process-observation.schema.json
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetFence.kt
    symbols: [ForceResetFence, RetainedResetStorage]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceDaemonRuntime.kt
    symbols: [ForceDaemonRetirement, ResetScopedProcess]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/NativeForceDaemonRuntime.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/FreshResetInstallation.kt
    symbols: [FreshResetInstallation]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RuntimeServiceGeneration.kt
    symbols: [RuntimeServiceGeneration]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ResetLoginEntries.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/InstallationResetFence.kt
    symbols: [InstallationResetRequest, InstallationResetStorage, installationResetFence]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceReinstallation.kt
    symbols: [installFresh, ForceReinstaller]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetOutcome.kt
    symbols: [ForceResetOutcome]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementShutdown.kt
    symbols: [executeInstallationLifecycle, LifecycleExecution]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationLifecycle.kt
    symbols: [InstallationLifecycle]
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationRequestRetirement.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/LifecycleProcesses.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/LifecycleOutcome.kt
    symbols: [LifecycleOutcome, LifecycleObservation]
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/InstallationShutdownFence.kt
  - path: distribution/contract/src/main/resources/management/management-lifecycle.schema.json
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/InstalledToolInvocation.kt
  - path: distribution/contract/src/main/resources/management/installation-reset.schema.json
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ResetActivationAdmission.kt
    symbols: [ResetActivationAdmission, ResetActivationLease]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstallationLifecycleFence.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/InstalledToolAdmission.kt
  - path: copilot/extension.mjs
  - path: pi/extension.ts
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PassiveRuntimeObservation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/OneShotObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/McpWorkspaceOperationClient.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpSingleChangeTool.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationTrustEnrollment.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/PackagedProviderCatalog.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/ControlDistributionAdmissionMain.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SelectedIdeInstallation.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/PriorInstallationPreparation.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationChild.kt
    symbols: [executeInstallationChild]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PriorInstallationReplacement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PriorRetirement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRegistryObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledEpochRetention.kt
    symbols: [InstalledEpochRetention, InstalledEpochRetentionFailure]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerInstallationState.kt
    symbols: [BrokerInstallationState]
  - path: packaging/run-portable-tests.py
  - path: packaging/run-portable-tests-container.sh
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
    symbols: [ControlDistributionLimits]
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
    symbols: [ConfigurationSchemaDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/InstalledConfigurationSchema.kt
    symbols: [InstalledConfigurationSchema]
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
    symbols: [ManagedInstallationOwnedTree]
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/InstallationReplacementReceipt.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/InstallationSnapshot.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedCopiedEpoch.kt
  - path: packaging/configuration-schema.json
  - path: packaging/installation-lifecycle.py
  - path: packaging/installation-recovery.py
  - path: packaging/prune-prior-installations.py
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/InstallationRecoveryReceipt.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/RecoveryPlugin.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ControlPayloadInventory.kt
  - path: install.sh
  - path: packaging/codex-mcp-registration.py
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/OneShotInvocationRecord.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/InstallationReplacementReceipt.kt
    symbols: [InstallationReplacementReceipt, PreviousInstallationPayload, InstallationReplacementStage]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PendingInstallationReplacement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
    symbols: [InstallationWorkflow]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationActivation.kt
    symbols: [InstallationActivation, InstallationActivationPolicy]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationCliInspection.kt
    symbols: [InstallationCliInspection]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationManifest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationOutcome.kt
    symbols: [InstallationFailure, InstallationOutcome]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
    symbols: [ControlInstallRequest, ControlPayload, InstallationProfile]
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateControlMetadataTask.kt
  - path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - path: distribution/release/resolve_version.py
    symbols: [observe_releases, published_versions]
  - path: distribution/release/run_product_gate.py
    symbols: [latest_version, run_gate]
  - path: .github/scripts/ci/routine_gate.py
  - path: .github/scripts/ci/validation_scope.py
  - path: .github/scripts/ci/required_checks.py
  - path: .github/scripts/ci/verify-checks.py
  - path: .githooks/pre-push
  - path: distribution/release/plugin-release.gradle.kts
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
    symbols: [GenerateAgentToolsTask]
  - path: agent-tools/.codex-plugin/plugin.json
  - path: agent-tools/.mcp.json
  - path: .agents/plugins/marketplace.json
  - path: packaging/installer_fixture.py
  - path: packaging/run-installed-product.py
  - path: packaging/test-installer-fixture.py
  - path: packaging/test-installer-entrypoint.py
  - path: .github/scripts/release/build-assets.sh
  - path: .github/scripts/release/ci-candidate.py
  - path: .github/scripts/release/publish-release.sh
  - path: .github/scripts/release/publish-developer.sh
  - path: .github/workflows/ci.yml
  - path: .github/workflows/docs.yml
  - path: .github/workflows/release.yml
  - path: .github/workflows/developer-release.yml
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationRecovery.kt
    symbols: [ControlInstallationRecovery]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/VerifiedInstallationPlan.kt
    symbols: [VerifiedInstallationPlan]
  - path: packaging/host-installation.py
    symbols: [HostInstallRequest, HostedPluginPayload, install]
  - path: distribution/release/component_release.py
  - path: distribution/release/component-release.schema.json
  - path: .github/workflows/release-component.yml
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateComponentReleaseTask.kt
    symbols: [GenerateComponentReleaseTask]
verified:
  - by: openwiki/0.6.1
    at: 2026-10-02T08:15:19.347Z
sources:
  - id: openwiki-source-16803a9d8df1ded50a107e29
    resource: repo://build-logic/src/main/kotlin/support/tasks/control/GenerateComponentReleaseTask.kt
  - id: openwiki-source-2a9daaac1604f238ef4c63fb
    resource: repo://build.gradle.kts
  - id: openwiki-source-b19700b4b7bbfa25fb4bb931
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationRecovery.kt
  - id: openwiki-source-8f4d86f42434fcc1ce269879
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - id: openwiki-source-bae14a3ed4bc7ba21f443356
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - id: openwiki-source-6f0b647814adafaaa5d5c5f2
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
  - id: openwiki-source-95d32ff923f76d4a11784db6
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceReinstallation.kt
  - id: openwiki-source-25e4768efcedd38972e98f0d
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetExecution.kt
  - id: openwiki-source-4a775e59f7cd3b1cc2d7cc10
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationLifecycle.kt
  - id: openwiki-source-7cd4eb997a7878eca6390142
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - id: openwiki-source-ae4b8d5875d797e7d0c59ae5
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementLifecycle.kt
  - id: openwiki-source-927dce901b5dbac4a4d84f48
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
  - id: openwiki-source-9aad56755086fa068397b99d
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ReadyReset.kt
  - id: openwiki-source-f3718a29d9c6d868d0d9d73e
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
  - id: openwiki-source-03ffc32a0ca502ab67c54b25
    resource: repo://install.sh
  - id: openwiki-source-6fea3b3b86300de08664c4ee
    resource: repo://packaging/host-installation.py
  - id: openwiki-source-4c9f1a2f0eff9672edf6ca2f
    resource: repo://packaging/installer_fixture.py
  - id: openwiki-source-8faf23d69764b95242de5e09
    resource: repo://packaging/run-installed-product.py
  - id: openwiki-source-671eb4bdcdbb3b2f0659d4c7
    resource: repo://packaging/test-installer-entrypoint.py
  - id: openwiki-source-27c69d628b97ee3d2536e2d1
    resource: repo://packaging/test-installer-removal.py
  - id: openwiki-source-d124e13c6fde72403a229a1e
    resource: repo://runtime/hosted/native/mixed_version_acceptance.py
  - id: openwiki-source-af72e3368d1f0c22109e4ecf
    resource: repo://runtime/hosted/native/README.md
  - id: openwiki-source-931b927626ea234f44ea20df
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
generated: { by: "codex", at: "2026-10-02T08:15:19.347Z" }
---

# Distribution and packaging

The control payload includes a versioned Codex marketplace assembled from the
canonical `agent-tools` skill and MCP configuration. `kast plugin codex` verifies
the installed payload before using Codex's marketplace and plugin commands.
Codex owns the installed plugin and its removal. Release assembly also emits
independent skill, plugin, and marketplace ZIPs; candidate admission verifies
their checksums and SBOM archive identities alongside the control and IDEA
archives. Query examples in the skill are generated from the public tool schema.

The `distribution:cli` module builds the native public `kast` management executable with Clikt and GraalVM. It reads a separate management ownership receipt and retains the selected public executable path across upgrades. The executable exposes installation status, version, supplied harness registration, verified stop/reinstall (with force flags), upgrade, and uninstall. The operational JVM CLI and transport launchers remain private within the managed payload. Installer path preflight runs before service retirement; executable publication follows control activation; fresh-pair installation also performs host activation. The release manifest qualifies bundled integration bytes before registration. Status reads installation receipts without starting Java or preparing a workspace. The running coordinator supplies a bounded passive projection for its captured control version, ready workspaces, live connection count, and each observed host identity, plugin version and compatibility. Installed and running control versions remain separate; missing live host evidence is unavailable. One-shot tool RPC calls publish a bounded process identity record while executing; MCP sessions retain the same canonical identity in their session directory. Native status counts only records whose exact process incarnation remains live. Unavailable observations remain distinct from verified empty results.

Distribution contracts own configuration keys, defaults, owners, operational limits, runtime identity, and bootstrap outcomes. Managed adapters own installation trees, recovery receipts, selected IDE discovery, and endpoints. The retired isolated runtime downloader, archive store, heap observer, and network/trust-store bootstrap have been removed; IDEA owns its import environment and trust configuration.

Root and packaging scripts orchestrate checkout installation, persistent lifecycle, release layout, and artifact checking. Control and host releases have separate artifacts and checksums. Fresh and default checkout installation select both; component installation selects only its artifact. The hosted-plugin ZIP is named for its own version and the IDEA release line (`idea-262.zip`). The public installer verifies its checksum, release version, plugin identity, and descriptor release line before atomically replacing only the Kast directory under IDEA's user plugin root; dry-run validates the archive without writing plugin state. The assembled-product check copies the production Control staging tree directly into an owned fixture before invoking launchers or the installer. It verifies independent artifact identities, required launchers including the native management command, and Control cold staging in the canonical layout beneath that fixture HOME. It verifies an installed/not-requested report, the exact publication receipt and absence of Host files; it establishes no loaded-host admission. The checked [configuration schema](../../packaging/configuration-schema.json) is a public boundary and must remain aligned with the Kotlin catalogue.

Native lifecycle commands fence admission at `<install-root>/shutdown.json`, outside the replaceable payload. Direct tools and coordinator startup honor the fence. Stop disables the owned coordinator and drains recorded tool processes within one 30-second retirement budget; stop with `--force` additionally signals only admitted exact process incarnations with qualified installed classpaths. The selected IDEA executable is re-derived from its saved launch receipt; a running user-owned host rejects with `HOST_RESTART_REQUIRED`. No replacement proceeds on an unverified shutdown. Reinstall cold-stages only the exact installed Control version, admits an installed/not-requested staging report, then bootstraps through the existing fenced lifecycle and restores recorded registrations. No Host artifact is required. A failed bootstrap restores the fence, retires any partially started coordinator, and returns a typed installation-pending result. JSON results follow the shipped closed lifecycle schema; stage observations use finite discriminated variants on stderr. Older payloads without the manifest-qualified lifecycle capability reject before effects. Hosted-only lifecycle manifests may retain deleted historical workspace paths because no workspace-specific process is retired; schema-1 isolated installations still require existing roots.

The control product also includes `kast-tool-rpc`. Installation retains its configured `kast-tool-rpc-complete` wrapper inside the ordinary payload and retires owned external command links. The one-shot catalog and call interface is shared by Copilot CLI and Pi extensions without using an MCP connection. Both adapters default to `$HOME/.local/share/kast/installation/bin/kast-tool-rpc-complete` and preserve an explicit `KAST_TOOL_RPC_COMMAND` transport override; ambient alternate installation selectors cannot redirect their default.

Codex registration requires the explicit `mcp` or `app-server` transport. MCP owns the selected `kast` server entry in Codex configuration. App Server owns `~/.local/bin/kast-codex`, an executable launcher for the selected installation's existing `kast-codex-complete` facade; the launcher accepts interactive Codex and `app-server` stdio invocation without adding an MCP entry or redirecting ordinary Desktop sessions. Schema-2 management receipts retain the closed connection identity. Schema-1 receipt admission refines existing Codex ownership to MCP while preserving its destination and payload digest. Both recorded Codex transports are independently owned; disconnecting Codex removes its recorded routes.

The public installer renders progress, activation qualifications, and finite failure details for people. `--verbose` retains full child JSON reports and diagnostics. Native upgrades retain the private installation report on disk without printing it by default.

CI selects checks on Linux before allocating product runners. Independent public MDX pages and Mintlify navigation run only documentation validation, for both pull requests and complete before/head push ranges. Installer-linked pages and the generated callable reference retain product, portable, and documentation checks. Conventional Kotlin sources and packaging inputs retain the product and portable checks without Mintlify validation or the separate Gradle graph-membership dry run. Build wiring, unclassified paths, uncertain diffs, and manual CI dispatch retain every check. The stable completion job requires every selected job to succeed and permits only explicitly planned skips. Documentation validation is a reusable workflow with its manual dispatch retained. When product validation is selected, main CI runs preflight checks, then builds and retains one exact next-patch release candidate with the product gate at that version. Full pull-request CI and pre-push fetch the published release catalog anew for each gate invocation, select its highest stable semantic version, and pass that version to the checkout build. Missing release authority rejects without a local-tag or placeholder fallback. Exact release-candidate builds retain their explicitly resolved candidate version. CI retains Gradle profile reports from full pull-request gates and main candidate builds for task timing review. The product job restores Gradle's local task-output cache; main and same-repository pull requests save new entries, while fork pull requests only restore entries. Release reuses the main candidate only when its version and source revision match the requested release, the producing main-push CI run completed successfully, and the asset inventory, checksums, and SBOM source/archive identities validate. An active exact-source main CI run yields `PENDING` when no successful exact candidate is available; the release attempt exits before fallback construction and can be retried after CI completes. Producer state is observed before artifacts so a run completing between observations may supply its candidate without a rebuild. This is conservative observation, not a lock against future runs. A missing candidate follows the existing exact-version build gate only when no active producer was observed; incomplete observations, unsupported workflow states, and invalid candidates reject. Minor and major releases use that build path unless a matching candidate exists. The release workflow retains the admitted candidate before publishing.

An explicitly dispatched developer workflow runs only from `main` and builds
that dispatch's exact commit through the
same product gate with a unique `0.0.<build>` version. Its read-only build job
retains the candidate; a separate trusted publication job validates the asset
inventory, checksums, and SBOM source identity before creating an immutable
developer prerelease. The public `developer-latest` branch contains a
mutable pointer to that exact prerelease and source revision. The installer
accepts `--developer-latest` to resolve the pointer before downloading and
verifying the versioned control and IDEA plugin assets. Stable release version
resolution ignores developer tags.

The public installer reports the selected IDEA product version and build before
fetching release-line-specific plugin bytes. An absent matching plugin is a
fail-closed compatibility result and precedes installation effects. Public installation enables the app-server suite and defaults the per-user login
LaunchAgent on without a prompt. Paired installation asks in a terminal whether to register a user-level Codex MCP entry. Explicit register and skip flags bypass that prompt; non-interactive paired installation defaults to registration for compatibility. Control-only and Host-only installs, and Control cold staging, skip registration without prompting. Explicit skip is accepted in either argument order; explicit registration rejects before download or installation effects because it requires a paired install. Skipping registration does not inspect or mutate Codex configuration and still installs the MCP launcher. Command collisions require explicit `--force` or manual removal.
Checkout installation replaces the sole user installation in place. Session mode and alternate installation roots reject before build or installation effects.

Single-directory schema-3 and hosted-only schema-2 installations retire their coordinator without invoking
the retired isolated-workspace `stop` command. Legacy schema-1 installations
retain that obligation. Lifecycle rejection reports identify the bounded stage
and outcome, including unresolved worker receipts; failed retirement preserves
the existing transition journal and state. Successful plugin installation still
requires the separately reported IDEA restart. Retired CLI start/stop guidance
does not claim authority to stop IDEA. The installed coordinator prepares workspaces for semantic demand. The explicit `workspace_lifecycle` tool remains available to the agent; the duplicate `workspace lifecycle` CLI command is retired.

Installation defaults to a private coordinator endpoint and persists an explicitly
selected public endpoint policy. Candidate configuration and executable qualification
precede prior-service retirement. Committed installation and service activation have
separate outcomes: ready, not requested, or pending with a finite child-process
failure and a resume command. Pending activation does not prevent the shell bootstrap
from installing the verified IDEA plugin. Saved configuration and installed launchers
retain the desired state; the private `kast-service enable` action resumes
service reconciliation without repeating installation. Workspace registration
is an explicit `kast-service register` action. The pending report does not claim service readiness.

After candidate qualification and recovery preparation, an ordinary upgrade
admits the prior retirement command and asks the selected daemon for an exact
update seal. A proven absent daemon needs no seal. Active daemon blockers or
unproven service state reject before retirement and preserve the installation.
The installer commits a sealed request before invoking the prior service's
disable control, so a failed or mismatched commit cannot stop that service.
New releases use the private `kast-service` executable for activation and
retirement; admitted older releases retain their public CLI retirement command.
If disable fails after commit, the same candidate can resume retirement while
the exact prior daemon remains reachable and reports its committed request.
The upgrade result retains finite blockers and daemon rejection causes.

`kast upgrade --control-only` stages and qualifies the candidate under existing
installer ownership. The candidate's live host admission runs before prior control
retirement. Unavailable or incompatible evidence rejects with the previous control
left in place. Replacement retires only control processes and swaps only control
files. Candidate activation verifies the exact reachable coordinator, then repeats
live host admission. A failure after retirement or replacement restores and verifies
the previous control using the existing replacement transaction. The outcome is
rejected, activated, rolled back, or recovery required, retaining the original and
recovery failures. IntelliJ identities and plugin files remain outside these effects.

`HostInstallRequest(HostedPluginPayload)` belongs to the independent plugin installer.
`install.sh --host-only --version <host-version>` admits host archive identity and
atomically installs only the plugin. IntelliJ's ordinary restart loads the selected
host; no control replacement or dynamic plugin lifecycle is introduced. Fresh
installation composes this owner with `ControlInstallRequest(ControlPayload)`.

Installation child processes emit `kast_installation` records by default with a closed stage and outcome. Prior admission, retirement, configuration validation, candidate and activated-command qualification, and App Server enablement retain distinct success, nonzero exit, deadline, I/O and interruption observations. New-payload admission remains authoritative; these records do not contain command arguments, environment values or filesystem paths. Ordinary installation rejects a failed prior admission or retirement with a stage-specific failure and preserves the selected release. An untrusted same-version payload or recovery receipt likewise rejects without replacement. Explicit `--force` remains a separate reset operation.

Prior launchd retirement completes only after observing the selected service absent. A `bootout` exit alone cannot prove retirement: the owner repeats bounded `launchctl list` observations within one ten-second deadline and preserves a finite deadline, I/O, interruption, or exit rejection. The typed `kast_installation_retirement` event retains the `OBSERVE_BEFORE`, `BOOTOUT`, and `OBSERVE_AFTER` stage with the closed result and its admitted exit code where present; it excludes command arguments, service labels, and child output. The public installer renders those facts for people and retains full records through `--verbose`.

`ControlDistributionLimits` owns the maximum verified control-product entry count and manifest size used by staged installation and runtime identity admission. The shell bootstrap, installed lifecycle, build verifier, and Kotlin owner are checked for the same entry limit, and release layout verification rejects a product outside that bound before publication. Upgrade admission uses the new, checksum-verified lifecycle implementation to inspect the prior installation. Failed inspection or retirement rejects the upgrade. This retains the resource limits while preventing copied limits from drifting below the product that the build produced.

`ControlPayloadInventory` counts paths globally, including the three payload roots, before sorting or hashing. Installer and broker use that same admission; `verifyReleaseRuntimeAdmission` exercises the runtime identity owner on the actual staged control product. Limit diagnostics retain the resource and observed lower bound. The hosted-plugin ZIP has a separate archive budget.

The product has one ordinary payload at `$HOME/.local/share/kast/installation`; schema 3 manifests and recovery receipts bind that physical path. Installation creates no `versions` directory or `current` selector. Native management and agent plugin registration use the stable physical directory. Before replacement, the installer admits the existing payload, protected state, retirement command and recovery baseline. The existing epoch owner validates any copied epoch against the old payload; it preserves an unchanged identity and removes only the admitted copied epoch when the new payload identity changes, so coordinator startup creates a fresh epoch. Protected journals and workspace enrollment remain retained. Control-only upgrades retain saved configuration and workspace registration while starting fresh control state and session ownership. They do not migrate sessions or copied epochs. Installation stages the new payload and records a typed replacement transaction under `<install-root>/recovery/replacement`, then swaps the ordinary directory under the parent activation lock. A failed swap restores the admitted payload and receipt, or retains a finite recovery-required outcome when restoration cannot be proven.

The replacement snapshot is temporary and remains until the shell bootstrap verifies IDEA plugin activation and completes Codex registration. Only active plugin bytes occupy `plugins/kast-ide-hosted`; a pending candidate and prior baseline stay outside plugin discovery in `.kast-plugin-recovery`. The installer finalizes the replacement only after checking the new physical installation identity and active plugin identity. It removes the admitted previous payload and transient baseline on success. Failed effects preserve recovery evidence and report an incomplete installation. Explicit legacy migration admits old manifests and selector ownership; normal runtime configuration does not follow legacy aliases. Historical unknown artifacts remain subject to the existing ownership review rather than guessed deletion.

`installation-recovery.py detach --control-only` fences control launches. New control receipts do not own an IDEA plugin. Historical paired receipts remain evidence and control detachment preserves their plugin files; explicit historical paired maintenance is available only as `detach-legacy-pair`; preparing the next control recovery baseline removes host ownership from the new receipt without changing plugin files. It executes retirement only after payload admission. Verified retired state is quarantined; uncertain processes and journals stay preserved and produce `DetachedWithUnresolvedState`. A legacy paired maintenance receipt with incomplete plugin ownership cannot produce a clean legacy maintenance result. The standalone `prepare` operation supports explicit legacy recovery without executing an unverified installation. Python and JVM installation transitions use compatible POSIX record locks.

`install.sh --force` uses the same verified release path with explicit reset authority.
Under the activation lock it fences and retires the selected and same-version target
services, removes same-user transport aliases and the exact derived upstream socket
directory, then moves payload/state and recovery bundles aside before restaging.
The managed filesystem adapter owns transport cleanup and entry quarantine; the CLI owns process retirement and maps the finite preparation result to `FORCE_RESET` evidence. Unknown upstream contents fail closed. Force
replaces command collisions and starts with fresh workspace enrollment; source trees
are untouched. A force dry run verifies and reports without performing reset effects.
Force plugin activation moves the exact same-user Kast plugin entry into private recovery storage without following a symlink target. The private CLI, App Server, and MCP launchers are required; installation cannot publish a CLI-only payload.

The public installer registers `kast-mcp-complete` once in user-level Codex MCP
configuration. It checks for a foreign `kast` entry before replacing the selected
installation and removes only its own entry on uninstall. Terminal Codex then
discovers the exact Gradle root for each session. Modern discovery or legacy
initialization queues native preparation for that root using the selected IDEA
lifecycle. Later semantic demand joins the same preparation and waits for exact
readiness. The initialize response explains that wait, automatic linked-model
reload, exact-reference reuse, relation coverage, continuations, and stage-specific
recovery. The terminal MCP catalog does not expose manual refresh or
`workspace_lifecycle`.
Its `add_declaration` tool plans, signs the exact native challenge, applies, and verifies
within one call, attempting recovery if application is unverified. The App Server exposes the same public tool and signs its exact
plan internally. If apply is cancelled, only a complete native `prior_state` or
`rolled_back` recovery settles the invocation as known and releases the App
Server workspace lane. Incomplete recovery leaves the lane protected.
The app-server suite is always installed. Activation may still be pending with
a finite reason when the host cannot start the service.

The app server exposes every qualified tool in the canonical agent catalog. There is no subset-selection setting or disabled product variant. Persistent installation activates the login service; explicit cold staging defers activation in that same installation. Cold staging selects only Control and preserves all Host files. It rejects combination with a control-only upgrade, which requires activation and live-host admission. The private `installation install --stage-only` option selects the existing installation owner's typed `STAGE_ONLY` policy and retains an installed `not-requested` activation result. Retired overrides reject before installation effects.

Read [configuration](../contracts/configuration.md) for ingress and ownership rules.

`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.

`assembleControlRelease` uses `controlVersion` and emits only the control release
record, artifact and checksum. `assembleHostRelease` uses `hostedPluginVersion`
and emits only the host release record, artifact and checksum. Component releases
resolve latest within `control-v` or `host-v`; their versions need not match.
`ControlRelease` declares its required hosted contract. `HostRelease` declares its
provided contract and supported IntelliJ release line. Component publication
validates the record against archived bytes and the exact source revision.

`GenerateControlMetadataTask` derives `ide-host.json` from control build identities
and the required hosted contract, with no plugin archive input. The plugin embeds
its own provenance and complete provided contract. `assembleRelease` remains a
fresh-install convenience containing both artifacts and host metadata; the pair
is admitted by contract equality. No isolated semantic payload is shipped.
Control installation manifests have no mandatory plugin digest. Lifecycle
inspection retains historical records so old control processes can retire safely.

Release asset construction, checksums, SBOM inventory, and publication agree on all five archives.

The [installed knowledge contract](../contracts/installed-knowledge.md) describes
`kast knowledge`, its isolated PSI extraction, verified module ownership and
scoped guide resources staged with the control product.

The generated catalogue also declares the four execution ceilings, host backlog
and connection capacity, and native source continuation byte/TTL limits.
`:cli:generateConfigurationCatalogue` owns the snapshot;
`verifyConfigurationIngress` checks its exact agreement with the Kotlin owners.
The [configuration contract](../contracts/configuration.md) records the defaults.

Workspace registry retention emits a bounded `kast_installation_registry`
observation with its exact outcome. A corrupt prior registry fails prior lifecycle
admission and keeps the selected installation and declared activation anchors. Failure to stop an exact prior service,
filesystem failure, and interruption remain explicit failures, rather than being
reported as successful retirement.

After the selected upgrade receipt is sealed, historical cleanup admits each
prior version through the lifecycle boundary. A separate review enumerates
prior Kast processes, login items, recovery bundles, plugin backups, external
anchors, and version entries that no longer pass admission. It preserves the selected
version and the plugin backup named by its recovery receipt. A terminal user
must answer `yes` for each uncertain entry; a declined or unattended entry is
retained with a finite reason. Process command lines supply runtime evidence
for version-root tracing, and an exact Kast launchd label is booted out before
its reviewed login item is removed. A process or entry that changes during
review is retained.

Selected IDEA discovery retains one canonical home across upgrades. Typed metadata resolution identifies its macOS ARM bundle and executable, rejects ambiguity and escapes, and admits the 262 release line rather than an exact patch. Installation inspection includes the launch observation and persists the derived receipt. A legacy selection can still attach to its live lifecycle endpoint when cold-launch metadata is unavailable.

Control assembly packages `share/kast/provider-catalog.json` from the same hosted
schema projection as the CLI. The artifact contains hosted metadata and schemas,
with no executable grammar. Distribution layout requires it; runtime release
admission qualifies the actual staged file through the production provider.

After installation admission and before retiring or replacing any installation, the installer enrolls or preserves the user-owned broker key pair. Partial, mismatched, unsafe or busy enrollment rejects with the finite trust failure. Plan mode creates no keys. Bounded trust observations contain only completion status or rejection cause, never key material. Keys live outside versioned payloads and remain unchanged across reinstalls and upgrades.

The control payload includes private executables at `share/kast/libexec/kast-daemon` and `share/kast/libexec/kast-service`, inside the existing inventory and checksum boundary. Launchd invokes the daemon directly; the installer invokes service control without the public CLI graph. The login LaunchAgent retains the service label and daemon executable, adding only the private login argument. Offline recovery admits that entry only when removing the argument from its bounded bytes yields the retained service plist for the exact Codex-home profile; it also recognizes the older one-shot entry. The assembled-product check verifies the private launchers are present and executable; their argument and readiness rules remain with their Kotlin owners.

`kast connect codex <mcp|app-server> --force` or
`kast connect <copilot|pi> --force` authorizes takeover of that integration's
Kast registration slot. The existing lock and release-manifest admission still
apply. Registration and receipt preimages are captured before replacement;
failed transactions restore both. Codex restoration uses the full original
configuration bytes, preserving fields outside its inspection projection.
Failed restoration retains its backup and reports the recovery destination.
Symlinked registration paths reject before mutation. Connecting records
configuration and does not establish that a client loaded or invoked the tools.

Destructive `uninstall --force` and `reinstall --force` bypass old payload and receipt ownership admission while retaining canonical root scope and verified daemon retirement. An exterior fence and persistent lock survive root replacement. The fence records retained storage before the old root is moved atomically to a scoped sibling recovery directory. The runtime boundary unloads exact root-derived launchd jobs, removes exact login entries, and retires scoped Kast process incarnations within a bounded deadline. Shared IDEA and Gradle processes are excluded. Erasure accepts only a quiescent typestate; a retirement or cleanup rejection preserves the fence and reports retained storage.

The closed lifecycle progresses through privately constructed fenced, quiescent, erased, staged, readiness-proven, and active types. A single-use lease rejects replayed effect transitions. The embedded installer cold-stages a fresh latest-stable payload under the selected root, requiring the manifest-qualified reset fence capability. Payload admission and a staging report establish committed bytes without claiming active readiness. Activation records `ACTIVATING` while retaining the exterior marker. Daemon startup requires that intent and the live exclusive reset lease; tools remain fenced until readiness verifies the fresh version and canonical service generation and the marker is lifted. Only then can the lifecycle produce `RESET_REINSTALLED`. Failed staging or activation restores the fence and retires partial startup. Failed compensation preserves the original and recovery failures in `RESET_RECOVERY_REQUIRED`. Interrupted retention remains discoverable through the exterior fence; the lock stays stable across resets. No old registration history survives successful erasure, external harness registrations remain, and erased data has no rollback. OS or filesystem refusal remains a finite failure. The canonical lifecycle schema includes all force reset variants.

Native installer dispatch explicitly binds the sole admitted HOME-derived root.
Public uninstall rejects an alternate root or nonphysical path before Control-only
removal. Installation checks existing physical ancestors before creating missing
directories, and force reset cannot resolve a parent alias into another tree.
Public command publication retains its own receipt-bound destination and rejects
parent aliases; that destination never selects a second installation.

The opt-in [mixed-version native check](../../runtime/hosted/native/README.md)
uses the existing normal user installation and IntelliJ profile. It requires
three real compiler-query witnesses across C1/P1, C2/P1 and C2/P2, unchanged
IntelliJ/Host/socket/plugin identities through the Control-only upgrade, and
unchanged Control files and generation through Host installation. Inert archives
retain original bytes for restoration at the same destinations. Original runtime
PIDs, sockets, readiness and epochs are not restored as authority; the original
Control must start with a fresh generation and verified original bytes. Static
and routine tests do not establish that native behavior.

The native inventory rejects entry or byte overflow before hashing the crossing
input, retaining the resource, maximum and observed lower bound. Its limits match
the existing Control distribution budget. Runtime installation identity hashes the
physical root and ordered immutable payload paths and bytes; it is distinct from
the downloaded archive checksum. Restoration admits the original registered
workspace before effects and enables the restored service from that exact root.
The legacy original native loaded-version projection may remain unavailable; the
report retains that qualification and requires the private production coordinator,
new epoch and fresh generation to agree with ready service evidence. Candidate
Control status remains strict.

Each component release records its own source revision and archive checksum. The
runner also records its checksum and checkout state; components may come from
different source revisions. A fresh semantic RUN is permitted only for the exact
typed freshness rejection with MOVED at MODEL_CAPTURE or CONTENT_REVALIDATION
and the observed restart_read instruction, matching the host's two declared restart
stages. At most three independent RUNs are permitted, with every rejection retained.
Focused helper tests prove bounded inventory, identity ordering and that recovery
predicate using case-owned inputs. They do not establish the required live
mixed-version result; the completed native proof remains a separate gate.
