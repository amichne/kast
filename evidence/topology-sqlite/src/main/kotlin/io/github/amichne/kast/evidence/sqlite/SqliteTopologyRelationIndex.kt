package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologyNodeIdentity
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySymbol

/** Exact-location adjacency over one re-admitted immutable snapshot; no live lookup or physical reads. */
internal class SqliteTopologyRelationIndex(content: TopologySnapshotContent) {
    // Preserve every location sharing a compiler identity; the caller still revalidates exact endpoint evidence.
    private val symbolsByCompilerIdentity = content.symbols.groupBy { it.evidence.compilerIdentity }
    private val callsBySource =
        content.edges.filter { it.kind == TopologyEdgeKind.CALL }.groupBy { it.source.nodeIdentity }
    private val edgesByKindAndTarget =
        content.edges.groupBy(TopologyEdge::kind).mapValues { (_, edges) ->
            edges.groupBy { it.target.nodeIdentity }
        }

    fun candidates(identity: CompilerSymbolIdentity): List<TopologySymbol> =
        symbolsByCompilerIdentity.getOrElse(identity, ::emptyList)

    fun edges(meaning: RelationMeaning, subject: TopologyNodeIdentity): List<TopologyEdge> =
        when (meaning) {
            RelationMeaning.Callees -> callsBySource.getOrElse(subject, ::emptyList)
            RelationMeaning.Callers -> incoming(TopologyEdgeKind.CALL, subject)
            RelationMeaning.References -> incoming(TopologyEdgeKind.REFERENCE, subject)
            RelationMeaning.TypeUses -> incoming(TopologyEdgeKind.TYPE_USE, subject)
            RelationMeaning.Implementations,
            RelationMeaning.Inheritors -> incoming(TopologyEdgeKind.INHERITANCE, subject)
            RelationMeaning.Overrides -> incoming(TopologyEdgeKind.OVERRIDE, subject)
        }

    private fun incoming(kind: TopologyEdgeKind, subject: TopologyNodeIdentity): List<TopologyEdge> =
        edgesByKindAndTarget[kind]?.get(subject).orEmpty()
}
