package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation

/** Public native boundary that compiles scope before bounded IntelliJ index discovery. */
class IntellijSymbolCompilerAdapter private constructor(
    private val nativeDiscovery: IntellijNativeDiscoveryAdapter,
) {
    constructor() : this(IntellijNativeDiscoveryAdapter())

    /**
     * Proof transition: `(Project, SymbolDiscoveryRequest,
     * WorkspaceSearchScopeModelCompilation) -> SymbolCompilation`.
     *
     * A compiled result establishes request-local scope compilation before bounded index access
     * and detached generation-bound candidate projection. [SymbolCompilerRejection] is the closed
     * expected failure. The live [Project], compiled scope, provider, VFS, and PSI values remain
     * inside this call.
     */
    suspend fun compile(
        project: Project,
        request: SymbolDiscoveryRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): SymbolCompilation = when (
        val result = nativeDiscovery.discover(project, request, modelCompilation)
    ) {
        is IntellijNativeDiscoveryResult.Discovered ->
            SymbolCompilation.Compiled(result.outcome)
        is IntellijNativeDiscoveryResult.ScopeRejected ->
            SymbolCompilation.Rejected(SymbolCompilerRejection.SCOPE_REJECTED)
        is IntellijNativeDiscoveryResult.Rejected -> SymbolCompilation.Rejected(
            when (result.reason) {
                IntellijNativeDiscoveryRejection.DUMB_MODE,
                IntellijNativeDiscoveryRejection.PROJECT_DISPOSED,
                    -> SymbolCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE
                IntellijNativeDiscoveryRejection.NO_NATIVE_PROVIDERS ->
                    SymbolCompilerRejection.PROVIDER_UNAVAILABLE
                IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT ->
                    SymbolCompilerRejection.INTERNAL_INVARIANT
            },
        )
    }
}
