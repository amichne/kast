package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector

internal sealed interface QueryRelationStageResult {
    data object NotStarted : QueryRelationStageResult

    data object ContractRejected : QueryRelationStageResult

    data class Read(
        val symbols: List<QuerySymbol>,
        val continuation: RelationContinuation?,
        val omissions: List<QueryRelationOmission>,
        val occurrences: List<QueryOccurrence> = emptyList(),
        val referenceObservations: List<io.github.amichne.kast.relation.contract.RelationReferenceOccurrence> =
            emptyList(),
        val observation: io.github.amichne.kast.query.contract.QueryRelationObservation? = null,
    ) : QueryRelationStageResult
}

internal fun QueryRelationStageResult.Read.nextTasks(task: PipelineTask.Related): List<PipelineTask> =
    symbols.map { PipelineTask.Symbol(it, task.stage.next) } +
        occurrences.map(PipelineTask::Occurrence) +
        referenceObservations.map(PipelineTask::ReferenceObservation) +
        omissions.map(PipelineTask::Omission) +
        observation?.evidenceUnits().orEmpty().map(PipelineTask::RelationObservation) +
        listOfNotNull(continuation?.let { task.copy(cursor = it) })

/** One bounded query hop delegates semantic discovery and cursor validation to the relation domain. */
internal class QueryRelationStage(private val relations: RelationOperations) {
    suspend fun read(task: PipelineTask.Related, state: QueryExecutionState): QueryRelationStageResult {
        val childBudget =
            state.relationBudget(state.request.budget.resources.resultLimit.value)
                ?: return QueryRelationStageResult.NotStarted
        val child =
            when (val admitted = task.relationRequest(childBudget)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return QueryRelationStageResult.ContractRejected
            }
        val result = relations.read(child)
        val cursor = observeCoverage(result, task, state)
        val omissions =
            when (val admitted = result.queryOmissions(task.value.selector, task.stage.meaning)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return QueryRelationStageResult.ContractRejected
            }
        preserveMeasuredPageOmissions(result, omissions, state)
        val facts =
            when (result) {
                is RelationReadResult.Complete -> result.batch.facts
                is RelationReadResult.Qualified -> result.batch.facts
                is RelationReadResult.Rejected -> emptyList()
            }
        val references =
            when (result) {
                is RelationReadResult.Complete -> result.batch.referenceOccurrences
                is RelationReadResult.Qualified -> result.batch.referenceOccurrences
                is RelationReadResult.Rejected -> emptyList()
            }
        val emit = task.stage.next as? ExactQueryStage.Emit
        val independentOccurrences = emit?.output == QueryOutputSyntax.Occurrences && references.isNotEmpty()
        val symbols =
            if (independentOccurrences) emptyList() else facts.map { it.toQuerySymbol(task.value.connections, state) }
        state.observeTime()
        return QueryRelationStageResult.Read(
            symbols,
            cursor,
            omissions,
            if (independentOccurrences) references.map(QueryOccurrence::Reference) else emptyList(),
            if (independentOccurrences) emptyList() else references,
            when (result) {
                is RelationReadResult.Complete ->
                    io.github.amichne.kast.query.contract.QueryRelationObservation.from(result)
                is RelationReadResult.Qualified ->
                    io.github.amichne.kast.query.contract.QueryRelationObservation.from(result)
                is RelationReadResult.Rejected -> null
            },
        )
    }

    private fun preserveMeasuredPageOmissions(
        result: RelationReadResult,
        omissions: List<QueryRelationOmission>,
        state: QueryExecutionState,
    ) {
        if (
            result is RelationReadResult.Qualified &&
                result.coverage is RelationIncompleteCoverage.Resumable &&
                omissions.any { it.evidence.reason in recoverableRelationPageLimits }
        ) {
            state.limit(QueryLimitation.RELATION_INCOMPLETE)
        }
    }

    private fun observeCoverage(
        result: RelationReadResult,
        task: PipelineTask.Related,
        state: QueryExecutionState,
    ): RelationContinuation? =
        when (result) {
            is RelationReadResult.Complete -> {
                state.consume(result.batch.examinedWorkUnits.value.coerceAtLeast(1L))
                null
            }
            is RelationReadResult.Qualified -> {
                state.consume(result.batch.examinedWorkUnits.value.coerceAtLeast(1L))
                when (val coverage = result.coverage) {
                    is RelationIncompleteCoverage.Resumable -> {
                        state.relationPageLimited(coverage.limitations)
                        coverage.continuation
                    }
                    is RelationIncompleteCoverage.TerminalIncomplete -> {
                        state.relationTerminallyLimited(coverage.limitations)
                        null
                    }
                }
            }
            is RelationReadResult.Rejected -> {
                state.failure(QueryItemFailure.Relation(task.value.selector, task.stage.meaning, result.reason))
                state.limit(QueryLimitation.RELATION_INCOMPLETE)
                null
            }
        }
}

private fun PipelineTask.Related.relationRequest(
    budget: io.github.amichne.kast.relation.contract.RelationBudget
): Refinement<RelationRequest, io.github.amichne.kast.relation.contract.RelationResumeFailure> =
    if (cursor == null)
        Refinement.Refined(RelationRequest.start(value.selector, stage.meaning, budget, stage.expansion))
    else
        RelationRequest.resume(
            value.selector,
            stage.meaning,
            budget,
            cursor,
            stage.expansion,
        )

/** Page limits clear after continuation; permanent omitted evidence and terminal limits stay query-visible. */
private fun RelationReadResult.queryOmissions(
    subject: SymbolSelector,
    meaning: RelationMeaning,
): Refinement<List<QueryRelationOmission>, QueryRelationStageOmissionFailure> {
    val qualified =
        when (this) {
            is RelationReadResult.Qualified -> this
            is RelationReadResult.Complete ->
                return if (batch.omissions.isEmpty()) Refinement.Refined(emptyList())
                else Refinement.Rejected(QueryRelationStageOmissionFailure.INCONSISTENT_COVERAGE)
            is RelationReadResult.Rejected -> return Refinement.Refined(emptyList())
        }
    if (qualified.batch.omissions.any { it.reason !in qualified.coverage.limitations }) {
        return Refinement.Rejected(QueryRelationStageOmissionFailure.INCONSISTENT_COVERAGE)
    }
    val recorded = qualified.batch.omissions.associateBy(RelationOmissionEvidence::reason)
    val reasons =
        when (val coverage = qualified.coverage) {
            is RelationIncompleteCoverage.Resumable ->
                coverage.limitations.filter { reason -> recorded[reason].isPersistentOmission(reason) }
            is RelationIncompleteCoverage.TerminalIncomplete -> coverage.limitations.toList()
        }
    val omissions = mutableListOf<QueryRelationOmission>()
    for (reason in reasons) {
        val evidence =
            recorded[reason]
                ?: RelationOmissionEvidence.unmeasured(
                    qualified.batch.request.providerCursor.provider,
                    reason,
                )
        when (val admitted = QueryRelationOmission.create(subject, meaning, evidence)) {
            is Refinement.Refined -> omissions += admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(QueryRelationStageOmissionFailure.PROVIDER_MISMATCH)
        }
    }
    return Refinement.Refined(omissions)
}

private fun RelationOmissionEvidence?.isPersistentOmission(reason: RelationLimitation): Boolean =
    reason !in recoverableRelationPageLimits ||
        (this != null && (measurement is RelationOmissionMeasurement.ObservedOnPage || samples.locations.isNotEmpty()))

private enum class QueryRelationStageOmissionFailure {
    INCONSISTENT_COVERAGE,
    PROVIDER_MISMATCH,
}
