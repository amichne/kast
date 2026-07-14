package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.api.validation.ParsedApplyEditsQuery
import io.github.amichne.kast.api.validation.ParsedCallHierarchyQuery
import io.github.amichne.kast.api.validation.ParsedCodeActionsQuery
import io.github.amichne.kast.api.validation.ParsedCompletionsQuery
import io.github.amichne.kast.api.validation.ParsedDiagnosticsQuery
import io.github.amichne.kast.api.validation.ParsedFileOutlineQuery
import io.github.amichne.kast.api.validation.ParsedImplementationsQuery
import io.github.amichne.kast.api.validation.ParsedImportOptimizeQuery
import io.github.amichne.kast.api.validation.ParsedReferencesQuery
import io.github.amichne.kast.api.validation.ParsedRefreshQuery
import io.github.amichne.kast.api.validation.ParsedRenameQuery
import io.github.amichne.kast.api.validation.ParsedSemanticInsertionQuery
import io.github.amichne.kast.api.validation.ParsedSymbolQuery
import io.github.amichne.kast.api.validation.ParsedTypeHierarchyQuery
import io.github.amichne.kast.api.validation.ParsedWorkspaceFilesQuery
import io.github.amichne.kast.api.validation.ParsedWorkspaceSearchQuery
import io.github.amichne.kast.api.validation.ParsedWorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery

internal class ObservedAnalysisBackend(
    private val delegate: CloseableAnalysisBackend,
    private val diagnostics: KastDiagnosticsService,
) : CloseableAnalysisBackend {
    override suspend fun capabilities(): BackendCapabilities = observe(KastBackendOperation.CAPABILITIES) {
        delegate.capabilities().also(diagnostics::recordCapabilities)
    }

    override suspend fun runtimeStatus(): RuntimeStatusResponse = observe(KastBackendOperation.RUNTIME_STATUS) {
        delegate.runtimeStatus()
            .also(diagnostics::recordRuntimeStatus)
            .let(diagnostics::enrichRuntimeStatus)
    }

    override suspend fun health(): HealthResponse = observe(KastBackendOperation.HEALTH) {
        delegate.health()
    }

    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult =
        observe(KastBackendOperation.RESOLVE_SYMBOL) { delegate.resolveSymbol(query) }

    override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult =
        observe(KastBackendOperation.FIND_REFERENCES) { delegate.findReferences(query) }

    override suspend fun callHierarchy(query: ParsedCallHierarchyQuery): CallHierarchyResult =
        observe(KastBackendOperation.CALL_HIERARCHY) { delegate.callHierarchy(query) }

    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult =
        observe(KastBackendOperation.CALL_HIERARCHY) { delegate.callRelations(query) }

    override suspend fun typeHierarchy(query: ParsedTypeHierarchyQuery): TypeHierarchyResult =
        observe(KastBackendOperation.TYPE_HIERARCHY) { delegate.typeHierarchy(query) }

    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult =
        observe(KastBackendOperation.TYPE_HIERARCHY) { delegate.hierarchyRelations(query) }

    override suspend fun semanticInsertionPoint(query: ParsedSemanticInsertionQuery): SemanticInsertionResult =
        observe(KastBackendOperation.SEMANTIC_INSERTION_POINT) { delegate.semanticInsertionPoint(query) }

    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult =
        observe(KastBackendOperation.DIAGNOSTICS) { delegate.diagnostics(query) }

    override suspend fun rename(query: ParsedRenameQuery): RenameResult =
        observe(KastBackendOperation.RENAME) { delegate.rename(query) }

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult =
        observe(KastBackendOperation.APPLY_EDITS) { delegate.applyEdits(query) }

    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult =
        observe(KastBackendOperation.OPTIMIZE_IMPORTS) { delegate.optimizeImports(query) }

    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult =
        observe(KastBackendOperation.REFRESH) { delegate.refresh(query) }

    override suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult =
        observe(KastBackendOperation.FILE_OUTLINE) { delegate.fileOutline(query) }

    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult =
        observe(KastBackendOperation.WORKSPACE_SYMBOL_SEARCH) { delegate.workspaceSymbolSearch(query) }

    override suspend fun workspaceSearch(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult =
        observe(KastBackendOperation.WORKSPACE_SEARCH) { delegate.workspaceSearch(query) }

    override suspend fun workspaceFiles(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult =
        observe(KastBackendOperation.WORKSPACE_FILES) { delegate.workspaceFiles(query) }

    override suspend fun implementations(query: ParsedImplementationsQuery): ImplementationsResult =
        observe(KastBackendOperation.IMPLEMENTATIONS) { delegate.implementations(query) }

    override suspend fun implementationRelations(
        query: KastImplementationsQuery,
    ): ImplementationRelationsResult = observe(KastBackendOperation.IMPLEMENTATIONS) {
        delegate.implementationRelations(query)
    }

    override suspend fun codeActions(query: ParsedCodeActionsQuery): CodeActionsResult =
        observe(KastBackendOperation.CODE_ACTIONS) { delegate.codeActions(query) }

    override suspend fun completions(query: ParsedCompletionsQuery): CompletionsResult =
        observe(KastBackendOperation.COMPLETIONS) { delegate.completions(query) }

    override fun close() {
        delegate.close()
    }

    private suspend inline fun <T> observe(
        operation: KastBackendOperation,
        block: () -> T,
    ): T {
        val token = diagnostics.recordOperationStarted(operation)
        return try {
            block().also { diagnostics.recordOperationSucceeded(token) }
        } catch (error: Throwable) {
            diagnostics.recordOperationFailed(token, error)
            throw error
        }
    }
}
