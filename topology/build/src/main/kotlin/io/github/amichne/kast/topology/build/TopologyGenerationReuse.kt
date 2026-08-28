package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.PublishedWorkspace

internal sealed interface TopologyGenerationReuse {
    data class Rebound(
        val generation: CompleteTopologyGeneration,
    ) : TopologyGenerationReuse

    data object SourceChanged : TopologyGenerationReuse

    data object Rejected : TopologyGenerationReuse
}

internal enum class VerifiedTopologyGenerationReuseFailure {
    WORKSPACE_MISMATCH,
    CANDIDATE_SET_MISMATCH,
    CHANGED_FILE_MISMATCH,
    PRIOR_SYMBOL_MISSING,
    FACT_REJECTED,
    COVERAGE_REJECTED,
}

internal sealed interface VerifiedTopologyGenerationReuse {
    data class Rebound(
        val generation: CompleteTopologyGeneration,
    ) : VerifiedTopologyGenerationReuse

    data class Rejected(
        val failure: VerifiedTopologyGenerationReuseFailure,
    ) : VerifiedTopologyGenerationReuse
}

/**
 * Rebinds a complete prior generation after one verified source write. Only the changed file's
 * current compiler extraction is admitted; every other file must retain byte-exact candidate
 * evidence. Incoming edges are rebound through compiler-grounded declaration meaning so source
 * offsets may move without weakening exact current topology identities.
 */
internal fun rebindVerifiedSingletonChange(
    workspace: PublishedWorkspace,
    candidates: TopologyCandidateSet,
    prior: TopologySnapshotContent,
    changed: CompleteTopologyFile,
): VerifiedTopologyGenerationReuse {
    val currentIdentity = TopologyWorkspaceIdentity.from(workspace)
    if (
        candidates.workspace != currentIdentity ||
        prior.snapshot.identity.lease.workspaceRoot != currentIdentity.lease.workspaceRoot
    ) {
        return rejected(VerifiedTopologyGenerationReuseFailure.WORKSPACE_MISMATCH)
    }
    val currentByPath = candidates.files.associateBy(TopologySourceFile::path)
    val priorByPath = prior.files.associateBy { it.file.path }
    if (
        currentByPath.size != candidates.files.size ||
        priorByPath.size != prior.files.size ||
        currentByPath.keys != priorByPath.keys
    ) {
        return rejected(VerifiedTopologyGenerationReuseFailure.CANDIDATE_SET_MISMATCH)
    }
    val currentChanged = currentByPath[changed.file.path]
        ?: return rejected(VerifiedTopologyGenerationReuseFailure.CHANGED_FILE_MISMATCH)
    if (currentChanged != changed.file) {
        return rejected(VerifiedTopologyGenerationReuseFailure.CHANGED_FILE_MISMATCH)
    }
    val changedPaths = currentByPath.keys.filterTo(linkedSetOf()) { path ->
        currentByPath.getValue(path).canonicalProjection() !=
            priorByPath.getValue(path).file.canonicalProjection()
    }
    if (changedPaths != setOf(changed.file.path)) {
        return rejected(VerifiedTopologyGenerationReuseFailure.CHANGED_FILE_MISMATCH)
    }

    val currentSymbolsByMeaning = changed.symbols.groupBy(TopologySymbol::meaning)
    if (currentSymbolsByMeaning.values.any { it.size != 1 }) {
        return rejected(VerifiedTopologyGenerationReuseFailure.FACT_REJECTED)
    }
    val reboundSymbols = linkedMapOf<TopologySymbol, TopologySymbol>()
    for (priorSymbol in prior.symbols) {
        val current = if (priorSymbol.file.path == changed.file.path) {
            currentSymbolsByMeaning[priorSymbol.meaning()]?.singleOrNull()
                ?: return rejected(VerifiedTopologyGenerationReuseFailure.PRIOR_SYMBOL_MISSING)
        } else {
            val file = currentByPath.getValue(priorSymbol.file.path)
            when (val admitted = TopologySymbol.admit(file, priorSymbol.evidence)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return rejected(VerifiedTopologyGenerationReuseFailure.FACT_REJECTED)
            }
        }
        reboundSymbols[priorSymbol] = current
    }

    val completed = mutableListOf<CompleteTopologyFile>()
    for (priorFile in prior.files) {
        if (priorFile.file.path == changed.file.path) continue
        val currentFile = currentByPath.getValue(priorFile.file.path)
        val symbols = priorFile.symbols.map { symbol ->
            reboundSymbols[symbol]
                ?: return rejected(VerifiedTopologyGenerationReuseFailure.PRIOR_SYMBOL_MISSING)
        }.sorted()
        val edges = mutableListOf<TopologyEdge>()
        for (edge in priorFile.edges) {
            val source = reboundSymbols[edge.source]
                ?: return rejected(VerifiedTopologyGenerationReuseFailure.PRIOR_SYMBOL_MISSING)
            val target = reboundSymbols[edge.target]
                ?: return rejected(VerifiedTopologyGenerationReuseFailure.PRIOR_SYMBOL_MISSING)
            when (val rebound = TopologyEdge.fromBoundary(
                edge.kind,
                source,
                target,
                edge.occurrence.startInclusive,
                edge.occurrence.endExclusive,
            )) {
                is Refinement.Refined -> edges += rebound.value
                is Refinement.Rejected ->
                    return rejected(VerifiedTopologyGenerationReuseFailure.FACT_REJECTED)
            }
        }
        val complete = when (val admitted = CompleteTopologyFile.admit(
            currentFile,
            symbols,
            edges.sorted(),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return rejected(VerifiedTopologyGenerationReuseFailure.FACT_REJECTED)
        }
        completed += complete
    }
    completed += changed
    return when (val generation = CompleteTopologyGeneration.admit(
        workspace,
        candidates.files,
        completed,
    )) {
        is Refinement.Refined -> VerifiedTopologyGenerationReuse.Rebound(generation.value)
        is Refinement.Rejected ->
            rejected(VerifiedTopologyGenerationReuseFailure.COVERAGE_REJECTED)
    }
}

private data class TopologySymbolMeaning(
    val compilerIdentity: io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity,
    val name: io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName,
    val qualifiedIdentity: io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity,
    val kind: io.github.amichne.kast.symbol.contract.CompilerSymbolKind,
)

private fun TopologySymbol.meaning(): TopologySymbolMeaning = TopologySymbolMeaning(
    evidence.compilerIdentity,
    evidence.name,
    evidence.qualifiedIdentity,
    evidence.kind,
)

private fun rejected(
    failure: VerifiedTopologyGenerationReuseFailure,
): VerifiedTopologyGenerationReuse = VerifiedTopologyGenerationReuse.Rejected(failure)

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
