package io.github.amichne.kast.topology.service

import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotContentReader

/**
 * Proof transition: `TopologySnapshotContentReader -> TopologyGraphOperations`.
 *
 * Establishes a read-only graph capability that constructs algorithms only from fully re-admitted
 * detached snapshot content. No compiler, filesystem, Gradle, module-model, or live workspace
 * capability is accepted by this service boundary.
 */
internal fun topologyGraphOperations(reader: TopologySnapshotContentReader): TopologyGraphOperations =
    TopologyGraphService(reader)

private class TopologyGraphService(
    private val reader: TopologySnapshotContentReader,
) : TopologyGraphOperations {
    override fun open(snapshot: PublishedTopologySnapshot): TopologyGraphOpen =
        when (val content = reader.read(snapshot)) {
            is TopologySnapshotContentRead.Loaded ->
                TopologyGraphOpen.Opened(SnapshotTopologyGraph(snapshot, GraphIndex(content.content)))
            is TopologySnapshotContentRead.Rejected -> TopologyGraphOpen.Rejected(content.failure)
        }
}

private class SnapshotTopologyGraph(
    override val snapshot: PublishedTopologySnapshot,
    private val index: GraphIndex,
) : TopologyGraph {
    override fun traverse(start: CompilerSymbolIdentity): TopologyGraphTraversal =
        index.traverse(start)

    override fun reachability(
        source: CompilerSymbolIdentity,
        target: CompilerSymbolIdentity,
    ): TopologyReachability = index.reachability(source, target)

    override fun cycles(): List<TopologyCycle> = index.cycles()

    override fun stronglyConnectedComponents(): List<TopologyStrongComponent> =
        index.stronglyConnectedComponents()

    override fun condensation(): TopologyCondensation = index.condensation()

    override fun quotient(level: TopologyQuotientLevel): TopologyQuotientGraph =
        index.quotient(level)

    override fun canonicalProjection(): String = index.canonicalProjection()
}
