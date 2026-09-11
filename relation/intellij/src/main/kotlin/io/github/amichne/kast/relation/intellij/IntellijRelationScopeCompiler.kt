package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectSourceMembership
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

internal sealed interface IntellijRelationScopeFailure {
    @ConsistentCopyVisibility
    data class ProjectModelRejected internal constructor(
        val failures: Set<WorkspaceSearchScopeModelFailure>,
    ) : IntellijRelationScopeFailure

    data object LeaseRootMismatch : IntellijRelationScopeFailure
    data object OwnerNotInModel : IntellijRelationScopeFailure
    data object TargetProvenanceUnknown : IntellijRelationScopeFailure
    data object TargetOwnershipAmbiguous : IntellijRelationScopeFailure
    data object NoReadableSourceRoots : IntellijRelationScopeFailure
}

internal sealed interface IntellijRelationNativePath {
    @ConsistentCopyVisibility
    data class Absolute internal constructor(val value: Path) : IntellijRelationNativePath
    data object Relative : IntellijRelationNativePath
    data object Unavailable : IntellijRelationNativePath

    companion object {
        /**
         * Proof transition: `Path -> IntellijRelationNativePath`.
         *
         * Establishes one normalized absolute path or the closed relative-path state. Raw path
         * extraction remains inside the request-local IntelliJ scope/file boundary.
         */
        fun classify(path: Path): IntellijRelationNativePath =
            if (path.isAbsolute) Absolute(path.normalize()) else Relative
    }
}

internal sealed interface IntellijRelationScopeCompilation {
    data class Compiled(
        val scope: CompiledRelationScope,
    ) : IntellijRelationScopeCompilation

    data class Rejected(
        val failures: Set<IntellijRelationScopeFailure>,
    ) : IntellijRelationScopeCompilation
}

/** Request-local proof that subject scope and imported model compiled before native work. */
internal class CompiledRelationScope internal constructor(
    val request: RelationRequest,
    val sourceRoots: List<ModelOwnedSourceRoot>,
    val nativeScope: GlobalSearchScope,
)

internal class IntellijRelationScopeCompiler(
    private val fileAdmission: (Path) -> Boolean = { true },
) {
    /**
     * Proof transition: `(Project, RelationRequest, WorkspaceSearchScopeModelCompilation) ->
     * IntellijRelationScopeCompilation`.
     *
     * A compiled result establishes the subject's exact root, model-owned source roots, explicit
     * source/generated/library policy, and one bounded request-local native scope.
     * [IntellijRelationScopeFailure] is the closed expected failure. Live project, VFS, index, and
     * scope objects remain inside the native adapter request.
     */
    fun compile(
        project: Project,
        request: RelationRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
        selectedScope: SymbolSearchScope = request.searchScope,
        constraints: SymbolDiscoveryConstraints = request.searchConstraints,
    ): IntellijRelationScopeCompilation {
        val model = when (modelCompilation) {
            is WorkspaceSearchScopeModelCompilation.Compiled -> modelCompilation.model
            is WorkspaceSearchScopeModelCompilation.Rejected ->
                return rejected(
                    IntellijRelationScopeFailure.ProjectModelRejected(modelCompilation.failures),
                )
        }
        if (model.workspaceRoot != request.subject.lease.workspaceRoot) {
            return rejected(IntellijRelationScopeFailure.LeaseRootMismatch)
        }
        val ownedRoots = rootsFor(selectedScope, model.sourceRoots)
        if (ownedRoots.isEmpty()) {
            return rejected(
                if (selectedScope is SymbolSearchScope.ExactFile) {
                    IntellijRelationScopeFailure.TargetProvenanceUnknown
                } else {
                    IntellijRelationScopeFailure.OwnerNotInModel
                },
            )
        }
        if (selectedScope is SymbolSearchScope.ExactFile && ownedRoots.size != 1) {
            return rejected(IntellijRelationScopeFailure.TargetOwnershipAmbiguous)
        }
        val readableRoots = ownedRoots.filter { root ->
            selectedScope.sourceKinds.includes(root.sourceKind) &&
            selectedScope.generatedSources.includes(root.provenance) &&
            when (val selected = constraints.sourceSets) {
                SymbolDiscoverySourceSets.All -> true
                is SymbolDiscoverySourceSets.Exact -> root.sourceSet in selected.values
            }
        }
        if (readableRoots.isEmpty()) {
            return rejected(IntellijRelationScopeFailure.NoReadableSourceRoots)
        }

        val pathPolicy = when (val scope = selectedScope) {
            is SymbolSearchScope.ExactFile ->
                RelationPathPolicy.ExactFile(Path.of(scope.file.value))
            else -> RelationPathPolicy.SourceRoots(
                readableRoots
                    .map { Path.of(it.sourceRoot.value) }
                    .distinct()
                    .sortedBy(Path::toString),
                model.sourceRoots.map { Path.of(it.sourceRoot.value) }.distinct(),
            )
        }
        val libraryPolicy = selectedScope.libraryPolicy()
        val libraryScope = ProjectScope.getLibrariesScope(project)
        return IntellijRelationScopeCompilation.Compiled(
            CompiledRelationScope(
                request,
                readableRoots,
                RelationModelScope(
                    GlobalSearchScope.allScope(project),
                    pathPolicy,
                    libraryPolicy,
                    libraryMembership = libraryScope::contains,
                    sourceMembership = { file ->
                        IntellijProjectSourceMembership.contains(project, file)
                    },
                    fileAdmission = { path ->
                        fileAdmission(path) && matchesDirectory(path, request.subject.lease.workspaceRoot.value, constraints)
                    },
                ),
            ),
        )
    }

    private fun rootsFor(
        scope: SymbolSearchScope,
        roots: List<ModelOwnedSourceRoot>,
    ): List<ModelOwnedSourceRoot> = when (scope) {
        is SymbolSearchScope.ExactFile -> {
            val file = Path.of(scope.file.value)
            val candidates = roots.filter { file.startsWith(Path.of(it.sourceRoot.value)) }
            val depth = candidates.maxOfOrNull { Path.of(it.sourceRoot.value).nameCount }
            candidates.filter { Path.of(it.sourceRoot.value).nameCount == depth }
        }
        is SymbolSearchScope.Module -> roots.filter { it.module == scope.module }
        is SymbolSearchScope.SourceSet -> roots.filter {
            it.project == scope.project && it.sourceSet == scope.sourceSet
        }
        is SymbolSearchScope.GradleProject -> roots.filter { it.project == scope.project }
        is SymbolSearchScope.Workspace -> roots
    }

    private fun rejected(
        failure: IntellijRelationScopeFailure,
    ): IntellijRelationScopeCompilation.Rejected =
        IntellijRelationScopeCompilation.Rejected(setOf(failure))
}

