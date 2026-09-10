package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

/** A fresh child declaration retains read-domain restrictions and carries its own native kind. */
internal fun SymbolDiscoveryConstraints.forSourceDeclaration(kind: DeclarationKind): SymbolDiscoveryConstraints {
    val compilerKind = when (kind) {
        DeclarationKind.CLASSLIKE -> CompilerSymbolKind.CLASSLIKE
        DeclarationKind.CONSTRUCTOR -> CompilerSymbolKind.CONSTRUCTOR
        DeclarationKind.FUNCTION -> CompilerSymbolKind.FUNCTION
        DeclarationKind.PROPERTY -> CompilerSymbolKind.PROPERTY
        DeclarationKind.TYPE_ALIAS -> CompilerSymbolKind.TYPE_ALIAS
    }
    val selection = when (val refined = SymbolDiscoveryDeclarationKinds.from(setOf(compilerKind))) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> error("A singleton declaration kind must refine")
    }
    return copy(declarationKinds = selection)
}

/** Re-establishes retained scope against the current exact model before opening source content. */
internal fun admitsSourceReadScope(
    model: WorkspaceSearchScopeModel,
    readScope: SourceReadScope,
    path: Path,
): Boolean {
    if (!path.isAbsolute || path.normalize() != path) return false
    val owners = model.sourceRoots.filter { path.startsWith(Path.of(it.sourceRoot.value)) }
    val depth = owners.maxOfOrNull { Path.of(it.sourceRoot.value).nameCount } ?: return false
    val mostSpecificOwners = owners.filter { Path.of(it.sourceRoot.value).nameCount == depth }
    return when (readScope) {
        SourceReadScope.ExactFile -> mostSpecificOwners.any {
            it.sourceKind == WorkspaceSourceRootKind.PRODUCTION || it.sourceKind == WorkspaceSourceRootKind.TEST
        }
        is SourceReadScope.Constrained -> {
            val scope = readScope.scope
            mostSpecificOwners.any { owner ->
                val selected = when (scope) {
                    is SymbolSearchScope.ExactFile -> path.toString() == scope.file.value
                    is SymbolSearchScope.Module -> owner.module == scope.module
                    is SymbolSearchScope.SourceSet -> owner.project == scope.project && owner.sourceSet == scope.sourceSet
                    is SymbolSearchScope.GradleProject -> owner.project == scope.project
                    is SymbolSearchScope.Workspace -> true
                }
                val sourceKind = when (scope.sourceKinds) {
                    SymbolSourceKindPolicy.PRODUCTION_ONLY -> owner.sourceKind == WorkspaceSourceRootKind.PRODUCTION
                    SymbolSourceKindPolicy.TEST_ONLY -> owner.sourceKind == WorkspaceSourceRootKind.TEST
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST -> owner.sourceKind == WorkspaceSourceRootKind.PRODUCTION ||
                        owner.sourceKind == WorkspaceSourceRootKind.TEST
                }
                val provenance = scope.generatedSources == SymbolGeneratedSourcePolicy.INCLUDE ||
                    owner.provenance == WorkspaceSourceRootProvenance.AUTHORED
                val sourceSet = when (val selection = readScope.constraints.sourceSets) {
                    SymbolDiscoverySourceSets.All -> true
                    is SymbolDiscoverySourceSets.Exact -> owner.sourceSet in selection.values
                }
                selected && sourceKind && provenance && sourceSet
            }
        }
    }
}
