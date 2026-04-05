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

    suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult

    suspend fun rename(query: RenameQuery): RenameResult

    suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult

    suspend fun refresh(query: RefreshQuery): RefreshResult {
        throw CapabilityNotSupportedException(
            capability = "REFRESH_WORKSPACE",
            message = "Workspace refresh is not available for this backend",
        )
    }
}
