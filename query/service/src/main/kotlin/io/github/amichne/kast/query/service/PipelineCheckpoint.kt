package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryCheckpointPartialSymbols
import io.github.amichne.kast.query.contract.QueryCheckpointStorageBytes
import io.github.amichne.kast.query.contract.QueryCheckpointStorageEstimate
import io.github.amichne.kast.query.contract.QueryCompositionInput
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.symbol.contract.SymbolDescription
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

internal data class PipelineCheckpoint(
    val seed: PipelineSeed.Accounted,
    override val lease: SemanticReadAuthority,
    val tasks: List<PipelineTask>,
    val identityRows: Map<ExactQueryStage, Map<QueryIdentityRowKey, QuerySymbol>>,
    val joinState: QueryJoinSnapshot,
    val limitations: Set<QueryLimitation>,
    val emittedCount: io.github.amichne.kast.query.contract.QueryCount,
    val impact: QueryImpactSnapshot? = null,
) : QueryCheckpointPartialSymbols {
    override val plan: AdmittedQueryPlan
        get() = seed.plan

    override fun partialSymbols(
        rowLimit: io.github.amichne.kast.kernel.ResultLimit,
        byteLimit: io.github.amichne.kast.query.contract.QueryByteLimit,
    ): io.github.amichne.kast.query.contract.QueryRows.Symbols = traceProof(rowLimit, byteLimit)

    override val retainedBytes: Long
        get() = retainedBytes(QueryImpactRetainedGraph())

    override fun retainedBytes(graph: QueryImpactRetainedGraph): Long = storageEstimate(graph).required.value

    fun storageEstimate(graph: QueryImpactRetainedGraph = QueryImpactRetainedGraph()): QueryCheckpointStorageEstimate =
        QueryCheckpointStorageEstimate(
            tasks = pipelineTaskBytes(tasks, graph, saturatedAdd(4096L, seed.retainedRootBytes(graph))).storageBytes(),
            identityRows =
                identityRows.values
                    .fold(0L) { total, rows ->
                        rows.values.fold(total) { size, row ->
                            saturatedAdd(size, saturatedMultiply(row.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER))
                        }
                    }
                    .storageBytes(),
            inputs = plan.retainedInputBytes(graph).storageBytes(),
            impact = (impact?.retainedBytes(graph) ?: 0L).storageBytes(),
            joins = QueryJoins(joinState).retainedBytes().storageBytes(),
        )
}

internal fun Long.storageBytes(): QueryCheckpointStorageBytes =
    when (val parsed = QueryCheckpointStorageBytes.parse(this)) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> parsed.value
        is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Checkpoint accounting produced negative storage")
    }

internal fun pipelineTaskBytes(
    tasks: List<PipelineTask>,
    graph: QueryImpactRetainedGraph,
    initial: Long = 0L,
): Long =
    tasks.fold(initial) { total, task ->
        saturatedAdd(total, graph.checkpointTask(task))
    }

internal fun PipelineTask.retainedOwnerBytes(graph: QueryImpactRetainedGraph): Long =
    saturatedAdd(TASK_OVERHEAD_BYTES, retainedPayloadBytes(graph))

private fun PipelineTask.retainedPayloadBytes(graph: QueryImpactRetainedGraph): Long =
    when (this) {
        is PipelineTask.TraceTask -> retainedTraceBytes()
        is PipelineTask.ImpactExplore -> route.retainedBytes(graph)
        PipelineTask.ImpactFinalize -> TASK_OVERHEAD_BYTES
        is PipelineTask.ValuePath -> graph.path(value)
        is PipelineTask.Failure -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Omission -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.RelationObservation -> value.retainedBytes
        is PipelineTask.WalkObservation -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Occurrence -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.ReferenceObservation -> saturatedMultiply(value.retainedBytes, RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.DiscoveryObservation -> value.retainedBytes
        is PipelineTask.WalkRecord -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Symbol -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Binding -> saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER)
        is PipelineTask.Related ->
            saturatedAdd(
                saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER),
                saturatedAdd(RELATION_CURSOR_BYTES, cursor?.providerState?.let(graph::providerState) ?: 0L),
            )
        is PipelineTask.Walk ->
            saturatedAdd(
                saturatedMultiply(value.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER),
                saturatedAdd(TRAVERSAL_CURSOR_BYTES, cursor?.checkpoint?.retainedBytes ?: 0L),
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
        is PipelineTask.Discover -> saturatedAdd(DISCOVERY_TASK_BYTES, remainder?.retainedBytes ?: 0L)
        is PipelineTask.DiscoverLocation -> DISCOVERY_TASK_BYTES
        is PipelineTask.DiscoverText -> DISCOVERY_TASK_BYTES
    }

