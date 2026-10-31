---
type: System Explanation
title: Functional package coverage
description: Routes every production package to its functional explanation and states
  the limits of coverage evidence.
tags:
- kast
- architecture
code_sources:
- path: settings.gradle.kts
- path: runtime/hosted/build.gradle.kts
- path: build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerManagement.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/AgentSessionBootstrap.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/CliRemoteClientHost.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/admission/CodexHostExecutable.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/CanonicalRoot.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStore.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/BrokerFailureDocument.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/copilot/CopilotFixtureProjection.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/BrokerProcess.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicQueryPackageName.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerControlRoute.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/schema/CanonicalJson.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt
- path: build-logic/src/main/kotlin/conventions/GenerateKnowledgeDocsTask.kt
- path: build-logic/src/main/kotlin/conventions/jsoncontracts/JsonContractBaseline.kt
- path: build-logic/src/main/kotlin/support/architecture/ArchitectureAdmission.kt
- path: build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt
- path: build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt
- path: build-logic/src/main/kotlin/support/architecture/projection/ArchitectureProjection.kt
- path: build-logic/src/main/kotlin/support/knowledge/GenerateInstalledKnowledgeTask.kt
- path: build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
- path: build-logic/src/main/kotlin/support/tasks/nativefixtures/NativeFixtureJvmArguments.kt
- path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/AddDeclarationApplyService.kt
- path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/AddDeclarationApplyObservation.kt
- path: change/intellij/src/main/kotlin/io/github/amichne/kast/change/intellij/BoundedMutationSourceRead.kt
- path: change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt
- path: change/protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocol.kt
- path: change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryPreparation.kt
- path: change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/AddDeclarationSemanticEvidenceChecks.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/CanonicalLiveOperationSupport.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/HostedRejectionSchemas.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/CliOptions.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/appserver/AppServerCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/change/ChangeCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/codex/CodexCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/ide/IdeCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/knowledge/KnowledgeCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/product/ProductCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/source/SourceCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/workspace/IndexCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolDocument.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationInput.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/AdmittedKnowledge.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/projection/CliBoundaryDocuments.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
- path: diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticEnumeration.kt
- path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/BoundedDiagnosticEnumeration.kt
- path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/BundledManifest.kt
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/bootstrap/ModelInputFailure.kt
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/BrokerPublicEndpointMode.kt
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/gradle/GradleImportEnvironment.kt
- path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ControlPayloadInventory.kt
- path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/endpoint/InstalledEndpointAlias.kt
- path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/CanonicalWorkspacePublication.kt
- path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/HostedWorkspaceStateFiles.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteTopologyRelationIndex.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/HistoricalArtifactAdmission.kt
- path: evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/cli/HistoricalOfflineDocuments.kt
- path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AdmittedReadRejections.kt
- path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
- path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/CanonicalDiagnosticEnumMappings.kt
- path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/metadata/HostedCapabilityDescriptor.kt
- path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalCallbackSchemaDocuments.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryCheckpointPartialSymbols.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/AdmittedBoundaryModel.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/AdmittedRelationPreparation.kt
- path: relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/DeadHostedEndpointOwner.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/AutomaticReadinessAction.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/HostedWorkspaceRefresh.kt
- path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDeclarationVisibility.kt
- path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/InstalledIntellijSourceReadPort.kt
- path: source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt
- path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CandidateSelector.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/BoundedLexicalCandidates.kt
- path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt
- path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactRead.kt
- path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt
- path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt
- path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/historical/HistoricalCanonicalEncoding.kt
- path: topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/AdmittedSourceRootEnumerator.kt
- path: topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/historical/IntellijHistoricalCaptureAdapter.kt
- path: topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/GraphIndex.kt
- path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalCallableObservation.kt
- path: traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/OneHopRelationReader.kt
- path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/BoundedSourceFileCollector.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/execution/AdmittedProjectReadExecution.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/AdmittedHostedQuery.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
sources:
  - id: openwiki-source-48edfaeb8d917960adc80600
    resource: repo://build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt
  - id: openwiki-source-7fa48d17b46dccf82f4b3238
    resource: repo://runtime/hosted/build.gradle.kts
  - id: openwiki-source-e620d7484b72a53c7fa812cd
    resource: repo://settings.gradle.kts
  - id: openwiki-source-0d3b3349cf83bfefc48ef336
    resource: repo://topology/service/build.gradle.kts
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Functional package coverage

## Scope and use

