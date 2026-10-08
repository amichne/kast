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
) {
    private val startedAt = clock.now()
    private var usedWork = 0L

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
        val discoveryWork = remainingWork() / 2L
        if (discoveryWork < 1L) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return null
        }
        val candidateCapacity = discoveryWork.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val resources = remainingResources(candidateCapacity) ?: return null
        val bytes = childByteAllowance() ?: return null
        return SymbolDiscoveryBudget(
            resources.copy(workUnitLimit = WorkUnitLimit.parse(discoveryWork).refined()),
            SymbolDiscoveryByteLimit.parse(bytes).refined(),
        )
    }

    /** Native flow grants are separate from page output and bounded by remaining checkpoint storage. */
    fun impactBudget(checkpointRemaining: Long, itemBytes: Long): RelationBudget? {
        if (!canContinue(true)) return null
        val capacity =
            minOf(remainingWork(), checkpointRemaining / itemBytes.coerceAtLeast(1L), Int.MAX_VALUE.toLong()).toInt()
        val resources = remainingResources(capacity) ?: return null
        if (checkpointRemaining < 1L) return null
        return RelationBudget(resources, RelationByteLimit.parse(checkpointRemaining).refined())
    }

    fun relationBudget(resultCapacity: Int): RelationBudget? {
        val resources = remainingResources(resultCapacity) ?: return null
        val bytes = childByteAllowance() ?: return null
        return RelationBudget(resources, RelationByteLimit.parse(bytes).refined())
    }

    fun traversalBudget(
        resultCapacity: Int,
        requestedExtent: io.github.amichne.kast.traversal.contract.TraversalExtent,
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
            extent = requestedExtent,
            frontier = ceiling.frontier,
            oneHop = oneHop,
        )
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
                RelationLimitation.CANDIDATE_LIMIT_REACHED -> upstreamLimit(QueryLimitation.WORK_LIMIT_REACHED)
                RelationLimitation.RETENTION_LIMIT_REACHED,
                RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE,
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
        RelationLimitation.CANDIDATE_LIMIT_REACHED,
    )

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Internally derived query value violated its invariant: $failure")
    }

internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