private fun PipelineTask.TraceTask.retainedTraceBytes(): Long =
    when (this) {
        is PipelineTask.TraceMembers -> retainedMemberBytes()
        is PipelineTask.TraceMember -> retainedMemberBytes()
    }

private fun PipelineTask.TraceMember.retainedMemberBytes(): Long =
    saturatedAdd(
        saturatedMultiply(member.selection.candidate.projectedUtf8Size().value, RETAINED_EVIDENCE_MULTIPLIER),
        DISCOVERY_TASK_BYTES,
    )

private fun PipelineTask.TraceMembers.retainedMemberBytes(): Long {
    val cursorBytes =
        (page as? io.github.amichne.kast.source.contract.SourceReadPage.Continue)?.continuation?.value?.length?.toLong()
            ?: 0L
    return saturatedAdd(
        saturatedMultiply(owner.projectedUtf8Size(), RETAINED_EVIDENCE_MULTIPLIER),
        saturatedAdd(saturatedMultiply(cursorBytes, SOURCE_CURSOR_CHARACTER_BYTES), DISCOVERY_TASK_BYTES),
    )
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
                input.result.walkObservations.map(PipelineTask::WalkObservation) +
                input.result.referenceObservations.map(PipelineTask::ReferenceObservation) +
                input.result.discoveryObservations.map { PipelineTask.DiscoveryObservation(it) } +
                input.result.relationObservations.map(PipelineTask::RelationObservation)
    }

/** A record output keeps each established occurrence as a separate retained row. */
internal fun PipelineTask.Symbol.expandOutput(output: QueryOutputSyntax): List<PipelineTask>? =
    when (output) {
        QueryOutputSyntax.Occurrences ->
            (value.arrival as? QueryArrivalEvidence.Proven)?.facts.orEmpty().map { fact ->
                PipelineTask.Occurrence(
                    io.github.amichne.kast.query.contract.QueryOccurrence.Declaration(
                        value.copy(arrival = QueryArrivalEvidence.Proven.one(fact)),
                        fact,
                    )
                )
            }
        QueryOutputSyntax.TraversalRecords ->
            (value.walkArrival as? QueryWalkArrival.Proven)?.records.orEmpty().map { record ->
                PipelineTask.WalkRecord(value.copy(walkArrival = QueryWalkArrival.Proven.one(record)))
            }
        QueryOutputSyntax.BindingRows -> null
        QueryOutputSyntax.ValuePaths -> null
        is QueryOutputSyntax.ImpactWitness -> null
        is QueryOutputSyntax.Symbols -> null
    }

private fun sourceTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Impact ->
            plan.source.producers.flatMap {
                QueryImpactRoute.initial(it, plan.source).map(PipelineTask::ImpactExplore)
            } + PipelineTask.ImpactFinalize
        is AdmittedQueryPlan.Symbols -> listOf(PipelineTask.Discover(plan.source, plan.stage))
        is AdmittedQueryPlan.Text -> listOf(PipelineTask.DiscoverText(plan.source, plan.stage))
        is AdmittedQueryPlan.Location -> listOf(PipelineTask.DiscoverLocation(plan.source, plan.stage))
        is AdmittedQueryPlan.ExactReferences -> plan.source.values.map { PipelineTask.Revalidate(it, plan.stage) }
        is AdmittedQueryPlan.Retained ->
            (when (val source = plan.source) {
                is QueryRetainedResult.Symbols -> source.symbols.map { PipelineTask.Symbol(it, plan.stage) }
                is QueryRetainedResult.Bindings -> source.bindingRows.map { PipelineTask.Binding(it, plan.stage) }
                is QueryRetainedResult.Occurrences -> source.occurrences.map(PipelineTask::Occurrence)
                is QueryRetainedResult.ValuePaths -> source.valuePaths.map(PipelineTask::ValuePath)
            }) +
                plan.source.failures.map(PipelineTask::Failure) +
                plan.source.omissions.map(PipelineTask::Omission) +
                plan.source.walkObservations.map(PipelineTask::WalkObservation) +
                plan.source.referenceObservations.map(PipelineTask::ReferenceObservation) +
                plan.source.discoveryObservations.map { PipelineTask.DiscoveryObservation(it) } +
                plan.source.relationObservations.map(PipelineTask::RelationObservation)
    }