This map routes production Kotlin packages to explanations of their functional owners.
The inventory uses package declarations from the active Gradle modules and the included build logic.
It excludes experimental repositories and test fixtures from the production package count.
The current source declares 97 package-and-module pairs across 43 modules and the included build logic.

One explanation can cover several packages when they share a mechanism.
Large packages also need topic pages for distinct operations, state owners, and failure paths.
Use this map to choose the first explanation, then use its source owners and related pages for the specific question.

## How the functional layers connect

Contracts carry identity, qualified evidence, and finite failure.
Services coordinate those contracts without platform authority.
IntelliJ adapters establish compiler facts under the original live workspace owner.
The hosted runtime composes those adapters and owns final publication.
Transport adapters admit public requests and preserve the resulting typed evidence.
Distribution code owns installation and process lifecycle, outside semantic execution.

Live topology dependencies support sound callback reuse inside the hosted runtime.
Historical graph storage and graph queries form a separate path.
The retired indexer does not become an active semantic host because its directory remains in the repository.

## Package routes

| Gradle owner | Declared package | Mechanism |
| --- | --- | --- |
| `app-server` | `io.github.amichne.kast.appserver` | [coordinator and workspace preparation](../transport/coordinator-and-workspace-preparation.md) |
| `app-server` | `io.github.amichne.kast.appserver.core` | [coordinator and workspace preparation](../transport/coordinator-and-workspace-preparation.md) |
| `app-server` | `io.github.amichne.kast.appserver.host` | [codex hosts and framing](../transport/codex-hosts-and-framing.md) |
| `app-server` | `io.github.amichne.kast.appserver.host.admission` | [codex hosts and framing](../transport/codex-hosts-and-framing.md) |
| `app-server` | `io.github.amichne.kast.appserver.ide` | [coordinator and workspace preparation](../transport/coordinator-and-workspace-preparation.md) |
| `app-server` | `io.github.amichne.kast.appserver.protocol` | [invocations and durable records](../transport/invocations-and-durable-records.md) |
| `app-server` | `io.github.amichne.kast.appserver.protocol.codex` | [codex hosts and framing](../transport/codex-hosts-and-framing.md) |
| `app-server` | `io.github.amichne.kast.appserver.protocol.copilot` | [harness delivery](../transport/harness-delivery.md) |
| `app-server` | `io.github.amichne.kast.appserver.provider` | [coordinator and workspace preparation](../transport/coordinator-and-workspace-preparation.md) |
| `app-server` | `io.github.amichne.kast.appserver.query` | [public admission](../queries/public-admission.md) |
| `app-server` | `io.github.amichne.kast.appserver.runtime` | [coordinator and workspace preparation](../transport/coordinator-and-workspace-preparation.md) |
| `app-server` | `io.github.amichne.kast.appserver.schema` | [public admission](../queries/public-admission.md) |
| `app-server` | `io.github.amichne.kast.appserver.storage` | [invocations and durable records](../transport/invocations-and-durable-records.md) |
| `build-logic` | `conventions` | [installed projection and retrieval](../knowledge/installed-projection-and-retrieval.md) |
| `build-logic` | `conventions.jsoncontracts` | [contracts and generation](../development/contracts-and-generation.md) |
| `build-logic` | `support.architecture` | [build and architecture](../development/build-and-architecture.md) |
| `build-logic` | `support.architecture.gradle` | [build and architecture](../development/build-and-architecture.md) |
| `build-logic` | `support.architecture.knowledge` | [installed projection and retrieval](../knowledge/installed-projection-and-retrieval.md) |
| `build-logic` | `support.architecture.projection` | [build and architecture](../development/build-and-architecture.md) |
| `build-logic` | `support.knowledge` | [installed projection and retrieval](../knowledge/installed-projection-and-retrieval.md) |
| `build-logic` | `support.tasks` | [build and architecture](../development/build-and-architecture.md) |
| `build-logic` | `support.tasks.nativefixtures` | [build and architecture](../development/build-and-architecture.md) |
| `change/apply` | `io.github.amichne.kast.change.apply` | [planning and write admission](../changes/planning-and-write-admission.md) |
| `change/contract` | `io.github.amichne.kast.change.contract` | [planning and write admission](../changes/planning-and-write-admission.md) |
| `change/intellij` | `io.github.amichne.kast.change.intellij` | [planning and write admission](../changes/planning-and-write-admission.md) |
| `change/plan` | `io.github.amichne.kast.change.plan` | [planning and write admission](../changes/planning-and-write-admission.md) |
| `change/protocol` | `io.github.amichne.kast.change.protocol` | [planning and write admission](../changes/planning-and-write-admission.md) |
| `change/recovery` | `io.github.amichne.kast.change.recovery` | [receipts and recovery](../changes/receipts-and-recovery.md) |
| `change/verify` | `io.github.amichne.kast.change.verify` | [receipts and recovery](../changes/receipts-and-recovery.md) |
| `cli` | `io.github.amichne.kast.cli` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.bootstrap` | [contracts and generation](../development/contracts-and-generation.md) |
| `cli` | `io.github.amichne.kast.cli.command` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.command.appserver` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.command.change` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.command.codex` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.command.ide` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.command.knowledge` | [installed projection and retrieval](../knowledge/installed-projection-and-retrieval.md) |
| `cli` | `io.github.amichne.kast.cli.command.product` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.command.source` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.command.tool` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.command.workspace` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.direct` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.ide` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.installation` | [installation and managed files](../distribution/installation-and-managed-files.md) |
| `cli` | `io.github.amichne.kast.cli.knowledge` | [installed projection and retrieval](../knowledge/installed-projection-and-retrieval.md) |
| `cli` | `io.github.amichne.kast.cli.mcp` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `cli` | `io.github.amichne.kast.cli.projection` | [contracts and generation](../development/contracts-and-generation.md) |
| `cli` | `io.github.amichne.kast.cli.rpc` | [direct mcp and tool rpc](../transport/direct-mcp-and-tool-rpc.md) |
| `diagnostic/contract` | `io.github.amichne.kast.diagnostic.contract` | [scans and progress](../semantics/diagnostics/scans-and-progress.md) |
| `diagnostic/intellij` | `io.github.amichne.kast.diagnostic.intellij` | [scans and progress](../semantics/diagnostics/scans-and-progress.md) |
| `diagnostic/service` | `io.github.amichne.kast.diagnostic.service` | [scans and progress](../semantics/diagnostics/scans-and-progress.md) |
| `distribution/cli` | `io.github.amichne.kast.distribution.cli` | [management and connections](../distribution/management-and-connections.md) |
| `distribution/contract` | `io.github.amichne.kast.distribution.contract` | [installation and managed files](../distribution/installation-and-managed-files.md) |
| `distribution/contract` | `io.github.amichne.kast.distribution.contract.bootstrap` | [configuration](../contracts/configuration.md) |
| `distribution/contract` | `io.github.amichne.kast.distribution.contract.configuration` | [configuration](../contracts/configuration.md) |
| `distribution/contract` | `io.github.amichne.kast.distribution.contract.gradle` | [configuration](../contracts/configuration.md) |
| `distribution/managed` | `io.github.amichne.kast.distribution.managed` | [installation and managed files](../distribution/installation-and-managed-files.md) |
| `distribution/managed` | `io.github.amichne.kast.distribution.managed.endpoint` | [installation and managed files](../distribution/installation-and-managed-files.md) |
| `evidence/contract` | `io.github.amichne.kast.evidence.contract` | [receipts and recovery](../changes/receipts-and-recovery.md) |
| `evidence/sqlite` | `io.github.amichne.kast.evidence.sqlite` | [receipts and recovery](../changes/receipts-and-recovery.md) |
| `evidence/topology-sqlite` | `io.github.amichne.kast.evidence.sqlite` | [historical capture and storage](../topology/historical-capture-and-storage.md) |
| `evidence/topology-sqlite` | `io.github.amichne.kast.evidence.sqlite.historical` | [historical capture and storage](../topology/historical-capture-and-storage.md) |
| `evidence/topology-sqlite` | `io.github.amichne.kast.evidence.sqlite.historical.cli` | [historical capture and storage](../topology/historical-capture-and-storage.md) |
| `kernel` | `io.github.amichne.kast.kernel` | [kernel](../modules/kernel.md) |
| `protocol/contract` | `io.github.amichne.kast.protocol.contract` | [contracts and generation](../development/contracts-and-generation.md) |
| `protocol/registry` | `io.github.amichne.kast.protocol.registry` | [contracts and generation](../development/contracts-and-generation.md) |
| `protocol/wire` | `io.github.amichne.kast.protocol.wire` | [contracts and generation](../development/contracts-and-generation.md) |
| `protocol/wire` | `io.github.amichne.kast.protocol.wire.metadata` | [contracts and generation](../development/contracts-and-generation.md) |
| `protocol/wire` | `io.github.amichne.kast.protocol.wire.presentation` | [contracts and generation](../development/contracts-and-generation.md) |
| `query/contract` | `io.github.amichne.kast.query.contract` | [execution and composition](../queries/execution-and-composition.md) |
| `query/protocol` | `io.github.amichne.kast.query.protocol` | [trace and completion](../queries/trace-and-completion.md) |
| `query/service` | `io.github.amichne.kast.query.service` | [execution and composition](../queries/execution-and-composition.md) |
| `relation/contract` | `io.github.amichne.kast.relation.contract` | [named relations](../semantics/relation/named-relations.md) |
| `relation/intellij` | `io.github.amichne.kast.relation.intellij` | [named relations](../semantics/relation/named-relations.md) |
| `relation/service` | `io.github.amichne.kast.relation.service` | [named relations](../semantics/relation/named-relations.md) |
| `runtime/hosted` | `io.github.amichne.kast.runtime.hosted` | [hosted composition and publication](../runtime/hosted-composition-and-publication.md) |
| `runtime/hosted` | `io.github.amichne.kast.runtime.hosted.lifecycle` | [idea lifecycle and refresh](../runtime/idea-lifecycle-and-refresh.md) |
| `runtime/hosted` | `io.github.amichne.kast.runtime.hosted.workspace` | [idea lifecycle and refresh](../runtime/idea-lifecycle-and-refresh.md) |
| `source/contract` | `io.github.amichne.kast.source.contract` | [anchors and paging](../semantics/source/anchors-and-paging.md) |
| `source/intellij` | `io.github.amichne.kast.source.intellij` | [anchors and paging](../semantics/source/anchors-and-paging.md) |
| `source/service` | `io.github.amichne.kast.source.service` | [anchors and paging](../semantics/source/anchors-and-paging.md) |
| `symbol/contract` | `io.github.amichne.kast.symbol.contract` | [discovery and identity](../semantics/symbol/discovery-and-identity.md) |
| `symbol/intellij` | `io.github.amichne.kast.symbol.intellij` | [discovery and identity](../semantics/symbol/discovery-and-identity.md) |
| `symbol/service` | `io.github.amichne.kast.symbol.service` | [discovery and identity](../semantics/symbol/discovery-and-identity.md) |
| `topology/build` | `io.github.amichne.kast.topology.build` | [live dependencies and reuse](../topology/live-dependencies-and-reuse.md) |
| `topology/build` | `io.github.amichne.kast.topology.build.historical` | [historical capture and storage](../topology/historical-capture-and-storage.md) |
| `topology/contract` | `io.github.amichne.kast.topology.contract` | [live dependencies and reuse](../topology/live-dependencies-and-reuse.md) |
| `topology/contract` | `io.github.amichne.kast.topology.contract.historical` | [historical capture and storage](../topology/historical-capture-and-storage.md) |
| `topology/intellij` | `io.github.amichne.kast.topology.intellij` | [live dependencies and reuse](../topology/live-dependencies-and-reuse.md) |
| `topology/intellij` | `io.github.amichne.kast.topology.intellij.historical` | [historical capture and storage](../topology/historical-capture-and-storage.md) |
| `topology/service` | `io.github.amichne.kast.topology.service` | [graph queries](../topology/graph-queries.md) |
| `traversal/contract` | `io.github.amichne.kast.traversal.contract` | [frontiers and continuations](../semantics/traversal/frontiers-and-continuations.md) |
| `traversal/service` | `io.github.amichne.kast.traversal.service` | [frontiers and continuations](../semantics/traversal/frontiers-and-continuations.md) |
| `workspace/contract` | `io.github.amichne.kast.workspace.contract` | [read authority](../semantics/workspace/read-authority.md) |
| `workspace/intellij-read` | `io.github.amichne.kast.workspace.intellij.read` | [model and freshness](../semantics/workspace/model-and-freshness.md) |
| `workspace/intellij-read` | `io.github.amichne.kast.workspace.intellij.read.epoch.execution` | [read authority](../semantics/workspace/read-authority.md) |
| `workspace/intellij-read` | `io.github.amichne.kast.workspace.intellij.read.hosted` | [model and freshness](../semantics/workspace/model-and-freshness.md) |

