package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IndexSyncQualification
import io.github.amichne.kast.protocol.contract.IndexSyncRejection
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.IndexSyncResult
import io.github.amichne.kast.protocol.contract.IndexSyncStateDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.workspace.contract.IndexSynchronizationFailure
import io.github.amichne.kast.workspace.contract.IndexSynchronizationOperations
import io.github.amichne.kast.workspace.contract.IndexSynchronizationResult
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshFailure

internal object HostedIndexSyncProtocol {
    fun bindings(
        operations: IndexSynchronizationOperations,
    ): List<TypedOperationBinding<*, *, *, *>> = listOf(
        TypedOperationBinding(CanonicalOperationWireBindings.indexSync, Handler(operations)),
    )

    private class Handler(
        private val operations: IndexSynchronizationOperations,
    ) : OperationHandler<IndexSyncRequest, IndexSyncResult, IndexSyncQualification, IndexSyncRejection> {
        override suspend fun execute(
            request: IndexSyncRequest,
        ): OperationOutcome<IndexSyncResult, IndexSyncQualification, IndexSyncRejection> =
            when (val result = operations.synchronize()) {
                is IndexSynchronizationResult.Synchronized -> complete(
                    result.workspace.generation,
                    IndexSyncStateDocument.SYNCHRONIZED,
                )
                is IndexSynchronizationResult.Unchanged -> complete(
                    result.workspace.generation,
                    IndexSyncStateDocument.UNCHANGED,
                )
                is IndexSynchronizationResult.Rejected -> OperationOutcome.Rejected(
                    result.failure.toProtocol(),
                )
            }
    }
}

private fun complete(
    generation: io.github.amichne.kast.kernel.EvidenceGeneration,
    state: IndexSyncStateDocument,
): OperationOutcome.Complete<IndexSyncResult> = OperationOutcome.Complete(
    EvidenceEnvelope(CanonicalOperation.INDEX_SYNC.id, generation, IndexSyncResult(state)),
)

private fun IndexSynchronizationFailure.toProtocol(): IndexSyncRejection = when (this) {
    IndexSynchronizationFailure.WorkspaceNotReady -> IndexSyncRejection.WORKSPACE_NOT_READY
    is IndexSynchronizationFailure.Refresh -> when (failure) {
        WorkspaceIndexRefreshFailure.INVALID_SOURCE_ROOT_SCOPE ->
            IndexSyncRejection.INVALID_SOURCE_ROOT_SCOPE
        WorkspaceIndexRefreshFailure.REFRESH_UNAVAILABLE -> IndexSyncRejection.REFRESH_UNAVAILABLE
        WorkspaceIndexRefreshFailure.INDEXING_INTERRUPTED ->
            IndexSyncRejection.INDEXING_INTERRUPTED
        WorkspaceIndexRefreshFailure.INDEXING_TIMED_OUT ->
            IndexSyncRejection.INDEXING_TIMED_OUT
        WorkspaceIndexRefreshFailure.INDEXING_FAILED -> IndexSyncRejection.INDEXING_FAILED
    }
    IndexSynchronizationFailure.PublicationInvalidated ->
        IndexSyncRejection.PUBLICATION_INVALIDATED
    is IndexSynchronizationFailure.PublicationBlocked -> IndexSyncRejection.PUBLICATION_BLOCKED
    IndexSynchronizationFailure.PublicationContractViolation ->
        IndexSyncRejection.PUBLICATION_CONTRACT_VIOLATION
}
