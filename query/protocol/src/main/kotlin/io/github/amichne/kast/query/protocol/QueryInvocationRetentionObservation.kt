package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.relation.contract.RelationProviderRetentionLedger

enum class QueryInvocationRetentionStage {
    BEFORE_EXECUTION,
    BEFORE_FACTS,
    FACTS_ACCEPTED,
    FACTS_REJECTED,
}

sealed interface QueryInvocationRetentionCapacity {
    data class Available(val bytes: QueryRetentionByteCount) : QueryInvocationRetentionCapacity

    data object Exhausted : QueryInvocationRetentionCapacity
}

/** Bounded conservative owner charges, never physical heap or cumulative semantic work. */
class QueryInvocationRetentionAdmission(
    val stage: QueryInvocationRetentionStage,
    val allowance: QueryByteLimit,
    val facts: QueryRetentionByteCount,
    val issuedState: QueryRetentionByteCount,
    val request: QueryRetentionByteCount,
) {
    val total: QueryRetentionByteCount =
        QueryRetentionByteCount.measured(facts.value.saturatedAdd(issuedState.value).saturatedAdd(request.value))
    val capacity: QueryInvocationRetentionCapacity =
        if (total.value > allowance.value) QueryInvocationRetentionCapacity.Exhausted
        else QueryInvocationRetentionCapacity.Available(QueryRetentionByteCount.measured(allowance.value - total.value))
}

/** Explicit observation boundary; no source, selectors, tokens or authority payloads are recorded. */
fun interface QueryInvocationRetentionObservation {
    fun observe(admission: QueryInvocationRetentionAdmission)

    /** Inventory charges are already inside semantic bytes; this snapshot is never added to admission again. */
    fun observeFacts(
        stage: QueryInvocationRetentionStage,
        estimate: QueryInvocationFactsRetentionEstimate,
        inventoryOwners: RelationProviderRetentionLedger,
    ) {}

    companion object {
        val None = QueryInvocationRetentionObservation {}
    }
}
