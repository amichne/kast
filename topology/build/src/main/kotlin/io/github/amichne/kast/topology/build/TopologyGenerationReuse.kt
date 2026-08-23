package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.PublishedWorkspace

internal enum class TopologyGenerationReuseFailure {
    WORKSPACE_ROOT_MISMATCH,
    CANDIDATE_IDENTITY_MISMATCH,
    SYMBOL_REBIND_REJECTED,
    EDGE_REBIND_REJECTED,
    FILE_COMPLETION_REJECTED,
    GENERATION_REJECTED,
}

internal sealed interface TopologyGenerationReuse {
    data class Rebound(
        val generation: CompleteTopologyGeneration,
    ) : TopologyGenerationReuse

    data object SourceChanged : TopologyGenerationReuse

    data class Rejected(
        val failure: TopologyGenerationReuseFailure,
    ) : TopologyGenerationReuse
}

/**
 * Proof transition: `(PublishedWorkspace, TopologyCandidateSet, TopologySnapshotContent) ->
 * TopologyGenerationReuse`.
 *
 * Rebound establishes that every current candidate has byte- and source-root-identical terminal
 * facts in the prior admitted snapshot, with all symbols and edges rebound to the current lease.
 * SourceChanged closes ordinary staleness. [TopologyGenerationReuseFailure] closes malformed
 * proof transitions. Raw compiler facts are never accepted by this transition.
 */
internal fun rebindUnchangedTopologyGeneration(
    workspace: PublishedWorkspace,
    candidates: TopologyCandidateSet,
    prior: TopologySnapshotContent,
): TopologyGenerationReuse {
    val currentIdentity = TopologyWorkspaceIdentity.from(workspace)
    if (prior.snapshot.identity.lease.workspaceRoot != currentIdentity.lease.workspaceRoot) {
        return rejected(TopologyGenerationReuseFailure.WORKSPACE_ROOT_MISMATCH)
    }
    if (candidates.workspace != currentIdentity) {
        return rejected(TopologyGenerationReuseFailure.CANDIDATE_IDENTITY_MISMATCH)
    }
    val currentFiles = candidates.files.associateBy { it.path }
    val priorFiles = prior.files.associateBy { it.file.path }
    if (currentFiles.keys != priorFiles.keys) return TopologyGenerationReuse.SourceChanged
    if (currentFiles.any { (path, current) ->
            current.canonicalProjection() != priorFiles.getValue(path).file.canonicalProjection()
        }
    ) {
        return TopologyGenerationReuse.SourceChanged
    }
    val symbols = mutableMapOf<CompilerSymbolIdentity, TopologySymbol>()
    for (symbol in prior.symbols) {
        val currentFile = currentFiles.getValue(symbol.file.path)
        val rebound = when (val admitted = TopologySymbol.admit(currentFile, symbol.evidence)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return rejected(TopologyGenerationReuseFailure.SYMBOL_REBIND_REJECTED)
        }
        symbols[rebound.evidence.compilerIdentity] = rebound
    }
    val edges = mutableListOf<TopologyEdge>()
    for (edge in prior.edges) {
        val source = symbols[edge.source.evidence.compilerIdentity]
                     ?: return rejected(TopologyGenerationReuseFailure.EDGE_REBIND_REJECTED)
        val target = symbols[edge.target.evidence.compilerIdentity]
                     ?: return rejected(TopologyGenerationReuseFailure.EDGE_REBIND_REJECTED)
        when (val rebound = TopologyEdge.fromBoundary(
            edge.kind,
            source,
            target,
            edge.occurrence.startInclusive,
            edge.occurrence.endExclusive,
        )) {
            is Refinement.Refined -> edges += rebound.value
            is Refinement.Rejected ->
                return rejected(TopologyGenerationReuseFailure.EDGE_REBIND_REJECTED)
        }
    }
    val completed = mutableListOf<CompleteTopologyFile>()
    for (file in candidates.files) {
        val completion = when (val admitted = CompleteTopologyFile.admit(
            file,
            symbols.values.filter { it.file == file }.sorted(),
            edges.filter { it.source.file == file }.sorted(),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return rejected(TopologyGenerationReuseFailure.FILE_COMPLETION_REJECTED)
        }
        completed += completion
    }
    return when (val generation = CompleteTopologyGeneration.admit(
        workspace,
        candidates.files,
        completed,
    )) {
        is Refinement.Refined -> TopologyGenerationReuse.Rebound(generation.value)
        is Refinement.Rejected ->
            rejected(TopologyGenerationReuseFailure.GENERATION_REJECTED)
    }
}

private fun rejected(
    failure: TopologyGenerationReuseFailure,
): TopologyGenerationReuse.Rejected = TopologyGenerationReuse.Rejected(failure)
