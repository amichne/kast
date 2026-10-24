package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalQualification

/** Mutable accounting is request-local; semantic authority remains in the typed request. */
internal class QueryExecutionState(
    val request: QueryExecutionRequest,
    private val clock: QueryNanoClock,
    branchObservation: io.github.amichne.kast.query.contract.QueryBranchObservation =
        io.github.amichne.kast.query.contract.QueryBranchObservation.None,
) {
    private val startedAt = clock.now()
    private var usedWork = 0L
    val branches = QueryBranchObserver(this, clock, branchObservation)

    fun consumedWork(): io.github.amichne.kast.query.contract.QueryWorkCount =
        io.github.amichne.kast.query.contract.QueryWorkCount.parse(usedWork).refined()

    private var usedBytes = 0L
    private val failures = mutableListOf<QueryItemFailure>()
    val limitations = linkedSetOf<QueryLimitation>()
    val upstreamLimitations = linkedSetOf<QueryLimitation>()
    var contractViolation: Boolean = false

    fun inheritRetainedLimitations(result: QueryRetainedResult) {
        val inherited = retainedQueryLimitations(result)
        limitations += inherited
        upstreamLimitations += inherited
    }

    fun canProcessImpact(): Boolean = observeTime()

    fun canContinue(workRequired: Boolean): Boolean {
        if (workRequired && !request.budget.resources.hasWorkCapacity(usedWork)) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return false
        }
        return !workRequired || observeTime()
    }

    fun consume(work: Long) {
        usedWork = saturatedAdd(usedWork, work)
        // Intermediate bytes bound child effects; only final output spends returned-byte authority.
        if (!request.budget.resources.permitsWork(usedWork)) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
        }
    }

    fun consumeUnit(): Boolean {
        if (!request.budget.resources.hasWorkCapacity(usedWork)) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return false
        }
        if (!request.budget.resources.hasTimeRemaining(elapsedMillis())) {
            limit(QueryLimitation.TIME_LIMIT_REACHED)
            return false
        }
        usedWork = saturatedAdd(usedWork, 1L)
        return true
    }

    /** Transfers one visibility unit together with the remaining elapsed authority. */
    fun sourceResources(): Refinement<ResourceBudget, QueryLimitation> {
        val elapsed = elapsedMillis()
        if (!request.budget.resources.hasWorkCapacity(usedWork)) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return Refinement.Rejected(QueryLimitation.WORK_LIMIT_REACHED)
        }
        if (!request.budget.resources.hasTimeRemaining(elapsed)) {
            limit(QueryLimitation.TIME_LIMIT_REACHED)
            return Refinement.Rejected(QueryLimitation.TIME_LIMIT_REACHED)
        }
        usedWork = saturatedAdd(usedWork, 1L)
        return when (val resources = request.budget.resources) {
            ResourceBudget.RunnerControlled -> Refinement.Refined(resources)
            is ResourceBudget.Bounded ->
                Refinement.Refined(
                    ResourceBudget(
                        ResultLimit.parse(1).refined(),
                        WorkUnitLimit.parse(1).refined(),
                        ElapsedTimeLimitMillis.parse(resources.elapsedTimeLimit.value - elapsed).refined(),
                    )
                )
        }
    }

    /**
     * Result authority is shared by sibling child calls in one stage. A stage may transform an existing stream without
     * spending the previous stage's cardinality again.
     */
    fun remainingResultCapacity(alreadyProduced: Int): Int? {
        val remaining = request.budget.outputResults.value - alreadyProduced
        if (remaining > 0) return remaining
        limit(QueryLimitation.RESULT_LIMIT_REACHED)
        return null
    }

    fun discoveryBudget(resultCapacity: Int): SymbolDiscoveryBudget? {
        val resources = remainingResources(resultCapacity) ?: return null
        val bytes = childByteAllowance() ?: return null
        return SymbolDiscoveryBudget(
            resources,
            SymbolDiscoveryByteLimit.parse(bytes).refined(),
            ResultLimit.parse(resultCapacity).refined(),
        )
    }

    fun discoveryBudget(match: QueryMatch, resultCapacity: Int): SymbolDiscoveryBudget? =
        when (match) {
            QueryMatch.All -> discoveryBudget(resultCapacity)
            is QueryMatch.Name ->
                when (match.policy) {
                    SymbolDiscoveryMatch.EXACT_NAME -> indexedDiscoveryBudget()
                    SymbolDiscoveryMatch.FUZZY -> discoveryBudget(resultCapacity)
                }
        }

    /**
     * Reserve one exact-refinement unit per admitted candidate. Exact-name and indexed-word discovery receive half the
     * remaining work, so candidate capacity is independent of the presentation limit and cannot consume the refinement
     * grant. The existing interpreter retains admitted candidates when final output fills a page.
     */
    fun indexedDiscoveryBudget(): SymbolDiscoveryBudget? {
        return when (val grant = request.budget.resources) {
            ResourceBudget.RunnerControlled -> discoveryBudget(request.budget.outputResults.value)
            is ResourceBudget.Bounded -> boundedIndexedDiscoveryBudget(grant)
        }
    }

    private fun boundedIndexedDiscoveryBudget(grant: ResourceBudget.Bounded): SymbolDiscoveryBudget? {
        val discoveryWork = (grant.workUnitLimit.value - usedWork) / 2L
        if (discoveryWork < 1L) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return null
        }
        val candidateCapacity = discoveryWork.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val resources = remainingResources(candidateCapacity)
        val bytes = childByteAllowance() ?: return null
        return when (resources) {
            is ResourceBudget.Bounded ->
                SymbolDiscoveryBudget(
                    resources.copy(workUnitLimit = WorkUnitLimit.parse(discoveryWork).refined()),
                    SymbolDiscoveryByteLimit.parse(bytes).refined(),
                )
            ResourceBudget.RunnerControlled,
            null -> null
        }
    }

    /** Native flow grants are separate from page output and bounded by remaining checkpoint storage. */
    fun impactBudget(checkpointRemaining: Long, itemBytes: Long): RelationBudget? {
        if (!canContinue(true)) return null
        val capacity =
            when (val grant = request.budget.resources) {
                is ResourceBudget.Bounded ->
                    minOf(
                            grant.workUnitLimit.value - usedWork,
                            checkpointRemaining / itemBytes.coerceAtLeast(1L),
                            Int.MAX_VALUE.toLong(),
                        )
                        .toInt()
                ResourceBudget.RunnerControlled -> request.budget.outputResults.value
            }
        val resources = remainingResources(capacity) ?: return null
        val nativeBytes =
            when (resources) {
                ResourceBudget.RunnerControlled -> request.budget.returnedBytes.value
                is ResourceBudget.Bounded -> checkpointRemaining
            }
        if (nativeBytes < 1L) return null
        return RelationBudget(
            resources,
            RelationByteLimit.parse(nativeBytes).refined(),
            ResultLimit.parse(capacity).refined(),
        )
    }

    fun relationBudget(resultCapacity: Int): RelationBudget? {
        val resources = remainingResources(resultCapacity) ?: return null
        val bytes = childByteAllowance() ?: return null
        return RelationBudget(
            resources,
            RelationByteLimit.parse(bytes).refined(),
            ResultLimit.parse(resultCapacity).refined(),
        )
    }

    fun traversalBudget(
        resultCapacity: Int,
        requestedExtent: io.github.amichne.kast.traversal.contract.TraversalExtent,
        ceiling: TraversalBudget,
    ): TraversalBudget? {
        val capacity = minOf(resultCapacity, ceiling.records.value)
        val resources = remainingResources(capacity) ?: return null
        val bytes = childByteAllowance() ?: return null
        val records = ResultLimit.parse(capacity).refined()
        val returnedBytes = TraversalByteLimit.parse(minOf(bytes, ceiling.returnedBytes.value)).refined()
        return queryTraversalBudget(resources, records, returnedBytes, requestedExtent, ceiling)
    }

    fun traversalLimited(qualification: TraversalQualification) {
        when (qualification) {
            is TraversalQualification.TerminalIncomplete -> upstreamLimit(QueryLimitation.TRAVERSAL_INCOMPLETE)
            is TraversalQualification.Resumable -> {
                // The typed checkpoint distinguishes retained page work from proven permanent omissions.
                if (qualification.continuation.checkpoint.terminalRelationLimitations.isNotEmpty()) {
                    upstreamLimit(QueryLimitation.TRAVERSAL_INCOMPLETE)
                }
            }
        }
    }

    /** A retained producer remainder discharges page bounds, while permanent omissions survive successors. */
    fun discoveryPageLimited(qualifications: Set<SymbolDiscoveryQualification>) {
        val permanent =
            qualifications -
                setOf(
                    SymbolDiscoveryQualification.RESULT_LIMIT_REACHED,
                    SymbolDiscoveryQualification.BYTE_LIMIT_REACHED,
                    SymbolDiscoveryQualification.WORK_LIMIT_REACHED,
                    SymbolDiscoveryQualification.TIME_LIMIT_REACHED,
                )
        if (permanent.isNotEmpty()) upstreamLimit(QueryLimitation.DISCOVERY_INCOMPLETE)
    }

    /** Child coverage proves incomplete discovery; aggregate result capacity remains query-owned. */
    fun discoveryLimited(qualifications: Set<SymbolDiscoveryQualification>) =
        permanentDiscoveryLimitations(qualifications).forEach(::upstreamLimit)

    /** A resumable page's local bounds are not aggregate limitations if Kast can continue it. */
    fun relationPageLimited(limitations: Set<RelationLimitation>) {
        if (limitations.any { it !in recoverableRelationPageLimits }) {
            limit(QueryLimitation.RELATION_INCOMPLETE)
        }
    }

    fun relationTerminallyLimited(limitations: Set<RelationLimitation>) =
        permanentRelationLimitations(limitations).forEach(::upstreamLimit)

    fun boundConnections(values: List<RelationFact>): List<RelationFact> = values.distinct().sorted()

    fun failure(failure: QueryItemFailure) {
        failures += failure
    }

    fun drainFailures(): List<QueryItemFailure> = failures.toList().also { failures.clear() }

    fun observeTime(): Boolean {
        if (request.budget.resources.hasTimeRemaining(elapsedMillis())) return true
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
        return when (val remaining = request.budget.resources.remainingAfter(usedWork, elapsedMillis())) {
            is Refinement.Refined ->
                when (val resources = remaining.value) {
                    ResourceBudget.RunnerControlled -> resources
                    is ResourceBudget.Bounded ->
                        resources.copy(resultLimit = ResultLimit.parse(resultCapacity).refined())
                }
            is Refinement.Rejected -> {
                limit(
                    if (request.budget.resources.hasWorkCapacity(usedWork)) QueryLimitation.TIME_LIMIT_REACHED
                    else QueryLimitation.WORK_LIMIT_REACHED
                )
                null
            }
        }
    }

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

    private fun elapsedMillis(): Long {
        val elapsedNanos = (clock.now() - startedAt).coerceAtLeast(0L)
        val elapsedMillis = elapsedNanos / 1_000_000L
        return elapsedMillis
    }
}

internal val recoverableRelationPageLimits =
    setOf(
        RelationLimitation.RESULT_LIMIT_REACHED,
        RelationLimitation.BYTE_LIMIT_REACHED,
        RelationLimitation.WORK_LIMIT_REACHED,
        RelationLimitation.TIME_LIMIT_REACHED,
        RelationLimitation.CANDIDATE_LIMIT_REACHED,
    )

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Internally derived query value violated its invariant: $failure")
    }

internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
