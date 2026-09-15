package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement

/** The complete evaluator runs between freshness checks, with no surrounding platform read lock. */
internal suspend fun <Value> runHostedReadTransaction(
    progress: HostedQueryProgress,
    validate: suspend () -> Refinement<Unit, HostedQueryFailure>,
    evaluate: suspend (HostedSemanticTimeAllowance) -> Value,
): HostedSemanticRead<Value> {
    when (val admitted = validate()) {
        is Refinement.Rejected -> return HostedSemanticRead.Rejected(admitted.failure)
        is Refinement.Refined -> Unit
    }
    progress.advance(HostedQueryStage.SEMANTIC_READ)
    val allowance =
        when (val admitted = progress.admitSemanticRead()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return HostedSemanticRead.Rejected(admitted.failure)
        }
    val value = evaluate(allowance.allowance)
    progress.advance(HostedQueryStage.CONTENT_REVALIDATION)
    return when (val admitted = validate()) {
        is Refinement.Rejected -> HostedSemanticRead.Rejected(admitted.failure)
        is Refinement.Refined -> {
            when (val completed = progress.validateCompletion(allowance)) {
                is Refinement.Rejected -> return HostedSemanticRead.Rejected(completed.failure)
                is Refinement.Refined -> Unit
            }
            progress.advance(HostedQueryStage.RESULT_DETACHED)
            HostedSemanticRead.Resolved(value)
        }
    }
}
