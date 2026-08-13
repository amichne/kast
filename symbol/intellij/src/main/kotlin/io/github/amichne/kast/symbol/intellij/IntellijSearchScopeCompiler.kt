package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.symbol.contract.SymbolReadableSources
import io.github.amichne.kast.symbol.contract.SymbolSearchOwner
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

internal enum class IntellijSearchScopeFailure {
    PROJECT_MODEL_REJECTED,
    LEASE_ROOT_MISMATCH,
    OWNER_NOT_IN_MODEL,
    NO_READABLE_SOURCE_ROOTS,
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
         * Establishes an absolute normalized native path or returns the closed [Relative]
         * failure. Raw [Path] extraction is permitted only inside the request-local native
         * search-scope boundary.
         */
        fun classify(path: Path): IntellijVirtualFilePath = if (path.isAbsolute) {
            Absolute(path.normalize())
        } else {
            Relative
        }
    }
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
     * explicit authored/generated policy, and a bounded native [GlobalSearchScope].
     * [IntellijSearchScopeFailure] is the closed expected failure. [Project] and the live scope
     * remain request-local and may be extracted only by the native query adapter.
     */
    fun compile(
        project: Project,
        request: SymbolSearchScopeRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijSearchScopeCompilation = compile(
        request = request,
        modelCompilation = modelCompilation,
        baseScope = GlobalSearchScope.projectScope(project),
        nativePath = ::nativePath,
    )

    /**
     * Proof transition equivalent to [compile], with the request-local native base scope and path
     * extractor supplied explicitly for isolated adapter proof. Expected failures remain
     * [IntellijSearchScopeFailure], and live values must not cross the query callback boundary.
     */
    internal fun compile(
        request: SymbolSearchScopeRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
        baseScope: GlobalSearchScope,
        nativePath: (VirtualFile) -> IntellijVirtualFilePath,
    ): IntellijSearchScopeCompilation {
        val model = when (modelCompilation) {
            is WorkspaceSearchScopeModelCompilation.Compiled -> modelCompilation.model
            is WorkspaceSearchScopeModelCompilation.Rejected ->
                return rejected(IntellijSearchScopeFailure.PROJECT_MODEL_REJECTED)
        }
        if (model.workspaceRoot != request.lease.workspaceRoot) {
            return rejected(IntellijSearchScopeFailure.LEASE_ROOT_MISMATCH)
        }

        val ownedRoots = when (val owner = request.owner) {
            SymbolSearchOwner.Workspace -> model.sourceRoots
            is SymbolSearchOwner.GradleProject -> model.sourceRoots.filter { it.project == owner.identity }
        }
        if (ownedRoots.isEmpty()) {
            return rejected(IntellijSearchScopeFailure.OWNER_NOT_IN_MODEL)
        }

        val readableRoots = ownedRoots.filter { root ->
            request.readableSources == SymbolReadableSources.AUTHORED_AND_GENERATED ||
            root.provenance == WorkspaceSourceRootProvenance.AUTHORED
        }
        if (readableRoots.isEmpty()) {
            return rejected(IntellijSearchScopeFailure.NO_READABLE_SOURCE_ROOTS)
        }

        val nativeRoots = readableRoots
            .map { Path.of(it.sourceRoot.value) }
            .distinct()
            .sortedBy(Path::toString)
        return IntellijSearchScopeCompilation.Compiled(
            CompiledIntellijSearchScope(
                lease = request.lease,
                sourceRoots = readableRoots,
                nativeScope = ModelOwnedGlobalSearchScope(baseScope, nativeRoots, nativePath),
            ),
        )
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
        query: (CompiledIntellijSearchScope) -> Value,
    ): IntellijScopedQueryResult<Value> = executeCompilation(
        compilation = compiler.compile(request, modelCompilation, baseScope, nativePath),
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

private class ModelOwnedGlobalSearchScope(
    baseScope: GlobalSearchScope,
    roots: List<Path>,
    private val nativePath: (VirtualFile) -> IntellijVirtualFilePath,
) : DelegatingGlobalSearchScope(baseScope, roots) {
    private val roots = roots.toList()

    override fun contains(file: VirtualFile): Boolean =
        super.contains(file) && when (val path = nativePath(file)) {
            is IntellijVirtualFilePath.Absolute -> roots.any(path.value::startsWith)
            IntellijVirtualFilePath.Relative,
            IntellijVirtualFilePath.Unavailable,
                -> false
        }
}

/**
 * Proof transition: VirtualFile to IntellijVirtualFilePath.
 *
 * Establishes an absolute normalized NIO path or returns the closed [IntellijVirtualFilePath]
 * failure state. The live [VirtualFile] and raw [Path] stay inside the native scope boundary.
 */
private fun nativePath(file: VirtualFile): IntellijVirtualFilePath = try {
    IntellijVirtualFilePath.classify(file.toNioPath())
} catch (_: UnsupportedOperationException) {
    IntellijVirtualFilePath.Unavailable
}
