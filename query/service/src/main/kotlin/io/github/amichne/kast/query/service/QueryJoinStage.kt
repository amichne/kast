package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryLimitation

/** The one query evaluator delegates only join-specific buffering, indexing, and pair emission here. */
internal class QueryJoinStage(
    private val request: QueryExecutionRequest,
    private val state: QueryExecutionState,
    private val tasks: ArrayDeque<PipelineTask>,
    restored: QueryJoinSnapshot?,
) {
    private val joins = QueryJoins(restored ?: QueryJoinSnapshot(emptyMap()))
    private val emittedBindings = mutableListOf<QueryBindingRow>()

    val bindingRows: List<QueryBindingRow>
        get() = emittedBindings

    fun recordBinding(row: QueryBindingRow) {
        emittedBindings += row
    }

    fun snapshot(): QueryJoinSnapshot = joins.snapshot()

    fun emitRightEvidence(task: PipelineTask.JoinEvidence): Boolean {
        tasks.removeFirst()
        val right = task.stage.right
        state.inheritRetainedLimitations(right)
        val evidence =
            right.failures.map(PipelineTask::Failure) +
                right.omissions.map(PipelineTask::Omission) +
                right.walkObservations.map(PipelineTask::WalkObservation)
        evidence.asReversed().forEach(tasks::addFirst)
        return true
    }

    fun enter(task: PipelineTask.Symbol, stage: ExactQueryStage.Join): Boolean {
        state.inheritRetainedLimitations(stage.right)
        tasks.removeFirst()
        tasks.addFirst(PipelineTask.Join(task.value, stage, QueryJoinCursor.Build))
        return true
    }

    fun advance(task: PipelineTask.Join): Boolean =
        when (val cursor = task.cursor) {
            QueryJoinCursor.Build -> build(task)
            is QueryJoinCursor.Inner -> inner(task, cursor)
            is QueryJoinCursor.Semi -> semi(task, cursor)
            QueryJoinCursor.Anti -> anti(task)
        }

    private fun build(task: PipelineTask.Join): Boolean {
        if (!state.consumeUnit()) return false
        return when (joins.buildNext(task.stage, request.budget.checkpointBytes.value)) {
            QueryJoinBuild.BUILT -> true
            QueryJoinBuild.COMPLETE -> {
                val cursor =
                    when (task.stage.mode) {
                        is QueryJoinMode.Inner -> QueryJoinCursor.Inner(0)
                        QueryJoinMode.Semi -> QueryJoinCursor.Semi(0, task.value)
                        QueryJoinMode.Anti -> QueryJoinCursor.Anti
                    }
                tasks.removeFirst()
                tasks.addFirst(task.copy(cursor = cursor))
                true
            }
            QueryJoinBuild.BYTE_LIMIT -> {
                state.limit(QueryLimitation.BYTE_LIMIT_REACHED)
                false
            }
        }
    }

    private fun inner(task: PipelineTask.Join, cursor: QueryJoinCursor.Inner): Boolean {
        val matches = joins.matches(task.stage, task.value) ?: return contractViolation()
        if (cursor.nextMatch !in 0..matches.size) return contractViolation()
        if (!state.consumeUnit()) return false
        if (cursor.nextMatch == matches.size) {
            tasks.removeFirst()
            return true
        }
        val right = joins.rightRows(task.stage).getOrNull(matches[cursor.nextMatch]) ?: return contractViolation()
        val mode = task.stage.mode as? QueryJoinMode.Inner ?: return contractViolation()
        val row =
            when (val joined = QueryBindingRow.join(mode, task.value, right)) {
                is Refinement.Refined -> joined.value
                is Refinement.Rejected -> return contractViolation()
            }
        tasks.removeFirst()
        if (cursor.nextMatch + 1 < matches.size) {
            tasks.addFirst(task.copy(cursor = QueryJoinCursor.Inner(cursor.nextMatch + 1)))
        }
        tasks.addFirst(PipelineTask.Binding(row, task.stage.next))
        return true
    }

    private fun semi(task: PipelineTask.Join, cursor: QueryJoinCursor.Semi): Boolean {
        val matches = joins.matches(task.stage, task.value) ?: return contractViolation()
        if (matches.isNotEmpty() && cursor.nextMatch !in matches.indices) return contractViolation()
        if (!state.consumeUnit()) return false
        if (matches.isEmpty()) {
            tasks.removeFirst()
            return true
        }
        val right = joins.rightRows(task.stage).getOrNull(matches[cursor.nextMatch]) ?: return contractViolation()
        val merged =
            when (val result = mergeRows(cursor.accumulated, right)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return contractViolation()
            }
        tasks.removeFirst()
        if (cursor.nextMatch + 1 == matches.size) {
            tasks.addFirst(PipelineTask.Symbol(merged, task.stage.next))
        } else {
            tasks.addFirst(task.copy(cursor = QueryJoinCursor.Semi(cursor.nextMatch + 1, merged)))
        }
        return true
    }

    private fun anti(task: PipelineTask.Join): Boolean {
        if (task.stage.right.completeMembership() is Refinement.Rejected) return contractViolation()
        val matches = joins.matches(task.stage, task.value) ?: return contractViolation()
        if (!state.consumeUnit()) return false
        tasks.removeFirst()
        if (matches.isEmpty()) tasks.addFirst(PipelineTask.Symbol(task.value, task.stage.next))
        return true
    }

    private fun contractViolation(): Boolean {
        state.contractViolation = true
        return false
    }
}
