package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryCompositionInput
import io.github.amichne.kast.query.contract.QueryContainingDeclaration
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalContinuation
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

private const val TASK_OVERHEAD_BYTES = 512L
private const val RETAINED_EVIDENCE_MULTIPLIER = 4L
private const val DISCOVERY_TASK_BYTES = 4096L
private const val RELATION_CURSOR_BYTES = 4096L
private const val TRAVERSAL_CURSOR_BYTES = 8192L
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

    data class Omission(val value: QueryRelationOmission) : PipelineTask

    data class WalkObservation(val value: QueryWalkObservation) : PipelineTask

    data class Occurrence(val value: QuerySymbol) : PipelineTask

    data class WalkRecord(val value: QuerySymbol) : PipelineTask

    data class Discover(val syntax: QueryDiscoverySyntax, val next: ExactQueryStage) : PipelineTask

    data class DiscoverLocation(val target: QueryContainingDeclaration, val next: ExactQueryStage) : PipelineTask

    data class Revalidate(val selector: SymbolSelector, val next: ExactQueryStage) : PipelineTask

    data class Feed(val stage: ExactQueryStage.Concat) : PipelineTask

    data class FlushSet(val stage: ExactQueryStage.Set) : PipelineTask

    data class FlushDistinct(val stage: ExactQueryStage.Distinct) : PipelineTask

    data class JoinEvidence(val stage: ExactQueryStage.Join) : PipelineTask

    data class Candidate(val value: SymbolDiscoverySelection, val stage: ExactQueryStage) : PipelineTask

    data class Symbol(val value: QuerySymbol, val stage: ExactQueryStage) : PipelineTask

    data class Binding(val value: QueryBindingRow, val stage: ExactQueryStage) : PipelineTask

    data class Related(val value: QuerySymbol, val stage: ExactQueryStage.Related, val cursor: RelationContinuation?) :
        PipelineTask

    data class Walk(val value: QuerySymbol, val stage: ExactQueryStage.Walk, val cursor: TraversalContinuation?) :
        PipelineTask

    data class Join(val value: QuerySymbol, val stage: ExactQueryStage.Join, val cursor: QueryJoinCursor) : PipelineTask
}

internal sealed interface QueryJoinCursor {
    data object Build : QueryJoinCursor

    data class Inner(val nextMatch: Int) : QueryJoinCursor

    data class Semi(val nextMatch: Int, val accumulated: QuerySymbol) : QueryJoinCursor

    data object Anti : QueryJoinCursor
}

internal fun PipelineTask.needsWork(): Boolean =
    when (this) {
        is PipelineTask.Discover,
        is PipelineTask.DiscoverLocation,
        is PipelineTask.Revalidate,
        is PipelineTask.Related,
        is PipelineTask.Walk,
        is PipelineTask.Join -> true
        is PipelineTask.Binding -> false
        else -> false
    }

internal data class PipelineCheckpoint(
    override val plan: AdmittedQueryPlan,
    override val lease: SemanticReadAuthority,
    val tasks: List<PipelineTask>,
    val identityRows: Map<ExactQueryStage, Map<CanonicalSymbolId, QuerySymbol>>,
    val joinState: QueryJoinSnapshot,
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
                saturatedAdd(plan.retainedInputBytes(), QueryJoins(joinState).retainedBytes()),
            ),
        )
}

private fun PipelineTask.retainedBytes(): Long =
    when (this) {
        is PipelineTask.Failure -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Omission -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.WalkObservation -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Occurrence -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.WalkRecord -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Symbol -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Binding -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Related ->
            saturatedAdd(
                saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER),
                RELATION_CURSOR_BYTES,
            )
        is PipelineTask.Walk ->
            saturatedAdd(
                saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER),
                TRAVERSAL_CURSOR_BYTES,
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
        is PipelineTask.JoinEvidence -> 0L
        is PipelineTask.Join ->
            saturatedAdd(
                saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER),
                (cursor as? QueryJoinCursor.Semi)?.accumulated?.projectedUtf8Size() ?: 0L,
            )
        is PipelineTask.Discover,
        is PipelineTask.DiscoverLocation -> DISCOVERY_TASK_BYTES
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
                input.result.failures.map(PipelineTask::Failure) +
                input.result.omissions.map(PipelineTask::Omission) +
                input.result.walkObservations.map(PipelineTask::WalkObservation)
    }

