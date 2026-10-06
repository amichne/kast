package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryPresentationExecution

/** Detached storage stays charged to retention; the active paired publisher measures the encoded evidence page. */
internal fun admitCallbackEvidenceOutput(
    presentation: QueryPresentationExecution?,
    evaluator: () -> Boolean,
    prepared: () -> Unit,
): Refinement<Boolean, QueryExecutionRejection> =
    when (presentation?.admitCallbackEvidence()) {
        null -> Refinement.Refined(evaluator())
        is Refinement.Refined -> {
            prepared()
            Refinement.Refined(true)
        }
        is Refinement.Rejected -> Refinement.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
    }

/** Reuses the evaluator's task queue; successful presentation admission cannot consume or duplicate a native task. */
internal class QueryCallbackEvidenceTasks(
    private val presentation: QueryPresentationExecution?,
    private val tasks: ArrayDeque<PipelineTask>,
    private val evaluate: (Long, () -> Unit) -> Boolean,
    private val reject: (QueryExecutionRejection) -> Unit,
) {
    fun walk(task: PipelineTask.WalkObservation, append: () -> Unit): Boolean =
        emit(
            task.value.callbackObservations.isNotEmpty() || task.value.callableObservations.isNotEmpty(),
            task.value.projectedUtf8Size(),
            append,
        )

    fun relation(task: PipelineTask.RelationObservation, append: () -> Unit): Boolean =
        emit(
            task.value.callbackObservations.isNotEmpty() || task.value.callableObservations.isNotEmpty(),
            task.value.projectedUtf8Size(),
            append,
        )

    private fun emit(hasCallbacks: Boolean, bytes: Long, append: () -> Unit): Boolean {
        if (!hasCallbacks) return evaluate(bytes, append)
        return when (
            val admitted =
                admitCallbackEvidenceOutput(presentation, { evaluate(bytes, append) }) {
                    append()
                    tasks.removeFirst()
                }
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> {
                reject(admitted.failure)
                false
            }
        }
    }
}
