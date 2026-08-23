package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationApplyService
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.verify.ResultingGenerationPublication
import io.github.amichne.kast.change.verify.ResultingGenerationPublicationRejection
import io.github.amichne.kast.change.verify.ResultingGenerationPublisher
import io.github.amichne.kast.change.verify.VerifiedMutationService
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.runtime.server.RuntimeServer
import io.github.amichne.kast.runtime.server.RuntimeServerConstruction
import io.github.amichne.kast.runtime.server.RuntimeServerConstructionFailure
import io.github.amichne.kast.runtime.server.ServerDispatch
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.symbol.service.SymbolDiscoveryService
import io.github.amichne.kast.symbol.service.SymbolExactService
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.topology.build.TopologyBuildService
import io.github.amichne.kast.workspace.service.ResultingWorkspacePublicationFailure
import io.github.amichne.kast.workspace.service.ResultingWorkspacePublicationResult
import io.github.amichne.kast.workspace.service.WorkspacePublicationCoordinator

/** Runnable target-only runtime containing the directly constructed operation graph and server. */
class KastRuntimeComposition private constructor(
    val operations: DirectKastOperations,
    private val server: RuntimeServer,
) : KastRuntimeDispatchOperations {
    /**
     * Proof transition: `String -> KastRuntimeDispatch`.
     *
     * Preserves canonical wire admission and exact target operation routing. Closed request,
     * decoding, semantic, and response failures remain data. Raw documents may cross only this
     * outer indexer transport boundary.
     */
    override suspend fun dispatch(document: String): KastRuntimeDispatch = when (
        val dispatch = server.dispatch(document)
    ) {
        is ServerDispatch.Responded -> KastRuntimeDispatch.Responded(dispatch.document)
        is ServerDispatch.Rejected -> KastRuntimeDispatch.Rejected(
            when (dispatch.failure) {
                is io.github.amichne.kast.runtime.server.ServerDispatchFailure.RequestAdmissionFailed ->
                    KastRuntimeDispatchFailure.REQUEST_ADMISSION_FAILED
                is io.github.amichne.kast.runtime.server.ServerDispatchFailure.RequestDecodingFailed ->
                    KastRuntimeDispatchFailure.REQUEST_DECODING_FAILED
                is io.github.amichne.kast.runtime.server.ServerDispatchFailure.ResponseEncodingFailed ->
                    KastRuntimeDispatchFailure.RESPONSE_ENCODING_FAILED
            },
        )
    }

    companion object {
        /**
         * Proof transition: `(WorkspaceRuntimePorts, SemanticRuntimePorts, ChangeRuntimePorts,
         * KastOperationHandlerFactory) -> KastRuntimeCompositionConstruction`.
         *
         * Constructs the sole complete target implementation graph from narrow effects, then
         * pairs operation-specific protocol handlers with the canonical production definitions
         * and generated serializers. [KastRuntimeCompositionFailure] is the closed expected
         * construction failure. Platform values and raw payload extraction remain inside the
         * injected ports and operation handlers.
         */
        fun create(
            workspacePorts: WorkspaceRuntimePorts,
            semanticPorts: SemanticRuntimePorts,
            topologyPorts: TopologyRuntimePorts,
            changePorts: ChangeRuntimePorts,
            handlers: KastOperationHandlerFactory,
        ): KastRuntimeCompositionConstruction = bind(
            constructGraph(workspacePorts, semanticPorts, topologyPorts, changePorts).operations,
            handlers,
        )

        internal fun constructGraph(
            workspacePorts: WorkspaceRuntimePorts,
            semanticPorts: SemanticRuntimePorts,
            topologyPorts: TopologyRuntimePorts,
            changePorts: ChangeRuntimePorts,
        ): DirectKastRuntimeGraph = constructGraph(
            WorkspacePublicationCoordinator(
                workspacePorts.reconciliation,
                workspacePorts.publication,
            ),
            semanticPorts,
            topologyPorts,
            changePorts,
        )

        internal fun constructGraph(
            workspace: WorkspacePublicationCoordinator,
            semanticPorts: SemanticRuntimePorts,
            topologyPorts: TopologyRuntimePorts,
            changePorts: ChangeRuntimePorts,
        ): DirectKastRuntimeGraph {
            val symbolDiscovery = SymbolDiscoveryService(workspace, semanticPorts.symbolDiscovery)
            val symbolExact = SymbolExactService(workspace, semanticPorts.symbolExact)
            val relation = RelationService(workspace, semanticPorts.relation)
            val topology = TopologyBuildService.create(
                workspace,
                workspace,
                topologyPorts.candidates,
                topologyPorts.extractor,
                topologyPorts.snapshots,
            )
            val traversal = TopologyBackedTraversalOperations(workspace, topologyPorts.snapshots)
            val diagnostic = DiagnosticService(workspace, semanticPorts.diagnostic)
            val recovery = AddDeclarationRecoveryService(changePorts.recoveryEvidence)
            val changeApply = AddDeclarationApplyService(
                recovery,
                changePorts.sourceObserver,
                changePorts.sourceWriter,
                changePorts.sourceRollback,
            )
            val changeVerify = VerifiedMutationService(
                workspace.resultingGenerationPublisher(),
                changePorts.verificationObserver,
            )
            val operations = DirectKastOperations.assemble(
                workspace,
                topology,
                symbolDiscovery,
                symbolExact,
                relation,
                traversal,
                diagnostic,
                changeApply,
                changeVerify,
                recovery,
                changePorts.recoveryRollback,
            )
            return DirectKastRuntimeGraph(workspace, operations)
        }

        internal fun bind(
            operations: DirectKastOperations,
            handlers: KastOperationHandlerFactory,
        ): KastRuntimeCompositionConstruction {
            val bindings: List<TypedOperationBinding<*, *, *, *>> = listOf(
                TypedOperationBinding(
                    CanonicalOperationWireBindings.workspaceInspect,
                    handlers.workspaceInspect(operations.workspaceInspect),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.topologyBuild,
                    handlers.topologyBuild(operations.topologyBuild),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.symbolDiscover,
                    handlers.symbolDiscover(operations.symbolDiscover),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.symbolResolve,
                    handlers.symbolResolve(operations.symbolResolve),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.symbolDescribe,
                    handlers.symbolDescribe(operations.symbolDescribe),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.relationRead,
                    handlers.relationRead(operations.relationRead),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.traversalRun,
                    handlers.traversalRun(operations.traversalRun),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.diagnosticCheck,
                    handlers.diagnosticCheck(operations.diagnosticCheck),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.changePlan,
                    handlers.changePlan(operations.changePlan),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.changeApply,
                    handlers.changeApply(operations.changeApply),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.changeVerify,
                    handlers.changeVerify(operations.changeVerify),
                ),
                TypedOperationBinding(
                    CanonicalOperationWireBindings.changeRecover,
                    handlers.changeRecover(operations.changeRecover),
                ),
            )
            return when (val construction = RuntimeServer.create(bindings)) {
                is RuntimeServerConstruction.Created -> KastRuntimeCompositionConstruction.Created(
                    KastRuntimeComposition(operations, construction.server),
                )
                is RuntimeServerConstruction.Rejected -> KastRuntimeCompositionConstruction.Rejected(
                    construction.failures.mapTo(linkedSetOf()) {
                        KastRuntimeCompositionFailure.ServerConstruction(it)
                    },
                )
            }
        }
    }
}