/** A record output keeps each established occurrence as a separate retained row. */
internal fun PipelineTask.Symbol.expandOutput(output: QueryOutputSyntax): List<PipelineTask>? =
    when (output) {
        QueryOutputSyntax.Occurrences ->
            (value.arrival as? QueryArrivalEvidence.Proven)?.facts.orEmpty().map { fact ->
                PipelineTask.Occurrence(value.copy(arrival = QueryArrivalEvidence.Proven.one(fact)))
            }
        QueryOutputSyntax.TraversalRecords ->
            (value.walkArrival as? QueryWalkArrival.Proven)?.records.orEmpty().map { record ->
                PipelineTask.WalkRecord(value.copy(walkArrival = QueryWalkArrival.Proven.one(record)))
            }
        QueryOutputSyntax.BindingRows -> null
        is QueryOutputSyntax.Symbols -> null
    }

private fun sourceTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> listOf(PipelineTask.Discover(plan.source, plan.stage))
        is AdmittedQueryPlan.Location -> listOf(PipelineTask.DiscoverLocation(plan.source, plan.stage))
        is AdmittedQueryPlan.ExactReferences -> plan.source.values.map { PipelineTask.Revalidate(it, plan.stage) }
        is AdmittedQueryPlan.Retained ->
            (when (val source = plan.source) {
                is QueryRetainedResult.Symbols -> source.symbols.map { PipelineTask.Symbol(it, plan.stage) }
                is QueryRetainedResult.Bindings -> source.bindingRows.map { PipelineTask.Binding(it, plan.stage) }
            }) +
                plan.source.failures.map(PipelineTask::Failure) +
                plan.source.omissions.map(PipelineTask::Omission) +
                plan.source.walkObservations.map(PipelineTask::WalkObservation)
    }

private fun boundaryTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.Location -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.ExactReferences -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.Retained -> boundaryTasks(plan.stage)
    }

private fun boundaryTasks(stage: ExactQueryStage): List<PipelineTask> =
    when (stage) {
        is ExactQueryStage.ProjectBinding -> boundaryTasks(stage.next)
        is ExactQueryStage.Concat -> listOf(PipelineTask.Feed(stage)) + boundaryTasks(stage.next)
        is ExactQueryStage.Set -> listOf(PipelineTask.FlushSet(stage)) + boundaryTasks(stage.next)
        is ExactQueryStage.Distinct -> listOf(PipelineTask.FlushDistinct(stage)) + boundaryTasks(stage.next)
        is ExactQueryStage.Join -> listOf(PipelineTask.JoinEvidence(stage)) + boundaryTasks(stage.next)
        is ExactQueryStage.Where -> boundaryTasks(stage.next)
        is ExactQueryStage.Related -> boundaryTasks(stage.next)
        is ExactQueryStage.Walk -> boundaryTasks(stage.next)
        is ExactQueryStage.Emit -> emptyList()
    }

internal fun AdmittedQueryPlan.retainedInputs(): List<QueryRetainedResult> {
    val stage =
        when (this) {
            is AdmittedQueryPlan.Symbols -> stage
            is AdmittedQueryPlan.Location -> stage
            is AdmittedQueryPlan.ExactReferences -> stage
            is AdmittedQueryPlan.Retained -> stage
        }
    val source = (this as? AdmittedQueryPlan.Retained)?.source?.let { listOf(it) }.orEmpty()
    return source + stage.retainedInputs()
}

internal fun AdmittedQueryPlan.outputSyntax(): QueryOutputSyntax =
    when (this) {
        is AdmittedQueryPlan.Symbols -> stage.outputSyntax()
        is AdmittedQueryPlan.Location -> stage.outputSyntax()
        is AdmittedQueryPlan.ExactReferences -> stage.outputSyntax()
        is AdmittedQueryPlan.Retained -> stage.outputSyntax()
    }

