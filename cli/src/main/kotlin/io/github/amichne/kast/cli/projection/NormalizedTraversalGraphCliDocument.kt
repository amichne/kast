package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
internal value class TraversalGraphNodeIndex(
    val value: Int,
)

@Serializable
@JvmInline
internal value class TraversalGraphProofIndex(
    val value: Int,
)

@Serializable
internal data class NormalizedTraversalGraphCliDocument(
    val snapshot: TraversalGraphSnapshotCliDocument,
    val nodes: List<NormalizedTraversalNodeCliDocument>,
    val edges: List<NormalizedTraversalEdgeCliDocument>,
    val proofs: List<NormalizedTraversalProofCliDocument>,
)

@Serializable
internal data class TraversalGraphSnapshotCliDocument(
    val canonicalRoot: String,
    val generation: Long,
)

@Serializable
internal data class NormalizedTraversalNodeCliDocument(
    val id: TraversalGraphNodeIndex,
    val selector: String,
    val kind: String,
    val name: String,
    val qualifiedIdentity: String?,
    val file: String,
    val range: SourceRangeCliDocument,
    val proof: TraversalGraphProofIndex,
)

@Serializable
internal data class NormalizedTraversalEdgeCliDocument(
    val depth: Int,
    val meaning: String,
    val source: TraversalGraphNodeIndex,
    val target: TraversalGraphNodeIndex,
    val occurrence: NormalizedTraversalOccurrenceCliDocument,
    val provenance: String,
    val coverage: String,
)

@Serializable
internal data class NormalizedTraversalOccurrenceCliDocument(
    val candidateSelector: String,
    val file: String,
    val range: SourceRangeCliDocument,
)

@Serializable
internal data class NormalizedTraversalProofCliDocument(
    val id: TraversalGraphProofIndex,
    val identity: String,
)

/**
 * Pure boundary normalization from repeated traversal records to compact node, edge, and proof
 * tables. Full compiler signatures remain behind `symbol_inspect`; this graph retains their exact
 * stable identities without repeating evidence on every edge.
 */
internal fun normalizeTraversalGraph(
    snapshotRoot: ProtocolText,
    generation: EvidenceGeneration,
    records: List<TraversalRecordDocument>,
): NormalizedTraversalGraphCliDocument {
    val nodes = mutableListOf<NormalizedTraversalNodeCliDocument>()
    val edges = mutableListOf<NormalizedTraversalEdgeCliDocument>()
    val proofs = mutableListOf<NormalizedTraversalProofCliDocument>()
    val nodeIndices = linkedMapOf<SymbolDocument, TraversalGraphNodeIndex>()
    val proofIndices = linkedMapOf<CompilerSymbolEvidenceDocument, TraversalGraphProofIndex>()

    fun proofIndex(evidence: CompilerSymbolEvidenceDocument): TraversalGraphProofIndex =
        proofIndices.getOrPut(evidence) {
            TraversalGraphProofIndex(proofs.size).also { index ->
                proofs += NormalizedTraversalProofCliDocument(index, evidence.identity.value)
            }
        }

    fun nodeIndex(symbol: SymbolDocument): TraversalGraphNodeIndex =
        nodeIndices.getOrPut(symbol) {
            val projected = symbol.toCliDocument()
            TraversalGraphNodeIndex(nodes.size).also { index ->
                nodes += NormalizedTraversalNodeCliDocument(
                    id = index,
                    selector = projected.selector,
                    kind = projected.kind,
                    name = projected.name,
                    qualifiedIdentity = projected.qualifiedIdentity,
                    file = projected.file,
                    range = projected.range,
                    proof = proofIndex(symbol.compilerEvidence),
                )
            }
        }

    records.forEach { record ->
        edges += record.relation.toNormalizedEdge(
            depth = record.depth.value,
            source = nodeIndex(record.relation.source),
            target = nodeIndex(record.relation.target),
        )
    }
    return NormalizedTraversalGraphCliDocument(
        snapshot = TraversalGraphSnapshotCliDocument(
            canonicalRoot = snapshotRoot.value,
            generation = generation.value,
        ),
        nodes = nodes.toList(),
        edges = edges.toList(),
        proofs = proofs.toList(),
    )
}

private fun RelationFactDocument.toNormalizedEdge(
    depth: Int,
    source: TraversalGraphNodeIndex,
    target: TraversalGraphNodeIndex,
): NormalizedTraversalEdgeCliDocument = NormalizedTraversalEdgeCliDocument(
    depth = depth,
    meaning = meaning.cliName(),
    source = source,
    target = target,
    occurrence = NormalizedTraversalOccurrenceCliDocument(
        candidateSelector = occurrence.candidateSelector.value,
        file = occurrence.file.value,
        range = SourceRangeCliDocument(
            occurrence.range.startInclusive.value,
            occurrence.range.endExclusive.value,
        ),
    ),
    provenance = provenance.cliName(),
    coverage = coverage.cliName(),
)
