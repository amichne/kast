---
type: Kotlin Module Group
title: Distribution and packaging
description: Typed configuration and runtime identity contracts constrain managed
  installation effects, release assembly, and acceptance harnesses.
resource: file://distribution
tags:
  - distribution
  - configuration
  - packaging
  - release
code_sources:
  - path: packaging/install-checkout.sh
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationJavaRuntime.kt
    symbols:
      - installationJavaRuntimeEnvironment
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
    symbols:
      - HostedQueryProgress
  - path: runtime/hosted/native/README.md
  - path: runtime/hosted/native/mixed_version_acceptance.py
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementExecution.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
    symbols:
      - PluginInstallOutcome
      - PluginInstallFailure
      - installCodexPlugin
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginBundle.kt
    symbols:
      - AdmittedPluginBundle
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/IntegrationRegistration.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RegistrationPayload.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementLifecycle.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
    symbols:
      - ForceResetRoot
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetExecution.kt
    symbols:
      - forceResetInstallation
      - ForceResetExecution
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
    symbols:
      - FencedReset
      - QuiescentReset
      - ErasedReset
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ResetProcessObservation.kt
    symbols:
      - ResetProcessObservation
  - path: distribution/contract/src/main/resources/management/reset-process-observation.schema.json
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetFence.kt
    symbols:
      - ForceResetFence
      - RetainedResetStorage
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceDaemonRuntime.kt
    symbols:
      - ForceDaemonRetirement
      - ResetScopedProcess
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/NativeForceDaemonRuntime.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RuntimeServiceGeneration.kt
    symbols:
      - RuntimeServiceGeneration
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ResetLoginEntries.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/InstallationResetFence.kt
    symbols:
      - InstallationResetRequest
      - InstallationResetStorage
      - installationResetFence
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstalledProductRuntime.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
  - path: packaging/managed_product_installation.py
  - path: packaging/runtime_base.py
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetOutcome.kt
    symbols:
      - ForceResetOutcome
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementShutdown.kt
    symbols:
      - executeInstallationLifecycle
      - LifecycleExecution
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationLifecycle.kt
    symbols:
      - InstallationLifecycle
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationRequestRetirement.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/LifecycleProcesses.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/LifecycleOutcome.kt
    symbols:
      - LifecycleOutcome
      - LifecycleObservation
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/InstallationShutdownFence.kt
  - path: distribution/contract/src/main/resources/management/management-lifecycle.schema.json
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/InstalledToolInvocation.kt
  - path: distribution/contract/src/main/resources/management/installation-reset.schema.json
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ResetActivationAdmission.kt
    symbols:
      - ResetActivationAdmission
      - ResetActivationLease
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
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UninstalledUserState.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationStateInspection.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/PackagedProviderCatalog.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/ControlDistributionAdmissionMain.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SelectedIdeInstallation.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/PriorInstallationPreparation.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationChild.kt
    symbols:
      - executeInstallationChild
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PriorInstallationReplacement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PriorRetirement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRegistryObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledEpochRetention.kt
    symbols:
      - InstalledEpochRetention
      - InstalledEpochRetentionFailure
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerInstallationState.kt
    symbols:
      - BrokerInstallationState
  - path: packaging/run-portable-tests.py
  - path: packaging/run-portable-tests-container.sh
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
    symbols:
      - ControlDistributionLimits
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
    symbols:
      - ConfigurationSchemaDocument
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/InstalledConfigurationSchema.kt
    symbols:
      - InstalledConfigurationSchema
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
    symbols:
      - ManagedInstallationOwnedTree
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
    symbols:
      - InstallationReplacementReceipt
      - PreviousInstallationPayload
      - InstallationReplacementStage
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PendingInstallationReplacement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
    symbols:
      - InstallationWorkflow
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationActivation.kt
    symbols:
      - InstallationActivation
      - InstallationActivationPolicy
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationCliInspection.kt
    symbols:
      - InstallationCliInspection
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationManifest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationOutcome.kt
    symbols:
      - InstallationFailure
      - InstallationOutcome
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
    symbols:
      - ControlInstallRequest
      - ControlPayload
      - InstallationProfile
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateControlMetadataTask.kt
  - path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - path: distribution/release/resolve_version.py
    symbols:
      - observe_releases
      - published_versions
  - path: distribution/release/run_product_gate.py
    symbols:
      - source_identity
      - run_gate
  - path: .github/scripts/ci/routine_gate.py
  - path: .github/scripts/ci/validation_scope.py
  - path: .github/scripts/ci/required_checks.py
  - path: .github/scripts/ci/validation_outcome.py
  - path: .github/scripts/ci/test_validation_outcome.py
  - path: .github/scripts/ci/verify-checks.py
  - path: .githooks/pre-push
  - path: distribution/release/plugin-release.gradle.kts
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
    symbols:
      - GenerateAgentToolsTask
  - path: agent-tools/.codex-plugin/plugin.json
  - path: agent-tools/mcp.json
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
    symbols:
      - ControlInstallationRecovery
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/VerifiedInstallationPlan.kt
    symbols:
      - VerifiedInstallationPlan
  - path: packaging/host-installation.py
    symbols:
      - HostInstallRequest
      - HostedPluginPayload
      - install
  - path: distribution/release/component_release.py
  - path: distribution/release/component-release.schema.json
  - path: .github/workflows/release-component.yml
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateComponentReleaseTask.kt
    symbols:
      - GenerateComponentReleaseTask