private fun boundaryTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Impact -> emptyList()
        is AdmittedQueryPlan.Symbols -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.Text -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.Location -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.ExactReferences -> boundaryTasks(plan.stage)
        is AdmittedQueryPlan.Retained -> boundaryTasks(plan.stage)
    }

private fun boundaryTasks(stage: ExactQueryStage): List<PipelineTask> =
    when (stage) {
        is ExactQueryStage.Trace -> listOf(PipelineTask.FlushDistinct(traceUseStage(stage))) + boundaryTasks(stage.next)
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
            is AdmittedQueryPlan.Impact -> stage
            is AdmittedQueryPlan.Symbols -> stage
            is AdmittedQueryPlan.Text -> stage
            is AdmittedQueryPlan.Location -> stage
            is AdmittedQueryPlan.ExactReferences -> stage
            is AdmittedQueryPlan.Retained -> stage
        }
    val source = (this as? AdmittedQueryPlan.Retained)?.source?.let { listOf(it) }.orEmpty()
    return source + stage.retainedInputs()
}

internal fun AdmittedQueryPlan.outputSyntax(): QueryOutputSyntax =
    when (this) {
        is AdmittedQueryPlan.Impact -> stage.outputSyntax()
        is AdmittedQueryPlan.Symbols -> stage.outputSyntax()
        is AdmittedQueryPlan.Text -> stage.outputSyntax()
        is AdmittedQueryPlan.Location -> stage.outputSyntax()
        is AdmittedQueryPlan.ExactReferences -> stage.outputSyntax()
        is AdmittedQueryPlan.Retained -> stage.outputSyntax()
    }

internal fun AdmittedQueryPlan.bindingMode(): QueryJoinMode.Inner {
    var mode = ((this as? AdmittedQueryPlan.Retained)?.source as? QueryRetainedResult.Bindings)?.mode
    var stage =
        when (this) {
            is AdmittedQueryPlan.Impact -> stage
            is AdmittedQueryPlan.Symbols -> stage
            is AdmittedQueryPlan.Text -> stage
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
                is ExactQueryStage.Trace -> stage.next
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
        is ExactQueryStage.Trace -> next.outputSyntax()
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

internal fun AdmittedQueryPlan.exceedsTraversalDepth(
    ceiling: io.github.amichne.kast.traversal.contract.TraversalExtent
): Boolean =
    when (this) {
        is AdmittedQueryPlan.Impact -> false
        is AdmittedQueryPlan.Symbols -> stage.exceedsTraversalDepth(ceiling)
        is AdmittedQueryPlan.Text -> stage.exceedsTraversalDepth(ceiling)
        is AdmittedQueryPlan.Location -> stage.exceedsTraversalDepth(ceiling)
        is AdmittedQueryPlan.ExactReferences -> stage.exceedsTraversalDepth(ceiling)
        is AdmittedQueryPlan.Retained -> stage.exceedsTraversalDepth(ceiling)
    }

private fun ExactQueryStage.exceedsTraversalDepth(
    ceiling: io.github.amichne.kast.traversal.contract.TraversalExtent
): Boolean =
    when (this) {
        is ExactQueryStage.Trace -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.ProjectBinding -> next.exceedsTraversalDepth(ceiling)
        is ExactQueryStage.Walk ->
            when (ceiling) {
                io.github.amichne.kast.traversal.contract.TraversalExtent.Exhaustive -> false
                is io.github.amichne.kast.traversal.contract.TraversalExtent.ThroughDepth ->
                    extent.exceeds(ceiling.maximumDepth)
            } || next.exceedsTraversalDepth(ceiling)
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
        is ExactQueryStage.Trace -> next.retainedInputs()
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

private fun AdmittedQueryPlan.retainedInputBytes(graph: QueryImpactRetainedGraph): Long =
    retainedInputs().fold((this as? AdmittedQueryPlan.Impact)?.source?.let(graph::source) ?: 0L) { size, result ->
        saturatedAdd(size, result.retainedBytes)
    }

internal fun discoveryDeclarationKinds(plan: AdmittedQueryPlan): QueryDeclarationKinds? =
    when (plan) {
        is AdmittedQueryPlan.Symbols -> plan.source.declarationKinds
        is AdmittedQueryPlan.Text -> plan.source.declarationKinds
        is AdmittedQueryPlan.Impact -> null
        is AdmittedQueryPlan.Location -> null
        is AdmittedQueryPlan.ExactReferences,
        is AdmittedQueryPlan.Retained -> null
    }

private const val SOURCE_CURSOR_CHARACTER_BYTES = 8L