internal fun AdmittedQueryPlan.bindingMode(): QueryJoinMode.Inner {
    var mode = ((this as? AdmittedQueryPlan.Retained)?.source as? QueryRetainedResult.Bindings)?.mode
    var stage =
        when (this) {
            is AdmittedQueryPlan.Symbols -> stage
            is AdmittedQueryPlan.Location -> stage
            is AdmittedQueryPlan.ExactReferences -> stage
            is AdmittedQueryPlan.Retained -> stage
        }
    while (stage !is ExactQueryStage.Emit) {
        stage =
            when (stage) {
                is ExactQueryStage.Join -> {
                    mode = stage.mode as? QueryJoinMode.Inner
                    stage.next
                }
                is ExactQueryStage.ProjectBinding -> {
                    mode = null
                    stage.next
                }
                is ExactQueryStage.Concat -> stage.next
                is ExactQueryStage.Set -> stage.next
                is ExactQueryStage.Distinct -> stage.next
                is ExactQueryStage.Where -> stage.next
                is ExactQueryStage.Related -> stage.next
                is ExactQueryStage.Walk -> stage.next
                is ExactQueryStage.Emit -> stage
            }
    }
    return requireNotNull(mode) { "Admitted binding output lost its column identity" }
}

private fun ExactQueryStage.outputSyntax(): QueryOutputSyntax =
    when (this) {
        is ExactQueryStage.ProjectBinding -> next.outputSyntax()
        is ExactQueryStage.Concat -> next.outputSyntax()
        is ExactQueryStage.Set -> next.outputSyntax()
        is ExactQueryStage.Distinct -> next.outputSyntax()
        is ExactQueryStage.Join -> next.outputSyntax()
        is ExactQueryStage.Where -> next.outputSyntax()
        is ExactQueryStage.Related -> next.outputSyntax()
        is ExactQueryStage.Walk -> next.outputSyntax()
        is ExactQueryStage.Emit -> output
    }

internal fun AdmittedQueryPlan.exceedsTraversalDepth(ceiling: TraversalDepthLimit): Boolean =
    when (this) {
        is AdmittedQueryPlan.Symbols -> stage.exceedsTraversalDepth(ceiling)
        is AdmittedQueryPlan.Location -> stage.exceedsTraversalDepth(ceiling)
        is AdmittedQueryPlan.ExactReferences -> stage.exceedsTraversalDepth(ceiling)
        is AdmittedQueryPlan.Retained -> stage.exceedsTraversalDepth(ceiling)
    }

private fun ExactQueryStage.exceedsTraversalDepth(ceiling: TraversalDepthLimit): Boolean =
    when (this) {
        is ExactQueryStage.ProjectBinding -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Walk -> maximumDepth.value > ceiling.value || next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Concat -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Set -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Distinct -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Join -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Where -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Related -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Emit -> false
    }

private fun ExactQueryStage.retainedInputs(): List<QueryRetainedResult> =
    when (this) {
        is ExactQueryStage.ProjectBinding -> next.retainedInputs()
        is ExactQueryStage.Concat ->
            (input as? QueryCompositionInput.Retained)?.result?.let { listOf(it) }.orEmpty() + next.retainedInputs()
        is ExactQueryStage.Set -> listOf(right) + next.retainedInputs()
        is ExactQueryStage.Distinct -> next.retainedInputs()
        is ExactQueryStage.Join -> listOf(right) + next.retainedInputs()
        is ExactQueryStage.Where -> next.retainedInputs()
        is ExactQueryStage.Related -> next.retainedInputs()
        is ExactQueryStage.Walk -> next.retainedInputs()
        is ExactQueryStage.Emit -> emptyList()
    }

private fun AdmittedQueryPlan.retainedInputBytes(): Long =
    retainedInputs().fold(0L) { size, result -> saturatedAdd(size, result.retainedBytes) }

internal fun discoverySyntax(plan: AdmittedQueryPlan): QueryDiscoverySyntax? =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> plan.source
        is AdmittedQueryPlan.Location -> null
        is AdmittedQueryPlan.ExactReferences,
        is AdmittedQueryPlan.Retained -> null
    }
