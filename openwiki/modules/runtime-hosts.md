---
type: Kotlin Module Group
title: Runtime and process hosts
description: The existing IDEA plugin owns semantic execution; CLI and App Server
  own installation, transport, sessions and canonical native mutation admission.
resource: file://runtime
tags:
- kotlin
- runtime
- server
- indexer
- cli
code_sources:
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerJavaRuntime.kt
  symbols: [BrokerJavaRuntime]
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolRegistration.kt
  symbols: [DirectToolRegistration]
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  symbols: [KastDirectToolSession]
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/HostedServiceAdmissionScan.kt
  symbols:
  - observeHostedEntries
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/HostedEndpointDocuments.kt
  symbols:
  - RecordedHostedEndpointOwner
  - DeclaredHostedEndpoint
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/HostedAdmissionEvidence.kt
  symbols:
  - HostedAdmissionEvidence
  - BoundedHostedAdmissionObserver
- path: app-server/src/main/resources/control/hosted-endpoint-owner.schema.json
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstallationLifecycleFence.kt
  symbols:
  - InstallationLifecycleFence
  - InstallationLifecycleStartAdmission
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ResetActivationAdmission.kt
  symbols:
  - ResetActivationAdmission
  - ResetActivationLease
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/InstalledToolAdmission.kt
  symbols:
  - InstalledToolAdmission
  - observeInstalledToolAdmission
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/InstallationLifecycleFenceTest.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/SessionRequests.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeAdmission.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeDocument.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeObservation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionMessages.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionPolicy.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStore.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadBindingDocuments.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadCatalogStore.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadMigrationObservation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationResponses.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationCapacityLimit.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFence.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationRecords.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/FileInvocationRecords.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationMigrationObservation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledWorkspacePreparation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceDemand.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonWorkspacePreparation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparationActivity.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/DaemonManagementProtocol.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonManagementClient.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/DaemonOperationProtocol.kt
  symbols:
  - DaemonOperationProtocol
  - DaemonOperationSelection
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonOperationClient.kt
  symbols:
  - DaemonOperationClient
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonManagement.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorRoutes.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastCatalogObservation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastInputSchema.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastCatalogSource.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastDirectInvocation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastInvocationAdmission.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleApplication.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleReadiness.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedVfsRefreshOutcome.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedGradleChangeTracker.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/WorkspaceStartupEnrollment.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerPublicEndpoint.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/ManagedCodexUpstream.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/UnixSocketOwnership.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/CodexUnixWebSocket.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerControlRoute.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/PersistentBrokerService.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerManagement.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/LegacyLoginBootstrap.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ServiceLoginAgent.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerServiceStartLock.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerLaunchdServiceDocument.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/PublishedBrokerServiceCommand.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/NativeCodexReadiness.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinatorClient.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ConfigurationAppliedInspection.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerStatus.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerStatusDocument.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedTransportObservation.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadBudgetReports.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryContinuations.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
- path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
- path: docs/reviews/live-semantic-read-acceptance.md
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt
  symbols:
  - InstalledCoordinator
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  symbols:
  - main
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/IndexingWait.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/OwnedHostedEndpoint.kt
- path: runtime/hosted/build.gradle.kts
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/compatibility/IdeHostCompatibility.kt
  symbols:
  - IdeHostCompatibilityPolicy
  - IdeReleaseLine
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointProtocol.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedPeerCancellation.kt
  symbols:
  - HostedPeerTermination
  - dispatchUntilPeerTermination
- path: query/protocol/build.gradle.kts
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeSocketClient.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeQuery.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/ide/IdeCommands.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeCoordinator.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/WorkspaceStartupEnrollment.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/ProjectCloseAuthority.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedResponse.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResponse.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceStateStore.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyGraph.kt
  symbols:
  - sourceDependencyClosure
  - sourceExpiredEntries
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticCheckpointStore.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateRecords.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedRetentionMeasurements.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticStateRecords.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticRetentionOwnership.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceRetentionAdmission.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourcePublicationSession.kt
- path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyExpiryTest.kt
- path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceClaimExpiryTest.kt
- path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyBindingTest.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedRetentionOwner.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedContract.kt
  symbols:
  - HostedContract
  - HostedCompatibilityPolicy
  - HostedCompatibilityRequirements
  - HostProvenance
