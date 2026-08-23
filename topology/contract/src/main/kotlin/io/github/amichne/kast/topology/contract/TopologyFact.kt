package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import java.nio.file.Path

enum class TopologySymbolFailure {
    FILE_MISMATCH,
}

/** One detached K2-grounded declaration owned by an admitted topology file. */
@ConsistentCopyVisibility
data class TopologySymbol private constructor(
    val file: TopologySourceFile,
    val evidence: CompilerGroundedSymbolEvidence,
) : Comparable<TopologySymbol> {
    override fun compareTo(other: TopologySymbol): Int = SYMBOL_ORDER.compare(this, other)

    fun canonicalProjection(): String = buildString {
        appendTopologyField(file.path.value)
        appendTopologyField(evidence.range.startInclusive.toString())
        appendTopologyField(evidence.range.endExclusive.toString())
        appendTopologyField(evidence.name.value)
        appendTopologyField(evidence.qualifiedIdentity.canonicalName())
        appendTopologyField(evidence.kind.name)
        appendTopologyField(evidence.compilerIdentity.value)
    }

    companion object {
        /**
         * Proof transition: `(TopologySourceFile, CompilerGroundedSymbolEvidence) ->
         * Refinement<TopologySymbol, TopologySymbolFailure>`.
         *
         * Establishes that detached K2 evidence names the exact admitted workspace file.
         * [TopologySymbolFailure] is the closed expected failure. Compiler evidence may enter only
         * from the topology K2 extraction adapter.
         */
        fun admit(
            file: TopologySourceFile,
            evidence: CompilerGroundedSymbolEvidence,
        ): Refinement<TopologySymbol, TopologySymbolFailure> {
            val expected = Path.of(file.workspace.lease.workspaceRoot.value)
                .resolve(file.path.value)
                .normalize()
                .toString()
            val observed = (evidence.file as? SymbolDiscoveryFileIdentity.Workspace)?.path?.value
            return if (observed == expected) {
                Refinement.Refined(TopologySymbol(file, evidence))
            } else {
                Refinement.Rejected(TopologySymbolFailure.FILE_MISMATCH)
            }
        }

        private val SYMBOL_ORDER = compareBy<TopologySymbol>(
            { it.evidence.compilerIdentity.value },
            { it.file.path.value },
            { it.evidence.range.startInclusive },
            { it.evidence.range.endExclusive },
        )
    }
}

enum class TopologyEdgeKind {
    REFERENCE,
    CALL,
    TYPE_USE,
    INHERITANCE,
    OVERRIDE,
}

enum class TopologyEdgeFailure {
    WORKSPACE_MISMATCH,
    SOURCE_FILE_MISMATCH,
    INVALID_OCCURRENCE,
}

/** One compiler-confirmed directed edge whose occurrence belongs to its source declaration file. */
@ConsistentCopyVisibility
data class TopologyEdge private constructor(
    val kind: TopologyEdgeKind,
    val source: TopologySymbol,
    val target: TopologySymbol,
    val occurrence: ExactDeclarationTextRange,
) : Comparable<TopologyEdge> {
    override fun compareTo(other: TopologyEdge): Int = EDGE_ORDER.compare(this, other)

    fun canonicalProjection(): String = buildString {
        appendTopologyField(kind.name)
        appendTopologyField(source.evidence.compilerIdentity.value)
        appendTopologyField(target.evidence.compilerIdentity.value)
        appendTopologyField(source.file.path.value)
        appendTopologyField(occurrence.startInclusive.toString())
        appendTopologyField(occurrence.endExclusive.toString())
    }

    companion object {
        /**
         * Proof transition: `(TopologyEdgeKind, TopologySymbol, TopologySymbol, Int, Int) ->
         * Refinement<TopologyEdge, TopologyEdgeFailure>`.
         *
         * Establishes common workspace identity, a source occurrence in the source symbol's
         * admitted file, and one non-empty source range. [TopologyEdgeFailure] is the closed
         * expected failure. Raw occurrence offsets may enter only from the topology K2 adapter.
         */
        fun fromBoundary(
            kind: TopologyEdgeKind,
            source: TopologySymbol,
            target: TopologySymbol,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
        ): Refinement<TopologyEdge, TopologyEdgeFailure> {
            if (source.file.workspace != target.file.workspace) {
                return Refinement.Rejected(TopologyEdgeFailure.WORKSPACE_MISMATCH)
            }
            val sourceEvidenceFile = source.evidence.file.stableValue
            val expectedFile = Path.of(source.file.workspace.lease.workspaceRoot.value)
                .resolve(source.file.path.value)
                .normalize()
                .toString()
            if (sourceEvidenceFile != expectedFile) {
                return Refinement.Rejected(TopologyEdgeFailure.SOURCE_FILE_MISMATCH)
            }
            val range = when (
                val admitted = ExactDeclarationTextRange.parse(
                    rawStartInclusive,
                    rawEndExclusive,
                )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    TopologyEdgeFailure.INVALID_OCCURRENCE,
                )
            }
            return Refinement.Refined(TopologyEdge(kind, source, target, range))
        }

        private val EDGE_ORDER = compareBy<TopologyEdge>(
            { it.kind.ordinal },
            { it.source.evidence.compilerIdentity.value },
            { it.target.evidence.compilerIdentity.value },
            { it.source.file.path.value },
            { it.occurrence.startInclusive },
            { it.occurrence.endExclusive },
        )
    }
}

private fun io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.canonicalName():
    String = when (this) {
    is io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Available -> value
    io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Unavailable -> ""
}
