package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.registry.CanonicalOperationDefinitions
import io.github.amichne.kast.protocol.registry.OperationRegistryArtifact
import kotlinx.serialization.Serializable

@Serializable
private data class OperationRegistryDocument(
    val schemaVersion: Int,
    val operations: List<OperationRegistryOperationDocument>,
)

@Serializable
private data class OperationRegistryOperationDocument(
    val operationId: String,
    val hostedExposure: String,
    val intents: List<String>,
)

/** Sole generated serializer binding catalog for the thirteen production operation definitions. */
object CanonicalOperationWireBindings {
    val operationRegistryDocument: String =
        wireJson.encodeToString(
            OperationRegistryDocument.serializer(),
            OperationRegistryDocument(
                schemaVersion = 2,
                operations =
                    OperationRegistryArtifact.from(CanonicalOperationDefinitions.registry).entries.map { entry ->
                        OperationRegistryOperationDocument(
                            operationId = entry.operationId.value,
                            hostedExposure = entry.hostedExposure.name.lowercase(),
                            intents = entry.hostedIntentIds,
                        )
                    },
            ),
        ) + "\n"

    /** Prints the generated registry document for the Gradle-owned resource boundary. */
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty()) { "operation registry projection accepts no arguments" }
        print(operationRegistryDocument)
    }

    val workspaceLifecycle =
        OperationWireBinding(
            CanonicalOperationDefinitions.workspaceLifecycle,
            GeneratedOperationSerializers(
                GeneratedWireCodecFactory(wireJson)
                    .create(io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.serializer()),
                GeneratedWireCodecFactory(wireJson)
                    .create(io.github.amichne.kast.protocol.contract.IdeLifecycleResult.serializer()),
                GeneratedWireCodecFactory(wireJson)
                    .create(io.github.amichne.kast.protocol.contract.IdeLifecycleQualification.serializer()),
                GeneratedWireCodecFactory(wireJson)
                    .create(io.github.amichne.kast.protocol.contract.IdeLifecycleFailure.serializer()),
            ),
        )
    val indexSync =
        OperationWireBinding(
            CanonicalOperationDefinitions.indexSync,
            GeneratedOperationSerializers(
                CanonicalIndexSerializers.request,
                CanonicalIndexSerializers.result,
                CanonicalIndexSerializers.qualification,
                CanonicalIndexSerializers.rejection,
            ),
        )
    val topologyBuild =
        OperationWireBinding(
            CanonicalOperationDefinitions.topologyBuild,
            GeneratedOperationSerializers(
                CanonicalTopologySerializers.request,
                CanonicalTopologySerializers.result,
                CanonicalTopologySerializers.qualification,
                CanonicalTopologySerializers.rejection,
            ),
        )
    val symbolDiscover =
        OperationWireBinding(
            CanonicalOperationDefinitions.symbolDiscover,
            GeneratedOperationSerializers(
                CanonicalReadSerializers.symbolDiscoverRequest,
                CanonicalReadSerializers.symbolDiscoverResult,
                CanonicalReadSerializers.symbolDiscoverQualification,
                CanonicalReadSerializers.symbolDiscoverRejection,
            ),
        )
    val symbolInspect =
        OperationWireBinding(
            CanonicalOperationDefinitions.symbolInspect,
            GeneratedOperationSerializers(
                CanonicalReadSerializers.symbolInspectRequest,
                CanonicalReadSerializers.symbolInspectResult,
                CanonicalReadSerializers.symbolInspectQualification,
                CanonicalReadSerializers.symbolInspectRejection,
            ),
        )
    val sourceRead =
        OperationWireBinding(
            CanonicalOperationDefinitions.sourceRead,
            GeneratedOperationSerializers(
                CanonicalSourceReadSerializers.request,
                CanonicalSourceReadSerializers.result,
                CanonicalSourceReadSerializers.qualification,
                CanonicalSourceReadSerializers.rejection,
                ReadRejectionBudgets.source,
            ),
        )
    val traversalRun =
        OperationWireBinding(
            CanonicalOperationDefinitions.traversalRun,
            GeneratedOperationSerializers(
                CanonicalReadSerializers.traversalRunRequest,
                CanonicalReadSerializers.traversalRunResult,
                CanonicalReadSerializers.traversalRunQualification,
                CanonicalReadSerializers.traversalRunRejection,
                ReadRejectionBudgets.traversal,
            ),
        )
    val queryRun =
        OperationWireBinding(
            CanonicalOperationDefinitions.queryRun,
            GeneratedOperationSerializers(
                CanonicalQuerySerializers.request,
                CanonicalQuerySerializers.result,
                CanonicalQuerySerializers.qualification,
                CanonicalQuerySerializers.rejection,
                ReadRejectionBudgets.query,
            ),
        )
    val diagnosticCheck =
        OperationWireBinding(
            CanonicalOperationDefinitions.diagnosticCheck,
            GeneratedOperationSerializers(
                CanonicalReadSerializers.diagnosticCheckRequest,
                CanonicalReadSerializers.diagnosticCheckResult,
                CanonicalReadSerializers.diagnosticCheckQualification,
                CanonicalReadSerializers.diagnosticCheckRejection,
                ReadRejectionBudgets.diagnostic,
            ),
        )
    val changePlan =
        OperationWireBinding(
            CanonicalOperationDefinitions.changePlan,
            GeneratedOperationSerializers(
                CanonicalChangeSerializers.changePlanRequest,
                CanonicalChangeSerializers.changePlanResult,
                CanonicalChangeSerializers.changePlanQualification,
                CanonicalChangeSerializers.changePlanRejection,
            ),
        )
    val change =
        OperationWireBinding(
            CanonicalOperationDefinitions.change,
            GeneratedOperationSerializers(
                GeneratedWireCodecFactory(wireJson)
                    .create(io.github.amichne.kast.protocol.contract.ChangeRequest.serializer()),
                GeneratedWireCodecFactory(wireJson)
                    .create(io.github.amichne.kast.protocol.contract.ChangeResult.serializer()),
                GeneratedWireCodecFactory(wireJson)
                    .create(io.github.amichne.kast.protocol.contract.ChangeQualification.serializer()),
                GeneratedWireCodecFactory(wireJson)
                    .create(io.github.amichne.kast.protocol.contract.ChangeRejection.serializer()),
            ),
        )
    val changeApply =
        OperationWireBinding(
            CanonicalOperationDefinitions.changeApply,
            GeneratedOperationSerializers(
                CanonicalChangeSerializers.changeApplyRequest,
                CanonicalChangeSerializers.changeApplyResult,
                CanonicalChangeSerializers.changeApplyQualification,
                CanonicalChangeSerializers.changeApplyRejection,
            ),
        )
    val changeRecover =
        OperationWireBinding(
            CanonicalOperationDefinitions.changeRecover,
            GeneratedOperationSerializers(
                CanonicalChangeSerializers.changeRecoverRequest,
                CanonicalChangeSerializers.changeRecoverResult,
                CanonicalChangeSerializers.changeRecoverQualification,
                CanonicalChangeSerializers.changeRecoverRejection,
            ),
        )

    internal val table: OperationWireTable =
        when (
            val construction =
                OperationWireTable.create(
                    listOf(
                        workspaceLifecycle,
                        indexSync,
                        topologyBuild,
                        queryRun,
                        symbolDiscover,
                        symbolInspect,
                        sourceRead,
                        traversalRun,
                        diagnosticCheck,
                        change,
                        changePlan,
                        changeApply,
                        changeRecover,
                    )
                )
        ) {
            is OperationWireTableConstruction.Created -> construction.table
            is OperationWireTableConstruction.Rejected ->
                error("Invalid generated canonical operation serializer table: ${construction.failures}")
        }
}
