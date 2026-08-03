package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.contract.result.MutationScratchInspectResult
import io.github.amichne.kast.api.contract.result.MutationScratchRecoveryResult
import io.github.amichne.kast.api.validation.ParsedMutationScratchInspectQuery
import io.github.amichne.kast.api.validation.ParsedMutationScratchRecoveryQuery
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.mutation.inspectMutationScratch
import io.github.amichne.kast.idea.mutation.recoverMutationScratch
import kotlinx.coroutines.withContext

internal suspend fun KastIndexerBackend.inspectMutationScratchOperation(
    query: ParsedMutationScratchInspectQuery,
): MutationScratchInspectResult = mutationAttemptGate.inspectAndAdmit(query.mutationAttemptId) {
    withContext(readDispatcher) {
        exactFileImageMutation.inspectMutationScratch(query)
    }
}

internal suspend fun KastIndexerBackend.recoverMutationScratchOperation(
    query: ParsedMutationScratchRecoveryQuery,
): MutationScratchRecoveryResult = mutationAttemptGate.recover(query.mutationAttemptId) {
    withContext(readDispatcher) {
        exactFileImageMutation.recoverMutationScratch(query)
    }
}
