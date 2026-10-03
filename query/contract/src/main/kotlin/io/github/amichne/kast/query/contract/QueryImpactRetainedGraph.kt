package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueSite
import java.util.IdentityHashMap

/**
 * Request-local storage arithmetic, not semantic identity or a retained evidence store. Every incoming reference is
 * charged. Immutable objects and their payload are charged once by JVM identity; equal detached copies remain distinct.
 * The epoch authority stays strongly retained; its externally owned model graph is not copied here.
 */
class QueryImpactRetainedGraph {
    private val visited = IdentityHashMap<Any, Unit>()

    internal fun node(value: Any, fields: () -> Long): Long =
        REFERENCE_STORAGE_BYTES.saturatedAdd(
            if (visited.put(value, Unit) == null) RETAINED_STATE_BASE_BYTES.saturatedAdd(fields()) else 0L
        )

    internal fun text(value: String): Long = node(value) { value.utf8UpperBound() }

    internal fun <T> collection(values: Collection<T>, element: (T) -> Long): Long =
        node(values) { values.fold(0L) { bytes, value -> bytes.saturatedAdd(element(value)) } }

    fun path(value: QueryImpactPath): Long = value.storageBytes(this).scaledStorage()

    fun source(value: QueryImpactSource): Long = value.storageBytes(this).scaledStorage()

    fun ledger(value: QueryImpactLedger): Long = value.storageBytes(this).scaledStorage()

    fun route(producer: ValueSite, steps: List<QueryImpactStep>, representation: QueryImpactRepresentation): Long =
        producer
            .storageBytes(this)
            .saturatedAdd(collection(steps) { it.storageBytes(this) })
            .saturatedAdd(representation.storageBytes(this))
            .scaledStorage()

    fun site(value: ValueSite): Long = value.storageBytes(this).scaledStorage()

    fun read(value: ValueFlowRead): Long =
        node(value) {
                when (value) {
                    is ValueFlowRead.Observed -> value.step.storageBytes(this)
                    is ValueFlowRead.Rejected,
                    is ValueFlowRead.ContractRejected -> 0L
                }
            }
            .scaledStorage()

    fun step(value: QueryImpactStep): Long = value.storageBytes(this).scaledStorage()

    fun representation(value: QueryImpactRepresentation): Long = value.storageBytes(this).scaledStorage()

    fun representationModel(value: RepresentationRule): Long = value.storageBytes(this).scaledStorage()

    fun boundaryModel(value: BoundaryModel): Long = value.storageBytes(this).scaledStorage()
}

/** Retains the existing conservative expansion factor; ownership changes no quota or allocation bound. */
private fun Long.scaledStorage(): Long = saturatedMultiply(DETACHED_EVIDENCE_STORAGE_MULTIPLIER)

private const val DETACHED_EVIDENCE_STORAGE_MULTIPLIER = 8L
private const val REFERENCE_STORAGE_BYTES = 8L
