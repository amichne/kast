package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectSourceMembership
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call
import java.nio.file.Path

internal sealed interface IntellijRelationScopeFailure {
    @ConsistentCopyVisibility
    data class ProjectModelRejected internal constructor(val failures: Set<WorkspaceSearchScopeModelFailure>) :
        IntellijRelationScopeFailure

    data object LeaseRootMismatch : IntellijRelationScopeFailure

    data object OwnerNotInModel : IntellijRelationScopeFailure

    data object TargetProvenanceUnknown : IntellijRelationScopeFailure

    data object TargetOwnershipAmbiguous : IntellijRelationScopeFailure

    data object NoReadableSourceRoots : IntellijRelationScopeFailure
}

internal sealed interface IntellijRelationNativePath {
    @ConsistentCopyVisibility data class Absolute internal constructor(val value: Path) : IntellijRelationNativePath

    data object Relative : IntellijRelationNativePath

    data object Unavailable : IntellijRelationNativePath

    companion object {
        /**
         * Proof transition: `Path -> IntellijRelationNativePath`.
         *
         * Establishes one normalized absolute path or the closed relative-path state. Raw path extraction remains
         * inside the request-local IntelliJ scope/file boundary.
         */
        fun classify(path: Path): IntellijRelationNativePath =
            if (path.isAbsolute) Absolute(path.normalize()) else Relative
    }
}

internal sealed interface IntellijRelationScopeCompilation {
    data class Compiled(val scope: CompiledRelationScope) : IntellijRelationScopeCompilation

    data class Rejected(val failures: Set<IntellijRelationScopeFailure>) : IntellijRelationScopeCompilation
}

/** Request-local proof that subject scope and imported model compiled before native work. */
internal class CompiledRelationScope
internal constructor(
    val request: RelationRequest,
    val sourceRoots: List<ModelOwnedSourceRoot>,
    val nativeScope: GlobalSearchScope,
    private val sourceDomain: RelationSourceDomainMembership,
    private val libraryMembership: (VirtualFile) -> Boolean,
    private val libraries: SymbolLibraryPolicy,
    private val observation: IntellijReadObservation,
) {
    /** Membership is decided before native candidate capacity or locator detachment. */
    fun admitProviderSite(file: VirtualFile?): RelationProviderScopeAdmission =
        when {
            file == null -> RelationProviderScopeAdmission.UNAVAILABLE
            nativeScope.contains(file) -> RelationProviderScopeAdmission.ADMITTED
            libraryMembership(file) ->
                if (libraries == SymbolLibraryPolicy.EXCLUDE) RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED
                else RelationProviderScopeAdmission.UNAVAILABLE
            else -> sourceDomain.classifyExcluded(relationNativePath(file))
        }.observed(observation)
}

internal enum class RelationProviderScopeAdmission {
    ADMITTED,
    SOURCE_DOMAIN_EXCLUDED,
    LIBRARY_POLICY_EXCLUDED,
    UNAVAILABLE,
}

/** Retains imported ownership separately from the smaller admitted source domain. */
internal class RelationSourceDomainMembership(
    private val paths: RelationPathPolicy,
    private val ownershipRoots: List<Path>,
    private val directoryAdmission: (Path) -> Boolean,
) {
    fun classifyExcluded(path: IntellijRelationNativePath): RelationProviderScopeAdmission {
        val absolute =
            when (path) {
                is IntellijRelationNativePath.Absolute -> path.value
                IntellijRelationNativePath.Relative,
                IntellijRelationNativePath.Unavailable -> return RelationProviderScopeAdmission.UNAVAILABLE
            }
        val owners = ownershipRoots.filter(absolute::startsWith)
        val depth = owners.maxOfOrNull(Path::getNameCount) ?: return RelationProviderScopeAdmission.UNAVAILABLE
        if (owners.count { it.nameCount == depth } != 1) return RelationProviderScopeAdmission.UNAVAILABLE
        return if (!paths.contains(absolute) || !directoryAdmission(absolute)) {
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED
        } else {
            // An eligible imported owner rejected by the native source boundary is not a proven domain exit.
            RelationProviderScopeAdmission.UNAVAILABLE
        }
    }
}

