package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation

/** Public native K2 boundary for exact selector resolution and description revalidation. */
class IntellijSymbolExactCompilerAdapter private constructor(
    private val resolver: IntellijSymbolSelectorResolver,
) {
    constructor() : this(IntellijSymbolSelectorResolver())

    /**
     * Proof transition: `(Project, SemanticReadLease, SymbolResolutionRequest,
     * WorkspaceSearchScopeModelCompilation) -> SymbolResolutionCompilation`.
     *
     * A resolved compilation establishes current-lease admission, exact scope compilation, K2
     * symbol resolution, and detached selector issuance. [SymbolExactCompilerRejection] is the
     * closed expected failure. Live project, PSI, VFS, scope, and compiler objects remain inside
     * this call.
     */
    suspend fun resolve(
        project: Project,
        currentLease: SemanticReadLease,
        request: SymbolResolutionRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): SymbolResolutionCompilation = when (
        val resolution = resolver.resolve(
            project,
            currentLease,
            request,
            modelCompilation,
        )
    ) {
        is IntellijSymbolSelectorResolution.Resolved ->
            SymbolResolutionCompilation.Resolved(resolution.selector)
        is IntellijSymbolSelectorResolution.Rejected ->
            SymbolResolutionCompilation.Rejected(resolution.reason.toCompilerRejection())
    }

    /**
     * Proof transition: `(Project, SemanticReadLease, ExactSymbolRequest,
     * WorkspaceSearchScopeModelCompilation) -> SymbolDescriptionCompilation`.
     *
     * A described compilation establishes current-lease admission and identical K2 compiler
     * evidence under the selector's retained scope before detached projection.
     * [SymbolExactCompilerRejection] is the closed expected failure. Live project, PSI, VFS,
     * scope, and compiler objects remain inside this call.
     */
    suspend fun describe(
        project: Project,
        currentLease: SemanticReadLease,
        request: ExactSymbolRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): SymbolDescriptionCompilation = when (
        val resolution = resolver.describe(
            project,
            currentLease,
            request,
            modelCompilation,
        )
    ) {
        is IntellijSymbolDescriptionResolution.Described ->
            SymbolDescriptionCompilation.Described(resolution.description)
        is IntellijSymbolDescriptionResolution.Rejected ->
            SymbolDescriptionCompilation.Rejected(resolution.reason.toCompilerRejection())
    }
}

private fun IntellijSymbolSelectorRejection.toCompilerRejection():
    SymbolExactCompilerRejection = when (this) {
    IntellijSymbolSelectorRejection.WORKSPACE_ROOT_MISMATCH ->
        SymbolExactCompilerRejection.WORKSPACE_ROOT_MISMATCH
    IntellijSymbolSelectorRejection.GENERATION_MOVED ->
        SymbolExactCompilerRejection.GENERATION_MOVED
    IntellijSymbolSelectorRejection.SCOPE_REJECTED ->
        SymbolExactCompilerRejection.SCOPE_REJECTED
    IntellijSymbolSelectorRejection.DUMB_MODE,
    IntellijSymbolSelectorRejection.PROJECT_DISPOSED,
    IntellijSymbolSelectorRejection.NATIVE_FAILURE,
        -> SymbolExactCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE
    IntellijSymbolSelectorRejection.STALE_LOCATION ->
        SymbolExactCompilerRejection.STALE_LOCATION
    IntellijSymbolSelectorRejection.OUTSIDE_SCOPE ->
        SymbolExactCompilerRejection.OUTSIDE_SCOPE
    IntellijSymbolSelectorRejection.AMBIGUOUS_DECLARATION ->
        SymbolExactCompilerRejection.AMBIGUOUS_DECLARATION
    IntellijSymbolSelectorRejection.UNSUPPORTED_DECLARATION ->
        SymbolExactCompilerRejection.UNSUPPORTED_DECLARATION
    IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE ->
        SymbolExactCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE
    IntellijSymbolSelectorRejection.DECLARATION_MOVED_OR_CHANGED ->
        SymbolExactCompilerRejection.DECLARATION_MOVED_OR_CHANGED
    IntellijSymbolSelectorRejection.COMPILER_EVIDENCE_MISMATCH,
    IntellijSymbolSelectorRejection.INTERNAL_INVARIANT,
        -> SymbolExactCompilerRejection.INTERNAL_INVARIANT
}
