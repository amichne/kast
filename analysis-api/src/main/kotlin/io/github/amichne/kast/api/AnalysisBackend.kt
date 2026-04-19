package io.github.amichne.kast.api

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

    suspend fun resolveSymbol(query: SymbolQuery): SymbolResult

    suspend fun findReferences(query: ReferencesQuery): ReferencesResult

    suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult {
        throw CapabilityNotSupportedException(
            capability = "CALL_HIERARCHY",
            message = "Call hierarchy is not available for this backend",
        )
    }

    suspend fun typeHierarchy(query: TypeHierarchyQuery): TypeHierarchyResult {
        throw CapabilityNotSupportedException(
            capability = "TYPE_HIERARCHY",
            message = "Type hierarchy is not available for this backend",
        )
    }

    suspend fun semanticInsertionPoint(query: SemanticInsertionQuery): SemanticInsertionResult {
        throw CapabilityNotSupportedException(
            capability = "SEMANTIC_INSERTION_POINT",
            message = "Semantic insertion point lookup is not available for this backend",
        )
    }

    suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult

    suspend fun rename(query: RenameQuery): RenameResult

    suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult

    suspend fun optimizeImports(query: ImportOptimizeQuery): ImportOptimizeResult {
        throw CapabilityNotSupportedException(
            capability = "OPTIMIZE_IMPORTS",
            message = "Import optimization is not available for this backend",
        )
    }

    suspend fun refresh(query: RefreshQuery): RefreshResult {
        throw CapabilityNotSupportedException(
            capability = "REFRESH_WORKSPACE",
            message = "Workspace refresh is not available for this backend",
        )
    }

    suspend fun fileOutline(query: FileOutlineQuery): FileOutlineResult {
        throw CapabilityNotSupportedException(
            capability = "FILE_OUTLINE",
            message = "File outline is not available for this backend",
        )
    }

    suspend fun workspaceSymbolSearch(query: WorkspaceSymbolQuery): WorkspaceSymbolResult {
        throw CapabilityNotSupportedException(
            capability = "WORKSPACE_SYMBOL_SEARCH",
            message = "Workspace symbol search is not available for this backend",
        )
    }

    suspend fun workspaceFiles(query: WorkspaceFilesQuery): WorkspaceFilesResult {
        throw CapabilityNotSupportedException(
            capability = "WORKSPACE_FILES",
            message = "Workspace file listing is not available for this backend",
        )
    }

    suspend fun implementations(query: ImplementationsQuery): ImplementationsResult {
        throw CapabilityNotSupportedException(
            capability = "IMPLEMENTATIONS",
            message = "Go to implementation is not available for this backend",
        )
    }

    suspend fun codeActions(query: CodeActionsQuery): CodeActionsResult {
        throw CapabilityNotSupportedException(
            capability = "CODE_ACTIONS",
            message = "Code actions are not available for this backend",
        )
    }

    suspend fun completions(query: CompletionsQuery): CompletionsResult {
        throw CapabilityNotSupportedException(
            capability = "COMPLETIONS",
            message = "Completions are not available for this backend",
        )
    }
}