- path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/CanonicalHostedContract.kt
  symbols:
  - CanonicalHostedContract
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCompatibilityMetadata.kt
  symbols:
  - HostedCompatibilityMetadata
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/HostedServicesObservation.kt
  symbols:
  - HostedServicesObservation
  - observeRunningHostedServices
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/IdeLifecycleClient.kt
  symbols:
  - IdeLifecycleClient
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/RegisteredHostedServices.kt
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/HostedServiceStatus.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatusRendering.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResultRetentionObservation.kt
sources:
  - id: openwiki-source-948a9971897847e7bf395c0e
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerManagement.kt
  - id: openwiki-source-d042914045998ac241003506
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerJavaRuntime.kt
  - id: openwiki-source-4497996830a7e09fc6368cb3
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/HostedServiceStatusProjection.kt
  - id: openwiki-source-c14bf8abf46d0827988ea4e4
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeResponseCause.kt
  - id: openwiki-source-2c3b08dc9c41e6f8d4b897ec
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeResponseEvidence.kt
  - id: openwiki-source-14d52fd94b27c13881cd2d4d
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeResponseFrame.kt
  - id: openwiki-source-7c05e12b47d08ef75636350e
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeSocketClient.kt
  - id: openwiki-source-e0ba61e5e6f650bc7c5673a6
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/HostedAdmissionEvidence.kt
  - id: openwiki-source-cce89281ffa7a590648e9d33
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/HostedServiceAdmissionScan.kt
  - id: openwiki-source-8d38b171e35bb98b83dfbe58
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstallationLifecycleFence.kt
  - id: openwiki-source-88f09847a1cc4b4d806ffe37
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledWorkspacePreparation.kt
  - id: openwiki-source-f60c4760fc0979d7f2c54537
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/PersistentBrokerService.kt
  - id: openwiki-source-41fd3211f83e8818b2bbfa99
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt
  - id: openwiki-source-fbea068a13125b931c0783c9
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/SettledOutputRejection.kt
  - id: openwiki-source-d0f22f03abacc7b6ee9cd104
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/RegisteredHostedServices.kt
  - id: openwiki-source-8ece5abae2f9aab84f07b127
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/ResetActivationAdmission.kt
  - id: openwiki-source-5e6cd1a13b9fb0a90d7aea12
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/WorkspaceStartupEnrollment.kt
  - id: openwiki-source-6b063183ca57510a100a1579
    resource: repo://app-server/src/main/resources/control/hosted-endpoint-owner.schema.json
  - id: openwiki-source-d719877f4ac62761c9b248b1
    resource: repo://app-server/src/test/kotlin/io/github/amichne/kast/appserver/InstallationLifecycleFenceTest.kt
  - id: openwiki-source-3adda3013ad0805c51b3ac26
    resource: repo://app-server/src/test/kotlin/io/github/amichne/kast/appserver/RegisteredHostedServicesTest.kt
  - id: openwiki-source-aa73ce5eea589b2954d82387
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolRegistration.kt
  - id: openwiki-source-c58b3f38ca46d44dd30a8771
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/InstalledToolAdmission.kt
  - id: openwiki-source-7685e27063adfdefffa21252
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - id: openwiki-source-1d161f3eb7933044519fd33a
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - id: openwiki-source-51fd272ea7471c9e42b84369
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatusRendering.kt
  - id: openwiki-source-a0efdefba63982f6209d2b1d
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/HostedServiceStatus.kt
  - id: openwiki-source-1905c35a82810ccf74696707
    resource: repo://protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/CanonicalHostedContract.kt
  - id: openwiki-source-680008eb9e24b45cf91f6d9d
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
  - id: openwiki-source-95d1815c680b1c389e562577
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCompatibilityMetadata.kt
  - id: openwiki-source-5efa910d729623be2b070bc2
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt
  - id: openwiki-source-b711ac75af08966d13a95b22
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
  - id: openwiki-source-30bb99efffb5b58bc0e7db17
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedPeerSiteCompletion.kt
  - id: openwiki-source-bd35e661989c1e05fe3916b5
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedPeerSiteReadPort.kt
  - id: openwiki-source-9d98513f38a6f5a6f98d8cdc
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResultRetentionObservation.kt
  - id: openwiki-source-dc64f3c714e0e7abfefc0f56
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt
  - id: openwiki-source-0e5288eaa02fcf3a6d2748f0
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedTraversalOperations.kt
  - id: openwiki-source-7f5d36c76505fdd995af1f49
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleApplication.kt
  - id: openwiki-source-eed0394918ec9e3d4c1a1c51
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/RegisteredPeerRead.kt
  - id: openwiki-source-738d50046b5f0d1312b7fcb7
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDiagnostics.kt
  - id: openwiki-source-08d8bbce5989f3afa7c532a7
    resource: repo://workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryRetentionDiagnosticsTest.kt
  - id: openwiki-source-73144b588342a1cc6d17731e
    resource: repo://workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedTraversalPhaseDiagnosticsTest.kt
