package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
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

interface AnalysisBackend {
    suspend fun capabilities(): BackendCapabilities

    suspend fun runtimeStatus(): RuntimeStatusResponse {
        val capabilities = capabilities()
        return RuntimeStatusResponse(
            state = RuntimeState.READY,
            healthy = true,
            active = true,
            indexing = false,
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
        )
    }

    suspend fun health(): HealthResponse {
        val capabilities = capabilities()
        return HealthResponse(
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
        )
    }

    suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult

    suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult

    suspend fun callHierarchy(query: ParsedCallHierarchyQuery): CallHierarchyResult {
        throw CapabilityNotSupportedException(
            capability = "CALL_HIERARCHY",
            message = "Call hierarchy is not available for this backend",
        )
    }

    suspend fun typeHierarchy(query: ParsedTypeHierarchyQuery): TypeHierarchyResult {
        throw CapabilityNotSupportedException(
            capability = "TYPE_HIERARCHY",
            message = "Type hierarchy is not available for this backend",
        )
    }

    suspend fun semanticInsertionPoint(query: ParsedSemanticInsertionQuery): SemanticInsertionResult {
        throw CapabilityNotSupportedException(
            capability = "SEMANTIC_INSERTION_POINT",
            message = "Semantic insertion point lookup is not available for this backend",
        )
    }

    suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult

    suspend fun rename(query: ParsedRenameQuery): RenameResult

    suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult

    suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult {
        throw CapabilityNotSupportedException(
            capability = "OPTIMIZE_IMPORTS",
            message = "Import optimization is not available for this backend",
        )
    }

    suspend fun refresh(query: ParsedRefreshQuery): RefreshResult {
        throw CapabilityNotSupportedException(
            capability = "REFRESH_WORKSPACE",
            message = "Workspace refresh is not available for this backend",
        )
    }

    suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult {
        throw CapabilityNotSupportedException(
            capability = "FILE_OUTLINE",
            message = "File outline is not available for this backend",
        )
    }

    suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult {
        throw CapabilityNotSupportedException(
            capability = "WORKSPACE_SYMBOL_SEARCH",
            message = "Workspace symbol search is not available for this backend",
        )
    }

    suspend fun workspaceSearch(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult {
        throw CapabilityNotSupportedException(
            capability = "WORKSPACE_SEARCH",
            message = "Workspace content search is not available for this backend",
        )
    }

    suspend fun workspaceFiles(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult {
        throw CapabilityNotSupportedException(
            capability = "WORKSPACE_FILES",
            message = "Workspace file listing is not available for this backend",
        )
    }

    suspend fun implementations(query: ParsedImplementationsQuery): ImplementationsResult {
        throw CapabilityNotSupportedException(
            capability = "IMPLEMENTATIONS",
            message = "Go to implementation is not available for this backend",
        )
    }

    suspend fun codeActions(query: ParsedCodeActionsQuery): CodeActionsResult {
        throw CapabilityNotSupportedException(
            capability = "CODE_ACTIONS",
            message = "Code actions are not available for this backend",
        )
    }

    suspend fun completions(query: ParsedCompletionsQuery): CompletionsResult {
        throw CapabilityNotSupportedException(
            capability = "COMPLETIONS",
            message = "Completions are not available for this backend",
        )
    }
}
