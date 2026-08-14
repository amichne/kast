package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

sealed interface IntellijSearchScopeFailure {
    @ConsistentCopyVisibility
    data class ProjectModelRejected internal constructor(
        val failures: Set<WorkspaceSearchScopeModelFailure>,
    ) : IntellijSearchScopeFailure

    data object LeaseRootMismatch : IntellijSearchScopeFailure

    data object OwnerNotInModel : IntellijSearchScopeFailure

    data object TargetProvenanceUnknown : IntellijSearchScopeFailure

    data object TargetOwnershipAmbiguous : IntellijSearchScopeFailure

    data object NoReadableSourceRoots : IntellijSearchScopeFailure
}

internal sealed interface IntellijVirtualFilePath {
    @ConsistentCopyVisibility
    data class Absolute internal constructor(
        val value: Path,
    ) : IntellijVirtualFilePath

    data object Relative : IntellijVirtualFilePath

    data object Unavailable : IntellijVirtualFilePath

    companion object {
        /**
         * Proof transition: Path to IntellijVirtualFilePath.
         *
         * Establishes an absolute normalized native path or returns a closed relative-path state.
         * Raw [Path] extraction is permitted only inside the request-local native search-scope
         * boundary.
         */
        fun classify(path: Path): IntellijVirtualFilePath = if (path.isAbsolute) {
            Absolute(path.normalize())
        } else {
            Relative
        }
    }
}

internal enum class IntellijLibraryMembership {
    LIBRARY,
    NOT_LIBRARY,
}

internal sealed interface IntellijSearchScopeCompilation {
    data class Compiled(
        val capability: CompiledIntellijSearchScope,
    ) : IntellijSearchScopeCompilation

    data class Rejected(
        val failures: Set<IntellijSearchScopeFailure>,
    ) : IntellijSearchScopeCompilation
}

/**
 * Request-local proof that the native scope was compiled from the same lease and detached model
 * policy before a PSI or index query started.
 */
internal class CompiledIntellijSearchScope internal constructor(
    val lease: SemanticReadLease,
    val scope: SymbolSearchScope,
    val sourceRoots: List<ModelOwnedSourceRoot>,
    internal val nativeScope: GlobalSearchScope,
)