internal class IntellijRelationScopeCompiler(private val fileAdmission: (Path) -> Boolean = { true }) {
    /**
     * Proof transition: `(Project, RelationRequest, WorkspaceSearchScopeModelCompilation) ->
     * IntellijRelationScopeCompilation`.
     *
     * A compiled result establishes the subject's exact root, model-owned source roots, explicit
     * source/generated/library policy, and one bounded request-local native scope. [IntellijRelationScopeFailure] is
     * the closed expected failure. Live project, VFS, index, and scope objects remain inside the native adapter
     * request.
     */
    fun compile(
        project: Project,
        request: RelationRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
        selectedScope: SymbolSearchScope = request.searchScope,
        constraints: SymbolDiscoveryConstraints = request.searchConstraints,
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): IntellijRelationScopeCompilation {
        val model =
            when (modelCompilation) {
                is WorkspaceSearchScopeModelCompilation.Compiled -> modelCompilation.model
                is WorkspaceSearchScopeModelCompilation.Rejected ->
                    return rejected(IntellijRelationScopeFailure.ProjectModelRejected(modelCompilation.failures))
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
                }
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

        val pathPolicy =
            when (val scope = selectedScope) {
                is SymbolSearchScope.ExactFile -> RelationPathPolicy.ExactFile(Path.of(scope.file.value))
                else ->
                    RelationPathPolicy.SourceRoots(
                        readableRoots.map { Path.of(it.sourceRoot.value) }.distinct().sortedBy(Path::toString),
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
                    readableRoots.filter { root ->
                        when (pathPolicy) {
                            is RelationPathPolicy.ExactFile ->
                                matchesDirectory(pathPolicy.file, model.workspaceRoot.value, constraints)
                            is RelationPathPolicy.SourceRoots ->
                                mayContainRequestedDirectory(root, model.workspaceRoot.value, constraints)
                        }
                    },
                    constraints,
                    libraryPolicy,
                    libraryMembership = libraryScope::contains,
                    sourceMembership = { file ->
                        observation.call(IntellijReadCall.RELATION_SCOPE_SOURCE_MEMBERSHIP) {
                            IntellijProjectSourceMembership.contains(project, file)
                        }
                    },
                    observation = observation,
                    fileAdmission = { path ->
                        fileAdmission(path) &&
                            matchesDirectory(path, request.subject.lease.workspaceRoot.value, constraints)
                    },
                ),
                RelationSourceDomainMembership(
                    pathPolicy,
                    model.sourceRoots.map { Path.of(it.sourceRoot.value) },
                    directoryAdmission = { path ->
                        matchesDirectory(path, request.subject.lease.workspaceRoot.value, constraints)
                    },
                ),
                libraryScope::contains,
                libraryPolicy,
                observation,
            )
        )
    }

    private fun rootsFor(
        scope: SymbolSearchScope,
        roots: List<ModelOwnedSourceRoot>,
    ): List<ModelOwnedSourceRoot> =
        when (scope) {
            is SymbolSearchScope.ExactFile -> {
                val file = Path.of(scope.file.value)
                val candidates = roots.filter { file.startsWith(Path.of(it.sourceRoot.value)) }
                val depth = candidates.maxOfOrNull { Path.of(it.sourceRoot.value).nameCount }
                candidates.filter { Path.of(it.sourceRoot.value).nameCount == depth }
            }
            is SymbolSearchScope.Module -> roots.filter { it.module == scope.module }
            is SymbolSearchScope.SourceSet ->
                roots.filter {
                    it.project == scope.project && it.sourceSet == scope.sourceSet
                }
            is SymbolSearchScope.GradleProject -> roots.filter { it.project == scope.project }
            is SymbolSearchScope.Workspace -> roots
        }

    private fun rejected(failure: IntellijRelationScopeFailure): IntellijRelationScopeCompilation.Rejected =
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
    sourceRoots: List<ModelOwnedSourceRoot>,
    constraints: SymbolDiscoveryConstraints,
    private val libraries: SymbolLibraryPolicy,
    private val libraryMembership: (VirtualFile) -> Boolean,
    private val sourceMembership: (VirtualFile) -> Boolean,
    private val fileAdmission: (Path) -> Boolean,
    private val observation: IntellijReadObservation,
) : DelegatingGlobalSearchScope(base, paths, sourceRoots, constraints, libraries) {
    // Native-name lookup is an adapter boundary; each entry retains its proven model ownership and source kind.
    private val moduleRootsByNativeName = sourceRoots.groupBy { it.module.value }

    // These are all calls to this request's scope API, including provider rechecks. They are neither
    // distinct files nor an inventory of hidden index work, and contain no file/module identities.
    override fun contains(file: VirtualFile): Boolean =
        observed(
            IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP,
            IntellijReadCounter.RELATION_SCOPE_FILES_ADMITTED,
            IntellijReadCounter.RELATION_SCOPE_FILES_EXCLUDED,
        ) {
            if (!super.contains(file)) return@observed false
            if (libraries == SymbolLibraryPolicy.INCLUDE && libraryMembership(file)) return@observed true
            when (val path = relationNativePath(file)) {
                is IntellijRelationNativePath.Absolute ->
                    sourceMembership(file) && fileAdmission(path.value) && paths.contains(path.value)
                IntellijRelationNativePath.Relative,
                IntellijRelationNativePath.Unavailable -> false
            }
        }

    override fun isSearchInLibraries(): Boolean =
        observed(
            IntellijReadCall.RELATION_SCOPE_LIBRARY_POLICY,
            IntellijReadCounter.RELATION_SCOPE_LIBRARY_SEARCH_ADMITTED,
            IntellijReadCounter.RELATION_SCOPE_LIBRARY_SEARCH_EXCLUDED,
        ) {
            libraries == SymbolLibraryPolicy.INCLUDE
        }

    // Imported model identities originate at Module.name. Do not enumerate live modules or resolve new owners here.
    override fun isSearchInModuleContent(module: Module): Boolean =
        observed(
            IntellijReadCall.RELATION_SCOPE_MODULE_MEMBERSHIP,
            IntellijReadCounter.RELATION_SCOPE_MODULES_ADMITTED,
            IntellijReadCounter.RELATION_SCOPE_MODULES_EXCLUDED,
        ) {
            if (module.isDisposed || module.project !== project) return@observed false
            moduleRootsByNativeName.containsKey(module.name.trim())
        }

    override fun isSearchInModuleContent(module: Module, testSources: Boolean): Boolean =
        observed(
            IntellijReadCall.RELATION_SCOPE_MODULE_SOURCE_KIND_MEMBERSHIP,
            IntellijReadCounter.RELATION_SCOPE_MODULE_SOURCE_KINDS_ADMITTED,
            IntellijReadCounter.RELATION_SCOPE_MODULE_SOURCE_KINDS_EXCLUDED,
        ) {
            if (module.isDisposed || module.project !== project) return@observed false
            val kind = if (testSources) WorkspaceSourceRootKind.TEST else WorkspaceSourceRootKind.PRODUCTION
            moduleRootsByNativeName[module.name.trim()].orEmpty().any { it.sourceKind == kind }
        }

    private inline fun observed(
        call: IntellijReadCall,
        admitted: IntellijReadCounter,
        excluded: IntellijReadCounter,
        crossinline membership: () -> Boolean,
    ): Boolean =
        observation.call(call) {
            membership().also { observation.count(if (it) admitted else excluded) }
        }
}