## Non-Kotlin functional owners

The [harness adapters](../transport/harness-delivery.md) cover Copilot, Pi, and the shared JavaScript delivery code.
The [installation explanation](../distribution/installation-and-managed-files.md) covers shell entry points and offline Python helpers.
The [release explanation](../distribution/release-candidates-and-promotion.md) covers CI selection, immutable candidates, and component publication.
The [test guide](../development/testing-and-native-qualification.md) covers the observation harnesses and their fixtures.

## Coverage limits and upkeep

Package coverage proves that this map assigns each inventoried package an explanation.
It does not prove every branch, caller, concurrent transition, or native effect.
Read focused tests for those claims, and keep installed-host evidence separate from source and fixture evidence.

When a production package changes, reexamine its route and the explanation's source citations.
When a new mechanism needs a separate owner, add a focused page and connect its neighbors.
Directory indexes belong to OpenWiki and regenerate at finalization.

## Source owners

- [settings.gradle.kts](../../settings.gradle.kts)
- [build.gradle.kts](../../runtime/hosted/build.gradle.kts)
- [KastCleanSlateModules.kt](../../build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt)
- [AppServerManagement.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerManagement.kt)
- [AgentSessionBootstrap.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/AgentSessionBootstrap.kt)
- [CliRemoteClientHost.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/CliRemoteClientHost.kt)
- [CodexHostExecutable.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/admission/CodexHostExecutable.kt)
- [CanonicalRoot.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/CanonicalRoot.kt)
- [FileThreadCatalogStore.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStore.kt)
- [BrokerFailureDocument.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/BrokerFailureDocument.kt)
- [CopilotFixtureProjection.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/copilot/CopilotFixtureProjection.kt)
- [BrokerProcess.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/BrokerProcess.kt)
- [PublicQueryPackageName.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicQueryPackageName.kt)
- [BrokerControlRoute.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerControlRoute.kt)
- [CanonicalJson.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/schema/CanonicalJson.kt)
- [PrivateRecordFiles.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt)
- [GenerateKnowledgeDocsTask.kt](../../build-logic/src/main/kotlin/conventions/GenerateKnowledgeDocsTask.kt)
- [JsonContractBaseline.kt](../../build-logic/src/main/kotlin/conventions/jsoncontracts/JsonContractBaseline.kt)
- [ArchitectureAdmission.kt](../../build-logic/src/main/kotlin/support/architecture/ArchitectureAdmission.kt)
- [ArchitectureTasks.kt](../../build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt)
- [ModuleKnowledgeProjection.kt](../../build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt)
- [ArchitectureProjection.kt](../../build-logic/src/main/kotlin/support/architecture/projection/ArchitectureProjection.kt)
- [GenerateInstalledKnowledgeTask.kt](../../build-logic/src/main/kotlin/support/knowledge/GenerateInstalledKnowledgeTask.kt)
- [GenerateAgentToolsTask.kt](../../build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt)
- [NativeFixtureJvmArguments.kt](../../build-logic/src/main/kotlin/support/tasks/nativefixtures/NativeFixtureJvmArguments.kt)
- [AddDeclarationApplyService.kt](../../change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/AddDeclarationApplyService.kt)
- [AddDeclarationApplyObservation.kt](../../change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/AddDeclarationApplyObservation.kt)
- [BoundedMutationSourceRead.kt](../../change/intellij/src/main/kotlin/io/github/amichne/kast/change/intellij/BoundedMutationSourceRead.kt)
- [PureAddDeclarationPlanningService.kt](../../change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt)
- [CanonicalChangePlanProtocol.kt](../../change/protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocol.kt)
- [AddDeclarationRecoveryPreparation.kt](../../change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryPreparation.kt)
- [AddDeclarationSemanticEvidenceChecks.kt](../../change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/AddDeclarationSemanticEvidenceChecks.kt)
- [CanonicalLiveOperationSupport.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/CanonicalLiveOperationSupport.kt)
- [HostedRejectionSchemas.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/HostedRejectionSchemas.kt)
- [CliOptions.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/CliOptions.kt)
- [AppServerCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/appserver/AppServerCommands.kt)
- [ChangeCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/change/ChangeCommands.kt)
- [CodexCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/codex/CodexCommands.kt)
- [IdeCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/ide/IdeCommands.kt)
- [KnowledgeCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/knowledge/KnowledgeCommands.kt)
- [ProductCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/product/ProductCommands.kt)
- [SourceCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/source/SourceCommands.kt)
- [PublicToolCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt)
- [IndexCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/workspace/IndexCommands.kt)
- [DirectToolDocument.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolDocument.kt)
- [ExistingIdeCli.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt)
- [ControlInstallationInput.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationInput.kt)
- [AdmittedKnowledge.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/AdmittedKnowledge.kt)
- [KastMcpMain.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt)
- [CliBoundaryDocuments.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/projection/CliBoundaryDocuments.kt)
- [KastToolRpcMain.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt)
- [DiagnosticEnumeration.kt](../../diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticEnumeration.kt)
- [BoundedDiagnosticEnumeration.kt](../../diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/BoundedDiagnosticEnumeration.kt)
- [DiagnosticScanService.kt](../../diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt)
- [BundledManifest.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/BundledManifest.kt)
- [ControlDistributionLimits.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt)
- [ModelInputFailure.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/bootstrap/ModelInputFailure.kt)
- [BrokerPublicEndpointMode.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/BrokerPublicEndpointMode.kt)
- [GradleImportEnvironment.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/gradle/GradleImportEnvironment.kt)
- [ControlPayloadInventory.kt](../../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ControlPayloadInventory.kt)
- [InstalledEndpointAlias.kt](../../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/endpoint/InstalledEndpointAlias.kt)
- [CanonicalWorkspacePublication.kt](../../evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/CanonicalWorkspacePublication.kt)
- [HostedWorkspaceStateFiles.kt](../../evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/HostedWorkspaceStateFiles.kt)
- [SqliteTopologyRelationIndex.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteTopologyRelationIndex.kt)
- [HistoricalArtifactAdmission.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/HistoricalArtifactAdmission.kt)
- [HistoricalOfflineDocuments.kt](../../evidence/topology-sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/historical/cli/HistoricalOfflineDocuments.kt)
- [EvidenceEnvelope.kt](../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt)
- [AdmittedReadRejections.kt](../../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AdmittedReadRejections.kt)
- [CanonicalAgentToolDefinitions.kt](../../protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt)
- [CanonicalDiagnosticEnumMappings.kt](../../protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/CanonicalDiagnosticEnumMappings.kt)
- [HostedCapabilityDescriptor.kt](../../protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/metadata/HostedCapabilityDescriptor.kt)
- [CanonicalCallbackSchemaDocuments.kt](../../protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalCallbackSchemaDocuments.kt)
- [QueryCheckpointPartialSymbols.kt](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryCheckpointPartialSymbols.kt)
- [AdmittedBoundaryModel.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/AdmittedBoundaryModel.kt)
- [PipelineCheckpoint.kt](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt)
- [CallbackArgumentBinding.kt](../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt)
- [AdmittedRelationPreparation.kt](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/AdmittedRelationPreparation.kt)
- [RelationService.kt](../../relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt)
- [DeadHostedEndpointOwner.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/DeadHostedEndpointOwner.kt)
- [AutomaticReadinessAction.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/AutomaticReadinessAction.kt)
- [HostedWorkspaceRefresh.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/HostedWorkspaceRefresh.kt)
- [SourceDeclarationVisibility.kt](../../source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDeclarationVisibility.kt)
- [InstalledIntellijSourceReadPort.kt](../../source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/InstalledIntellijSourceReadPort.kt)
- [SourceReadService.kt](../../source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt)
- [CandidateSelector.kt](../../symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CandidateSelector.kt)
- [BoundedLexicalCandidates.kt](../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/BoundedLexicalCandidates.kt)
- [ExactRevalidationService.kt](../../symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt)
- [SemanticCallbackFactRead.kt](../../topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactRead.kt)
- [HistoricalCaptureCoordinator.kt](../../topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/historical/HistoricalCaptureCoordinator.kt)
- [CompleteTopologyGeneration.kt](../../topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt)
- [HistoricalCanonicalEncoding.kt](../../topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/historical/HistoricalCanonicalEncoding.kt)
- [AdmittedSourceRootEnumerator.kt](../../topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/AdmittedSourceRootEnumerator.kt)
- [IntellijHistoricalCaptureAdapter.kt](../../topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/historical/IntellijHistoricalCaptureAdapter.kt)
- [GraphIndex.kt](../../topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/GraphIndex.kt)
- [TraversalCallableObservation.kt](../../traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalCallableObservation.kt)
- [OneHopRelationReader.kt](../../traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/OneHopRelationReader.kt)
- [CanonicalSemanticProjectRoot.kt](../../workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt)
- [BoundedSourceFileCollector.kt](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/BoundedSourceFileCollector.kt)
- [AdmittedProjectReadExecution.kt](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/execution/AdmittedProjectReadExecution.kt)
- [AdmittedHostedQuery.kt](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/AdmittedHostedQuery.kt)

## Read next

- [Repository guide](../quickstart.md)
- [Build roles](../development/build-and-architecture.md)
- [Native evidence](../development/testing-and-native-qualification.md)
