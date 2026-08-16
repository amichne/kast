package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.registry.CanonicalOperationDefinitions

/** Sole generated serializer binding catalog for the eleven production operation definitions. */
object CanonicalOperationWireBindings {
    val workspaceInspect = OperationWireBinding(
        CanonicalOperationDefinitions.workspaceInspect,
        GeneratedOperationSerializers(
            CanonicalReadSerializers.workspaceInspectRequest,
            CanonicalReadSerializers.workspaceInspectResult,
            CanonicalReadSerializers.workspaceInspectQualification,
            CanonicalReadSerializers.workspaceInspectRejection,
        ),
    )
    val symbolDiscover = OperationWireBinding(
        CanonicalOperationDefinitions.symbolDiscover,
        GeneratedOperationSerializers(
            CanonicalReadSerializers.symbolDiscoverRequest,
            CanonicalReadSerializers.symbolDiscoverResult,
            CanonicalReadSerializers.symbolDiscoverQualification,
            CanonicalReadSerializers.symbolDiscoverRejection,
        ),
    )
    val symbolResolve = OperationWireBinding(
        CanonicalOperationDefinitions.symbolResolve,
        GeneratedOperationSerializers(
            CanonicalReadSerializers.symbolResolveRequest,
            CanonicalReadSerializers.symbolResolveResult,
            CanonicalReadSerializers.symbolResolveQualification,
            CanonicalReadSerializers.symbolResolveRejection,
        ),
    )
    val symbolDescribe = OperationWireBinding(
        CanonicalOperationDefinitions.symbolDescribe,
        GeneratedOperationSerializers(
            CanonicalReadSerializers.symbolDescribeRequest,
            CanonicalReadSerializers.symbolDescribeResult,
            CanonicalReadSerializers.symbolDescribeQualification,
            CanonicalReadSerializers.symbolDescribeRejection,
        ),
    )
    val relationRead = OperationWireBinding(
        CanonicalOperationDefinitions.relationRead,
        GeneratedOperationSerializers(
            CanonicalReadSerializers.relationReadRequest,
            CanonicalReadSerializers.relationReadResult,
            CanonicalReadSerializers.relationReadQualification,
            CanonicalReadSerializers.relationReadRejection,
        ),
    )
    val traversalRun = OperationWireBinding(
        CanonicalOperationDefinitions.traversalRun,
        GeneratedOperationSerializers(
            CanonicalReadSerializers.traversalRunRequest,
            CanonicalReadSerializers.traversalRunResult,
            CanonicalReadSerializers.traversalRunQualification,
            CanonicalReadSerializers.traversalRunRejection,
        ),
    )
    val diagnosticCheck = OperationWireBinding(
        CanonicalOperationDefinitions.diagnosticCheck,
        GeneratedOperationSerializers(
            CanonicalReadSerializers.diagnosticCheckRequest,
            CanonicalReadSerializers.diagnosticCheckResult,
            CanonicalReadSerializers.diagnosticCheckQualification,
            CanonicalReadSerializers.diagnosticCheckRejection,
        ),
    )
    val changePlan = OperationWireBinding(
        CanonicalOperationDefinitions.changePlan,
        GeneratedOperationSerializers(
            CanonicalChangeSerializers.changePlanRequest,
            CanonicalChangeSerializers.changePlanResult,
            CanonicalChangeSerializers.changePlanQualification,
            CanonicalChangeSerializers.changePlanRejection,
        ),
    )
    val changeApply = OperationWireBinding(
        CanonicalOperationDefinitions.changeApply,
        GeneratedOperationSerializers(
            CanonicalChangeSerializers.changeApplyRequest,
            CanonicalChangeSerializers.changeApplyResult,
            CanonicalChangeSerializers.changeApplyQualification,
            CanonicalChangeSerializers.changeApplyRejection,
        ),
    )
    val changeVerify = OperationWireBinding(
        CanonicalOperationDefinitions.changeVerify,
        GeneratedOperationSerializers(
            CanonicalChangeSerializers.changeVerifyRequest,
            CanonicalChangeSerializers.changeVerifyResult,
            CanonicalChangeSerializers.changeVerifyQualification,
            CanonicalChangeSerializers.changeVerifyRejection,
        ),
    )
    val changeRecover = OperationWireBinding(
        CanonicalOperationDefinitions.changeRecover,
        GeneratedOperationSerializers(
            CanonicalChangeSerializers.changeRecoverRequest,
            CanonicalChangeSerializers.changeRecoverResult,
            CanonicalChangeSerializers.changeRecoverQualification,
            CanonicalChangeSerializers.changeRecoverRejection,
        ),
    )

    internal val table: OperationWireTable = when (
        val construction = OperationWireTable.create(
            listOf(
                workspaceInspect,
                symbolDiscover,
                symbolResolve,
                symbolDescribe,
                relationRead,
                traversalRun,
                diagnosticCheck,
                changePlan,
                changeApply,
                changeVerify,
                changeRecover,
            ),
        )
    ) {
        is OperationWireTableConstruction.Created -> construction.table
        is OperationWireTableConstruction.Rejected ->
            error("Invalid generated canonical operation serializer table: ${construction.failures}")
    }
}
