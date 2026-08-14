package io.github.amichne.kast.testing

import io.github.amichne.kast.api.continuation.*
import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.DigestSelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.validation.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText

class FakeAnalysisBackend private constructor(
    internal val workspaceRoot: Path,
    internal val symbol: Symbol,
    internal val symbolAnchors: List<Location>,
    internal val referenceLocations: List<Location>,
    internal val diagnosticsByFile: Map<String, List<Diagnostic>>,
    internal val typeHierarchyRootSymbol: Symbol,
    internal val typeHierarchyAnchors: List<Location>,
    internal val typeHierarchySupertypeSymbol: Symbol,
    internal val typeHierarchySubtypeSymbol: Symbol,
    internal val limits: ServerLimits,
    internal val backendName: String,
) : CloseableAnalysisBackend {
    internal val referenceContinuations =
        ServerHeldContinuationStore<
            ReferencePageToken,
            FakeReferenceIdentity,
            FakeReferenceContinuation,
            FakeReferencePage,
        >(
            capacity = limits.typedContinuationCapacity,
            timeToLive = limits.typedContinuationTtl,
            tokenIssuer = ContinuationTokenIssuer(ReferencePageToken::random),
            stateDisposer = ContinuationStateDisposer { },
        )
    internal val diagnosticContinuations =
        ServerHeldContinuationStore<
            DiagnosticPageToken,
            FakeDiagnosticIdentity,
            FakeDiagnosticContinuation,
            FakeDiagnosticPage,
        >(
            capacity = limits.typedContinuationCapacity,
            timeToLive = limits.typedContinuationTtl,
            tokenIssuer = ContinuationTokenIssuer(DiagnosticPageToken::random),
            stateDisposer = ContinuationStateDisposer { },
        )
    internal val workspaceSnapshots =
        ServerHeldContinuationStore<
            WorkspaceFileSnapshotToken,
            FakeWorkspaceSnapshotIdentity,
            FakeWorkspaceSnapshotState,
            FakeWorkspaceInventory,
        >(
            capacity = limits.typedContinuationCapacity,
            timeToLive = limits.typedContinuationTtl,
            tokenIssuer = ContinuationTokenIssuer(WorkspaceFileSnapshotToken::random),
            stateDisposer = ContinuationStateDisposer { },
        )
    internal val workspacePages =
        ServerHeldContinuationStore<
            WorkspaceFilePageToken,
            FakeWorkspacePageIdentity,
            FakeWorkspacePageState,
            FakeWorkspacePage,
        >(
            capacity = limits.typedContinuationCapacity,
            timeToLive = limits.typedContinuationTtl,
            tokenIssuer = ContinuationTokenIssuer(WorkspaceFilePageToken::random),
            stateDisposer = ContinuationStateDisposer { },
        )
    internal val availableFiles: MutableSet<String> = buildSet {
        addAll(symbolAnchors.map(Location::filePath))
        addAll(diagnosticsByFile.keys)
        addAll(typeHierarchyAnchors.map(Location::filePath))
        Files.walk(workspaceRoot).use { paths ->
            paths.filter(Files::isRegularFile).map(Path::toString).forEach(::add)
        }
    }.toMutableSet()
    override val selectorHandles: SelectorHandleAuthority =
        DigestSelectorHandleAuthority(
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize().toString(),
            backendName = backendName,
            backendVersion = "0.1.0-test",
            backendInstanceId = UUID.randomUUID().toString(),
            semanticGeneration = { 0L },
        )

    override suspend fun capabilities(): BackendCapabilities = capabilitiesResult()

    override suspend fun runtimeStatus(): RuntimeStatusResponse = RuntimeStatusResponse(
        state = RuntimeState.READY,
        backendName = backendName,
        backendVersion = "0.1.0-test",
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize().toString(),
        readiness = RuntimeReadiness.available(fakeEvidenceRevision()),
        referenceCoverageState = ReferenceCoverageState.COMPLETE,
    )

    override suspend fun health(): HealthResponse = healthResult()

    override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult =
        findReferencesResult(query)

    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult =
        diagnosticsResult(query)

    override suspend fun rename(query: ParsedRenameQuery): RenameResult = renameResult(query)

    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult =
        optimizeImportsResult(query)

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult =
        applyEditsResult(query)

    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult = refreshResult(query)

    override suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult =
        fileOutlineResult(query)

    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult =
        workspaceSymbolSearchResult(query)

    override suspend fun workspaceSearch(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult =
        workspaceSearchResult(query)

    override suspend fun workspaceFiles(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult =
        workspaceFilesResult(query)

    override suspend fun implementations(query: ParsedImplementationsQuery): ImplementationsResult =
        implementationsResult(query)

    override suspend fun codeActions(query: ParsedCodeActionsQuery): CodeActionsResult = codeActionsResult(query)

    override suspend fun completions(query: ParsedCompletionsQuery): CompletionsResult = completionsResult(query)

    override suspend fun semanticGraph(query: ParsedSemanticGraphQuery): SemanticGraphResult {
        val coverage = query.filePaths.map { filePath ->
            val absolute = filePath.value.toJavaPath()
            requireKnownFile(absolute.toString())
            val relative = SemanticGraphSourcePath.parse(workspaceRoot.relativize(absolute).toString())
            SemanticGraphFileCoverage(
                path = relative,
                contentHash = SemanticGraphSha256.parse(FileHashing.sha256(absolute.readText())),
                status = SemanticGraphFileStatus.REFRESHED,
            )
        }
        val removed = query.removedFilePaths.map { filePath ->
            SemanticGraphFileCoverage(
                path = SemanticGraphSourcePath.parse(workspaceRoot.relativize(filePath.value.toJavaPath()).toString()),
                contentHash = null,
                status = SemanticGraphFileStatus.REMOVED,
            )
        }
        val symbols = coverage.map { file ->
            SemanticGraphSymbol(
                canonicalKey = SemanticGraphSymbolKey.parse("file:${file.path.value}"),
                kind = SemanticGraphSymbolKind.FILE,
                name = NonBlankString(Path.of(file.path.value).fileName.toString()),
                path = file.path,
                startOffset = ByteOffset(0),
                endOffset = ByteOffset(Files.size(workspaceRoot.resolve(file.path.value)).toInt()),
                line = LineNumber(1),
            )
        }
        return SemanticGraphResult(
            generation = SemanticGraphGeneration(0),
            scopeFingerprint = SemanticGraphSha256.parse("0".repeat(64)),
            coverage = SemanticGraphCoverage(coverage + removed),
            symbolCount = NonNegativeInt(symbols.size),
            edgeOccurrenceCount = NonNegativeInt(0),
        )
    }

    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
        requireKnownFile(query.position.filePath.value)
        return when {
            hasMatchingAnchor(symbolAnchors, query.position) -> SymbolResult(symbol.withDeclarationScopeIfRequested(query))
            hasMatchingAnchor(typeHierarchyAnchors, query.position) -> SymbolResult(typeHierarchyRootSymbol.withDeclarationScopeIfRequested(query))
            else -> throw missingSymbol(query.position)
        }
    }

    override suspend fun callHierarchy(query: ParsedCallHierarchyQuery): CallHierarchyResult {
        requireAnchor(query.position)
        val outgoingReference = referenceLocations.firstOrNull() ?: symbol.location
        val rootChildren = if (query.depth.value == 0) {
            emptyList()
        } else if (query.direction == CallDirection.OUTGOING) {
            listOf(
                CallNode(
                    symbol = Symbol(
                        fqName = "sample.use",
                        kind = SymbolKind.FUNCTION,
                        location = outgoingReference,
                    ),
                    callSite = outgoingReference,
                    children = emptyList(),
                ),
            )
        } else {
            referenceLocations.mapIndexed { index, referenceLocation ->
                CallNode(
                    symbol = Symbol(
                        fqName = "sample.caller$index",
                        kind = SymbolKind.FUNCTION,
                        location = referenceLocation,
                    ),
                    callSite = referenceLocation,
                    children = emptyList(),
                )
            }
        }

        return CallHierarchyResult(
            root = CallNode(symbol = symbol, children = rootChildren),
            stats = CallHierarchyStats(
                totalNodes = 1 + rootChildren.size,
                totalEdges = rootChildren.size,
                truncatedNodes = 0,
                maxDepthReached = if (rootChildren.isEmpty()) 0 else 1,
                timeoutReached = false,
                maxTotalCallsReached = false,
                maxChildrenPerNodeReached = false,
                filesVisited = rootChildren.mapNotNull { child -> child.callSite?.filePath }.distinct().size.coerceAtLeast(1),
            ),
        )
    }

    override suspend fun typeHierarchy(query: ParsedTypeHierarchyQuery): TypeHierarchyResult {
        requireTypeHierarchyAnchor(query.position)
        val directChildren = when (query.direction) {
            TypeHierarchyDirection.SUPERTYPES -> listOf(typeHierarchySupertypeSymbol)
            TypeHierarchyDirection.SUBTYPES -> listOf(typeHierarchySubtypeSymbol)
            TypeHierarchyDirection.BOTH -> listOf(typeHierarchySupertypeSymbol, typeHierarchySubtypeSymbol)
        }
        val maxChildren = (query.maxResults.value - 1).coerceAtLeast(0)
        val children = if (query.depth.value == 0) {
            emptyList()
        } else {
            directChildren.take(maxChildren).map { childSymbol ->
                TypeHierarchyNode(
                    symbol = childSymbol,
                    children = emptyList(),
                )
            }
        }
        val truncated = query.depth.value > 0 && directChildren.size > children.size

        return TypeHierarchyResult(
            root = TypeHierarchyNode(
                symbol = typeHierarchyRootSymbol,
                truncation = if (truncated) {
                    TypeHierarchyTruncation(
                        reason = TypeHierarchyTruncationReason.MAX_RESULTS,
                        details = "Reached maxResults=${query.maxResults.value}",
                    )
                } else {
                    null
                },
                children = children,
            ),
            stats = TypeHierarchyStats(
                totalNodes = 1 + children.size,
                maxDepthReached = if (children.isEmpty()) 0 else 1,
                truncated = truncated,
            ),
        )
    }

    override suspend fun semanticInsertionPoint(query: ParsedSemanticInsertionQuery): SemanticInsertionResult {
        requireKnownFile(query.position.filePath.value)
        val content = Files.readString(Path.of(query.position.filePath.value))
        val insertionOffset = when (query.target) {
            SemanticInsertionTarget.CLASS_BODY_START -> content.indexOf('{')
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: throw missingSymbol(query.position)

            SemanticInsertionTarget.CLASS_BODY_END -> content.lastIndexOf('}')
                .takeIf { it >= 0 }
                ?: throw missingSymbol(query.position)

            SemanticInsertionTarget.FILE_TOP -> 0
            SemanticInsertionTarget.FILE_BOTTOM -> content.length
            SemanticInsertionTarget.AFTER_IMPORTS -> afterImportsOffset(content)
        }
        return SemanticInsertionResult(
            insertionOffset = insertionOffset,
            filePath = query.position.filePath.value,
        )
    }

    override fun close() {
        val failures = listOf(
            referenceContinuations,
            diagnosticContinuations,
            workspaceSnapshots,
            workspacePages,
        ).mapNotNull { store -> runCatching(store::close).exceptionOrNull() }
        failures.firstOrNull()?.let { firstFailure ->
            failures.drop(1).forEach(firstFailure::addSuppressed)
            throw firstFailure
        }
    }

    companion object {
        fun sample(
            workspaceRoot: Path,
            limits: ServerLimits = ServerLimits(
                maxResults = 100,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 4,
            ),
            backendName: String = "fake",
        ): FakeAnalysisBackend = fromSpec(sampleFakeAnalysisBackendSpec(workspaceRoot, limits, backendName))

        fun contractFixture(
            fixture: AnalysisBackendContractFixture,
            limits: ServerLimits = ServerLimits(
                maxResults = 100,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 4,
            ),
            backendName: String = "fake",
        ): FakeAnalysisBackend = fromSpec(contractFakeAnalysisBackendSpec(fixture, limits, backendName))

        private fun fromSpec(spec: FakeAnalysisBackendSpec): FakeAnalysisBackend = FakeAnalysisBackend(
            workspaceRoot = spec.workspaceRoot,
            symbol = spec.symbol,
            symbolAnchors = spec.symbolAnchors,
            referenceLocations = spec.referenceLocations,
            diagnosticsByFile = spec.diagnosticsByFile,
            typeHierarchyRootSymbol = spec.typeHierarchyRootSymbol,
            typeHierarchyAnchors = spec.typeHierarchyAnchors,
            typeHierarchySupertypeSymbol = spec.typeHierarchySupertypeSymbol,
            typeHierarchySubtypeSymbol = spec.typeHierarchySubtypeSymbol,
            limits = spec.limits,
            backendName = spec.backendName,
        )
    }
}

/**
 * Proof transition: `Long -> EvidenceRevision` for deterministic fake-runtime evidence.
 *
 * Establishes the positive revision required by every available fake lane. Raw extraction is not
 * permitted beyond the protocol serializer exercised by consuming contract tests.
 */
private fun fakeEvidenceRevision(): EvidenceRevision = when (val resolution = EvidenceRevision.parse(1)) {
    is EvidenceRevisionResolution.Resolved -> resolution.revision
    is EvidenceRevisionResolution.Rejected -> error("The fixed fake evidence revision must be positive")
}
