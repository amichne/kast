package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.protocol.*
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.traversal.contract.*

internal class CanonicalRelationReadHandler(
    operations: RelationOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<RelationReadRequest, RelationReadResult, RelationReadQualification, RelationReadRejection> {
    private val protocol = CanonicalRelationReadProtocol(operations, authority)

    override suspend fun execute(
        request: RelationReadRequest
    ): OperationOutcome<RelationReadResult, RelationReadQualification, RelationReadRejection> {
        val lookup = authority.exact(request.exactSelector)
        val current =
            when (lookup) {
                is ExactSelectorLookup.Found -> lookup.selector.lease
                is ExactSelectorLookup.Rejected ->
                    return OperationOutcome.Rejected(
                        if (lookup.reason == SelectorLookupRejection.WRONG_KIND)
                            RelationReadRejection.SELECTOR_WRONG_KIND
                        else RelationReadRejection.SELECTOR_MALFORMED
                    )
            }
        val budget =
            when (val admitted = relationBudget(request.limit.value)) {
                is RelationBudgetAdmission.Admitted -> admitted.budget
                RelationBudgetAdmission.Rejected ->
                    return OperationOutcome.Rejected(RelationReadRejection.RELATION_UNSUPPORTED)
            }
        return protocol.execute(request, current, budget)
    }
}

internal class CanonicalTraversalRunHandler(
    operations: TraversalOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<TraversalRunRequest, TraversalRunResult, TraversalRunQualification, TraversalRunRejection> {
    private val protocol = CanonicalTraversalRunProtocol(operations, authority)

    override suspend fun execute(
        request: TraversalRunRequest
    ): OperationOutcome<TraversalRunResult, TraversalRunQualification, TraversalRunRejection> {
        val lookup = authority.exact(request.exactSelector)
        val current =
            when (lookup) {
                is ExactSelectorLookup.Found -> lookup.selector.lease
                is ExactSelectorLookup.Rejected ->
                    return OperationOutcome.Rejected(
                        if (lookup.reason == SelectorLookupRejection.WRONG_KIND)
                            TraversalRunRejection.SELECTOR_WRONG_KIND
                        else TraversalRunRejection.SELECTOR_MALFORMED
                    )
            }
        val budget =
            when (val admitted = traversalBudget(request.maximumDepth.value, request.maximumResults.value)) {
                is TraversalBudgetAdmission.Admitted -> admitted.budget
                TraversalBudgetAdmission.Rejected ->
                    return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            }
        return protocol.execute(request, current, budget)
    }
}

private const val SEMANTIC_WORK_MULTIPLIER = 100L
private const val SEMANTIC_TIME_MILLIS = 30_000L
private const val SEMANTIC_TRAVERSAL_HOP_TIME_MILLIS = 1_000L
private const val SEMANTIC_RETURNED_BYTES = 1_048_576L

private sealed interface RelationBudgetAdmission {
    data class Admitted(val budget: RelationBudget) : RelationBudgetAdmission

    data object Rejected : RelationBudgetAdmission
}

private sealed interface TraversalBudgetAdmission {
    data class Admitted(val budget: TraversalBudget) : TraversalBudgetAdmission

    data object Rejected : TraversalBudgetAdmission
}

private sealed interface BudgetValue<out Value> {
    data class Refined<Value>(val value: Value) : BudgetValue<Value>

    data object Rejected : BudgetValue<Nothing>
}

/**
 * Proof transition: `(Int, Long) -> RelationBudgetAdmission`.
 *
 * Admitted establishes positive record, work, elapsed, and byte bounds. Rejected closes every refinement failure. Raw
 * count extraction is confined to this protocol boundary.
 */
private fun relationBudget(
    rawLimit: Int,
    rawElapsedMillis: Long = SEMANTIC_TIME_MILLIS,
): RelationBudgetAdmission {
    val results =
        when (val value = ResultLimit.parse(rawLimit).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return RelationBudgetAdmission.Rejected
        }
    val work =
        when (val value = WorkUnitLimit.parse(rawLimit * SEMANTIC_WORK_MULTIPLIER).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return RelationBudgetAdmission.Rejected
        }
    val elapsed =
        when (val value = ElapsedTimeLimitMillis.parse(rawElapsedMillis).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return RelationBudgetAdmission.Rejected
        }
    val bytes =
        when (val value = RelationByteLimit.parse(SEMANTIC_RETURNED_BYTES).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return RelationBudgetAdmission.Rejected
        }
    return RelationBudgetAdmission.Admitted(RelationBudget(ResourceBudget(results, work, elapsed), bytes))
}

/**
 * Proof transition: `(Int, Int) -> TraversalBudgetAdmission`.
 *
 * Admitted establishes aggregate and one-hop bounds where no one-hop authority exceeds its traversal bound. Rejected
 * closes every numeric refinement failure. Raw counts remain here.
 */
private fun traversalBudget(
    rawDepth: Int,
    rawResults: Int,
): TraversalBudgetAdmission {
    val relation =
        when (val admitted = relationBudget(rawResults, SEMANTIC_TRAVERSAL_HOP_TIME_MILLIS)) {
            is RelationBudgetAdmission.Admitted -> admitted.budget
            RelationBudgetAdmission.Rejected -> return TraversalBudgetAdmission.Rejected
        }
    val records =
        when (val value = ResultLimit.parse(rawResults).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
        }
    val bytes =
        when (val value = TraversalByteLimit.parse(SEMANTIC_RETURNED_BYTES).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
        }
    val work =
        when (val value = WorkUnitLimit.parse(rawResults * SEMANTIC_WORK_MULTIPLIER).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
        }
    val elapsed =
        when (val value = ElapsedTimeLimitMillis.parse(SEMANTIC_TIME_MILLIS).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
        }
    val depth =
        when (val value = TraversalDepthLimit.parse(rawDepth).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
        }
    val frontier =
        when (val value = TraversalFrontierLimit.parse(rawResults).budgetValue()) {
            is BudgetValue.Refined -> value.value
            BudgetValue.Rejected -> return TraversalBudgetAdmission.Rejected
        }
    return TraversalBudgetAdmission.Admitted(TraversalBudget(records, bytes, work, elapsed, depth, frontier, relation))
}

/**
 * Proof transition: `Refinement<Value, Failure> -> BudgetValue<Value>`.
 *
 * Preserves a refined numeric capability or closes every expected parser failure as [BudgetValue.Rejected]. Raw numeric
 * extraction remains in the enclosing budget admission.
 */
private fun <Value, Failure> Refinement<Value, Failure>.budgetValue(): BudgetValue<Value> =
    when (this) {
        is Refinement.Refined -> BudgetValue.Refined(value)
        is Refinement.Rejected -> BudgetValue.Rejected
    }
