package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName

/** Refines the public expansion domain once; downstream stages retain these exact source ownership facts. */
internal fun QueryExpansionScopeDocument.boundary(): Refinement<RelationSearchBoundary, QueryExpansionScopeFailure> =
    when (this) {
        QueryExpansionScopeDocument.RetainedSeed -> Refinement.Refined(RelationSearchBoundary.RETAINED_SUBJECT)
        QueryExpansionScopeDocument.Workspace -> Refinement.Refined(RelationSearchBoundary.WORKSPACE_EXPANSION)
        is QueryExpansionScopeDocument.Sources -> sourceBoundary()
    }

private fun QueryExpansionScopeDocument.Sources.sourceBoundary():
    Refinement<RelationSearchBoundary, QueryExpansionScopeFailure> {
    if (sourceSets.values.distinct().size != sourceSets.values.size)
        return Refinement.Rejected(QueryExpansionScopeFailure.DUPLICATE_SOURCE_SET)
    val sets = linkedSetOf<WorkspaceSourceSetName>()
    for (raw in sourceSets.values) {
        when (val set = WorkspaceSourceSetName.parse(raw.value)) {
            is Refinement.Refined -> sets += set.value
            is Refinement.Rejected -> return Refinement.Rejected(QueryExpansionScopeFailure.SOURCE_SET_REJECTED)
        }
    }
    val admittedSets =
        when (val selected = SymbolDiscoverySourceSets.Exact.from(sets)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return Refinement.Rejected(QueryExpansionScopeFailure.EMPTY_SOURCE_SET)
        }
    val admittedDirectory = directory?.let {
        val path =
            when (val selected = SymbolDiscoveryDirectory.parse(it.path.value)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return Refinement.Rejected(QueryExpansionScopeFailure.DIRECTORY_REJECTED)
            }
        SymbolDiscoveryDirectoryConstraint(path, SymbolDiscoveryContainment.valueOf(it.containment.name))
    }
    return Refinement.Refined(
        RelationSearchBoundary.Explicit(
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.valueOf(sourcePolicy.name),
                SymbolGeneratedSourcePolicy.valueOf(generatedSources.name),
                SymbolLibraryPolicy.EXCLUDE,
            ),
            admittedDirectory,
            admittedSets,
        )
    )
}
