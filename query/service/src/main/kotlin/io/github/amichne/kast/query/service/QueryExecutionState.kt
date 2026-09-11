package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification

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
    var contractViolation: Boolean = false

    fun consume(work: Long, bytes: Long) {
        usedWork = saturatedAdd(usedWork, work)
        usedBytes = saturatedAdd(usedBytes, bytes)
        if (usedWork > request.budget.resources.workUnitLimit.value) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
        }
        if (usedBytes > request.budget.returnedBytes.value) {
            limit(QueryLimitation.BYTE_LIMIT_REACHED)
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

    /** Child coverage proves incomplete discovery; aggregate result capacity remains query-owned. */
    fun discoveryLimited(qualifications: Set<SymbolDiscoveryQualification>) {
        limit(QueryLimitation.DISCOVERY_INCOMPLETE)
        qualifications.forEach { qualification ->
            when (qualification) {
                SymbolDiscoveryQualification.BYTE_LIMIT_REACHED -> limit(QueryLimitation.BYTE_LIMIT_REACHED)
                SymbolDiscoveryQualification.WORK_LIMIT_REACHED -> limit(QueryLimitation.WORK_LIMIT_REACHED)
                SymbolDiscoveryQualification.TIME_LIMIT_REACHED -> limit(QueryLimitation.TIME_LIMIT_REACHED)
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
        limit(QueryLimitation.RELATION_INCOMPLETE)
        limitations.forEach { limitation ->
            when (limitation) {
                RelationLimitation.RESULT_LIMIT_REACHED -> limit(QueryLimitation.RESULT_LIMIT_REACHED)
                RelationLimitation.BYTE_LIMIT_REACHED -> limit(QueryLimitation.BYTE_LIMIT_REACHED)
                RelationLimitation.WORK_LIMIT_REACHED -> limit(QueryLimitation.WORK_LIMIT_REACHED)
                RelationLimitation.TIME_LIMIT_REACHED -> limit(QueryLimitation.TIME_LIMIT_REACHED)
                RelationLimitation.DUMB_MODE_TRANSITION,
                RelationLimitation.UNRESOLVED_TARGET,
                RelationLimitation.UNSUPPORTED_ITEM,
                RelationLimitation.PROVIDER_FAILURE,
                RelationLimitation.PROVIDER_INCOMPLETE -> Unit
            }
        }
    }

    fun <Value> boundResults(values: List<Value>): List<Value> {
        val limit = request.budget.resources.resultLimit.value
        if (values.size > limit) this.limit(QueryLimitation.RESULT_LIMIT_REACHED)
        return values.take(limit)
    }

    fun boundConnections(values: List<RelationFact>): List<RelationFact> = boundResults(values.distinct().sorted())

    fun <Value> boundOutput(
        values: List<Value>,
        projectedUtf8Size: (Value) -> Long,
    ): List<Value> = buildList {
        for (value in values) {
            val bytes = projectedUtf8Size(value)
            if (!consumeOutput(bytes)) break
            add(value)
        }
    }

    fun boundedFailures(): List<QueryItemFailure> = boundOutput(failures.toList(), QueryItemFailure::projectedUtf8Size)

    fun failure(failure: QueryItemFailure) {
        if (failures.size >= request.budget.resources.resultLimit.value) {
            limit(QueryLimitation.RESULT_LIMIT_REACHED)
        } else {
            failures += failure
        }
    }

    fun observeTime(): Boolean {
        if (remainingMillis() >= 1L) return true
        limit(QueryLimitation.TIME_LIMIT_REACHED)
        return false
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

    /** Leaves at least half of remaining byte authority available for downstream projection. */
    private fun childByteAllowance(): Long? {
        val remaining = remainingBytes() ?: return null
        return (remaining / 2L).coerceAtLeast(1L)
    }

    private fun consumeOutput(bytes: Long): Boolean {
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

    private companion object {
        val recoverableRelationPageLimits =
            setOf(
                RelationLimitation.RESULT_LIMIT_REACHED,
                RelationLimitation.BYTE_LIMIT_REACHED,
                RelationLimitation.WORK_LIMIT_REACHED,
                RelationLimitation.TIME_LIMIT_REACHED,
            )
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Internally derived query value violated its invariant: $failure")
    }

internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
