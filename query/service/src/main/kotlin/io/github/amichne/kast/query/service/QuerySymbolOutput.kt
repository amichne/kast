package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolSource

/** Final symbol projection retains source-read failures and shares the interpreter output authority. */
internal suspend fun QueryReadStages.emitSymbol(
    task: PipelineTask.Symbol,
    stage: ExactQueryStage.Emit,
    state: QueryExecutionState,
    tasks: ArrayDeque<PipelineTask>,
    emit: (Long, () -> Unit) -> Boolean,
    append: (QuerySymbol) -> Unit,
): Boolean {
    val expanded = task.expandOutput(stage.output)
    if (expanded != null) {
        tasks.removeFirst()
        expanded.asReversed().forEach(tasks::addFirst)
        return true
    }
    val output =
        stage.output as? QueryOutputSyntax.Symbols
            ?: run {
                state.contractViolation = true
                return false
            }
    if (QuerySymbolField.SOURCE !in output.fields.values || task.value.source !is QuerySymbolSource.Pending)
        return emit(task.value.projectedUtf8Size()) { append(task.value) }
    val resources =
        when (val admitted = state.sourceResources()) {
            is Refinement.Rejected -> return false
            is Refinement.Refined -> admitted.value
        }
    tasks.removeFirst()
    tasks.addFirst(task.copy(value = sourceWindow(task.value, state, resources)))
    return !state.contractViolation
}
