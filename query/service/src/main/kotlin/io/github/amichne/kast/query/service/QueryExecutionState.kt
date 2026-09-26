package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryWalkCoverage
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalQualification

/** Mutable accounting is request-local; semantic authority remains in the typed request. */
internal class QueryExecutionState(
    val request: QueryExecutionRequest,
    private val clock: QueryNanoClock,
) {
    private val startedAt = clock.now()
    private var usedWork = 0L
    private var usedBytes = 0L
    private val failures = mutableListOf<QueryItemFailure>()
    val limitations = linkedSetOf<QueryLimitation>()
    val upstreamLimitations = linkedSetOf<QueryLimitation>()
    var contractViolation: Boolean = false

    fun inheritRetainedLimitations(result: QueryRetainedResult) {
        val inherited = (result.coverage as? QueryCoverage.Qualified)?.limitations.orEmpty()
        val failures =
            result.failures.map { failure ->
                when (failure) {
                    is QueryItemFailure.Refinement,
                    is QueryItemFailure.ExactReference -> QueryLimitation.REFINEMENT_INCOMPLETE
                    is QueryItemFailure.Visibility,
                    is QueryItemFailure.PredicateUnproven -> QueryLimitation.VISIBILITY_INCOMPLETE
                    is QueryItemFailure.Source -> QueryLimitation.SOURCE_INCOMPLETE
                    is QueryItemFailure.Relation -> QueryLimitation.RELATION_INCOMPLETE
                    is QueryItemFailure.Walk -> QueryLimitation.TRAVERSAL_INCOMPLETE
                }
            }
        val omissions = if (result.omissions.isEmpty()) emptyList() else listOf(QueryLimitation.RELATION_INCOMPLETE)
        val walks =
            if (result.walkObservations.any { it.coverage.isTerminallyIncomplete() })
                listOf(QueryLimitation.TRAVERSAL_INCOMPLETE)
            else emptyList()
        limitations += inherited + failures + omissions + walks
        upstreamLimitations += inherited + failures + omissions + walks
    }

    fun canContinue(workRequired: Boolean): Boolean {
        if (workRequired && remainingWork() < 1L) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return false
        }
        return !workRequired || observeTime()
    }

    fun consume(work: Long) {
        usedWork = saturatedAdd(usedWork, work)
        // Intermediate bytes bound child effects; only final output spends returned-byte authority.
        if (usedWork > request.budget.resources.workUnitLimit.value) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
        }
    }

    fun consumeUnit(): Boolean {
        if (remainingWork() < 1L) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return false
        }
        if (remainingMillis() < 1L) {
            limit(QueryLimitation.TIME_LIMIT_REACHED)
            return false
        }
        usedWork += 1L
        return true
    }

    /** Transfers one visibility unit together with the remaining elapsed authority. */
    fun sourceResources(): Refinement<ResourceBudget, QueryLimitation> {
        val millis = remainingMillis()
        if (remainingWork() < 1L) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return Refinement.Rejected(QueryLimitation.WORK_LIMIT_REACHED)
        }
        if (millis < 1L) {
            limit(QueryLimitation.TIME_LIMIT_REACHED)
            return Refinement.Rejected(QueryLimitation.TIME_LIMIT_REACHED)
        }
        usedWork += 1L
        return Refinement.Refined(
            ResourceBudget(
                ResultLimit.parse(1).refined(),
                WorkUnitLimit.parse(1).refined(),
                ElapsedTimeLimitMillis.parse(millis).refined(),
            )
        )
    }

    /**
     * Result authority is shared by sibling child calls in one stage. A stage may transform an existing stream without
     * spending the previous stage's cardinality again.
     */
    fun remainingResultCapacity(alreadyProduced: Int): Int? {
        val remaining = request.budget.resources.resultLimit.value - alreadyProduced
        if (remaining > 0) return remaining
        limit(QueryLimitation.RESULT_LIMIT_REACHED)
        return null
    }

    fun discoveryBudget(resultCapacity: Int): SymbolDiscoveryBudget? {
        val resources = remainingResources(resultCapacity) ?: return null
        val bytes = childByteAllowance() ?: return null
        return SymbolDiscoveryBudget(resources, SymbolDiscoveryByteLimit.parse(bytes).refined())
    }

    fun relationBudget(resultCapacity: Int): RelationBudget? {
        val resources = remainingResources(resultCapacity) ?: return null
        val bytes = childByteAllowance() ?: return null
        return RelationBudget(resources, RelationByteLimit.parse(bytes).refined())
    }

    fun traversalBudget(
        resultCapacity: Int,
        requestedDepth: TraversalDepthLimit,
        ceiling: TraversalBudget,
    ): TraversalBudget? {
        val resources = remainingResources(minOf(resultCapacity, ceiling.records.value)) ?: return null
        val bytes = childByteAllowance() ?: return null
        val records = resources.resultLimit
        val returnedBytes = TraversalByteLimit.parse(minOf(bytes, ceiling.returnedBytes.value)).refined()
        val work = WorkUnitLimit.parse(minOf(resources.workUnitLimit.value, ceiling.workUnits.value)).refined()
        val time =
            ElapsedTimeLimitMillis.parse(minOf(resources.elapsedTimeLimit.value, ceiling.elapsedTime.value)).refined()
        val oneHopResources =
            ResourceBudget(
                ResultLimit.parse(minOf(records.value, ceiling.oneHop.resources.resultLimit.value)).refined(),
                WorkUnitLimit.parse(minOf(work.value, ceiling.oneHop.resources.workUnitLimit.value)).refined(),
                ElapsedTimeLimitMillis.parse(minOf(time.value, ceiling.oneHop.resources.elapsedTimeLimit.value))
                    .refined(),
            )
        val oneHop =
            RelationBudget(
                oneHopResources,
                RelationByteLimit.parse(minOf(returnedBytes.value, ceiling.oneHop.returnedBytes.value)).refined(),
            )
        return TraversalBudget(
            records = records,
            returnedBytes = returnedBytes,
            workUnits = work,
            elapsedTime = time,
            depth = requestedDepth,
            frontier = ceiling.frontier,
            oneHop = oneHop,
        )
    }

    fun traversalLimited(qualification: TraversalQualification) {
        if (
            qualification is TraversalQualification.TerminalIncomplete ||
                TraversalLimitation.ONE_HOP_INCOMPLETE in qualification.limitations
        ) {
            upstreamLimit(QueryLimitation.TRAVERSAL_INCOMPLETE)
        }
    }

    /** Child coverage proves incomplete discovery; aggregate result capacity remains query-owned. */
    fun discoveryLimited(qualifications: Set<SymbolDiscoveryQualification>) {
        upstreamLimit(QueryLimitation.DISCOVERY_INCOMPLETE)
        qualifications.forEach { qualification ->
            when (qualification) {
                SymbolDiscoveryQualification.BYTE_LIMIT_REACHED -> upstreamLimit(QueryLimitation.BYTE_LIMIT_REACHED)
                SymbolDiscoveryQualification.WORK_LIMIT_REACHED -> upstreamLimit(QueryLimitation.WORK_LIMIT_REACHED)
                SymbolDiscoveryQualification.TIME_LIMIT_REACHED -> upstreamLimit(QueryLimitation.TIME_LIMIT_REACHED)
                SymbolDiscoveryQualification.RESULT_LIMIT_REACHED,
                SymbolDiscoveryQualification.DUMB_MODE_TRANSITION,
                SymbolDiscoveryQualification.PROVIDER_FAILURE,
                SymbolDiscoveryQualification.UNSCOPED_PROVIDER,
                SymbolDiscoveryQualification.UNSUPPORTED_ITEM,
                SymbolDiscoveryQualification.EXACT_DEFINITION_UNAVAILABLE -> Unit
            }
        }
    }

    /** A resumable page's local bounds are not aggregate limitations if Kast can continue it. */
    fun relationPageLimited(limitations: Set<RelationLimitation>) {
        if (limitations.any { it !in recoverableRelationPageLimits }) {
            limit(QueryLimitation.RELATION_INCOMPLETE)
        }
    }

    fun relationTerminallyLimited(limitations: Set<RelationLimitation>) {
        upstreamLimit(QueryLimitation.RELATION_INCOMPLETE)
        limitations.forEach { limitation ->
            when (limitation) {
                RelationLimitation.RESULT_LIMIT_REACHED -> upstreamLimit(QueryLimitation.RESULT_LIMIT_REACHED)
                RelationLimitation.BYTE_LIMIT_REACHED -> upstreamLimit(QueryLimitation.BYTE_LIMIT_REACHED)
                RelationLimitation.WORK_LIMIT_REACHED -> upstreamLimit(QueryLimitation.WORK_LIMIT_REACHED)
                RelationLimitation.TIME_LIMIT_REACHED -> upstreamLimit(QueryLimitation.TIME_LIMIT_REACHED)
                RelationLimitation.DUMB_MODE_TRANSITION,
                RelationLimitation.UNRESOLVED_TARGET,
                RelationLimitation.UNSUPPORTED_ITEM,
                RelationLimitation.PROVIDER_FAILURE,
                RelationLimitation.PROVIDER_INCOMPLETE,
                RelationLimitation.PROVIDER_STALLED -> Unit
            }
        }
    }

    fun boundConnections(values: List<RelationFact>): List<RelationFact> = values.distinct().sorted()

    fun failure(failure: QueryItemFailure) {
        failures += failure
    }

    fun drainFailures(): List<QueryItemFailure> = failures.toList().also { failures.clear() }

    fun observeTime(): Boolean {
        if (remainingMillis() >= 1L) return true
        limit(QueryLimitation.TIME_LIMIT_REACHED)
        return false
    }

    private fun upstreamLimit(limitation: QueryLimitation) {
        upstreamLimitations += limitation
        limit(limitation)
    }

    fun limit(limitation: QueryLimitation) {
        limitations += limitation
    }

    private fun remainingResources(resultCapacity: Int): ResourceBudget? {
        if (resultCapacity < 1) {
            limit(QueryLimitation.RESULT_LIMIT_REACHED)
            return null
        }
        val work = remainingWork()
        val millis = remainingMillis()
        if (work < 1L) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return null
        }
        if (millis < 1L) {
            limit(QueryLimitation.TIME_LIMIT_REACHED)
            return null
        }
        return ResourceBudget(
            ResultLimit.parse(resultCapacity).refined(),
            WorkUnitLimit.parse(work).refined(),
            ElapsedTimeLimitMillis.parse(millis).refined(),
        )
    }

    private fun remainingWork(): Long = (request.budget.resources.workUnitLimit.value - usedWork).coerceAtLeast(0L)

    private fun remainingBytes(): Long? {
        val remaining = (request.budget.returnedBytes.value - usedBytes).coerceAtLeast(0L)
        if (remaining < 1L) {
            limit(QueryLimitation.BYTE_LIMIT_REACHED)
            return null
        }
        return remaining
    }

    /** Child materialization has its own bounded bytes; it never spends final-output authority. */
    private fun childByteAllowance(): Long? {
        remainingBytes() ?: return null
        return request.budget.returnedBytes.value
    }

    fun consumeOutput(bytes: Long): Boolean {
        val remaining = remainingBytes() ?: return false
        if (bytes > remaining) {
            limit(QueryLimitation.BYTE_LIMIT_REACHED)
            return false
        }
        usedBytes = saturatedAdd(usedBytes, bytes)
        return true
    }

    private fun remainingMillis(): Long {
        val elapsedNanos = (clock.now() - startedAt).coerceAtLeast(0L)
        val elapsedMillis = elapsedNanos / 1_000_000L
        return (request.budget.resources.elapsedTimeLimit.value - elapsedMillis).coerceAtLeast(0L)
    }
}

internal val recoverableRelationPageLimits =
    setOf(
        RelationLimitation.RESULT_LIMIT_REACHED,
        RelationLimitation.BYTE_LIMIT_REACHED,
        RelationLimitation.WORK_LIMIT_REACHED,
        RelationLimitation.TIME_LIMIT_REACHED,
    )

private fun QueryWalkCoverage.isTerminallyIncomplete(): Boolean =
    this is QueryWalkCoverage.TerminalIncomplete ||
        (this is QueryWalkCoverage.Resumable && TraversalLimitation.ONE_HOP_INCOMPLETE in limitations)

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Internally derived query value violated its invariant: $failure")
    }

internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
