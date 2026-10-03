package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryOutputSyntax

/** Binding row projection shares the interpreter task order and output grant. */
internal object QueryBindingTasks {
    fun advance(
        task: PipelineTask.Binding,
        state: QueryExecutionState,
        tasks: ArrayDeque<PipelineTask>,
        emit: (Long, () -> Unit) -> Boolean,
        record: (io.github.amichne.kast.query.contract.QueryBindingRow) -> Unit,
    ): Boolean =
        when (val stage = task.stage) {
            is ExactQueryStage.ProjectBinding -> {
                val selected =
                    when (stage.name) {
                        task.value.left.name -> task.value.left.value.symbol
                        task.value.right.name -> task.value.right.value.symbol
                        else -> {
                            state.contractViolation = true
                            return false
                        }
                    }
                tasks.removeFirst()
                tasks.addFirst(PipelineTask.Symbol(selected, stage.next))
                true
            }
            is ExactQueryStage.Emit ->
                if (stage.output == QueryOutputSyntax.BindingRows) {
                    emit(task.value.projectedUtf8Size()) { record(task.value) }
                } else {
                    state.contractViolation = true
                    false
                }
            else -> {
                state.contractViolation = true
                false
            }
        }
}