/** Composition-owned target service graph retained only until canonical handler binding. */
internal data class DirectKastRuntimeGraph(
    val workspace: WorkspacePublicationCoordinator,
    val operations: DirectKastOperations,
)

/**
 * Proof transition: `WorkspacePublicationCoordinator -> ResultingGenerationPublisher`.
 *
 * Preserves exact-prior publication admission and projects every workspace transition failure to
 * the closed verification publication protocol. Raw workspace effects remain in the coordinator.
 */
private fun WorkspacePublicationCoordinator.resultingGenerationPublisher(): ResultingGenerationPublisher =
    ResultingGenerationPublisher { prior ->
        when (val result = reconcileAfter(prior)) {
            is ResultingWorkspacePublicationResult.Published ->
                ResultingGenerationPublication.Published(result.publication.workspace)
            is ResultingWorkspacePublicationResult.Rejected ->
                ResultingGenerationPublication.Rejected(
                    when (result.failure) {
                        ResultingWorkspacePublicationFailure.CurrentPublicationUnavailable,
                        is ResultingWorkspacePublicationFailure.PriorPublicationMismatch,
                        ResultingWorkspacePublicationFailure.NoPublication,
                            -> ResultingGenerationPublicationRejection.CURRENT_PUBLICATION_UNAVAILABLE
                        ResultingWorkspacePublicationFailure.Invalidated ->
                            ResultingGenerationPublicationRejection.RECONCILIATION_INVALIDATED
                        is ResultingWorkspacePublicationFailure.Blocked ->
                            ResultingGenerationPublicationRejection.RECONCILIATION_BLOCKED
                        is ResultingWorkspacePublicationFailure.InvalidResult ->
                            ResultingGenerationPublicationRejection.PUBLICATION_PROTOCOL_REJECTED
                    },
                )
        }
    }

/** Narrow composition-owned dispatch capability supplied to the isolated indexer host. */
fun interface KastRuntimeDispatchOperations {
    /**
     * Proof transition: `String -> KastRuntimeDispatch`.
     *
     * Establishes canonical request admission and exact operation routing. Expected failures are
     * closed by [KastRuntimeDispatch.Rejected]. Raw documents may cross only the outer host frame.
     */
    suspend fun dispatch(document: String): KastRuntimeDispatch
}

/** Composition-owned dispatch result exposed to the isolated indexer host. */
sealed interface KastRuntimeDispatch {
    data class Responded(
        val document: String,
    ) : KastRuntimeDispatch

    data class Rejected(
        val failure: KastRuntimeDispatchFailure,
    ) : KastRuntimeDispatch
}

/** Closed transport failure projection that does not export runtime-server implementation types. */
enum class KastRuntimeDispatchFailure {
    REQUEST_ADMISSION_FAILED,
    REQUEST_DECODING_FAILED,
    RESPONSE_ENCODING_FAILED,
}

/** Closed construction result for the target-only runtime graph. */
sealed interface KastRuntimeCompositionConstruction {
    data class Created(
        val composition: KastRuntimeComposition,
    ) : KastRuntimeCompositionConstruction

    data class Rejected(
        val failures: Set<KastRuntimeCompositionFailure>,
    ) : KastRuntimeCompositionConstruction
}

/** Finite failures that prevent target runtime authority from being issued. */
sealed interface KastRuntimeCompositionFailure {
    data class ServerConstruction(
        val failure: RuntimeServerConstructionFailure,
    ) : KastRuntimeCompositionFailure
}
