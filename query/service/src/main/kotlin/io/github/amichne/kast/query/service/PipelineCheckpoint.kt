package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryCompositionInput
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryRetainedResult
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

    data class Feed(val stage: ExactQueryStage.Concat) : PipelineTask

    data class FlushSet(val stage: ExactQueryStage.Set) : PipelineTask

    data class FlushDistinct(val stage: ExactQueryStage.Distinct) : PipelineTask

    data class Candidate(val value: SymbolDiscoverySelection, val stage: ExactQueryStage) : PipelineTask

    data class Symbol(val value: QuerySymbol, val stage: ExactQueryStage) : PipelineTask

    data class Related(val value: QuerySymbol, val stage: ExactQueryStage.Related, val cursor: RelationContinuation?) :
        PipelineTask
}

internal data class PipelineCheckpoint(
    override val plan: AdmittedQueryPlan,
    override val lease: SemanticReadAuthority,
    val tasks: List<PipelineTask>,
    val identityRows: Map<ExactQueryStage, Map<CanonicalSymbolId, QuerySymbol>>,
    val limitations: Set<QueryLimitation>,
) : QueryCheckpoint {
    override val retainedBytes: Long =
        saturatedAdd(
            (initialTasks(plan) + tasks).fold(0L) { total, task ->
                saturatedAdd(total, saturatedAdd(TASK_OVERHEAD_BYTES, task.retainedBytes()))
            },
            saturatedAdd(
                identityRows.values.fold(0L) { total, rows ->
                    rows.values.fold(total) { size, row ->
                        saturatedAdd(size, saturatedMultiply(row.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER))
                    }
                },
                plan.retainedInputBytes(),
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
        is PipelineTask.Feed ->
            when (val input = stage.input) {
                is QueryCompositionInput.ExactReferences ->
                    saturatedMultiply(input.references.values.size.toLong(), REFERENCE_TASK_BYTES)
                is QueryCompositionInput.Retained -> input.result.retainedBytes
            }
        is PipelineTask.FlushSet -> stage.right.retainedBytes
        is PipelineTask.FlushDistinct -> 0L
        is PipelineTask.Discover -> DISCOVERY_TASK_BYTES
    }

private fun saturatedMultiply(left: Long, right: Long): Long =
    if (left < 0L || right < 0L || left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

internal fun initialTasks(plan: AdmittedQueryPlan): List<PipelineTask> = sourceTasks(plan) + boundaryTasks(plan)

internal fun PipelineTask.Feed.expand(): List<PipelineTask> =
    when (val input = stage.input) {
        is QueryCompositionInput.ExactReferences ->
            input.references.values.map { PipelineTask.Revalidate(it, stage.next) }
        is QueryCompositionInput.Retained ->
            input.result.symbols.map { PipelineTask.Symbol(it, stage.next) } +
                input.result.failures.map(PipelineTask::Failure)
    }

private fun sourceTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> listOf(PipelineTask.Discover(plan.source, plan.stage))
        is AdmittedQueryPlan.ExactReferences -> plan.source.values.map { PipelineTask.Revalidate(it, plan.stage) }
        is AdmittedQueryPlan.Retained ->
            plan.source.symbols.map { PipelineTask.Symbol(it, plan.stage) } +
                plan.source.failures.map(PipelineTask::Failure)
    }

private fun boundaryTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.ExactReferences -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.Retained -> boundaryTasks(plan.stage)
    }

private fun boundaryTasks(stage: ExactQueryStage): List<PipelineTask> =
    when (stage) {
        is ExactQueryStage.Concat -> listOf(PipelineTask.Feed(stage)) + boundaryTasks(stage.next)
        is ExactQueryStage.Set -> listOf(PipelineTask.FlushSet(stage)) + boundaryTasks(stage.next)
        is ExactQueryStage.Distinct -> listOf(PipelineTask.FlushDistinct(stage)) + boundaryTasks(stage.next)
        is ExactQueryStage.Where -> boundaryTasks(stage.next)
        is ExactQueryStage.Related -> boundaryTasks(stage.next)
        is ExactQueryStage.Emit -> emptyList()
    }

internal fun AdmittedQueryPlan.retainedInputs(): List<QueryRetainedResult> {
    val stage =
        when (this) {
            is AdmittedQueryPlan.Symbols -> stage
            is AdmittedQueryPlan.ExactReferences -> stage
            is AdmittedQueryPlan.Retained -> stage
        }
    val source = (this as? AdmittedQueryPlan.Retained)?.source?.let { listOf(it) }.orEmpty()
    return source + stage.retainedInputs()
}

private fun ExactQueryStage.retainedInputs(): List<QueryRetainedResult> =
    when (this) {
        is ExactQueryStage.Concat ->
            (input as? QueryCompositionInput.Retained)?.result?.let { listOf(it) }.orEmpty() + next.retainedInputs()
        is ExactQueryStage.Set -> listOf(right) + next.retainedInputs()
        is ExactQueryStage.Distinct -> next.retainedInputs()
        is ExactQueryStage.Where -> next.retainedInputs()
        is ExactQueryStage.Related -> next.retainedInputs()
        is ExactQueryStage.Emit -> emptyList()
    }

private fun AdmittedQueryPlan.retainedInputBytes(): Long =
    retainedInputs().fold(0L) { size, result -> saturatedAdd(size, result.retainedBytes) }

internal fun discoverySyntax(plan: AdmittedQueryPlan): QueryDiscoverySyntax? =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> plan.source
        is AdmittedQueryPlan.ExactReferences,
        is AdmittedQueryPlan.Retained -> null
    }
