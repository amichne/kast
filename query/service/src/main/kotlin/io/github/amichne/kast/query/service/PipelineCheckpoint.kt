package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

private const val TASK_OVERHEAD_BYTES = 512L
private const val RETAINED_EVIDENCE_MULTIPLIER = 4L
private const val DISCOVERY_TASK_BYTES = 4096L
private const val RELATION_CURSOR_BYTES = 4096L
private const val REFERENCE_TASK_BYTES = 512L
private const val DISTINCT_SYMBOL_BYTES = 512L

internal val pageLimits =
    setOf(
        QueryLimitation.RESULT_LIMIT_REACHED,
        QueryLimitation.BYTE_LIMIT_REACHED,
        QueryLimitation.WORK_LIMIT_REACHED,
        QueryLimitation.TIME_LIMIT_REACHED,
    )

/** A detached depth-first ordered pipeline. Stage identity retains each distinct operator's own history. */
internal sealed interface PipelineTask {
    data class Failure(val value: QueryItemFailure) : PipelineTask

    data class Discover(val syntax: QueryDiscoverySyntax, val next: ExactQueryStage) : PipelineTask

    data class Revalidate(val selector: SymbolSelector, val next: ExactQueryStage) : PipelineTask

    data class AppendReferences(val stage: ExactQueryStage.AppendReferences) : PipelineTask

    data class Candidate(val value: SymbolDiscoverySelection, val stage: ExactQueryStage) : PipelineTask

    data class Symbol(val value: QuerySymbol, val stage: ExactQueryStage) : PipelineTask

    data class Related(val value: QuerySymbol, val stage: ExactQueryStage.Related, val cursor: RelationContinuation?) :
        PipelineTask
}

internal data class PipelineCheckpoint(
    override val plan: AdmittedQueryPlan,
    override val lease: SemanticReadAuthority,
    val tasks: List<PipelineTask>,
    val symbolDistinct: Map<ExactQueryStage.Distinct, Set<CanonicalSymbolId>>,
    val limitations: Set<QueryLimitation>,
) : QueryCheckpoint {
    override val retainedBytes: Long =
        saturatedAdd(
            (initialTasks(plan) + tasks).fold(0L) { total, task ->
                saturatedAdd(total, saturatedAdd(TASK_OVERHEAD_BYTES, task.retainedBytes()))
            },
            saturatedAdd(
                symbolDistinct.values.fold(0L) { total, values ->
                    saturatedAdd(total, saturatedMultiply(values.size.toLong(), DISTINCT_SYMBOL_BYTES))
                },
                (plan as? AdmittedQueryPlan.Retained)?.source?.retainedBytes ?: 0L,
            ),
        )
}

private fun PipelineTask.retainedBytes(): Long =
    when (this) {
        is PipelineTask.Failure -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Symbol -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Related ->
            saturatedAdd(
                saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER),
                RELATION_CURSOR_BYTES,
            )
        is PipelineTask.Candidate ->
            saturatedMultiply(value.candidate.projectedUtf8Size().value, RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Revalidate ->
            saturatedMultiply(
                QuerySymbol(SymbolDescription.from(selector), emptyList()).projectedUtf8Size(),
                RETAINED_EVIDENCE_MULTIPLIER,
            )
        is PipelineTask.AppendReferences ->
            saturatedMultiply(stage.references.values.size.toLong(), REFERENCE_TASK_BYTES)
        is PipelineTask.Discover -> DISCOVERY_TASK_BYTES
    }

private fun saturatedMultiply(left: Long, right: Long): Long =
    if (left < 0L || right < 0L || left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

internal fun initialTasks(plan: AdmittedQueryPlan): List<PipelineTask> = sourceTasks(plan) + appendTasks(plan)

private fun sourceTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> listOf(PipelineTask.Discover(plan.source, ExactQueryStage.Distinct(plan.stage)))
        is AdmittedQueryPlan.ExactReferences -> plan.source.values.map { PipelineTask.Revalidate(it, plan.stage) }
        is AdmittedQueryPlan.Retained -> plan.source.symbols.map { PipelineTask.Symbol(it, plan.stage) }
    }

private fun appendTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> appendTasks(plan.stage)
        is AdmittedQueryPlan.ExactReferences -> appendTasks(plan.stage)
        is AdmittedQueryPlan.Retained -> appendTasks(plan.stage)
    }

private fun appendTasks(stage: ExactQueryStage): List<PipelineTask> =
    when (stage) {
        is ExactQueryStage.AppendReferences -> listOf(PipelineTask.AppendReferences(stage)) + appendTasks(stage.next)
        is ExactQueryStage.Distinct -> appendTasks(stage.next)
        is ExactQueryStage.Where -> appendTasks(stage.next)
        is ExactQueryStage.Related -> appendTasks(stage.next)
        is ExactQueryStage.Emit -> emptyList()
    }

internal fun discoverySyntax(plan: AdmittedQueryPlan): QueryDiscoverySyntax? =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> plan.source
        is AdmittedQueryPlan.ExactReferences,
        is AdmittedQueryPlan.Retained -> null
    }
