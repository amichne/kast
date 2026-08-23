package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationApplyOperations
import io.github.amichne.kast.change.apply.AddDeclarationApplyService
import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackPort
import io.github.amichne.kast.change.verify.ChangeVerificationObserver
import io.github.amichne.kast.change.verify.VerifiedMutationOperations
import io.github.amichne.kast.change.verify.VerifiedMutationService
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.OpenCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationDiscard
import io.github.amichne.kast.evidence.contract.WorkspacePublicationOpening
import io.github.amichne.kast.evidence.contract.WorkspacePublicationPreparation
import io.github.amichne.kast.evidence.contract.WorkspacePublicationResult
import io.github.amichne.kast.evidence.contract.WorkspacePublicationTransaction
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.service.SymbolDiscoveryService
import io.github.amichne.kast.symbol.service.SymbolExactService
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateCapture
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateReconciliation
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceReconciliationPort
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.service.WorkspacePublicationCoordinator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class KastRuntimeCompositionTest {
    @Test
    fun `composition api owns service construction instead of accepting a service aggregate`() {
        val create = KastRuntimeComposition.Companion::class.java.declaredMethods.single {
            it.name == "create"
        }

        assertEquals(
            listOf(
                "WorkspaceRuntimePorts",
                "SemanticRuntimePorts",
                "TopologyRuntimePorts",
                "ChangeRuntimePorts",
                "KastOperationHandlerFactory",
            ),
            create.parameterTypes.map(Class<*>::getSimpleName),
        )
    }

    @Test
    fun `composition constructs every target service and exact nominal association`() {
        val handlers = RecordingHandlerFactory()
        val composition = KastRuntimeComposition.create(
            workspacePorts(),
            semanticPorts(),
            topologyPorts(),
            changePorts(),
            handlers,
        ).created()
        val operations = composition.operations

        assertSame(operations.workspaceInspect, handlers.observed.getValue(CanonicalOperation.WORKSPACE_INSPECT))
        assertSame(operations.topologyBuild, handlers.observed.getValue(CanonicalOperation.TOPOLOGY_BUILD))
        assertSame(operations.symbolDiscover, handlers.observed.getValue(CanonicalOperation.SYMBOL_DISCOVER))
        assertSame(operations.symbolResolve, handlers.observed.getValue(CanonicalOperation.SYMBOL_RESOLVE))
        assertSame(operations.symbolDescribe, handlers.observed.getValue(CanonicalOperation.SYMBOL_DESCRIBE))
        assertSame(operations.relationRead, handlers.observed.getValue(CanonicalOperation.RELATION_READ))
        assertSame(operations.traversalRun, handlers.observed.getValue(CanonicalOperation.TRAVERSAL_RUN))
        assertSame(operations.diagnosticCheck, handlers.observed.getValue(CanonicalOperation.DIAGNOSTIC_CHECK))
        assertSame(operations.changePlan, handlers.observed.getValue(CanonicalOperation.CHANGE_PLAN))
        assertSame(operations.changeApply, handlers.observed.getValue(CanonicalOperation.CHANGE_APPLY))
        assertSame(operations.changeVerify, handlers.observed.getValue(CanonicalOperation.CHANGE_VERIFY))
        assertSame(operations.changeRecover, handlers.observed.getValue(CanonicalOperation.CHANGE_RECOVER))
        assertEquals(CanonicalOperation.entries.toSet(), handlers.observed.keys)
        assertSame(operations.symbolResolve, operations.symbolDescribe)

        assertSame(WorkspacePublicationCoordinator::class.java, operations.workspaceInspect.javaClass)
        assertSame(SymbolDiscoveryService::class.java, operations.symbolDiscover.javaClass)
        assertSame(SymbolExactService::class.java, operations.symbolResolve.javaClass)
        assertSame(RelationService::class.java, operations.relationRead.javaClass)
        assertSame(AddDeclarationApplyService::class.java, operations.changeApply.javaClass)
        assertSame(VerifiedMutationService::class.java, operations.changeVerify.javaClass)
    }

    private fun KastRuntimeCompositionConstruction.created(): KastRuntimeComposition = when (this) {
        is KastRuntimeCompositionConstruction.Created -> composition
        is KastRuntimeCompositionConstruction.Rejected -> error("unexpected rejection: $failures")
    }

    private class RecordingHandlerFactory : KastOperationHandlerFactory {
        val observed = linkedMapOf<CanonicalOperation, Any>()

        override fun workspaceInspect(operations: WorkspaceInspectionOperations) =
            record<WorkspaceInspectRequest, WorkspaceInspectResult, WorkspaceInspectQualification, WorkspaceInspectRejection>(
                CanonicalOperation.WORKSPACE_INSPECT,
                operations,
                WorkspaceInspectRejection.RUNTIME_BLOCKED,
            )

        override fun topologyBuild(operations: TopologyBuildOperations) =
            record<TopologyBuildRequest, TopologyBuildResult, TopologyBuildQualification, TopologyBuildRejection>(
                CanonicalOperation.TOPOLOGY_BUILD,
                operations,
                TopologyBuildRejection.WORKSPACE_NOT_READY,
            )

        override fun symbolDiscover(operations: SymbolDiscoveryOperations) =
            record<SymbolDiscoverRequest, SymbolDiscoverResult, SymbolDiscoverQualification, SymbolDiscoverRejection>(
                CanonicalOperation.SYMBOL_DISCOVER,
                operations,
                SymbolDiscoverRejection.WORKSPACE_NOT_READY,
            )

        override fun symbolResolve(operations: SymbolExactOperations) =
            record<SymbolResolveRequest, SymbolResolveResult, SymbolResolveQualification, SymbolResolveRejection>(
                CanonicalOperation.SYMBOL_RESOLVE,
                operations,
                SymbolResolveRejection.WORKSPACE_NOT_READY,
            )

        override fun symbolDescribe(operations: SymbolExactOperations) =
            record<SymbolDescribeRequest, SymbolDescribeResult, SymbolDescribeQualification, SymbolDescribeRejection>(
                CanonicalOperation.SYMBOL_DESCRIBE,
                operations,
                SymbolDescribeRejection.WORKSPACE_NOT_READY,
            )

        override fun relationRead(operations: RelationOperations) =
            record<RelationReadRequest, RelationReadResult, RelationReadQualification, RelationReadRejection>(
                CanonicalOperation.RELATION_READ,
                operations,
                RelationReadRejection.WORKSPACE_NOT_READY,
            )

        override fun traversalRun(operations: TraversalOperations) =
            record<TraversalRunRequest, TraversalRunResult, TraversalRunQualification, TraversalRunRejection>(
                CanonicalOperation.TRAVERSAL_RUN,
                operations,
                TraversalRunRejection.WORKSPACE_NOT_READY,
            )

        override fun diagnosticCheck(operations: DiagnosticOperations) =
            record<DiagnosticCheckRequest, DiagnosticCheckResult, DiagnosticCheckQualification, DiagnosticCheckRejection>(
                CanonicalOperation.DIAGNOSTIC_CHECK,
                operations,
                DiagnosticCheckRejection.WORKSPACE_NOT_READY,
            )

        override fun changePlan(operations: ChangePlanningOperations) =
            record<ChangePlanRequest, ChangePlanResult, ChangePlanQualification, ChangePlanRejection>(
                CanonicalOperation.CHANGE_PLAN,
                operations,
                ChangePlanRejection.WORKSPACE_NOT_READY,
            )

        override fun changeApply(operations: AddDeclarationApplyOperations) =
            record<ChangeApplyRequest, ChangeApplyResult, ChangeApplyQualification, ChangeApplyRejection>(
                CanonicalOperation.CHANGE_APPLY,
                operations,
                ChangeApplyRejection.PLAN_NOT_FOUND,
            )

        override fun changeVerify(operations: VerifiedMutationOperations) =
            record<ChangeVerifyRequest, ChangeVerifyResult, ChangeVerifyQualification, ChangeVerifyRejection>(
                CanonicalOperation.CHANGE_VERIFY,
                operations,
                ChangeVerifyRejection.APPLICATION_NOT_FOUND,
            )

        override fun changeRecover(operations: ChangeRecoveryOperations) =
            record<ChangeRecoverRequest, ChangeRecoverResult, ChangeRecoverQualification, ChangeRecoverRejection>(
                CanonicalOperation.CHANGE_RECOVER,
                operations,
                ChangeRecoverRejection.PLAN_NOT_FOUND,
            )

        private fun <
            Request : OperationRequest,
            Result : OperationResult,
            Qualification : OperationQualification,
            Rejection : OperationRejection,
            > record(
            operation: CanonicalOperation,
            operations: Any,
            rejection: Rejection,
        ): OperationHandler<Request, Result, Qualification, Rejection> {
            observed[operation] = operations
            return OperationHandler { OperationOutcome.Rejected(rejection) }
        }
    }

    private companion object {
        fun workspacePorts(): WorkspaceRuntimePorts = WorkspaceRuntimePorts(
            reconciliation = object : WorkspaceReconciliationPort {
                override fun capture(signals: Set<WorkspaceSignal>): WorkspaceCandidateCapture =
                    error("not executed")

                override fun reconcile(candidate: WorkspaceCandidate): WorkspaceCandidateReconciliation =
                    error("not executed")
            },
            publication = object : WorkspacePublicationTransaction {
                override fun begin(): WorkspacePublicationOpening = error("not executed")

                override fun prepare(
                    open: OpenCanonicalWorkspacePublication,
                    candidate: ReconciledWorkspace,
                ): WorkspacePublicationPreparation = error("not executed")

                override fun commit(
                    prepared: PreparedCanonicalWorkspacePublication,
                ): WorkspacePublicationResult = error("not executed")

                override fun discard(
                    open: OpenCanonicalWorkspacePublication,
                ): WorkspacePublicationDiscard = error("not executed")

                override fun discard(
                    prepared: PreparedCanonicalWorkspacePublication,
                ): WorkspacePublicationDiscard = error("not executed")
            },
        )

        fun semanticPorts(): SemanticRuntimePorts = SemanticRuntimePorts(
            symbolDiscovery = SymbolCompilerPort { error("not executed") },
            symbolExact = object : SymbolExactCompilerPort {
                override suspend fun resolve(
                    request: SymbolResolutionRequest,
                ): SymbolResolutionCompilation = error("not executed")

                override suspend fun describe(
                    request: ExactSymbolRequest,
                ): SymbolDescriptionCompilation = error("not executed")
            },
            relation = RelationCompilerPort { error("not executed") },
            diagnostic = DiagnosticCompilerPort { error("not executed") },
        )

        fun topologyPorts(): TopologyRuntimePorts = TopologyRuntimePorts(
            candidates = { error("not executed") },
            extractor = { error("not executed") },
            snapshots = UnusedTopologySnapshotStore,
        )

        fun changePorts(): ChangeRuntimePorts = ChangeRuntimePorts(
            recoveryEvidence = UnusedRecoveryEvidenceStore,
            sourceObserver = AddDeclarationSourceObserver { error("not executed") },
            sourceWriter = AddDeclarationSourceWriter { _, _ -> error("not executed") },
            sourceRollback = AddDeclarationSourceRollback { _, _ -> error("not executed") },
            recoveryRollback = AddDeclarationRollbackPort { error("not executed") },
            verificationObserver = ChangeVerificationObserver { error("not executed") },
        )
    }
}

private object UnusedTopologySnapshotStore : TopologySnapshotStore {
    override fun eligible(identity: TopologyWorkspaceIdentity): TopologySnapshotEligibility =
        error("not executed")

    override fun publish(generation: CompleteTopologyGeneration): TopologyPublicationResult =
        error("not executed")

    override fun read(snapshot: PublishedTopologySnapshot): TopologySnapshotContentRead =
        error("not executed")
}

private object UnusedRecoveryEvidenceStore : MutationRecoveryEvidenceStore {
    override fun prepare(
        record: MutationRecoveryRecord.PreWriteDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.PreWriteDurable> = error("not executed")

    override fun recordApplied(
        prior: MutationRecoveryRecord.PreWriteDurable,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.AppliedWritesDurable> = error("not executed")

    override fun <Record : MutationRecoveryRecord.Terminal> recordTerminal(
        prior: MutationRecoveryRecord.AppliedWritesDurable,
        record: Record,
    ): MutationRecoveryPersistResult<Record> = error("not executed")

    override fun load(binding: MutationPlanBinding): MutationRecoveryLoadResult = error("not executed")
}
