package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.LocalBindingReadRemainder
import io.github.amichne.kast.relation.contract.RelationProviderRetainedGraph
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowWorkReceipt
import io.github.amichne.kast.relation.contract.ValueSite
import java.util.IdentityHashMap

/**
 * Request-local storage arithmetic, not semantic identity or a retained evidence store. Every incoming reference is
 * charged. Immutable objects and their payload are charged once by JVM identity; equal detached copies remain distinct.
 * The epoch authority stays strongly retained; its externally owned model graph is not copied here.
 */
class QueryImpactRetainedGraph private constructor(private val parent: QueryImpactRetainedGraph?) {
    constructor() : this(null)

    private val providers: RelationProviderRetainedGraph = RelationProviderRetainedGraph(parent?.providers)

    fun providerState(value: RelationProviderState): Long = value.retainedBytes(providers)

    fun checkpointTask(value: QueryCheckpointStorageOwner): Long = node(value) { value.retainedBytes(this) }

    /** Stage newly charged identities without changing accepted request-local accounting. */
    fun transaction(): Transaction = Transaction(this)

    class Transaction internal constructor(private val owner: QueryImpactRetainedGraph) {
        val graph = QueryImpactRetainedGraph(owner)

        fun commit() {
            owner.visited.putAll(graph.visited)
            graph.providers.commitToParent()
        }
    }

    private fun contains(value: Any): Boolean = visited.containsKey(value) || parent?.contains(value) == true

    private val visited = IdentityHashMap<Any, Unit>()

    internal fun node(value: Any, fields: () -> Long): Long =
        REFERENCE_STORAGE_BYTES.saturatedAdd(
            if (contains(value)) 0L
            else {
                visited[value] = Unit
                RETAINED_STATE_BASE_BYTES.saturatedAdd(fields())
            }
        )

    internal fun text(value: String): Long = node(value) { value.utf8UpperBound() }

    internal fun <T> collection(values: Collection<T>, element: (T) -> Long): Long =
        node(values) { values.fold(0L) { bytes, value -> bytes.saturatedAdd(element(value)) } }

    fun requestedSite(value: QueryImpactRequestedSite): Long = value.storageBytes(this).scaledStorage()

    fun peerSiteAdmission(value: QueryImpactPeerSiteAdmission): Long = value.storageBytes(this).scaledStorage()

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
                    is ValueFlowRead.Suspended ->
                        value.step.storageBytes(this).saturatedAdd(remainderStorage(value.remainder))
                    is ValueFlowRead.Rejected,
                    is ValueFlowRead.ContractRejected -> 0L
                }
            }
            .scaledStorage()

    fun remainder(value: LocalBindingReadRemainder): Long = remainderStorage(value).scaledStorage()

    private fun remainderStorage(value: LocalBindingReadRemainder): Long =
        node(value) {
            value.source
                .storageBytes(this)
                .saturatedAdd(value.boundary.storageBytes(this))
                .saturatedAdd(
                    collection(value.consumed) { key ->
                        node(key) {
                            node(key.element) { 0L }
                                .saturatedAdd(node(key.reference) { 0L })
                                .saturatedAdd(text(key.kind.value))
                        }
                    }
                )
                .saturatedAdd(collection(value.emitted) { it.storageBytes(this) })
        }

    fun receipts(values: List<ValueFlowWorkReceipt>): Long = receiptStorage(values).scaledStorage()

    internal fun receiptStorage(values: List<ValueFlowWorkReceipt>): Long =
        collection(values) { node(it) { it.domain.storageBytes(this) } }

    fun step(value: QueryImpactStep): Long = value.storageBytes(this).scaledStorage()

    fun representation(value: QueryImpactRepresentation): Long = value.storageBytes(this).scaledStorage()

    fun representationModel(value: RepresentationRule): Long = value.storageBytes(this).scaledStorage()

    fun boundaryModel(value: BoundaryModel): Long = value.storageBytes(this).scaledStorage()
}

/** Retains the existing conservative expansion factor; ownership changes no quota or allocation bound. */
private fun Long.scaledStorage(): Long = saturatedMultiply(DETACHED_EVIDENCE_STORAGE_MULTIPLIER)

private const val DETACHED_EVIDENCE_STORAGE_MULTIPLIER = 8L
private const val REFERENCE_STORAGE_BYTES = 8L
