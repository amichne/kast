package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.time.Duration

internal class DispatcherTimeoutHealthBackend(
    private val delegate: AnalysisBackend,
    private val delayMillis: Long,
) : AnalysisBackend by delegate {
    override suspend fun health() = run {
        delay(delayMillis)
        delegate.health()
    }
}

internal class DispatcherCancellationHealthBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    override suspend fun health() = throw CancellationException("backend cancelled")
}

internal class DispatcherProgressBoundRefreshBackend(
    private val delegate: AnalysisBackend,
    private val delay: Duration,
) : AnalysisBackend by delegate {
    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult {
        delay(delay)
        return delegate.refresh(query)
    }
}

internal enum class RelationshipInterruptionMode {
    TIMEOUT,
    CANCELLED,
}

internal class InterruptingRelationshipsBackend(
    private val delegate: AnalysisBackend,
    private val mode: RelationshipInterruptionMode,
) : AnalysisBackend by delegate {
    override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult = interrupt()

    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult = interrupt()

    override suspend fun implementationRelations(
        query: KastImplementationsQuery,
    ): ImplementationRelationsResult = interrupt()

    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult = interrupt()

    private suspend fun interrupt(): Nothing = when (mode) {
        RelationshipInterruptionMode.TIMEOUT -> withTimeout(1) {
            delay(100)
            error("Relationship provider timeout was not enforced")
        }
        RelationshipInterruptionMode.CANCELLED -> throw CancellationException("Relationship provider cancelled")
    }
}

internal class CapturingApplyEditsBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    var appliedFileHashes: List<Pair<String, String>> = emptyList()
        private set

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        appliedFileHashes = query.fileHashes.map { fileHash ->
            fileHash.filePath.value to fileHash.hash
        }
        return delegate.applyEdits(query)
    }
}

internal class RecordingMutationBackend(
    private val delegate: AnalysisBackend,
    private val incompleteRefresh: Boolean = false,
) : AnalysisBackend by delegate {
    val operations = mutableListOf<String>()

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        operations += "apply"
        return delegate.applyEdits(query)
    }

    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult {
        operations += "refresh"
        if (!incompleteRefresh) return delegate.refresh(query)
        return RefreshResult.focused(
            fileStatuses = query.filePaths.map { filePath ->
                SemanticAdmissionStatus.incomplete(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.PENDING,
                    analysisAvailability = AnalysisAvailabilityState.PENDING,
                    analysisStatus = FileAnalysisStatus.skipped(
                        filePath,
                        FileAnalysisState.PENDING_INDEX,
                        "Indexer is indexing",
                    ),
                )
            },
            attemptCount = 3,
            elapsedMillis = 50,
        )
    }

    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult {
        operations += "optimize"
        return delegate.optimizeImports(query)
    }

    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        operations += "diagnostics"
        return delegate.diagnostics(query)
    }
}

internal class MissingRefreshCapabilityBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    var applyCalls: Int = 0
        private set

    override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
        mutationCapabilities = delegate.capabilities().mutationCapabilities - MutationCapability.REFRESH_WORKSPACE,
    )

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        applyCalls += 1
        return delegate.applyEdits(query)
    }
}

internal class MissingRefreshRenameBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    var applyCalls: Int = 0
        private set

    override suspend fun capabilities(): BackendCapabilities {
        val capabilities = delegate.capabilities()
        return capabilities.copy(
            mutationCapabilities = capabilities.mutationCapabilities - MutationCapability.REFRESH_WORKSPACE,
        )
    }

    override suspend fun rename(query: ParsedRenameQuery): RenameResult {
        val result = delegate.rename(query)
        return RenameResult.of(
            edits = result.edits,
            fileHashes = result.fileHashes,
            fileImages = result.fileImages,
            proof = result.proof,
            searchScope = result.searchScope,
        )
    }

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        applyCalls += 1
        return delegate.applyEdits(query)
    }
}

internal class IncompleteDiagnosticsBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        val fileStatuses = query.filePaths.value.map { filePath ->
            FileAnalysisStatus.skipped(
                filePath = filePath,
                state = FileAnalysisState.BACKEND_FAILURE,
                message = "Semantic analysis was unavailable after the operation",
            )
        }
        val diagnostics = query.filePaths.value.map { filePath ->
            Diagnostic(
                location = Location(
                    filePath = filePath.value,
                    startOffset = 0,
                    endOffset = 0,
                    startLine = 0,
                    startColumn = 0,
                    preview = "",
                ),
                severity = DiagnosticSeverity.ERROR,
                message = "Semantic analysis was unavailable after the operation",
                code = "ANALYSIS_FAILURE",
            )
        }
        return DiagnosticsResult.of(
            diagnostics = diagnostics,
            fileStatuses = fileStatuses,
            fileHashes = emptyList(),
        )
    }
}

internal class CompilerDiagnosticsBeyondLimitBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        val filePath = query.filePaths.value.single()
        fun diagnostic(
            severity: DiagnosticSeverity,
            offset: Int,
            code: String,
        ): Diagnostic = Diagnostic(
            location = Location(
                filePath = filePath.value,
                startOffset = offset,
                endOffset = offset,
                startLine = 0,
                startColumn = 0,
                preview = "",
            ),
            severity = severity,
            message = code,
            code = code,
        )
        return DiagnosticsResult.of(
            diagnostics = listOf(
                diagnostic(DiagnosticSeverity.WARNING, 0, "EARLY_WARNING"),
                diagnostic(DiagnosticSeverity.ERROR, 1, "LATE_COMPILER_ERROR"),
            ),
            fileStatuses = listOf(FileAnalysisStatus.analyzed(filePath)),
            fileHashes = listOf(
                FileHash(filePath.value, FileHashing.sha256(Files.readString(Path.of(filePath.value)))),
            ),
        )
    }
}

internal class ExactLookupBackend(
    private val delegate: AnalysisBackend,
    private val symbols: List<Symbol>,
    private val resolvedSymbols: List<Symbol> = symbols,
) : AnalysisBackend by delegate {
    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult =
        WorkspaceSymbolResult(symbols = symbols)

    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult = SymbolResult(
        symbol = resolvedSymbols.single { symbol ->
            symbol.location.filePath == query.position.filePath.value &&
                symbol.location.startOffset == query.position.offset.value
        },
    )
}

internal class RecordingPagedRelationshipsBackend(
    private val delegate: AnalysisBackend,
    private val missingCapability: ReadCapability? = null,
    private val hierarchyFailure: ConflictException? = null,
) : AnalysisBackend by delegate {
    var callRelationCalls: Int = 0
        private set
    var implementationRelationCalls: Int = 0
        private set
    var hierarchyRelationCalls: Int = 0
        private set

    override suspend fun capabilities(): BackendCapabilities {
        val capabilities = delegate.capabilities()
        return if (missingCapability == null) {
            capabilities
        } else {
            capabilities.copy(readCapabilities = capabilities.readCapabilities - missingCapability)
        }
    }

    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult {
        callRelationCalls += 1
        return CallRelationsResult.Available(emptyList(), emptyRelationPage())
    }

    override suspend fun implementationRelations(
        query: KastImplementationsQuery,
    ): ImplementationRelationsResult {
        implementationRelationCalls += 1
        return ImplementationRelationsResult.Available(emptyList(), emptyRelationPage())
    }

    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult {
        hierarchyRelationCalls += 1
        hierarchyFailure?.let { throw it }
        return HierarchyRelationsResult.Available(emptyList(), emptyRelationPage())
    }

    private fun emptyRelationPage(): RelationTraversalPageInfo = RelationTraversalPageInfo.create(
        evidence = RelationshipResultEvidence.Complete(
            cardinality = ResultCardinality.Exact(0),
            coverage = RelationshipSearchCoverage.complete(),
        ),
        returnedCount = 0,
        returnedBefore = 0,
        visitedCandidateCount = 0,
        candidateVisitLimit = 16_384,
        nextHandle = null,
    )
}
