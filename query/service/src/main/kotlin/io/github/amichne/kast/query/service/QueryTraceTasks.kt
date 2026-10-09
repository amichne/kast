package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryGroupingEvidence
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryTracePhase
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind

/** Fixed fan-out through the existing relation tasks, checkpoint, budget and qualification owners. */
internal fun traceTasks(symbol: QuerySymbol, stage: ExactQueryStage.Trace): List<PipelineTask> {
    val tasks = mutableListOf<PipelineTask>(PipelineTask.Symbol(symbol, stage.next))
    val uses = ExactQueryStage.Trace(QueryTracePhase.USE, stage.expansion, stage.next)
    fun related(meaning: RelationMeaning, next: ExactQueryStage) {
        tasks += PipelineTask.Related(symbol, ExactQueryStage.Related(meaning, next, stage.expansion), null)
    }
    when (stage.phase) {
        QueryTracePhase.SEED -> {
            related(RelationMeaning.References, uses)
            if (symbol.isCallable()) related(RelationMeaning.Callers, uses)
            val implementations = ExactQueryStage.Trace(QueryTracePhase.IMPLEMENTATION, stage.expansion, stage.next)
            when (symbol.description.kind) {
                CompilerSymbolKind.CLASSLIKE -> {
                    related(RelationMeaning.Implementations, implementations)
                    tasks += PipelineTask.TraceMembers(symbol, stage)
                }
                CompilerSymbolKind.FUNCTION,
                CompilerSymbolKind.PROPERTY -> related(RelationMeaning.Overrides, implementations)
                CompilerSymbolKind.CONSTRUCTOR,
                CompilerSymbolKind.TYPE_ALIAS -> Unit
            }
        }
        QueryTracePhase.IMPLEMENTATION -> {
            related(RelationMeaning.References, uses)
            if (symbol.isCallable()) related(RelationMeaning.Callers, uses)
        }
        QueryTracePhase.USE -> if (symbol.isCallable()) tasks += PipelineTask.Symbol(symbol, traceUseStage(stage))
    }
    return tasks
}

/** Collect all upstream arrival evidence before spending one downstream caller read per exact capability. */
internal fun traceUseStage(stage: ExactQueryStage.Trace): ExactQueryStage.Distinct =
    ExactQueryStage.Distinct(
        ExactQueryStage.Related(RelationMeaning.Callers, stage.next, stage.expansion),
        QueryGroupingEvidence.ALL_SCOPED_ARRIVALS,
    )

private fun QuerySymbol.isCallable(): Boolean =
    when (description.kind) {
        CompilerSymbolKind.FUNCTION,
        CompilerSymbolKind.CONSTRUCTOR -> true
        CompilerSymbolKind.CLASSLIKE,
        CompilerSymbolKind.PROPERTY,
        CompilerSymbolKind.TYPE_ALIAS -> false
    }

internal fun advanceTrace(
    task: PipelineTask.Symbol,
    stage: ExactQueryStage.Trace,
    tasks: ArrayDeque<PipelineTask>,
): Boolean {
    tasks.removeFirst()
    traceTasks(task.value, stage).asReversed().forEach(tasks::addFirst)
    return true
}
