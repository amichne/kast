package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.ExactFileImageResult
import io.github.amichne.kast.api.contract.result.MutationScratchRecoveryResult
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.SemanticGraphResult
import io.github.amichne.kast.api.validation.ParsedApplyEditsQuery
import io.github.amichne.kast.api.validation.ParsedExactFileImageQuery
import io.github.amichne.kast.api.validation.ParsedMutationScratchRecoveryQuery
import io.github.amichne.kast.api.validation.ParsedRefreshQuery
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.mutation.applyEditsOperation
import io.github.amichne.kast.idea.backend.mutation.exactFileImageCasOperation
import io.github.amichne.kast.idea.backend.mutation.recoverMutationScratchOperation
import io.github.amichne.kast.idea.backend.mutation.refreshOperation
import io.github.amichne.kast.idea.transition.WorkspaceSignal

internal suspend fun KastIndexerBackend.coordinatedSemanticGraph(
    query: ParsedSemanticGraphQuery,
): SemanticGraphResult {
    return try {
        workspaceSemanticGate.current { semanticGraphOperation(query) }
    } catch (_: PublishedSemanticGraphIncompleteException) {
        workspaceTransitionRequester.reconcile(WorkspaceSignal.RecoveryAudit)
        workspaceSemanticGate.current { semanticGraphOperation(query) }
    }
}

internal suspend fun KastIndexerBackend.coordinatedApplyEdits(
    query: ParsedApplyEditsQuery,
): ApplyEditsResult = workspaceTransitionRequester.mutate(
    signal = WorkspaceSignal.Source,
    detail = "workspace edit mutation is active",
) {
    applyEditsOperation(query)
}

internal suspend fun KastIndexerBackend.coordinatedExactFileImageCas(
    query: ParsedExactFileImageQuery,
): ExactFileImageResult = workspaceTransitionRequester.mutate(
    signal = WorkspaceSignal.Source,
    detail = "exact file-image mutation is active",
) {
    exactFileImageCasOperation(query)
}

internal suspend fun KastIndexerBackend.coordinatedMutationScratchRecovery(
    query: ParsedMutationScratchRecoveryQuery,
): MutationScratchRecoveryResult = workspaceTransitionRequester.mutate(
    signal = WorkspaceSignal.Source,
    detail = "mutation scratch recovery is active",
) {
    recoverMutationScratchOperation(query)
}

internal suspend fun KastIndexerBackend.coordinatedRefresh(
    query: ParsedRefreshQuery,
): RefreshResult {
    if (query.externalFailureIds.isNotEmpty()) {
        return workspaceTransitionRequester.mutate(
            signal = WorkspaceSignal.Source,
            detail = "semantic failure classification mutation is active",
        ) {
            refreshOperation(query)
        }
    }
    workspaceTransitionRequester.reconcile(
        if (query.filePaths.isEmpty()) WorkspaceSignal.RecoveryAudit else WorkspaceSignal.Source,
    )
    return workspaceSemanticGate.current { refreshOperation(query) }
}