internal class IntellijSearchScopeCompiler {
    /**
     * Proof transition:
     * SymbolSearchScopeRequest + WorkspaceSearchScopeModelCompilation + Project
     * to IntellijSearchScopeCompilation.
     *
     * A compiled result establishes matching canonical-root admission, exact model ownership,
     * explicit file/module/source-set/project/workspace targeting, production/test and generated
     * policy, declared library admission through [ProjectScope.getLibrariesScope] backed by
     * IntelliJ's project file index, and a bounded native [GlobalSearchScope].
     * [IntellijSearchScopeFailure] is the closed expected failure. [Project] and all live
     * file-index and scope values remain request-local and may be extracted only by the native
     * query adapter.
     */
    fun compile(
        project: Project,
        request: SymbolSearchScopeRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijSearchScopeCompilation {
        val libraryScope = ProjectScope.getLibrariesScope(project)
        return compile(
            request = request,
            modelCompilation = modelCompilation,
            baseScope = GlobalSearchScope.allScope(project),
            nativePath = ::nativePath,
            libraryMembership = { file ->
                if (libraryScope.contains(file)) {
                    IntellijLibraryMembership.LIBRARY
                } else {
                    IntellijLibraryMembership.NOT_LIBRARY
                }
            },
        )
    }

    /**
     * Proof transition equivalent to [compile], with the request-local native base scope, path
     * extractor, and IntelliJ library classifier supplied explicitly for isolated adapter proof.
     * Expected failures remain [IntellijSearchScopeFailure], and live values must not cross the
     * query callback boundary.
     */
    internal fun compile(
        request: SymbolSearchScopeRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
        baseScope: GlobalSearchScope,
        nativePath: (VirtualFile) -> IntellijVirtualFilePath,
        libraryMembership: (VirtualFile) -> IntellijLibraryMembership,
    ): IntellijSearchScopeCompilation {
        val model = when (modelCompilation) {
            is WorkspaceSearchScopeModelCompilation.Compiled -> modelCompilation.model
            is WorkspaceSearchScopeModelCompilation.Rejected ->
                return rejected(IntellijSearchScopeFailure.ProjectModelRejected(modelCompilation.failures))
        }
        if (model.workspaceRoot != request.lease.workspaceRoot) {
            return rejected(IntellijSearchScopeFailure.LeaseRootMismatch)
        }

        val ownedRoots = rootsFor(request.scope, model.sourceRoots)
        if (ownedRoots.isEmpty()) {
            val failure = if (request.scope is SymbolSearchScope.ExactFile) {
                IntellijSearchScopeFailure.TargetProvenanceUnknown
            } else {
                IntellijSearchScopeFailure.OwnerNotInModel
            }
            return rejected(failure)
        }
        if (request.scope is SymbolSearchScope.ExactFile && ownedRoots.size != 1) {
            return rejected(IntellijSearchScopeFailure.TargetOwnershipAmbiguous)
        }

        val readableRoots = ownedRoots.filter { root ->
            request.scope.sourceKinds.includes(root.sourceKind) &&
            request.scope.generatedSources.includes(root.provenance)
        }
        if (readableRoots.isEmpty()) {
            return rejected(IntellijSearchScopeFailure.NoReadableSourceRoots)
        }

        val pathPolicy = when (val scope = request.scope) {
            is SymbolSearchScope.ExactFile ->
                IntellijModelPathPolicy.ExactFile(Path.of(scope.file.value))
            else ->
                IntellijModelPathPolicy.SourceRoots(
                    readableRoots
                        .map { Path.of(it.sourceRoot.value) }
                        .distinct()
                        .sortedBy(Path::toString),
                )
        }
        val libraryPolicy = request.scope.libraryPolicy()
        return IntellijSearchScopeCompilation.Compiled(
            CompiledIntellijSearchScope(
                lease = request.lease,
                scope = request.scope,
                sourceRoots = readableRoots,
                nativeScope = ModelOwnedGlobalSearchScope(
                    baseScope = baseScope,
                    pathPolicy = pathPolicy,
                    libraryPolicy = libraryPolicy,
                    nativePath = nativePath,
                    libraryMembership = libraryMembership,
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
            val mostSpecificDepth = candidates.maxOfOrNull { Path.of(it.sourceRoot.value).nameCount }
            candidates.filter { Path.of(it.sourceRoot.value).nameCount == mostSpecificDepth }
        }
        is SymbolSearchScope.Module -> roots.filter { it.module == scope.module }
        is SymbolSearchScope.SourceSet -> roots.filter {
            it.project == scope.project && it.sourceSet == scope.sourceSet
        }
        is SymbolSearchScope.GradleProject -> roots.filter { it.project == scope.project }
        is SymbolSearchScope.Workspace -> roots
    }

    private fun rejected(
        failure: IntellijSearchScopeFailure,
    ): IntellijSearchScopeCompilation.Rejected =
        IntellijSearchScopeCompilation.Rejected(setOf(failure))
}

internal sealed interface IntellijScopedQueryResult<out Value> {
    data class Completed<Value>(
        val value: Value,
    ) : IntellijScopedQueryResult<Value>

    data class Rejected(
        val failures: Set<IntellijSearchScopeFailure>,
    ) : IntellijScopedQueryResult<Nothing>
}

internal class IntellijSearchScopeQueryAdapter(
    private val compiler: IntellijSearchScopeCompiler = IntellijSearchScopeCompiler(),
) {
    /**
     * Compiles the detached policy before invoking [query]. Rejected ownership never enters native
     * PSI or index work, and the callback receives only the proof-carrying compiled capability.
     */
    fun <Value> execute(
        project: Project,
        request: SymbolSearchScopeRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
        query: (CompiledIntellijSearchScope) -> Value,
    ): IntellijScopedQueryResult<Value> = executeCompilation(
        compilation = compiler.compile(project, request, modelCompilation),
        query = query,
    )

    internal fun <Value> execute(
        request: SymbolSearchScopeRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
        baseScope: GlobalSearchScope,
        nativePath: (VirtualFile) -> IntellijVirtualFilePath,
        libraryMembership: (VirtualFile) -> IntellijLibraryMembership,
        query: (CompiledIntellijSearchScope) -> Value,
    ): IntellijScopedQueryResult<Value> = executeCompilation(
        compilation = compiler.compile(
            request,
            modelCompilation,
            baseScope,
            nativePath,
            libraryMembership,
        ),
        query = query,
    )

    private fun <Value> executeCompilation(
        compilation: IntellijSearchScopeCompilation,
        query: (CompiledIntellijSearchScope) -> Value,
    ): IntellijScopedQueryResult<Value> = when (compilation) {
        is IntellijSearchScopeCompilation.Compiled ->
            IntellijScopedQueryResult.Completed(query(compilation.capability))
        is IntellijSearchScopeCompilation.Rejected ->
            IntellijScopedQueryResult.Rejected(compilation.failures)
    }
}

private sealed interface IntellijModelPathPolicy {
    fun contains(path: Path): Boolean

    data class ExactFile(
        val file: Path,
    ) : IntellijModelPathPolicy {
        override fun contains(path: Path): Boolean = path == file
    }

    data class SourceRoots(
        val roots: List<Path>,
    ) : IntellijModelPathPolicy {
        override fun contains(path: Path): Boolean = roots.any(path::startsWith)
    }
}

private class ModelOwnedGlobalSearchScope(
    baseScope: GlobalSearchScope,
    private val pathPolicy: IntellijModelPathPolicy,
    private val libraryPolicy: SymbolLibraryPolicy,
    private val nativePath: (VirtualFile) -> IntellijVirtualFilePath,
    private val libraryMembership: (VirtualFile) -> IntellijLibraryMembership,
) : DelegatingGlobalSearchScope(baseScope, pathPolicy) {
    override fun contains(file: VirtualFile): Boolean {
        if (!super.contains(file)) {
            return false
        }
        if (
            libraryPolicy == SymbolLibraryPolicy.INCLUDE &&
            libraryMembership(file) == IntellijLibraryMembership.LIBRARY
        ) {
            return true
        }
        return when (val path = nativePath(file)) {
            is IntellijVirtualFilePath.Absolute -> pathPolicy.contains(path.value)
            IntellijVirtualFilePath.Relative,
            IntellijVirtualFilePath.Unavailable,
                -> false
        }
    }

    override fun isSearchInLibraries(): Boolean = libraryPolicy == SymbolLibraryPolicy.INCLUDE
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

/**
 * Proof transition: VirtualFile to IntellijVirtualFilePath.
 *
 * Establishes an absolute normalized NIO path or returns the closed
 * [IntellijVirtualFilePath.Unavailable] state. The live [VirtualFile] and raw [Path] stay inside the
 * native scope boundary.
 */
internal fun nativePath(file: VirtualFile): IntellijVirtualFilePath = try {
    IntellijVirtualFilePath.classify(file.toNioPath())
} catch (_: UnsupportedOperationException) {
    IntellijVirtualFilePath.Unavailable
}