internal sealed interface RelationPathPolicy {
    fun contains(path: Path): Boolean

    data class ExactFile(val file: Path) : RelationPathPolicy {
        override fun contains(path: Path): Boolean = path == file
    }

    data class SourceRoots(val roots: List<Path>, val ownershipRoots: List<Path>) : RelationPathPolicy {
        override fun contains(path: Path): Boolean {
            val owners = ownershipRoots.filter(path::startsWith)
            val depth = owners.maxOfOrNull(Path::getNameCount) ?: return false
            return owners.any { it.nameCount == depth && it in roots }
        }
    }
}

private class RelationModelScope(
    base: GlobalSearchScope,
    private val paths: RelationPathPolicy,
    private val libraries: SymbolLibraryPolicy,
    private val libraryMembership: (VirtualFile) -> Boolean,
    private val sourceMembership: (VirtualFile) -> Boolean,
    private val fileAdmission: (Path) -> Boolean,
) : DelegatingGlobalSearchScope(base, paths) {
    override fun contains(file: VirtualFile): Boolean {
        if (!super.contains(file)) return false
        if (libraries == SymbolLibraryPolicy.INCLUDE && libraryMembership(file)) return true
        return when (val path = relationNativePath(file)) {
            is IntellijRelationNativePath.Absolute -> sourceMembership(file) &&
                fileAdmission(path.value) && paths.contains(path.value)
            IntellijRelationNativePath.Relative,
            IntellijRelationNativePath.Unavailable,
                -> false
        }
    }

    override fun isSearchInLibraries(): Boolean = libraries == SymbolLibraryPolicy.INCLUDE
}

internal fun relationNativePath(file: VirtualFile): IntellijRelationNativePath =
    try {
        IntellijRelationNativePath.classify(file.toNioPath())
    } catch (_: UnsupportedOperationException) {
        IntellijRelationNativePath.Unavailable
    } catch (_: IllegalArgumentException) {
        IntellijRelationNativePath.Unavailable
    }

private fun SymbolSourceKindPolicy.includes(kind: WorkspaceSourceRootKind): Boolean = when (this) {
    SymbolSourceKindPolicy.PRODUCTION_ONLY -> kind == WorkspaceSourceRootKind.PRODUCTION
    SymbolSourceKindPolicy.TEST_ONLY -> kind == WorkspaceSourceRootKind.TEST
    SymbolSourceKindPolicy.PRODUCTION_AND_TEST ->
        kind == WorkspaceSourceRootKind.PRODUCTION || kind == WorkspaceSourceRootKind.TEST
}

private fun SymbolGeneratedSourcePolicy.includes(
    provenance: WorkspaceSourceRootProvenance,
): Boolean = when (this) {
    SymbolGeneratedSourcePolicy.EXCLUDE -> provenance == WorkspaceSourceRootProvenance.AUTHORED
    SymbolGeneratedSourcePolicy.INCLUDE -> true
}

private fun SymbolSearchScope.libraryPolicy(): SymbolLibraryPolicy = when (this) {
    is SymbolSearchScope.Workspace -> libraries
    is SymbolSearchScope.ExactFile,
    is SymbolSearchScope.GradleProject,
    is SymbolSearchScope.Module,
    is SymbolSearchScope.SourceSet,
        -> SymbolLibraryPolicy.EXCLUDE
}

private fun matchesDirectory(path: Path, root: String, constraints: SymbolDiscoveryConstraints): Boolean {
    val restriction = constraints.directory ?: return true
    val requested = Path.of(root).resolve(restriction.directory.value).normalize()
    return when (restriction.containment) {
        SymbolDiscoveryContainment.DIRECT -> path.parent == requested
        SymbolDiscoveryContainment.DESCENDANTS -> path.startsWith(requested)
    }
}
