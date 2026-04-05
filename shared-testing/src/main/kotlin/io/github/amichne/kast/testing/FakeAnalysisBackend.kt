package io.github.amichne.kast.testing

import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CallHierarchyStats
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.Diagnostic
import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.RefreshResult
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class FakeAnalysisBackend private constructor(
    private val workspaceRoot: Path,
    private val symbol: Symbol,
    private val symbolAnchors: List<Location>,
    private val referenceLocations: List<Location>,
    private val diagnosticsByFile: Map<String, List<Diagnostic>>,
    private val limits: ServerLimits,
    private val backendName: String,
) : AnalysisBackend {
    private val availableFiles: Set<String> = buildSet {
        addAll(symbolAnchors.map(Location::filePath))
        addAll(diagnosticsByFile.keys)
    }

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = backendName,
        backendVersion = "0.1.0-test",
        workspaceRoot = workspaceRoot.toString(),
        readCapabilities = setOf(
            ReadCapability.RESOLVE_SYMBOL,
            ReadCapability.FIND_REFERENCES,
            ReadCapability.CALL_HIERARCHY,
            ReadCapability.DIAGNOSTICS,
        ),
        mutationCapabilities = setOf(
            MutationCapability.RENAME,
            MutationCapability.APPLY_EDITS,
            MutationCapability.REFRESH_WORKSPACE,
        ),
        limits = limits,
    )

    override suspend fun health(): HealthResponse {
        val capabilities = capabilities()
        return HealthResponse(
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
        )
    }

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult {
        requireAnchor(query.position)
        return SymbolResult(symbol)
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult {
        requireAnchor(query.position)

        val declaration = if (query.includeDeclaration) symbol else null
        return ReferencesResult(
            declaration = declaration,
            references = referenceLocations,
        )
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult {
        requireAnchor(query.position)
        val outgoingReference = referenceLocations.firstOrNull() ?: symbol.location
        val rootChildren = if (query.depth == 0) {
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

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult {
        query.filePaths.forEach(::requireKnownFile)
        return DiagnosticsResult(
            diagnostics = query.filePaths
                .flatMap { filePath -> diagnosticsByFile[filePath].orEmpty() }
                .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset })),
        )
    }

    override suspend fun rename(query: RenameQuery): RenameResult {
        requireAnchor(query.position)
        val edits = symbolAnchors
            .map { anchor ->
                TextEdit(
                    filePath = anchor.filePath,
                    startOffset = anchor.startOffset,
                    endOffset = anchor.endOffset,
                    newText = query.newName,
                )
            }
            .distinctBy { edit -> Triple(edit.filePath, edit.startOffset, edit.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        val affectedFiles = edits.map(TextEdit::filePath).distinct()

        return RenameResult(
            edits = edits,
            fileHashes = affectedFiles.map { filePath ->
                FileHash(
                    filePath = filePath,
                    hash = FileHashing.sha256(Files.readString(Path.of(filePath))),
                )
            },
            affectedFiles = affectedFiles,
        )
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult = LocalDiskEditApplier.apply(query)

    override suspend fun refresh(query: RefreshQuery): RefreshResult {
        val refreshedFiles = query.filePaths
            .ifEmpty { availableFiles.toList() }
            .sorted()
        return RefreshResult(
            refreshedFiles = refreshedFiles,
            removedFiles = emptyList(),
            fullRefresh = query.filePaths.isEmpty(),
        )
    }

    private fun requireAnchor(position: FilePosition) {
        requireKnownFile(position.filePath)
        val matchingAnchor = symbolAnchors.any { anchor ->
            anchor.filePath == position.filePath &&
                position.offset in anchor.startOffset until anchor.endOffset
        }
        if (!matchingAnchor) {
            throw NotFoundException(
                message = "No symbol was found at the requested offset",
                details = mapOf(
                    "filePath" to position.filePath,
                    "offset" to position.offset.toString(),
                ),
            )
        }
    }

    private fun requireKnownFile(filePath: String) {
        if (filePath !in availableFiles) {
            throw NotFoundException(
                message = "The fake backend only exposes its fixture files",
                details = mapOf("filePath" to filePath),
            )
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
        ): FakeAnalysisBackend {
            val sourceDirectory = workspaceRoot.resolve("src")
            Files.createDirectories(sourceDirectory)
            val file = sourceDirectory.resolve("Sample.kt")
            val content = """
                package sample

                fun greet() = "hi"

                fun use() = greet()
            """.trimIndent() + "\n"
            file.writeText(content)

            val declarationOffset = content.indexOf("greet")
            val referenceOffset = content.lastIndexOf("greet")
            val symbolLocation = referenceLocation(file.toString(), declarationOffset)
            val referenceLocation = referenceLocation(file.toString(), referenceOffset)
            val symbol = Symbol(
                fqName = "sample.greet",
                kind = SymbolKind.FUNCTION,
                location = symbolLocation,
                containingDeclaration = "sample",
            )

            return FakeAnalysisBackend(
                workspaceRoot = workspaceRoot,
                symbol = symbol,
                symbolAnchors = listOf(symbolLocation, referenceLocation),
                referenceLocations = listOf(referenceLocation),
                diagnosticsByFile = emptyMap(),
                limits = limits,
                backendName = backendName,
            )
        }

        fun contractFixture(
            fixture: AnalysisBackendContractFixture,
            limits: ServerLimits = ServerLimits(
                maxResults = 100,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 4,
            ),
            backendName: String = "fake",
        ): FakeAnalysisBackend {
            val symbol = Symbol(
                fqName = fixture.symbolFqName,
                kind = SymbolKind.FUNCTION,
                location = fixture.declarationLocation,
                containingDeclaration = "sample",
            )

            return FakeAnalysisBackend(
                workspaceRoot = fixture.workspaceRoot,
                symbol = symbol,
                symbolAnchors = listOf(
                    fixture.declarationLocation,
                    fixture.firstUsageLocation,
                    fixture.secondUsageLocation,
                ),
                referenceLocations = fixture.referenceLocations,
                diagnosticsByFile = mapOf(
                    fixture.brokenFile.toString() to listOf(
                        Diagnostic(
                            location = Location(
                                filePath = fixture.brokenFile.toString(),
                                startOffset = 0,
                                endOffset = 0,
                                startLine = 3,
                                startColumn = 1,
                                preview = fixture.brokenPreview,
                            ),
                            severity = DiagnosticSeverity.ERROR,
                            message = "The fake contract fixture reports a syntax error",
                            code = "FAKE_PARSE_ERROR",
                        ),
                    ),
                ),
                limits = limits,
                backendName = backendName,
            )
        }

        private fun referenceLocation(
            filePath: String,
            offset: Int,
        ): Location {
            val line = if (offset < 15) 2 else 4
            val column = if (offset < 15) 5 else 13
            return Location(
                filePath = filePath,
                startOffset = offset,
                endOffset = offset + "greet".length,
                startLine = line,
                startColumn = column,
                preview = "greet",
            )
        }
    }
}
