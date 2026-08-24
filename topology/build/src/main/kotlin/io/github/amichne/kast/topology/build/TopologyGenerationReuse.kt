package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.PublishedWorkspace

internal sealed interface TopologyGenerationReuse {
    data class Rebound(
        val generation: CompleteTopologyGeneration,
    ) : TopologyGenerationReuse

    data object SourceChanged : TopologyGenerationReuse

    data object Rejected : TopologyGenerationReuse
}

/**
 * Proof transition: `(PublishedWorkspace, TopologyCandidateSet, TopologySnapshotContent) ->
 * TopologyGenerationReuse`.
 *
 * Rebound establishes that every current candidate has byte- and source-root-identical terminal
 * facts in the prior admitted snapshot, with all symbols and edges rebound to the current lease.
 * SourceChanged closes ordinary staleness. [TopologyGenerationReuse.Rejected] closes a malformed
 * proof transition without manufacturing detail that the public snapshot contract cannot consume.
 * Raw compiler facts are never accepted by this transition.
 */
internal fun rebindUnchangedTopologyGeneration(
    workspace: PublishedWorkspace,
    candidates: TopologyCandidateSet,
    prior: TopologySnapshotContent,
): TopologyGenerationReuse {
    val currentIdentity = TopologyWorkspaceIdentity.from(workspace)
    if (prior.snapshot.identity.lease.workspaceRoot != currentIdentity.lease.workspaceRoot) {
        return TopologyGenerationReuse.Rejected
    }
    if (prior.snapshot.identity.sourceState != currentIdentity.sourceState) {
        return TopologyGenerationReuse.SourceChanged
    }
    if (candidates.workspace != currentIdentity) {
        return TopologyGenerationReuse.Rejected
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
    val symbols = mutableMapOf<TopologySymbol, TopologySymbol>()
    for (symbol in prior.symbols) {
        val currentFile = currentFiles.getValue(symbol.file.path)
        val rebound = when (val admitted = TopologySymbol.admit(currentFile, symbol.evidence)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return TopologyGenerationReuse.Rejected
        }
        symbols[symbol] = rebound
    }
    val edges = mutableListOf<TopologyEdge>()
    for (edge in prior.edges) {
        val source = symbols[edge.source]
                     ?: return TopologyGenerationReuse.Rejected
        val target = symbols[edge.target]
                     ?: return TopologyGenerationReuse.Rejected
        when (val rebound = TopologyEdge.fromBoundary(
            edge.kind,
            source,
            target,
            edge.occurrence.startInclusive,
            edge.occurrence.endExclusive,
        )) {
            is Refinement.Refined -> edges += rebound.value
            is Refinement.Rejected ->
                return TopologyGenerationReuse.Rejected
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
                return TopologyGenerationReuse.Rejected
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
            TopologyGenerationReuse.Rejected
    }
}
