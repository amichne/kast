package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.CandidateQueryStage
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryCandidate
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

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

    data class Discover(val syntax: QueryDiscoverySyntax, val next: CandidateQueryStage) : PipelineTask

    data class Revalidate(val selector: SymbolSelector, val next: ExactQueryStage) : PipelineTask

    data class Candidate(val value: SymbolDiscoverySelection, val stage: CandidateQueryStage) : PipelineTask

    data class Symbol(val value: QuerySymbol, val stage: ExactQueryStage) : PipelineTask

    data class Related(val value: QuerySymbol, val stage: ExactQueryStage.Related, val cursor: RelationContinuation?) :
        PipelineTask
}

internal data class PipelineCheckpoint(
    override val plan: AdmittedQueryPlan,
    override val lease: SemanticReadAuthority,
    val tasks: List<PipelineTask>,
    val candidateDistinct: Map<CandidateQueryStage.Distinct, Set<SymbolDiscoveryCandidate>>,
    val symbolDistinct: Map<ExactQueryStage.Distinct, Set<CanonicalSymbolId>>,
    val limitations: Set<QueryLimitation>,
) : QueryCheckpoint {
    override val retainedBytes: Long =
        (initialTasks(plan) + tasks).sumOf { task ->
            512L +
                when (task) {
                    is PipelineTask.Failure -> task.value.projectedUtf8Size() * 4L
                    is PipelineTask.Symbol -> task.value.projectedUtf8Size() * 4L
                    is PipelineTask.Related -> task.value.projectedUtf8Size() * 4L + 4096L
                    is PipelineTask.Candidate -> QueryCandidate(task.value).projectedUtf8Size() * 4L
                    is PipelineTask.Revalidate ->
                        QuerySymbol(SymbolDescription.from(task.selector), emptyList()).projectedUtf8Size() * 4L
                    is PipelineTask.Discover -> 4096L
                }
        } +
            candidateDistinct.values.sumOf { values -> values.sumOf { it.projectedUtf8Size().value * 4L + 256L } } +
            symbolDistinct.values.sumOf { it.size.toLong() * 512L }
}

internal fun initialTasks(plan: AdmittedQueryPlan): List<PipelineTask> =
    when (plan) {
        is AdmittedQueryPlan.Candidates -> listOf(PipelineTask.Discover(plan.source, plan.stage))
        is AdmittedQueryPlan.Symbols ->
            listOf(
                PipelineTask.Discover(
                    plan.source,
                    CandidateQueryStage.Inspect(ExactQueryStage.Distinct(plan.stage)),
                )
            )
        is AdmittedQueryPlan.CandidateReferences -> plan.source.values.map { PipelineTask.Candidate(it, plan.stage) }
        is AdmittedQueryPlan.ExactReferences -> plan.source.values.map { PipelineTask.Revalidate(it, plan.stage) }
    }

internal fun discoverySyntax(plan: AdmittedQueryPlan): QueryDiscoverySyntax? =
    when (plan) {
        is AdmittedQueryPlan.Candidates -> plan.source
        is AdmittedQueryPlan.Symbols -> plan.source
        is AdmittedQueryPlan.CandidateReferences,
        is AdmittedQueryPlan.ExactReferences -> null
    }

internal fun isCandidateOutput(plan: AdmittedQueryPlan): Boolean {
    fun candidate(stage: CandidateQueryStage): Boolean =
        when (stage) {
            is CandidateQueryStage.Emit -> true
            is CandidateQueryStage.Distinct -> candidate(stage.next)
            is CandidateQueryStage.Inspect -> false
        }
    return when (plan) {
        is AdmittedQueryPlan.Candidates -> candidate(plan.stage)
        is AdmittedQueryPlan.CandidateReferences -> candidate(plan.stage)
        is AdmittedQueryPlan.Symbols,
        is AdmittedQueryPlan.ExactReferences -> false
    }
}
