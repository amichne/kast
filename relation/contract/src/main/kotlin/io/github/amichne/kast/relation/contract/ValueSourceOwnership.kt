package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance

enum class ValueSourceOwnershipFailure {
    FILE_NOT_WORKSPACE,
    ROOT_OUTSIDE_WORKSPACE,
    FILE_OUTSIDE_ROOT,
}

/** The native adapter supplies the exact imported owner after its current model selects that source root. */
class ValueSourceOwnership
private constructor(
    val declaration: ValueDeclarationIdentity,
    val basis: SemanticReadIdentity,
    val root: ModelOwnedSourceRoot,
) {
    fun matches(endpoint: RelationEndpoint): Boolean =
        declaration == endpoint.valueIdentity && basis == endpoint.lease.identity

    internal fun admittedBy(request: RelationRequest): Boolean =
        admittedBy(request.searchScope) &&
            when (val sets = request.searchConstraints.sourceSets) {
                SymbolDiscoverySourceSets.All -> true
                is SymbolDiscoverySourceSets.Exact -> root.sourceSet in sets.values
            }

    private fun admittedBy(scope: SymbolSearchScope): Boolean {
        val kind =
            when (scope.sourceKinds) {
                SymbolSourceKindPolicy.PRODUCTION_ONLY -> root.sourceKind == WorkspaceSourceRootKind.PRODUCTION
                SymbolSourceKindPolicy.TEST_ONLY -> root.sourceKind == WorkspaceSourceRootKind.TEST
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST -> root.sourceKind != WorkspaceSourceRootKind.UNKNOWN
            }
        val provenance =
            when (root.provenance) {
                WorkspaceSourceRootProvenance.AUTHORED -> true
                WorkspaceSourceRootProvenance.GENERATED -> scope.generatedSources == SymbolGeneratedSourcePolicy.INCLUDE
                WorkspaceSourceRootProvenance.UNKNOWN -> false
            }
        val owner =
            when (scope) {
                is SymbolSearchScope.ExactFile -> declaration.file.stableValue == scope.file.value
                is SymbolSearchScope.Module -> root.module == scope.module
                is SymbolSearchScope.SourceSet -> root.project == scope.project && root.sourceSet == scope.sourceSet
                is SymbolSearchScope.GradleProject -> root.project == scope.project
                is SymbolSearchScope.Workspace -> true
            }
        return kind && provenance && owner
    }

    companion object {
        fun fromCompiler(
            declaration: RelationEndpoint,
            root: ModelOwnedSourceRoot,
        ): Refinement<ValueSourceOwnership, ValueSourceOwnershipFailure> {
            val file =
                declaration.file as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(ValueSourceOwnershipFailure.FILE_NOT_WORKSPACE)
            val workspace = declaration.lease.workspaceRoot.value.normalizedDirectory()
            val source = root.sourceRoot.value.normalizedDirectory()
            if (source != workspace && !source.startsWith("$workspace/"))
                return Refinement.Rejected(ValueSourceOwnershipFailure.ROOT_OUTSIDE_WORKSPACE)
            if (!file.path.value.replace('\\', '/').startsWith("$source/"))
                return Refinement.Rejected(ValueSourceOwnershipFailure.FILE_OUTSIDE_ROOT)
            return Refinement.Refined(ValueSourceOwnership(declaration.valueIdentity, declaration.lease.identity, root))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ValueSourceOwnership && declaration == other.declaration && basis == other.basis && root == other.root

    override fun hashCode(): Int = 31 * (31 * declaration.hashCode() + basis.hashCode()) + root.hashCode()
}

private fun String.normalizedDirectory(): String = replace('\\', '/').trimEnd('/')