internal fun relationNativePath(file: VirtualFile): IntellijRelationNativePath =
    try {
        IntellijRelationNativePath.classify(file.toNioPath())
    } catch (_: UnsupportedOperationException) {
        IntellijRelationNativePath.Unavailable
    } catch (_: IllegalArgumentException) {
        IntellijRelationNativePath.Unavailable
    }

private fun SymbolSourceKindPolicy.includes(kind: WorkspaceSourceRootKind): Boolean =
    when (this) {
        SymbolSourceKindPolicy.PRODUCTION_ONLY -> kind == WorkspaceSourceRootKind.PRODUCTION
        SymbolSourceKindPolicy.TEST_ONLY -> kind == WorkspaceSourceRootKind.TEST
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST ->
            kind == WorkspaceSourceRootKind.PRODUCTION || kind == WorkspaceSourceRootKind.TEST
    }

private fun SymbolGeneratedSourcePolicy.includes(provenance: WorkspaceSourceRootProvenance): Boolean =
    when (this) {
        SymbolGeneratedSourcePolicy.EXCLUDE -> provenance == WorkspaceSourceRootProvenance.AUTHORED
        SymbolGeneratedSourcePolicy.INCLUDE -> true
    }

private fun SymbolSearchScope.libraryPolicy(): SymbolLibraryPolicy =
    when (this) {
        is SymbolSearchScope.Workspace -> libraries
        is SymbolSearchScope.ExactFile,
        is SymbolSearchScope.GradleProject,
        is SymbolSearchScope.Module,
        is SymbolSearchScope.SourceSet -> SymbolLibraryPolicy.EXCLUDE
    }

private fun matchesDirectory(path: Path, root: String, constraints: SymbolDiscoveryConstraints): Boolean {
    val restriction = constraints.directory ?: return true
    val requested = Path.of(root).resolve(restriction.directory.value).normalize()
    return when (restriction.containment) {
        SymbolDiscoveryContainment.DIRECT -> path.parent == requested
        SymbolDiscoveryContainment.DESCENDANTS -> path.startsWith(requested)
    }
}

/** Cheap directory intersection retains containing roots and eligible descendants before native enumeration. */
private fun mayContainRequestedDirectory(
    root: ModelOwnedSourceRoot,
    workspace: String,
    constraints: SymbolDiscoveryConstraints,
): Boolean {
    val restriction = constraints.directory ?: return true
    val requested = Path.of(workspace).resolve(restriction.directory.value).normalize()
    val source = Path.of(root.sourceRoot.value)
    return when (restriction.containment) {
        SymbolDiscoveryContainment.DIRECT -> requested.startsWith(source)
        SymbolDiscoveryContainment.DESCENDANTS -> requested.startsWith(source) || source.startsWith(requested)
    }
}