generated: { by: "codex", at: "2026-10-09T14:52:54.878Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-09T14:52:54.878Z
---

# Runtime and process hosts

`runtime:hosted` is the sole production semantic host. Its project service uses the already open IDEA project and delegates admission, model capture, and read epochs to `workspace:intellij-read`. IDEA owns imports and incremental indexes. Kast has no isolated indexer, second workspace importer, index-copy path, or topology backend in the shipped runtime graph. The retained topology modules and their separate SQLite adapter remain buildable for upcoming work.

The plugin archive contains the semantic contracts, services, IntelliJ adapters and durable change stores. Its name binds the independent hosted-plugin version and IDEA release line. Runtime admission retains exact observed IDEA/Kotlin identities while allowing the configured release-line compatibility policy. The historical [native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md) states the tested baseline; a build or schema check alone does not expand that qualification.

Kast Control and Kast Host communicate through one exact `HostedContract`:
protocol identity, operation registry digest, wire schema digest and capability set.
Host version remains provenance and never decides compatibility. The digest covers
the actual hosted schemas and request/response serializer graphs, including the
application lifecycle exchange. Agent descriptions and management commands are
outside this boundary. A breaking hosted behavior change requires a protocol
identity change even if its serialized shape is unchanged.

The existing project describe exchange and application inspect exchange supply
required live compatibility evidence. Both clients admit that evidence before
sending subsequent operations. Older hosts without it reject. The loaded plugin
reads provenance and contract from its own packaged runtime; control replacement
cannot change these files. Every later connection repeats admission.

`HostedSemanticServices` composes request-scoped symbol, source, relation and diagnostic services from one admitted project/read context. Canonical queries, planning and post-write verification share this factory. It does not retain a project read across requests or supply project-opening authority.

`HostedResponse` retains the original complete, qualified or rejected outcome alongside its encoded document. Encoding and response-size failures retain the original semantic result. The endpoint observer reports semantic classification rather than treating every written frame as completion. [Hosted queries](../flows/hosted-query.md) describes lifetime and cancellation.

The hosted provider admits public intent tools and supported changes through the existing IDEA plugin. A missing host rejects. The private installed `kast` executable retains product-version inspection for release identity; former semantic CLI routes reject.

Installation lifecycle admission fences automatic coordinator startup while detachment, shutdown, transition or reset markers remain. Unknown observation rejects. A reset marker permits startup only for an exact installation-bound `ACTIVATING` record and a currently held exclusive reset lease; malformed, foreign, oversized or unlocked records remain fenced. Installed direct tools separately reject shutdown or reset markers, including unproven absence, before workspace discovery or dispatch. This allows the reset owner to establish daemon readiness while tool calls remain stopped until it lifts the exterior fence. The focused `InstallationLifecycleFenceTest` covers absent installation state, held versus released activation locks, and malformed, foreign and oversized records. See [distribution lifecycle](distribution.md) for retirement, cold staging and readiness obligations.

App Server owns persistent sessions, the invocation journal, project-close controller confirmation, provider qualification and workspace lanes. A cancelled change apply settles the journal and releases its lane only after native recovery proves `prior_state` or `rolled_back`; unresolved effects retain uncertainty. `CoordinatorControl` provides bounded owner-correlated status with zero worker reservations and rejects retired worker demands. Service enablement starts the daemon without enrolling its current directory. New Codex threads discover the nearest canonical `settings.gradle.kts` owner, or validate the explicit exact owner, before registration and binding. Broad historical HOME registration cannot replace that repository owner; saved incorrect bindings reject without retargeting. Registration preserves closed failures and emits bounded, payload-free startup evidence. Thread-binding validation for resume and invocation remains read-only. Workspace enrollment remains routing data. It grants no importer or worker capability. Provider qualification verifies the packaged catalog against the canonical registry. Installed semantic provider calls first use the shared workspace preparation owner. They then use the App Server-owned IDEA client directly; canonical changes use the same workspace demand before immutable plan loading, preserving canonical request admission, finite failures, root/host binding and operation output validation. Pure request and result projection lives in `protocol:wire`.

Installed service admission uses the existing explicitly owned Java candidate when supplied, otherwise the saved IDEA selection's bundled JBR. It admits canonical executable paths and a bounded Java release declaration with feature 25 or newer into `BrokerJavaRuntime`, which command construction retains. Exactly one `JAVA_VERSION` declaration must be present and fully quoted; a malformed duplicate beside a valid declaration also rejects. An absent selection or incompatible runtime rejects with `JAVA_RUNTIME_UNAVAILABLE`; ambient `JAVA_HOME` supplies no fallback. A managed upstream socket alias is admitted only when its resolved socket is open in the launched Codex process; the alias and target identities are retained and rechecked before and after later connections. A successful WebSocket probe alone does not prove ownership. Unproven aliases reject with `SOCKET_ALIAS_OWNER_UNPROVEN`.

Planning stores immutable live plans; applying and recovering require their exact canonical identity and current native admission. Installation and apply create no broker keys or replacement trust mechanism. Read [request dispatch](../flows/request-dispatch.md) and [change lifecycle](../flows/change-lifecycle.md) for the complete boundaries.

The [installed knowledge contract](../contracts/installed-knowledge.md) describes
`kast knowledge`, its isolated PSI extraction, verified module ownership and
scoped guide resources staged with the control product.

HostedSemanticServices projects the context’s admitted time allowance into query and diagnostic-scope budgets; configured capacities remain separately available. Schema-4 receipts retain the effective allowance, remaining host time, completion reserve and finite admission outcome.

`HostedReferenceStore` retains at most 16,384 token entries and 32 MiB of token UTF-8 bytes by default, with typed read-limit overrides. It clears the previous table on an admitted epoch change or project disposal. Repeated references reuse the same handle, and current-epoch handles are never evicted for capacity. When a new entry cannot fit, issuance keeps the valid inline token and records `REFERENCE_INLINE_CAPACITY`. No PSI, source payload, or in-process authority is retained in the table.

`HostedQueryResponse` bounds the actual encoded query bytes. An oversized positive result becomes a qualified prefix carrying `BYTE_LIMIT_REACHED`, all existing limitations, the original proven lower bound, and every item failure. If mandatory evidence alone cannot fit, the response remains rejected.

Runtime installation admission uses the shared distribution traversal budget rather than the historical 4,096-path broker ceiling. `PAYLOAD_LIMIT_EXCEEDED` remains distinct through coordinator, service, and client failure projections. Both lifecycle transitions and standalone recovery fences block startup.

`ide status` includes a fresh passive `readiness` observation. `admission_ready`
proves existing-project admission checks at that instant; `unavailable` retains
the closed failure and conditional recovery guidance. It creates no semantic
read permit, source epoch, import, or indexing wait. Query admission still checks
saved content and current authority. Older hosts may omit the new observation;
absence provides no readiness proof.

`HostedQueryContinuations` owns three bounded stores for the current project read
authority: query execution/results/output, source native cursors/output, and
diagnostic scan/output. Lookup follows fresh host admission and checks the
semantic basis. Claims, cached fitted pages and their advertised dependencies
share each owner's entry, charged-byte and TTL bounds. There is no parallel
output-suffix pool or separate native source registry. Allocations remain hidden
until final freshness, deadline and native-drain checks permit atomic publication.
Commit replaces consumed producer payloads with the exact immutable page and
prunes unadvertised children. Cancellation releases only its own attempt. Project
disposal or epoch replacement retires all owners; replay never renews token age.
All owners check each token's original deadline even when an active claim pins
its physical storage. A younger page remains valid only while every referenced
dependency remains within its original age bound. Claimed storage does not admit
expired tokens or permit publication after expiry. The source owner prunes expired
and broken unclaimed dependency pages while preserving claimed storage for cleanup.
Current-byte, entry-count and byte high-water gauges remain separate for each
owner and estimate quota accounting rather than heap memory. See the
[hosted retention bounds](../flows/hosted-query.md#hosted-continuation-retention-bounds).

Connection admission bounds parallel semantic dispatch; each hosted read independently owns a bounded lifetime permit. Epoch ownership transitions and mutation application retain their narrower synchronization.
`CONNECTION_RELEASE` is emitted after the admitted connection's semaphore permit
is released, so native fault fixtures can wait for a correlated drain witness
before the next health request. The structured observer retains finite outcomes,
durations and byte counts without request or response payloads. Read rejection
projection preserves any admitted execution report through fitting and encoding.

Read containment keeps the admitted execution report when a timeout, final freshness check or publication failure rejects the operation. The executor records the report at semantic admission; failure projection does not reconstruct it from defaults. Pre-admission failures carry no grant. Output fitting retains the original semantic outcome internally when publishing an oversized or unencodable response fails.

Enabled persistent Codex integration defaults to installation-owned private discovery.
The CLI supplies its endpoint explicitly and the desktop façade attaches to the
same coordinator, so a standalone native Codex daemon can coexist. Explicit
`codex-control` mode selects one canonical public socket per `CODEX_HOME`.
Endpoint policy participates in service identity while the upstream remains private.
Canonical startup completes native initialization before readiness publication;
the private coordinator qualifies host attachment separately. The existing session
hub owns per-client initialization, request correlation, thread/controller ownership
and native tool refinement. An unreachable socket is not ownership proof and cannot
be unlinked by startup. An absent Kast service rejects an occupied canonical endpoint
before sending the Kast status protocol.

Status reports lifecycle, endpoint ownership, native protocol, catalog and upstream
observations separately. Service status requires the requested service identity;
configuration inspection retains the proven incumbent identity so it can report
unapplied configuration changes. Status leaves semantic readiness unobserved rather than
inferring IDEA authority. Installed transport acceptance does not qualify stock
interactive CLI or Desktop tool exposure; the explicit CLI route and Desktop
façade remain pending those gates.

The hosted endpoint also composes a workspace refresh owner outside `workspace:intellij-read`. Its internal socket retains file refresh, Gradle model reload, bounded status and one task-success rule per project. Public `workspace_lifecycle` requests do not select refresh effects. The direct refresh request remains for disposable native acceptance and requires an already linked project. Initial linking is admitted only through application lifecycle opening; semantic request admission saves project-owned documents and waits for native incremental recursive VFS refresh before entering the passive compiler read. Explicit file refresh retains forced dirty marking for external writes. Gradle-aware VFS events mark the imported model stale; a read returns a typed presemantic blocker, and the coordinator reopens and retries one safe read after native import.

Fresh semantic reads additionally use `ReacquiringQueryReferences`, backed by the
separate detached exact-locator store. Its request-local accounting charges
recovery work and elapsed time before admitting the remaining semantic budget.
Older-epoch locators can be evicted to retain current handles. Continuation stores
and mutation planning do not use this capability. The
[query protocol](query-protocol.md#automatic-acquisition-for-fresh-reads) specifies
the identity checks and returned handle metadata.

The plugin additionally owns one `IdeLifecycleApplication` service and user-scoped control endpoint per selected graphical application home. It remains available with zero projects, advertises actual build, host incarnation and capabilities, and retains at most 256 operation records. The endpoint reuses existing ownership and framed transport. A dead endpoint with the same root and socket can be reclaimed after a plugin upgrade without matching its old capability catalog; the absent PID, exclusive lock and unchanged file identities still govern retirement. Project semantic services remain project-scoped. The agent-only `workspace_lifecycle` tool calls the App Server-owned lifecycle client directly and exposes inspect/open/present/release/close/status. `request_user_close` requires the existing controller lease and an exact local confirmation for one native target. Preparation cannot produce that confirmation. The daemon uses opening before semantic dispatch and never obtains user-close authority from that preparation. Existing-project opening waits through active import and requests one linked Gradle model reload on first attach, after tracked Gradle changes, or when the cached model is unavailable. A clean subsequent opening reuses the admitted model. Project-owned documents are saved before refresh or read; a failed save rejects. An indexing transition rejected before semantic evaluation waits briefly for smart mode and retries the read once. Canonical reads discard and replay results invalidated by a moved VFS epoch within the same host deadline; change workflows retain one evaluation.

App Server qualifies its complete tool catalog from the bounded packaged provider
contract and canonical registry. It has no Kast process executor or CLI version
probe. The provider retains contract identity across qualification and startup;
a changed contract rejects before execution. Mutation authority remains with current native plan and recovery admission.

Catalog qualification emits one bounded typed stage/outcome observation per contract
read. It retains source, document, projection, metadata, input-schema and output-schema
failures without logging catalog contents. Request unions may nest, but every
leaf must be a closed object; empty unions and open leaves reject.

The coordinator also owns `/kast-management` on the same private Unix socket.
Versioned typed coordinator/session status, registration and controller requests
bypass Codex host admission.
Registration requires the exact installation, epoch, generation and configuration
identity, and checks the existing lifecycle/stopped fence before enrollment.
The CLI verifies published service ownership before requesting registration;
offline or unproven service state cannot fall back to direct registry writes.
Canonical workspace identity and revision survive acknowledgment admission.
Unknown protocols and unobserved replies remain finite failures, with no automatic
retry. Both control routes share the same connection budget. The shared session
owner enforces controller changes against actual connected observers, leases,
active turns and pending approvals. Native clients cannot assert another client's
identity to claim or release control. Public status reads existing session state
passively; it does not initialize or probe Codex. Initial installation bootstrap
retains its existing owner.

The coordinator retains workspace preparation operations independently of Codex
admission and management connection lifetimes. Exact canonical settings roots
coalesce to one request ID and native open operation. Native pending observations
must preserve host/request identity and advance monotonically through opening,
importing and admission. Completed records retain root, host and project identity;
they are historical observations, not authority to skip fresh native admission.
Blocked operations and elapsed deadlines do not automatically replay opening.
A bounded 256-record table rejects excess roots without discarding evidence. Close
settles pending records without closing IDEA projects. Preparation events expose
bounded typed stage/outcome evidence without roots or payloads. Installed tool demand shares this owner and waits within the canonical readiness
budget. Before one semantic exchange, it requires a fresh selected-application
inspection and an exact project descriptor match. A proven host change removes only
the current root binding and starts one fresh preparation before semantic dispatch;
the historical record remains. A second change rejects. Transport failures never replay the
semantic request. Preparation rejection retains its finite cause and operation ID
as known pre-execution failure evidence.

Hosted provider calls use the shared workspace demand owner and the existing-IDE client. Canonical mutations preserve exact plan identity. Complete, qualified and rejected outcomes retain distinct replies. The former `/kast-operation` CLI RPC route is removed.

Launchd invokes the private daemon entry point without the public CLI command graph. The managed readiness environment is required at ingress and then qualified by the existing coordinator. Published-command recovery admits the private daemon form only when the entire plist matches the document generated from the recovered command, including launch behavior and logging. It still admits the earlier `kast broker serve` form for retirement.
On enable, the login variant of the exact service plist is published only after the service and its private receipt are qualified. It retains the same label and daemon executable and adds the private `--login` argument. At login the daemon verifies that exact agent, retained service receipt and loaded launchd label under the service lock before clearing a prior stop. Explicit disable removes the agent, so login cannot override it. An unknown file blocks lifecycle effects. The legacy bootstrap command remains available to converge an older one-shot entry on the direct service job.
Installation activation and new-release retirement use a separate private
service-control entry point. It admits registration, enable, disable, stop, destructive repair. Older installed
releases retain their admitted public CLI disable path only during retirement.
The legacy login bootstrap is admitted only from an exact generated file under
owned, physical LaunchAgents directories with private file permissions. Enable,
disable, and destructive repair reject a foreign or changed agent before service
effects; the marker text alone grants no ownership.

Invocation replay evidence lives in private hash-sharded records. The store reads
only the addressed digest, preserves its input fingerprint and finite phase, and
rejects attempts to settle a terminal record or a historical admission owned by a
previous process. Active capacity is separate from durable history; the response
cache evicts only completed results at its separate bound. Durable admission
precedes workspace submission; known pre-execution rejections settle
before response publication. Duplicate active calls join the original result.
An evicted completed response cannot authorize another prompt or execution. Legacy migration validates and verifies a
locked stage before publishing the layout marker and retains the original
journal. Incomplete migration, unsafe paths, malformed records and lock contention
remain typed failures. Migration observations contain only stage and outcome.

Thread bindings use private digest shards and lazy per-thread validation. A missing
historical workspace does not prevent opening the daemon or using another bound
workspace. Current records retain catalog, working directory, workspace and typed
installation/epoch ownership; conflicting rewrites reject. Locked legacy migration
validates historical identity without claiming current filesystem authority,
verifies a stage, publishes its version marker atomically and retains the source.
Legacy records return `NEW_CONVERSATION_REQUIRED` and cannot be overwritten by a
new binding. Codex history is outside this store. Read/write failures retain their
finite causes through broker projection. Both durable stores share the scoped
`PrivateRecordFiles` effect owner; their record schemas and transition rules remain
separate.

Private update preparation retains a candidate and request identity while active
owners supply finite blockers. Status alone never seals. The quiescence check and
seal share the mutex used by lazy frontend creation, session ingress and management
mutations. A seal rejects new work; cancellation reopens only an uncommitted seal,
and commit is idempotent. Pending upstream requests are bounded and lost requests
retain reconciliation uncertainty. Structured update observations contain finite
stages/outcomes without identities or payloads. The installed management client
qualifies each update reply against the exact daemon target, candidate digest and
request identity; malformed pending blockers and mismatched commit or cancel
responses reject.

The installer-facing upgrade boundary observes the exact launchd label and
retained service markers before requesting a seal. Only absent launchd and absent
markers establish that no managed daemon needs retirement. Active service
admission retains typed blockers and a permit bound to the daemon target,
candidate and request; rejected or ambiguous observations do not authorize
replacement. Installer activation commits that permit before prior-service
retirement. A pending update leaves the selected installation and command links
in place for a later upgrade attempt. If retirement fails after commit, another
ordinary attempt for the same candidate can resume from the active daemon's
qualified committed request. A different candidate or daemon identity rejects.

The coordinator's passive management reply projects its captured running control version, currently ready workspace roots, observed live connection count, and bounded fresh compatibility observations for each known host. Each host observation retains its project root and host identity, plugin provenance and exact compatibility result; missing evidence remains unavailable. A pending frontend leaves connection count unavailable. The native management client correlates this reply with the installation epoch and live service generation.

Control status distinguishes installed and running Control versions and reports
each observed Host's identity, loaded plugin version and compatibility. It reads
the current workspace registry and selects only its admitted exact physical
settings roots for fresh bounded live describe/status exchanges. A one-shot Tool
RPC query in another process does not need to populate the daemon client's query
history. Status does not prepare or enroll workspaces, open projects or select an
ancestor when a registered root moves or loses its settings file.

A rejected registered root retains its known path with an unavailable result. A
rejected registry instead reports REGISTRY_UNAVAILABLE with its registry path and
closed failure; it invents no host or workspace identity. Compatible, incompatible
and unavailable remain distinct per observed host, and missing live evidence
never becomes compatibility proof. The passive exchange retains its existing
750 ms total and 500 ms per-host bounds.

Control upgrade preflight filters the existing endpoint directory to the project
family before decoding or charging live-host capacity. It validates the owned
regular descriptor with a Control-local owner envelope and excludes only proven
absent processes, before requiring a current contract or an existing physical
root. Unknown liveness rejects. Every live survivor passes the complete current
descriptor, exact physical root and socket, and the existing live describe
exchange with matching owner and Host identity. Legacy records for exited owners
therefore cannot obstruct admission, while unsupported live hosts still reject.
The owner envelope is outside the hosted-contract digest inputs.

Preflight scan diagnostics contain closed stage and outcome variants with finite
failures. Each distinct tuple is emitted at most once per scan, keeping evidence
bounded without including paths, process identities or payloads.

The hosted service factory now supplies the K2 value-flow adapter, exact producer seeds, callable model revalidation and boundary-site revalidation from the same admitted project, source model and observation capability. Successful native acquisition contributes measured work and time to the shared request accounting; model interpretation remains outside the compiler adapter.

Each semantic diagnostic receipt initializes seed-read, declaration-model, boundary-site-model and value-flow provider counters to zero before observations. Site revalidation rejection counts also begin at explicit zero and retain actual increments through receipt publication. Retained-page captures can therefore distinguish an observed zero from an unavailable counter. These bounded, host/epoch-correlated runtime measurements do not grant compiler, model or completion authority.

The existing client response boundary emits private typed frame, body and
admission activity. Frame bounds precede body acquisition; finite canonical-wire,
live-basis and completion causes survive in diagnostic evidence beside the
existing public failure. No response payload or opaque identity is logged.

Hosted traversal retains the existing traversal service and relation authority.
Its observation adapter enters `TRAVERSAL` around coordination and restores it
after relation reads. The shared diagnostic clock records exclusive phase
intervals, leaving native inventory and confirmation in their own phases; these
intervals are not inclusive traversal duration or CPU measurements.

`HostedCanonicalQuery` supplies an explicit retention observation callback to the canonical protocol. The adapter maps the sole invocation issuer's capture start and success, each closed capture failure, and issuance outcomes into existing bounded `RETENTION` diagnostics. It records no source payload or handles and creates no new state or semantic authority. Typed adapter tests and the actual diagnostic serializer golden cover success and rejection signals.

The peer-site adapter uses the already created lifecycle application's registry without activating it. Exact registered host, canonical root and non-disposed project must agree before an independently owned child read is allowed. That read revalidates the complete target basis before restoring declaration tokens, uses single evaluation and final freshness validation, and binds the actual admitted grant, aggregate work and whole elapsed time into a completed receipt. An over-granted child or missing/changed authority fails closed. It neither opens the peer nor traverses target flow.

One-shot Tool RPC invocation checks compiled `AgentToolName` registrations without
constructing discovery schemas. The complete hosted and direct catalogs are projected
lazily on discovery, with exact name and count checks against the same registration
owner. Canonical public request admission and installation shutdown admission still
precede workspace preparation and execution.

Only known root admission failures before dispatch (`WORKSPACE_START_UNAVAILABLE`, `WORKSPACE_START_NOT_DIRECTORY`, `WORKSPACE_ROOT_MARKER_NOT_FOUND`, `WORKSPACE_INVALID_ROOT_MARKER`) settle as known without fencing recovery. Other provider or interrupted effect outcomes preserve uncertainty unless native recovery proves settlement.
