package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement

/** The complete evaluator runs between freshness checks, with no surrounding platform read lock. */
internal suspend fun <Value> runHostedReadTransaction(
    progress: HostedQueryProgress,
    validate: suspend () -> Refinement<Unit, HostedQueryFailure>,
    evaluate: suspend () -> Value,
): HostedSemanticRead<Value> {
    when (val admitted = validate()) {
        is Refinement.Rejected -> return HostedSemanticRead.Rejected(admitted.failure)
        is Refinement.Refined -> Unit
    }
    progress.advance(HostedQueryStage.SEMANTIC_READ)
    val value = evaluate()
    progress.advance(HostedQueryStage.CONTENT_REVALIDATION)
    return when (val admitted = validate()) {
        is Refinement.Rejected -> HostedSemanticRead.Rejected(admitted.failure)
        is Refinement.Refined -> {
            progress.advance(HostedQueryStage.RESULT_DETACHED)
            HostedSemanticRead.Resolved(value)
        }
    }
}