sources:
  - id: openwiki-source-baf30c604828cfde90a8ab63
    resource: repo://.githooks/pre-push
  - id: openwiki-source-3b28c686cbf92ac682eb5142
    resource: repo://.github/scripts/ci/build_execution.py
  - id: openwiki-source-0e96c1b9a971eb3f67a64455
    resource: repo://.github/scripts/ci/fast_checks.py
  - id: openwiki-source-970d1a886c7a46d7b0335a81
    resource: repo://.github/scripts/ci/gradle_diagnostics.py
  - id: openwiki-source-d09e57e512e655a9f4013dba
    resource: repo://.github/scripts/ci/gradle_graph.py
  - id: openwiki-source-782f88ea0a8c13260d76dfa4
    resource: repo://.github/scripts/ci/native_handoff.py
  - id: openwiki-source-de62ffd8b4fe61a2095f5212
    resource: repo://.github/scripts/ci/routine_gate.py
  - id: openwiki-source-eaec0145ee5bcb15fda00e9c
    resource: repo://.github/scripts/ci/source_validation.py
  - id: openwiki-source-5830aecb2b5f6a16093ed507
    resource: repo://.github/scripts/ci/test_push_policy.py
  - id: openwiki-source-d7f5bb97a65625ecdf33ddcf
    resource: repo://.github/scripts/ci/test_routine_gate.py
  - id: openwiki-source-8c864abdf1b09fa67b48adbc
    resource: repo://.github/scripts/release/build_candidate.py
  - id: openwiki-source-5e92c81a46b459b558f8901e
    resource: repo://.github/scripts/release/product_candidate.py
  - id: openwiki-source-e61ce41a823368fa4e25eb84
    resource: repo://.github/scripts/release/product_producer.py
  - id: openwiki-source-f02c30e87f3db4d071a7be5b
    resource: repo://.github/scripts/release/publish_developer.py
  - id: openwiki-source-164e2da859b5277df81c7d94
    resource: repo://.github/workflows/ci.yml
  - id: openwiki-source-09ee21d9fc32593e7f00c78d
    resource: repo://.github/workflows/composed-linux.yml
  - id: openwiki-source-804f227d6f0416062f3436fe
    resource: repo://.github/workflows/developer-release.yml
  - id: openwiki-source-4d1d392666be6dfdd7a91a2e
    resource: repo://.github/workflows/release.yml
  - id: openwiki-source-20e85ee434d9dedec13e85f7
    resource: repo://build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - id: openwiki-source-16803a9d8df1ded50a107e29
    resource: repo://build-logic/src/main/kotlin/support/tasks/control/GenerateComponentReleaseTask.kt
  - id: openwiki-source-2a9daaac1604f238ef4c63fb
    resource: repo://build.gradle.kts
  - id: openwiki-source-805b19d15acbc9e33b10fa51
    resource: repo://cli/native-image.args
  - id: openwiki-source-b19700b4b7bbfa25fb4bb931
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationRecovery.kt
  - id: openwiki-source-211f90f77f217ad3273b0d4f
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
  - id: openwiki-source-8f4d86f42434fcc1ce269879
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - id: openwiki-source-02ab91cf3c45f7eb0a7c73b8
    resource: repo://cli/src/main/native-launchers/bin/kast
  - id: openwiki-source-6e0cb99be2f37815eed2066a
    resource: repo://cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/NativeForceResetCompositionTest.kt
  - id: openwiki-source-0e50d1b18d1925b709ede5d3
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerRuntime.kt
  - id: openwiki-source-ea2b24fdd206a91568317996
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceDaemonRuntime.kt
  - id: openwiki-source-bae14a3ed4bc7ba21f443356
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - id: openwiki-source-6f0b647814adafaaa5d5c5f2
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
  - id: openwiki-source-fe95fd24c4ce348de98bb357
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/HarnessUninstall.kt
  - id: openwiki-source-4a775e59f7cd3b1cc2d7cc10
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationLifecycle.kt
  - id: openwiki-source-6ead538479055040a6fa54d0
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstalledProductRuntime.kt
  - id: openwiki-source-cfc838a1a4168e12e43d463d
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/IntegrationDisconnection.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - id: openwiki-source-b30c7b16cf2f49971ca55950
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgrade.kt
  - id: openwiki-source-e72662b6389b5cd2f0412448
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRetirement.kt
  - id: openwiki-source-e1eccbe769b8e78a5354c5ca
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementExecution.kt
  - id: openwiki-source-7cd4eb997a7878eca6390142
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - id: openwiki-source-a62e12ff14ead337deede61b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
  - id: openwiki-source-36e0824ede001594d7142429
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/NativeForceDaemonRuntime.kt
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
  - id: openwiki-source-d258f44cd873e00e544ca7ed
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UninstallManagedRoot.kt
  - id: openwiki-source-d6537b8c3c1d3e623334d7d1
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UserStateInventory.kt
  - id: openwiki-source-c6a7f896f4d5f15e0d036721
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/NativeControlResetSelectionTest.kt
  - id: openwiki-source-09db336d3e004f256dbf17f7
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/HostedEndpointPaths.kt
  - id: openwiki-source-879895757e79a8add3de70b0
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/NativeControlInvocation.kt
  - id: openwiki-source-f3718a29d9c6d868d0d9d73e
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
  - id: openwiki-source-8677e434adb584035406f54a
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SelectedIdeInstallation.kt
  - id: openwiki-source-9c28868aa566a6cc319d3878
    resource: repo://distribution/release/plugin-release.gradle.kts
  - id: openwiki-source-360539b338a8755db6cff27a
    resource: repo://distribution/release/run_product_gate.py
  - id: openwiki-source-eb5e76c5c52dc1f7704250ce
    resource: repo://distribution/release/test_run_product_gate.py
  - id: openwiki-source-81d5f1627e19148569f46f81
    resource: repo://gradle/libs.versions.toml
  - id: openwiki-source-03ffc32a0ca502ab67c54b25
    resource: repo://install.sh
  - id: openwiki-source-74a7088da9d18da2237d9186
    resource: repo://packaging/bootstrap.sh.in
  - id: openwiki-source-245e3f9878cb2ef01a1fcbd1
    resource: repo://packaging/component_probe.py
  - id: openwiki-source-d53edde1666c585216ff3c8d
    resource: repo://packaging/composed_bundle.py
  - id: openwiki-source-3af59f4285e4c5ca70150126
    resource: repo://packaging/composed_host_probe.py
  - id: openwiki-source-e08b876ca00219319cbb81aa
    resource: repo://packaging/composed_release.py
  - id: openwiki-source-ef7a40843a617c29102e725f
    resource: repo://packaging/composition_platform.py
  - id: openwiki-source-611b757652f80d3a33875855
    resource: repo://packaging/container_probe.py
  - id: openwiki-source-6fea3b3b86300de08664c4ee
    resource: repo://packaging/host-installation.py
  - id: openwiki-source-ab52e7c01cf17ee137f833e7
    resource: repo://packaging/immutable_store.py
  - id: openwiki-source-4070dc53852de69cdff3bd47
    resource: repo://packaging/install-checkout.sh
  - id: openwiki-source-1fb25dc686d01d69495da290
    resource: repo://packaging/install-legacy.sh
  - id: openwiki-source-4c9f1a2f0eff9672edf6ca2f
    resource: repo://packaging/installer_fixture.py
  - id: openwiki-source-8e1c0e02a6021c5adc87ccac
    resource: repo://packaging/isolated_runtime.py
  - id: openwiki-source-621afc296584eb4217553339
    resource: repo://packaging/managed_headless.py
  - id: openwiki-source-1380068036b00bec817a6579
    resource: repo://packaging/managed_installation.py
  - id: openwiki-source-7f5792fd36f508c6577b3562
    resource: repo://packaging/managed_product_installation.py
  - id: openwiki-source-a4a155ae2c50f22d2a80ceaa
    resource: repo://packaging/managed_release.py
  - id: openwiki-source-e7484ccb9a72cd71aa938a2f
    resource: repo://packaging/product_acceptance.py
  - id: openwiki-source-72a2e0c4bf1e54eeb4c8db6c
    resource: repo://packaging/product_lifecycle_probe.py
  - id: openwiki-source-8faf23d69764b95242de5e09
    resource: repo://packaging/run-installed-product.py
  - id: openwiki-source-5f93c137a1afcd8823606c72
    resource: repo://packaging/runtime_archive.py
  - id: openwiki-source-0b3a81cf548ab371bc04037e
    resource: repo://packaging/runtime_base.py
  - id: openwiki-source-fbdf7de26cc8b3b5b59b53a6
    resource: repo://packaging/test_product_acceptance.py
  - id: openwiki-source-7fa48d17b46dccf82f4b3238
    resource: repo://runtime/hosted/build.gradle.kts
  - id: openwiki-source-d124e13c6fde72403a229a1e
    resource: repo://runtime/hosted/native/mixed_version_acceptance.py
  - id: openwiki-source-af72e3368d1f0c22109e4ecf
    resource: repo://runtime/hosted/native/README.md
  - id: openwiki-source-7f5d36c76505fdd995af1f49
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleApplication.kt
  - id: openwiki-source-57ba3a687a2a322141093d5f
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/OwnedHostedEndpoint.kt
  - id: openwiki-source-931b927626ea234f44ea20df
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
generated: { by: "amp", at: "2026-11-13T19:40:08.615Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-13T21:54:15.364Z
---

# Distribution and packaging

## Current managed product boundary

Public installation selects one complete product for macOS ARM64 or Linux x86-64.
Preview and stable identify exact complete builds; stable promotion retains the qualified bytes rather than rebuilding them with another version.
The product record requires both targets and complete qualification receipts before public admission.
`InstalledProductSelection` admits recorded identity for exact repair even when runtime bytes are missing.
`InstalledProductRuntime` adds local runtime admission for semantic requests, without release-server access or desktop fallback.

The first explicit semantic request creates trust for one exact canonical Gradle root and selects private Host state.
CLI, MCP, and RPC share that request owner; session hooks and MCP initialization remain passive.
Scoped stop is temporary. Upgrade retirement first acquires exclusive query leases, then fences and retires exact generations.
Vanished-root reclamation requires settled private evidence and retains an exterior receipt after interrupted deletion.

Uninstall removes its own unchanged preparation fence when no removal journal was committed.
Inactive rejected workspace records need exclusive access and private-state admission before removal.
Repair stages verified runtime/base replacements in the existing journal and resets damaged owned bytes only after Host retirement.
Interrupted reset resumes from retained bytes without deleting repositories, workspace profiles, or external Gradle homes.

Native qualification is not complete. Component qualification now precedes sealed-product admission and detached installed-product acceptance.
The stages below replace the earlier circular setup route without claiming successful native Host execution.
Full product removal integrates recorded provider-resource and receipted Pi guidance cleanup; native behavior remains unqualified.
`uninstall --force [--json]` is restricted to legacy state. Its exterior workspace lifetime lease protects admission before detachment.
Either managed store or `product-selection.json` rejects with `MANAGED_STATE_REQUIRES_UNINSTALL`; managed products use ordinary uninstall with its recovery journal.

The Control payload contains CLI-first Codex and Copilot plugins from the canonical `agent-tools` skill.
`kast install <harness>` admits the installed bundle and exact provider resources before setup.
The default composition includes passive native session hooks without an automatic MCP server.
Explicit `--mcp` selects the optional transport.
Codex and Copilot own their plugins. The Pi receipt identifies the owned skill directory.
Control assembly stamps the version in the skill front matter.
Both legacy and managed installer completion enter the capability-marked guidance refresh branch.
`ProductInstalled` carries notices. Failed or interrupted refresh preserves `HARNESS_REFRESH_REQUIRED`; success clears that notice while preserving other notices.
This source contract does not establish live provider refresh success.
Release assembly emits plugin and marketplace ZIP archives without a standalone skill archive.
Query examples come from the public tool schema.

The `distribution:cli` module owns the `Clikt` management implementation. The `cli` module composes it with the existing private entry points in one GraalVM executable. It reads a separate management ownership receipt and keeps the selected public executable path across upgrades. Its six management verbs are `install`, `status`, `stop`, `versions`, `upgrade`, and `uninstall`. Thin private launchers select query, MCP, tool RPC, service, daemon, Codex, or installer modes in the same native executable. The Control archive contains no bundled JVM libraries.

Product installation admits selected bytes before replacement and leaves semantic preparation to an explicit request. The release manifest qualifies bundled integration bytes before registration. Status reads installation receipts without starting Java or preparing a workspace.

The running coordinator supplies a bounded passive projection for its captured control version, ready workspaces, and live connection count. The projection also keeps each observed host identity, plugin version, and compatibility. Installed and running control versions remain separate; missing live host evidence is unavailable. One-shot tool RPC calls publish a bounded process identity record while executing; MCP sessions keep the same canonical identity in their session directory. Native status counts only records whose exact process incarnation remains live. Unavailable observations remain distinct from verified empty results.

Distribution contracts own configuration keys, defaults, owners, operational limits, runtime identity, and bootstrap outcomes. Managed adapters own installation trees, recovery receipts, selected IDE discovery, and endpoints. The retired isolated runtime download adapter, archive store, heap observer, and network/trust-store bootstrap have been removed; IDEA owns its import environment and trust configuration.

Control and Host retain separate component artifact ownership and reviewed `idea-262.zip` and `idea-263.zip` plugin variants. Public installation selects them only through one complete product record, not independent component choices. The retained `run-installed-product.py` harness stages production Control, a receipted Host, and an empty canonical mutation database in an owned HOME. It invokes native uninstall and checks owned removal while preserving a sentinel. Those legacy fixture assertions are not evidence that the new managed product passed native qualification. The checked [configuration schema](../../packaging/configuration-schema.json) must remain aligned with the Kotlin catalogue.

## Component qualification and installed acceptance

`composed_release._qualify_release` validates exact component inputs and invokes `ComponentHarness`, a test-native process boundary.
The component probes exercise compiler behavior and private workspace retirement without first installing a public product.
Their `COMPONENT_NATIVE` receipt binds source, bundle, and harness identity; it is not installed-product acceptance.
The retained `container_probe.py` filename does not imply use of retired public container commands.

`product_producer.py target` records one native target's evidence.
Its `seal` operation requires both target inputs from the exact producer attempt and creates the immutable build record without rebuilding.
That record can then enter installed-product acceptance through normal managed admission.
The later `accept` operation joins detached receipts without rewriting the sealed record.
`product_candidate.py` distinguishes a sealed candidate from one admitted with both acceptance receipts.
Preview publication and stable promotion require those receipts and retain the original record and payload bytes.

`product_lifecycle_probe.py` and `product_acceptance.py` own the installed acceptance scenarios and detached evidence contract.
The baseline needs sealed component qualification, not prior publication or installed acceptance.
CI admits its exact record digest, source revision, producer attempt, and successful sealing job.
A later failure in the baseline run does not erase that sealing evidence.
The baseline can share the candidate's source revision, but each native target needs a different actual bundle/runtime/Control composition.
Changing only metadata or repeating exact repair cannot supply upgrade evidence.

The installed probe first upgrades the baseline to the candidate and checks a changed compiler lifetime.
It then uninstalls that product and installs the candidate again without retained managed workspace state.
Before any semantic request, it checks help, status, description help, query planning, and the RPC catalog.
These passive calls must not create semantic workspace state.
Three simultaneous shipped public first queries to one root must all succeed and share the compiler Host identity observed by a later query.
This is a fresh-install check, not merely parallel requests to a warm Host.

The probe also checks CLI, MCP, RPC, stop/restart, simultaneous requests across two roots, and edits.
Final uninstall must preserve sentinels in the private HOME, a foreign IDEA profile, the explicit external Gradle directory, and both repositories.
The receipt inventory includes `FRESH_INSTALL`, `PASSIVE_DISCOVERY`, and `SAME_ROOT_PARALLEL_FIRST_QUERIES`.
It writes a detached receipt only after all required cases complete.
The receipt binds the original build record, native target, Control digest, producer, baseline record, and baseline component digests.
Both target receipts must name the same baseline record and source revision.
CI runs the native target matrix after sealing, then joins its receipts before publication admission.
No native Host success, completed two-target acceptance, or publication is established by this explanation.
Source checks and decoded receipts alone cannot supply that evidence.

## Immutable runtime bases and components

`runtime_base.py` separates immutable vendor files from build-specific runtime components.
Base identity includes the target, donor digest, recipe inputs, support inputs, and exact file inventory.
Overlay copies verified files rather than hard-linking them, rejects component overlap, and cannot mutate the retained base through a composition.
Product publication retains bases in `kast-runtime-bases` and complete runtimes in the workspace store.
Only the producer projects split archives into qualification inputs; promotion does not rebuild or hydrate them.

The retained standalone archive assembler can describe additional platforms, but it is not a public installation route or native qualification proof.

Workspace containers instead share an installer-free runtime ZIP for their version and platform.
The native reader checks the archive digest, typed manifest, file digests, paths, and links before sealing payload bytes and atomic publication.
The top-level directory stays owner-private and writable so publication can rename it.
It invokes neither Python nor an archive executable.
Assembly and admission contracts do not establish native qualification on each platform.

Host and lifecycle sockets use the shared HOME-and-target digest scheme under canonical `/tmp`.
Private namespace admission checks ownership and mode `0700` without repairing foreign state.
Installation and workspace path lengths no longer determine socket path lengths.
See [managed workspace admission](../distribution/management-and-connections.md#managed-workspace-admission) for trust, routing, and stop policy.

## Managed headless assembly

`managed_headless.py` admits an explicit vendor archive digest before it creates a new owned installation.
It installs the bundled bootstrap descriptor, seals runtime files, and invokes the existing installer with a private HOME and bundled JBR.
The assembler rejects an existing destination.
Its manifest records input digests, Host dependency roots, plugin decisions, and each kept or removed file.
An explicit exclusion removes a whole plugin directory.
Unknown plugins, Host roots, and dependencies of kept plugins reject before removal.
The private profile disables excluded plugin IDs through the installer-owned profile receipt.
Assembly records `NOT_RUN` for Host acceptance.
Only installed production behavior can establish that acceptance.

Native lifecycle commands fence admission at `<install-root>/shutdown.json`, outside the replaceable payload.
Direct tools and coordinator startup honor the fence.
Force retirement recognizes worker modes in the canonical native Control image through the shared mode contract.
The process adapter skips only leading runtime options that the pinned GraalVM parser consumes.
Bare `-Dfoo`, unsupported `-X` forms, and misplaced selectors stay application data.
It excludes management and installer modes and rejects an unknown mode or unavailable process arguments before erasure.
The selected PID and start identity remain necessary through capture and retirement.
The retained disposable native force-uninstall case asserts MCP client retirement and Control removal; it is not qualification of the current public parser.
Legacy installation shutdown disables the owned coordinator and drains recorded tool processes within one 30-second retirement budget.
Force retirement signals only admitted exact process incarnations with qualified installed class paths.
A running selected desktop Host can reject with `HOST_RESTART_REQUIRED`.
The retained private `kast-task` owner can examine retirement before removing its exact shutdown fence; it is not the public query route.
Public exact repair reuses the recorded complete product and existing replacement journal, not the deleted Control-only force-reinstallation owner.

The control product also includes `kast-tool-rpc`. Installation retains its configured `kast-tool-rpc-complete` wrapper inside the ordinary payload and retires owned external command links. The one-shot catalog and call interface is shared by Copilot CLI and Pi extensions without using an MCP connection. Optional catalog query-session support reuses one bounded child for a logical query and its output drains; it closes before the adapter invocation returns. Catalog discovery and per-RPC native authority checks remain separate. Both adapters default to `$HOME/.local/share/kast/installation/bin/kast-tool-rpc-complete` and preserve an explicit `KAST_TOOL_RPC_COMMAND` transport override; ambient alternate installation selectors cannot redirect their default.

Codex registration uses its plugin manager.
The retired `connect` and `plugin` setup forms direct callers to `kast install`.
New App Server registration rejects before effects.
Legacy receipt variants keep exact destination and payload ownership for cleanup.
Plugin installation rejects foreign standalone MCP entries before plugin effects.
After Codex admits the plugin, Kast removes only receipt-owned legacy registrations.

## Retained installer diagnostics and CI evidence

The legacy installer adapter renders progress, activation qualifications, and finite failure details. Its `--verbose` option retains child reports; it is not a public product-bootstrap option.

Default failure presentation reads a bounded tail of each child stream and gives rejection and recovery details priority over routine telemetry.
It displays at most 12 details and one truncation notice.
The child exit status remains authoritative, and `--verbose` preserves the complete child streams.

CI classifies the exact changed-path window on Linux before allocating product runners.
An audited edit to authored docs runs Mintlify, the Markdown parser, glossary tests, and glossary projection checks.
Known regular main/test Kotlin sources in the listed product modules run product and portable validation.
Installer examples, generated schemas, scripts, build configuration, protected paths, and unknown or mixed inputs keep full validation.
The completion job requires every selected job to succeed and every excluded job to be skipped.
It then seals a validation record with the exact source revision, source tree, comparison base, event, and producer run identities.
One product invocation tests and assembles the complete Control/Host pair at `0.0.<CI run number>`.
The installed Kast dispatcher exits without qualification. Explicit local qualification uses version `0.0.0` with a `LOCAL_VALIDATION` record.
The full source commit and tree identify the checkout; no published release label is selected.
The wrapper sets source, Control, and Host versions and passes the admitted source revision.
Clean source is checked before execution and again after Gradle succeeds.
The staged Control build identity must match that version, commit, and tree before `VALIDATED`.
A dry-run records `DRY_RUN`; failures cannot inherit an earlier success.
Before/after admission detects observed drift, not transient edits later restored.
Local qualification grants no publication or installation authority.

Full and affected source validation and the local product gate request single-use Gradle daemons.
Source validation uses sequential Isolated Projects configuration for graph inspection and task execution.
Disk caches remain enabled; client retirement does not prove synchronous daemon retirement.
On graph timeout or cancellation, optional diagnostics attempt a bounded daemon thread dump.
Invocation association and process start identity are checked before and after attachment.
Incomplete diagnostics do not replace the graph failure or prevent client cleanup.

Routine gate membership runs product and native-input dry-runs as separate phases.
Each phase streams output and reports progress with a default 600-second execution deadline.
Both commands limit Gradle to two workers and a 5 GiB heap, with single-use daemons.
Timeout and cancellation keep partial logs and attempt bounded diagnostics before owned process-group cleanup.
The deadline excludes that bounded diagnostic and cleanup work.
Each attempt replaces prior success and clears both phases' old reports before starting.
A phase must exit successfully and satisfy its task inventory before validation can continue.
Missing proofs, launch failures, deadlines, and observed cancellation leave a rejected record with a nonzero exit code.
These controls do not serialize separate checkouts or establish synchronous daemon retirement.

The legacy component candidate retains exact commit, tree, producer, platform, toolchain, and payload digests. Its same-repository PR reuse requires matching trees and successful producer evidence. That candidate is not a complete product. The complete-product lane instead joins retained native inputs from both targets for the exact producer attempt without rebuilding. Missing target evidence rejects; retention is 30 days.

Linux complete-product composition starts after scope selection.
Fresh Mac composition restores the exact same-attempt release from the existing native and assembly jobs rather than compiling Control twice.
Admission retains source revision, tree, version, archive digest, platform, and compiler checks.
The reused PR candidate path keeps independent compilation because it has no same-attempt native release.
Both native component harnesses and target qualification remain required.
Sealing joins successful source work and both qualified targets before installed acceptance.

Successful main push CI requests developer promotion.
The publisher first admits the validation outcome from that exact successful source, run, and attempt.
Missing, malformed, contradictory, or mismatched outcome evidence rejects promotion.
`DOCS_VALIDATED` completes without a candidate, promotion record, product release, or developer pointer update.
`PRODUCT_VALIDATED` keeps the tested-candidate publication path.
Its trusted publication job downloads retained bytes without running Gradle or native-image.
A separate promotion record binds the unchanged candidate to the successful main run and merged commit.
The publisher rechecks producer success, complete asset inventory, checksums, source identity, and remote file digests.
Existing releases must match exactly for retries.
A delayed publisher can keep its immutable release but cannot update latest after main advances.
The public `developer-latest` branch contains a pointer to the exact prerelease and original build revision.
The public bootstrap selects preview or stable; the retained legacy installer accepts `--developer-latest`.
Manual publication accepts a successful current-main push or manual CI run ID with matching event and producer evidence.

The Linux source job and macOS native job configure cache transport for Gradle User Home state and task outputs.
Only main push and main workflow-dispatch runs can save shared cache entries from these jobs.
Pull requests, forks, merge groups, and other refs are read-only.
A repository secret supplies the cache encryption key and is unavailable to fork PRs.
A supplied key alone does not establish that configuration-cache state was captured.
Successful main candidate reuse skips Gradle setup.
An authorized full CI dispatch on accepted main can seed the shared cache without automatic developer publication.
Configured transport does not establish an observed task cache hit or lower build time.
CI pins the BellSoft `liberica-nik` distribution at `25.0.2+13`. It keeps native analysis/resource metrics and finite build outcome records for matched comparisons. The unified producer uses `-Os` and transfers one exact executable through the native handoff.
Mac qualification imports the executable from the assembled Control archive into the existing `publicLauncherTest` suite before candidate sealing.
The workflow keeps the native suite's XML and HTML reports with the Mac qualification evidence.
Stable product release maps a public version to the exact retained preview tag and build-record digest without rebuilding.

## Legacy component installation and recovery

The remaining component installer, checkout, and desktop lifecycle owners support legacy development and recovery contracts.
They are not prerequisites or alternate public setup instructions for the managed product.
Control stages `packaging/install-legacy.sh` as its private `share/kast/install.sh` lifecycle adapter.
Native component uninstall uses that adapter, not the public bootstrap parser.
The root `install.sh` remains the public managed-product entry point.
The legacy installer reports the selected IDEA version and build before it fetches release-line-specific plugin bytes.
An absent matching plugin rejects before installation effects.
Paired installation offers the Codex plugin and defaults to that choice without a terminal.

`--install-codex-plugin` and `--skip-codex-plugin` select the choice explicitly.
The former MCP registration flags remain aliases.
Component installation and stage-only installation skip the plugin.
An explicit plugin choice needs paired installation and rejects component requests before download or installation effects.
Installation does not start a broker or publish desktop discovery.

Checkout installation replaces the sole user installation in place.
Session mode and alternate roots reject before build or installation effects.

Single-directory schema-3 and hosted-only schema-2 installations retire their coordinator without invoking
the retired isolated-workspace `stop` command. Legacy schema-1 installations
keep that obligation. Lifecycle rejection reports identify the bounded stage
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
keep the desired state; the private `kast-service enable` action resumes
service reconciliation without repeating installation. Workspace registration
is an explicit `kast-service register` action. The pending report does not claim service readiness.

After candidate qualification and recovery preparation, an ordinary upgrade
admits the prior retirement command and asks the selected daemon for an exact
update seal. A proven absent daemon needs no seal. Active daemon blockers or
unproven service state reject before retirement and preserve the installation.
The installer commits a sealed request before invoking the prior service's
disable control, so a failed or mismatched commit cannot stop that service.
New releases use the private `kast-service` executable for activation and
retirement; admitted older releases keep their public CLI retirement command.
If disable fails after commit, the same candidate can resume retirement while
the exact prior daemon remains reachable and reports its committed request.
The upgrade result retains finite blockers and daemon rejection causes.

The retained Control-only installer path stages and qualifies the candidate under its existing owner; public `upgrade` no longer accepts that selector.
Live Host admission precedes prior Control retirement.
Unavailable or incompatible evidence rejects with the previous Control in place.
Replacement retires only Control processes and swaps only Control files.

The candidate repeats live Host admission without a coordinator startup prerequisite.
A later failure uses the existing replacement transaction to restore the prior Control.
The result keeps the original and recovery failures.
IntelliJ identities and plugin files stay outside these effects.

`HostInstallRequest(HostedPluginPayload)` belongs to the independent plugin installer.
The legacy component installer admits host archive identity and
atomically installs only the plugin. An ordinary IntelliJ IDEA restart loads the selected
host; no control replacement or dynamic plugin lifecycle is introduced. Fresh
installation composes this owner with `ControlInstallRequest(ControlPayload)`.

Installation child processes emit `kast_installation` records by default with a closed stage and outcome. Prior admission, retirement, configuration validation, candidate and activated-command qualification, and App Server enablement keep distinct success, nonzero exit, deadline, I/O and interruption observations. New-payload admission remains authoritative; these records do not contain command arguments, environment values or filesystem paths. Ordinary installation rejects a failed prior admission or retirement with a stage-specific failure and preserves the selected release. A same-version payload without trust proof or recovery receipt likewise rejects without replacement. Explicit `--force` remains a separate reset operation.

Prior launchd retirement completes only after observing the selected service absent. A `bootout` exit alone cannot prove retirement: the owner repeats bounded `launchctl list` observations within one ten-second deadline and preserves a finite deadline, I/O, interruption, or exit rejection. The typed `kast_installation_retirement` event retains the `OBSERVE_BEFORE`, `BOOTOUT`, and `OBSERVE_AFTER` stage with the closed result and its admitted exit code where present; it excludes command arguments, service labels, and child output. The public installer renders those facts for people and retains full records through `--verbose`.

`ControlDistributionLimits` owns the maximum verified control-product entry count and manifest size used by staged installation and runtime identity admission. The shell bootstrap, installed lifecycle, build verifier, and Kotlin owner are checked for the same entry limit, and release layout verification rejects a product outside that bound before publication. Upgrade admission uses the new, checksum-verified lifecycle implementation to inspect the prior installation. Failed inspection or retirement rejects the upgrade. This retains the resource limits while preventing copied limits from drifting below the product that the build produced.

`ControlPayloadInventory` counts paths globally, including the three payload roots, before sorting or hashing. Installer and broker use that same admission; `verifyReleaseRuntimeAdmission` exercises the runtime identity owner on the actual staged control product. Limit diagnostics keep the resource and observed lower bound. The hosted-plugin ZIP has a separate archive budget.

The product has one ordinary payload at `$HOME/.local/share/kast/installation`; schema 3 manifests and recovery receipts bind that physical path. Installation creates no `versions` directory or `current` selector. Native management and agent plugin registration use the stable physical directory. Before replacement, the installer admits the existing payload, protected state, retirement command and recovery baseline.

If an epoch was copied, the existing epoch owner makes sure that it remains valid against the old payload. It preserves an unchanged identity. If the new payload identity changes, the owner removes only the admitted copied epoch. Coordinator startup then creates a fresh epoch.

Protected journals and workspace enrollment remain retained. Control-only upgrades keep saved configuration and workspace registration while starting fresh control state and session ownership. They do not migrate sessions or copied epochs.

Installation stages the new payload and records a typed replacement transaction under `<install-root>/recovery/replacement`, then swaps the ordinary directory under the parent activation lock. A failed swap restores the admitted payload and receipt, or keeps a finite recovery-required outcome when restoration cannot be proven.

The replacement snapshot is temporary and remains until the shell bootstrap verifies IDEA plugin activation and completes Codex registration. Only active plugin bytes occupy `plugins/kast-ide-hosted`; a pending candidate and prior baseline stay outside plugin discovery in `.kast-plugin-recovery`. The installer finalizes the replacement only after checking the new physical installation identity and active plugin identity. It removes the admitted previous payload and transient baseline on success. Failed effects preserve recovery evidence and report an incomplete installation. Explicit legacy migration admits old manifests and selector ownership; normal runtime configuration does not follow legacy aliases. Historical unknown artifacts remain subject to the existing ownership review rather than guessed deletion.

`installation-recovery.py detach --control-only` fences control launches. New control receipts do not own an IDEA plugin. Historical paired receipts remain evidence and control detachment preserves their plugin files; explicit historical paired maintenance is available only as `detach-legacy-pair`; preparing the next control recovery baseline removes host ownership from the new receipt without changing plugin files. It executes retirement only after payload admission. Verified retired state is quarantined; uncertain processes and journals stay preserved and produce `DetachedWithUnresolvedState`. A legacy paired maintenance receipt with incomplete plugin ownership cannot produce a clean legacy maintenance result. The standalone `prepare` operation supports explicit legacy recovery without executing an unverified installation. Python and JVM installation transitions use compatible POSIX record locks.

The legacy installer force path uses its verified release path with explicit reset authority; the public product bootstrap rejects that option.
Under the activation lock it fences and retires the selected and same-version target
services, removes same-user transport aliases and the exact derived upstream socket
directory, then moves payload/state and recovery bundles aside before another staging step.
The managed filesystem adapter owns transport cleanup and entry quarantine; the CLI owns process retirement and maps the finite preparation result to `FORCE_RESET` evidence. Unknown upstream contents fail closed. Force
replaces command collisions and starts with fresh workspace enrollment; source trees
are untouched. A force dry run verifies and reports without performing reset effects.
Force plugin activation moves the exact same-user Kast plugin entry into private recovery storage without following a symlink target. The private CLI, App Server, and MCP launchers are required; installation cannot publish a CLI-only payload.

Current harness installation is explicit and CLI-first; optional MCP initialization and discovery are passive.
The first explicit semantic request uses the shared managed workspace owner and waits for its exact endpoint.
The terminal MCP catalog does not expose manual refresh or `workspace_lifecycle`.
Its `add_declaration` tool plans, submits the exact canonical plan identity, applies, and verifies
within one call, attempting recovery if application is unverified. The App Server exposes the same public tool through current native mutation admission. If apply is cancelled, only a complete native `prior_state` or
`rolled_back` recovery settles the invocation as known and releases the App
Server workspace lane. Incomplete recovery leaves the lane protected.
The app-server suite is always installed. Activation can still be pending with
a finite reason when the host cannot start the service.

The shared catalog includes every qualified canonical tool.
Installation does not enable a login service.
Stage-only installation selects only Control and preserves Host files.
It rejects combination with control-only upgrade, which needs live Host admission.
The private stage-only option uses the existing typed `STAGE_ONLY` policy.
Both ordinary and staged installation report activation as `not-requested`.
Retired overrides reject before installation effects.

Read [configuration](../contracts/configuration.md) for ingress and ownership rules.

`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.

`assembleControlRelease` uses `controlVersion` for Control records, artifacts, and checksums.
`assembleHostRelease` uses `hostedPluginVersion` for Host records, archives, and checksums for the reviewed IDEA release lines.
Component releases
resolve latest within `control-v` or `host-v`; their versions need not match.
`ControlRelease` declares its required hosted contract. `HostRelease` declares its
provided contract and supported IntelliJ release line. Component publication
validates the record against archived bytes and the exact source revision.

`GenerateControlMetadataTask` derives `ide-host.json` from control build identities
and the required hosted contract, with no plugin archive input. The plugin embeds
its own provenance and complete provided contract. `assembleRelease` remains a
fresh-install convenience containing both artifacts and host metadata; the pair
is admitted by contract equality. Public product records additionally bind the managed runtime and native evidence.
Control installation manifests have no necessary plugin digest. Lifecycle
inspection retains historical records so old control processes can retire safely.

Release asset construction, checksums, SBOM inventory, and publication use the same archive inventory.

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

Selected IDEA discovery retains one canonical home across upgrades. Typed metadata resolution identifies its macOS ARM bundle and executable and rejects ambiguity and escapes. It admits the reviewed `262` and `263` release lines. Installation inspection includes the launch observation and persists the derived receipt. A legacy selection can still attach to its live lifecycle endpoint when cold-launch metadata is unavailable.

Control assembly packages `share/kast/provider-catalog.json` from the same hosted
schema projection as the CLI. The artifact contains hosted metadata and schemas,
with no executable grammar. Distribution layout requires it; runtime release
admission qualifies the actual staged file through the production provider.

Installation and mutation create no broker signing keys. Existing legacy key cleanup is a bounded migration effect after successful keyless service readiness, preserving unsafe or partial legacy state on rejection. Enabling the service from HOME starts the daemon without enrolling HOME; each fresh repository session selects its existing canonical Gradle settings owner.

The control payload includes private executables at `share/kast/libexec/kast-daemon` and `share/kast/libexec/kast-service`, inside the existing inventory and checksum boundary. Launchd invokes the daemon directly; the installer invokes service control without the public CLI graph. The login LaunchAgent retains the service label and daemon executable, adding only the private login argument. Offline recovery admits that entry only when removing the argument from its bounded bytes yields the retained service property-list document for the exact Codex-home profile; it also recognizes the older one-shot entry. The assembled-product check verifies the private launchers are present and executable; their argument and readiness rules remain with their Kotlin owners.

Harness setup preserves foreign configuration and rejects conflicting resources.
Only exact receipt-owned legacy registrations migrate after successful provider setup.
Standalone `kast install skill --output <parent>` exports the complete skill without provider configuration.
Extra, changed, and symbolic-link entries reject. Identical export preserves the directory.
Setup records selected guidance, but it does not prove that a client loaded or invoked the tools.

Legacy force-reset internals bypass old payload and receipt admission while preserving canonical scope and verified retirement. An exterior fence and persistent lock survive root replacement. Exact root-derived jobs and scoped process incarnations remain distinct from shared IDEA and Gradle processes. Erasure requires quiescent typed state; rejection preserves retained storage. Public force admission rejects managed state before detachment.

The retained lifecycle progresses through privately constructed fenced, quiescent, and erased types.
A single-use lease rejects repeated effect transitions.
Staged/installed reset states, force reinstall, and its staging compensation owner have been removed.
The exterior lock stays stable across resets.
OS and filesystem refusal remain finite failures.

Native installer dispatch explicitly binds the sole admitted HOME-derived root.
Public uninstall rejects an alternate root or nonphysical path before owned
removal. Installation checks existing physical ancestors before creating missing
directories, and force reset cannot resolve a parent alias into another tree.
Public command publication retains its own receipt-bound destination and rejects
parent aliases; that destination never selects a second installation.

The retained legacy [mixed-version native check](../../runtime/hosted/native/README.md)
uses the existing normal user installation and IntelliJ profile. It requires
three real compiler-query witnesses across C1/P1, C2/P1 and C2/P2, unchanged
IntelliJ/Host/socket/plugin identities through the Control-only upgrade, and
unchanged Control files and generation through Host installation. Inert archives
keep original bytes for restoration at the same destinations. Original runtime
PIDs, sockets, readiness and epochs are not restored as authority; the original
Control must start with a fresh generation and verified original bytes. Static
and routine tests do not establish that native behavior.

The native inventory rejects entry or byte overflow before hashing the crossing
input, retaining the resource, maximum and observed lower bound. Its limits match
the existing Control distribution budget. Runtime installation identity hashes the
physical root and ordered immutable payload paths and bytes; it is distinct from
the downloaded archive checksum. Restoration admits the original registered
workspace before effects and enables the restored service from that exact root.
The legacy original native loaded-version projection can remain unavailable; the
report retains that qualification and requires the private production coordinator,
new epoch and fresh generation to agree with ready service evidence. Candidate
Control status remains strict.

Each component release records its own source revision and archive checksum. The
runner also records its checksum and checkout state; components can come from
different source revisions. A fresh semantic RUN is permitted only for the exact
typed freshness rejection with MOVED at MODEL_CAPTURE or CONTENT_REVALIDATION
and the observed `restart_read` instruction. This harness intentionally accepts only
those two rejection stages. At most three independent `RUN` calls are permitted, with every rejection retained.
Focused helper tests prove bounded inventory, identity ordering and that recovery
predicate using case-owned inputs. They do not establish the required live
mixed-version result; the completed native proof remains a separate gate.

The legacy checkout adapter admits the selected IDEA bundle's executable JBR and Java
25-or-newer release before Gradle or staging. It scopes that runtime to its children.
The checkout path needs exactly one fully quoted `JAVA_VERSION` declaration.
Duplicate declarations, an unclosed quote, trailing text, and incompatible runtime environments reject before Gradle starts.
Installed Control launchers select modes in the shared native executable without JVM setup.
Nonempty `HOME` and `TMPDIR` values pass through explicit native system properties.

Full uninstall retains a receipt-bound exterior retirement journal across cleanup. Managed product removal retains an admitted recovery interpreter and payload tree through `UninstallManagedRoot`; legacy receipts retain their separate Host removal path. Unknown or changed evidence remains protected after Control removal. `IntegrationDisconnection` removes receipted guidance directories without deleting foreign siblings.

`ProviderRegistration` stores CODEX/COPILOT routing intent before provider mutation, not resource ownership.
Ordinary uninstall removes those recorded resources through current provider inventories before Host retirement; conflicts preserve the Host.
The registration lock protects a final empty-record check through payload removal.
Explicit harness removal forgets only its selected route after verified cleanup.
Kotlin schema-2/schema-3 receipts and Python replacement preserve the routing records.
These source rules do not establish native provider cleanup or product qualification.

User-state inventory retains endpoint locks and physical identities through retirement and deletion. Unknown contents, live hosts, changed identities, malformed or unsettled mutation evidence, or a WAL without a completed checkpoint reject and preserve recovery state. The owning SQLite adapter inspects exact schemas and bounded typed plan, attempt, journal and receipt records read-only; it does not initialize, checkpoint or manufacture recovery. Upgrade does not invoke this full user-state cleanup.

## Managed composition and selection

`kast versions` exposes stable and a five-entry catalog of published main builds.
The catalog orders immutable identities by main ancestry and keeps original candidate and promotion provenance.
Upgrade captures one typed selection and persists its tracking or pinned policy with installed identity.
An unqualified pinned upgrade rejects before release access.

The managed coordinator captures Control, Host, profile, command, configuration, and receipt preimages in one typed journal.
It fences calls before settlement and coordinates the existing effect owners under admitted locks.
Stopped state, compatible hot reload, and explicit restart use separate finite strategies.
Pending recovery precedes selection and consumes only the recorded transaction.
Unresolved restoration preserves the fence and returns `RECOVERY_REQUIRED`.
The public bootstrap uses this complete-product selection contract rather than the legacy component installer.
These source rules do not prove a new installed semantic session or successful live reload.

## Detailed distribution routes

- [Installation and managed files](../distribution/installation-and-managed-files.md) explains Control replacement and independent Host receipts.
- [Native management and connections](../distribution/management-and-connections.md) explains public command scope and host-owned registrations.
- [Upgrade, shutdown, and reset](../distribution/upgrades-shutdown-and-reset.md) traces guarded lifecycle transitions.
- [Release candidates and promotion](../distribution/release-candidates-and-promotion.md) explains immutable source and artifact proof.

Host receipt admission now qualifies a device change through the recorded root inode and owner.
It keeps a consistent mapping from the receipt's device identities to their current identities before it examines the current destination and inventory.
Removal permits missing recorded artifacts, but each remaining artifact must match its receipt.
Reinstall still needs the exact inventory. Foreign or changed artifacts remain protected.

The private tool RPC also admits `plan-query` before installation composition.
That request-only path supplies scope advice without starting an installed host.
It does not add a public native management command.
