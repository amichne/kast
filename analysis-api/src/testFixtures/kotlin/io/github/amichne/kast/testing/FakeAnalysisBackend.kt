package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.continuation.ContinuationAccessFailure
import io.github.amichne.kast.api.continuation.ContinuationConsumeResult
import io.github.amichne.kast.api.continuation.ContinuationIssueResult
import io.github.amichne.kast.api.continuation.ContinuationLeaseResult
import io.github.amichne.kast.api.continuation.ContinuationOwnedState
import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.continuation.ContinuationStateDisposer
import io.github.amichne.kast.api.continuation.ContinuationStateProjection
import io.github.amichne.kast.api.continuation.ContinuationStateTransition
import io.github.amichne.kast.api.continuation.ContinuationTokenIssuer
import io.github.amichne.kast.api.continuation.ContinuationTransition
import io.github.amichne.kast.api.continuation.ServerHeldContinuationStore
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.DeclarationScope
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionItem
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorScope
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.protocol.WorkspaceInventoryStaleException
import io.github.amichne.kast.api.contract.OutlineSymbol
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.ParameterInfo
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.contract.result.TypeHierarchyTruncation
import io.github.amichne.kast.api.contract.result.TypeHierarchyTruncationReason
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.writeText

class FakeAnalysisBackend private constructor(
    private val workspaceRoot: Path,
    private val symbol: Symbol,
    private val symbolAnchors: List<Location>,
    private val referenceLocations: List<Location>,
    private val diagnosticsByFile: Map<String, List<Diagnostic>>,
    private val typeHierarchyRootSymbol: Symbol,
    private val typeHierarchyAnchors: List<Location>,
    private val typeHierarchySupertypeSymbol: Symbol,
    private val typeHierarchySubtypeSymbol: Symbol,
    private val limits: ServerLimits,
    private val backendName: String,
) : CloseableAnalysisBackend {
    private val referenceContinuations =
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
    private val diagnosticContinuations =
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
    private val workspaceSnapshots =
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
    private val workspacePages =
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
    private val availableFiles: MutableSet<String> = buildSet {
        addAll(symbolAnchors.map(Location::filePath))
        addAll(diagnosticsByFile.keys)
        addAll(typeHierarchyAnchors.map(Location::filePath))
        Files.walk(workspaceRoot).use { paths ->
            paths.filter(Files::isRegularFile).map(Path::toString).forEach(::add)
        }
    }.toMutableSet()

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = backendName,
        backendVersion = "0.1.0-test",
        workspaceRoot = workspaceRoot.toString(),
        readCapabilities = setOf(
            ReadCapability.RESOLVE_SYMBOL,
            ReadCapability.FIND_REFERENCES,
            ReadCapability.CALL_HIERARCHY,
            ReadCapability.TYPE_HIERARCHY,
            ReadCapability.SEMANTIC_INSERTION_POINT,
            ReadCapability.DIAGNOSTICS,
            ReadCapability.FILE_OUTLINE,
            ReadCapability.WORKSPACE_SYMBOL_SEARCH,
            ReadCapability.WORKSPACE_SEARCH,
            ReadCapability.WORKSPACE_FILES,
            ReadCapability.IMPLEMENTATIONS,
            ReadCapability.CODE_ACTIONS,
            ReadCapability.COMPLETIONS,
        ),
        mutationCapabilities = setOf(
            MutationCapability.RENAME,
            MutationCapability.APPLY_EDITS,
            MutationCapability.FILE_OPERATIONS,
            MutationCapability.OPTIMIZE_IMPORTS,
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

    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
        requireKnownFile(query.position.filePath.value)
        return when {
            hasMatchingAnchor(symbolAnchors, query.position) -> SymbolResult(symbol.withDeclarationScopeIfRequested(query))
            hasMatchingAnchor(typeHierarchyAnchors, query.position) -> SymbolResult(typeHierarchyRootSymbol.withDeclarationScopeIfRequested(query))
            else -> throw missingSymbol(query.position)
        }
    }

    override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult {
        requireAnchor(query.position)

        val declaration = if (query.includeDeclaration) symbol else null
        val allReferences = referenceLocations
            .distinctBy { location -> Triple(location.filePath, location.startOffset, location.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }, { it.endOffset }))
        val identity = fakeReferenceIdentity(query)
        val pageToken = query.pageToken
        if (pageToken == null) {
            val page = fakeReferencePage(
                allReferences = allReferences,
                pageStart = 0,
                maxResults = query.maxResults.value,
            )
            val nextPageToken = if (page.hasMore) {
                issueReferenceContinuation(identity, FakeReferenceContinuation(page.nextOffset)).value
            } else {
                null
            }
            return page.toResult(declaration, allReferences.size, nextPageToken)
        }

        return when (val consumed = referenceContinuations.consume(pageToken, identity) { continuation ->
            val page = fakeReferencePage(
                allReferences = allReferences,
                pageStart = continuation.offset,
                maxResults = query.maxResults.value,
            )
            if (page.hasMore) {
                continuation.offset = page.nextOffset
                ContinuationTransition.Reissue(page, identity)
            } else {
                ContinuationTransition.Complete(page)
            }
        }) {
            is ContinuationConsumeResult.Completed ->
                consumed.output.toResult(declaration, allReferences.size, nextPageToken = null)
            is ContinuationConsumeResult.Reissued ->
                consumed.output.toResult(declaration, allReferences.size, consumed.token.value)
            is ContinuationConsumeResult.Rejected -> throwReferenceContinuationFailure(consumed.failure)
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

    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        val filePaths = query.filePaths.value
        filePaths.forEach { requireKnownFile(it.value) }
        val identity = FakeDiagnosticIdentity(
            filePaths = filePaths.map { path -> path.value },
            maxResults = query.maxResults.value,
        )
        val pageToken = query.pageToken
        if (pageToken != null) {
            return when (val consumed = diagnosticContinuations.consume(pageToken, identity) { continuation ->
                val page = continuation.page(query.maxResults.value)
                if (page.hasMore) {
                    continuation.offset = page.nextOffset
                    ContinuationTransition.Reissue(page, identity)
                } else {
                    ContinuationTransition.Complete(page)
                }
            }) {
                is ContinuationConsumeResult.Completed -> consumed.output.toResult(nextPageToken = null)
                is ContinuationConsumeResult.Reissued -> consumed.output.toResult(consumed.token.value)
                is ContinuationConsumeResult.Rejected -> throwDiagnosticContinuationFailure(consumed.failure)
            }
        }

        val diagnostics = filePaths
            .flatMap { filePath -> diagnosticsByFile[filePath.value].orEmpty() }
            .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }))
        val fileStatuses = filePaths.map(FileAnalysisStatus::analyzed)
        val page = FakeDiagnosticPage(
            diagnostics = diagnostics,
            fileStatuses = fileStatuses,
            pageOffset = 0,
            maxResults = query.maxResults.value,
        )
        val nextPageToken = if (page.hasMore) {
            issueDiagnosticContinuation(
                identity,
                FakeDiagnosticContinuation(
                    diagnostics = diagnostics,
                    fileStatuses = fileStatuses,
                    offset = page.nextOffset,
                ),
            ).value
        } else {
            null
        }
        return page.toResult(nextPageToken)
    }

    override suspend fun rename(query: ParsedRenameQuery): RenameResult {
        requireAnchor(query.position)
        val edits = symbolAnchors
            .map { anchor ->
                TextEdit(
                    filePath = anchor.filePath,
                    startOffset = anchor.startOffset,
                    endOffset = anchor.endOffset,
                    newText = query.newName.value,
                )
            }
            .distinctBy { edit -> Triple(edit.filePath, edit.startOffset, edit.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        val affectedFiles = edits.map(TextEdit::filePath).distinct()

        return RenameResult.of(
            edits = edits,
            fileHashes = affectedFiles.map { filePath ->
                FileHash(
                    filePath = filePath,
                    hash = FileHashing.sha256(Files.readString(Path.of(filePath))),
                )
            },
        )
    }

    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult {
        query.filePaths.value.map { it.value }.forEach(::requireKnownFile)
        return ImportOptimizeResult(
            edits = emptyList(),
            fileHashes = emptyList(),
            affectedFiles = emptyList(),
        )
    }

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult =
        applyEditsToFixtureFiles(query.toWire())

    private fun applyEditsToFixtureFiles(query: ApplyEditsQuery): ApplyEditsResult {
        if (query.edits.isEmpty() && query.fileOperations.isEmpty()) {
            throw ValidationException("At least one text edit or file operation is required")
        }

        val affectedFiles = mutableListOf<String>()
        val createdFiles = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()

        EditPlanValidator.validateFileOperations(query.fileOperations).forEach { operation ->
            try {
                when (operation) {
                    is ValidatedFileOperation.CreateFile -> {
                        val path = Path.of(operation.filePath)
                        if (Files.exists(path)) {
                            throw ConflictException(
                                message = "File already exists",
                                details = mapOf("filePath" to operation.filePath),
                            )
                        }
                        path.parent?.let(Files::createDirectories)
                        Files.writeString(path, operation.content)
                        createdFiles += operation.filePath
                        availableFiles.add(operation.filePath)
                    }

                    is ValidatedFileOperation.DeleteFile -> {
                        ensureFixtureFileHash(operation.filePath, operation.expectedHash)
                        Files.delete(Path.of(operation.filePath))
                        deletedFiles += operation.filePath
                        availableFiles.remove(operation.filePath)
                    }
                }
                affectedFiles += operation.filePath
            } catch (exception: Exception) {
                throw PartialApplyException(
                    details = mutationFailureDetails(
                        failedFile = operation.filePath,
                        affectedFiles = affectedFiles,
                        createdFiles = createdFiles,
                        deletedFiles = deletedFiles,
                        exception = exception,
                    ),
                )
            }
        }

        val validatedEdits = if (query.edits.isEmpty()) {
            emptyList()
        } else {
            EditPlanValidator.validate(query.edits, query.fileHashes)
        }
        val currentContents = validatedEdits.associateWith { plan ->
            readFixtureFile(plan.filePath)
        }

        currentContents.forEach { (plan, content) ->
            val currentHash = FileHashing.sha256(content)
            if (currentHash != plan.expectedHash) {
                throw ConflictException(
                    message = "The file changed after the edit plan was created",
                    details = mapOf(
                        "filePath" to plan.filePath,
                        "expectedHash" to plan.expectedHash,
                        "actualHash" to currentHash,
                    ),
                )
            }
        }

        val appliedEdits = mutableListOf<TextEdit>()
        validatedEdits.forEach { plan ->
            val updatedContent = EditPlanValidator.applyEditsToContent(
                currentContents.getValue(plan),
                plan.edits,
            )
            try {
                writeFixtureFileAtomically(plan.filePath, updatedContent)
                affectedFiles += plan.filePath
                appliedEdits += plan.edits.sortedBy { it.startOffset }
            } catch (exception: Exception) {
                throw PartialApplyException(
                    details = mutationFailureDetails(
                        failedFile = plan.filePath,
                        affectedFiles = affectedFiles,
                        createdFiles = createdFiles,
                        deletedFiles = deletedFiles,
                        exception = exception,
                    ),
                )
            }
        }

        return ApplyEditsResult(
            applied = appliedEdits,
            affectedFiles = affectedFiles.distinct().sorted(),
            createdFiles = createdFiles.sorted(),
            deletedFiles = deletedFiles.sorted(),
        )
    }

    private fun readFixtureFile(filePath: String): String {
        val path = Path.of(filePath)
        if (!Files.exists(path)) {
            throw NotFoundException(
                message = "File does not exist",
                details = mapOf("filePath" to filePath),
            )
        }
        return Files.readString(path)
    }

    private fun ensureFixtureFileHash(filePath: String, expectedHash: String) {
        val content = readFixtureFile(filePath)
        val currentHash = FileHashing.sha256(content)
        if (currentHash != expectedHash) {
            throw ConflictException(
                message = "The file changed after the delete plan was created",
                details = mapOf(
                    "filePath" to filePath,
                    "expectedHash" to expectedHash,
                    "actualHash" to currentHash,
                ),
            )
        }
    }

    private fun writeFixtureFileAtomically(filePath: String, content: String) {
        val target = Path.of(filePath)
        val parent = target.parent
        parent?.let(Files::createDirectories)
        val tempFile = Files.createTempFile(parent, ".kast-", ".tmp")
        try {
            Files.writeString(tempFile, content)
            Files.move(tempFile, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (exception: Exception) {
            Files.deleteIfExists(tempFile)
            throw exception
        }
    }

    private fun mutationFailureDetails(
        failedFile: String,
        affectedFiles: List<String>,
        createdFiles: List<String>,
        deletedFiles: List<String>,
        exception: Exception,
    ): Map<String, String> = mapOf(
        "failedFile" to failedFile,
        "appliedFiles" to affectedFiles.joinToString(","),
        "createdFiles" to createdFiles.joinToString(","),
        "deletedFiles" to deletedFiles.joinToString(","),
        "reason" to (exception.message ?: exception::class.java.simpleName),
    )

    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult {
        if (query.filePaths.isEmpty()) return RefreshResult.full()
        val fileStatuses = query.filePaths.map { filePath ->
            if (filePath.value in availableFiles && Files.exists(filePath.toJavaPath())) {
                SemanticAdmissionStatus.admitted(filePath)
            } else {
                SemanticAdmissionStatus.removed(filePath)
            }
        }
        return RefreshResult.focused(
            fileStatuses = fileStatuses,
            attemptCount = 1,
            elapsedMillis = 0,
        )
    }

    override suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult {
        requireKnownFile(query.filePath.value)
        val allSymbols = buildList {
            add(symbol)
            add(typeHierarchyRootSymbol)
            add(typeHierarchySupertypeSymbol)
            add(typeHierarchySubtypeSymbol)
        }
        val fileSymbols = allSymbols
            .filter { it.location.filePath == query.filePath.value }
            .map { OutlineSymbol(symbol = it) }
        return FileOutlineResult(symbols = fileSymbols)
    }

    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult {
        val allSymbols = buildList {
            add(symbol)
            add(typeHierarchyRootSymbol)
            add(typeHierarchySupertypeSymbol)
            add(typeHierarchySubtypeSymbol)
        }
        val pattern = query.pattern.value
        val matcher: (String) -> Boolean = if (query.regex) {
            val regex = Regex(pattern);
            { name -> regex.containsMatchIn(name) }
        } else {
            { name -> name.contains(pattern, ignoreCase = true) }
        }
        val matched = allSymbols
            .filter { sym ->
                val simpleName = sym.fqName.substringAfterLast('.')
                matcher(simpleName) && (query.kind == null || sym.kind == query.kind)
            }
            .take(query.maxResults.value)
        return WorkspaceSymbolResult(symbols = matched)
    }

    override suspend fun workspaceSearch(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult {
        val regex = compileWorkspaceSearchRegex(query)
        val fileGlob = query.fileGlob?.value
        val matches = mutableListOf<SearchMatch>()
        var truncated = false

        outer@ for (filePath in availableFiles.filter { it.endsWith(".kt") }.sorted()) {
            if (fileGlob != null && !matchesFileGlob(filePath, fileGlob)) continue
            val content = runCatching { Files.readString(Path.of(filePath)) }.getOrElse { continue }
            for ((lineIndex, line) in content.lineSequence().withIndex()) {
                for (column in searchColumns(line, query, regex)) {
                    if (matches.size >= query.maxResults.value) {
                        truncated = true
                        break@outer
                    }
                    matches += SearchMatch(
                        filePath = filePath,
                        lineNumber = lineIndex + 1,
                        columnNumber = column + 1,
                        preview = line.trimEnd(),
                    )
                }
            }
        }

        return WorkspaceSearchResult(matches = matches, truncated = truncated)
    }

    override suspend fun workspaceFiles(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult {
        val suppliedSnapshotToken = query.snapshotToken
        val snapshot = if (suppliedSnapshotToken == null) {
            val inventory = workspaceInventory(query.kindDomain)
            val token = issueWorkspaceSnapshot(query.kindDomain, inventory)
            FakeWorkspaceSnapshot(token, inventory)
        } else {
            FakeWorkspaceSnapshot(
                token = suppliedSnapshotToken,
                inventory = leaseWorkspaceSnapshot(suppliedSnapshotToken, query.kindDomain),
            )
        }

        val requestedModule = query.moduleName?.value
        if (!query.includeFiles) {
            return workspaceFilesResult(
                snapshot = snapshot,
                modules = workspaceMetadataModules(snapshot.inventory, requestedModule),
            )
        }
        if (requestedModule != null && requestedModule != FAKE_MODULE_NAME && query.pageToken == null) {
            return workspaceFilesResult(snapshot, emptyList())
        }

        val pageSize = query.maxFilesPerModule?.value ?: snapshot.inventory.files.size.coerceAtLeast(1)
        val identity = FakeWorkspacePageIdentity(
            snapshotToken = snapshot.token,
            kindDomain = query.kindDomain,
            moduleName = requestedModule,
            pageSize = pageSize,
        )
        val pageToken = query.pageToken
        val page = if (pageToken == null) {
            firstWorkspacePage(snapshot.inventory, identity)
        } else {
            consumeWorkspacePage(pageToken, identity, snapshot.inventory)
        }
        return workspaceFilesResult(
            snapshot = snapshot,
            modules = listOf(workspaceModule(snapshot.inventory, page)),
        )
    }

    override suspend fun implementations(query: ParsedImplementationsQuery): ImplementationsResult {
        requireTypeHierarchyAnchor(query.position)
        return ImplementationsResult(
            declaration = typeHierarchySupertypeSymbol,
            implementations = listOf(typeHierarchySubtypeSymbol).take(query.maxResults.value),
            exhaustive = query.maxResults.value >= 1,
        )
    }

    override suspend fun codeActions(query: ParsedCodeActionsQuery): CodeActionsResult {
        requireKnownFile(query.position.filePath.value)
        return CodeActionsResult(actions = emptyList())
    }

    override suspend fun completions(query: ParsedCompletionsQuery): CompletionsResult {
        requireKnownFile(query.position.filePath.value)
        val kindFilter = query.kindFilter
        val items = listOf(
            CompletionItem(
                name = "greet",
                fqName = symbol.fqName,
                kind = symbol.kind,
                type = symbol.returnType ?: symbol.type,
                parameters = symbol.parameters,
                documentation = symbol.documentation,
            ),
        ).filter { item -> kindFilter == null || item.kind in kindFilter }
        val capped = items.take(query.maxResults.value)
        return CompletionsResult(
            items = capped,
            exhaustive = items.size <= capped.size,
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

    private fun workspaceInventory(kindDomain: WorkspaceFileKindDomain): FakeWorkspaceInventory =
        FakeWorkspaceInventory(
            files = availableFiles
                .asSequence()
                .filter { filePath -> kindDomain.admits(filePath) }
                .sorted()
                .toList(),
        )

    private fun issueWorkspaceSnapshot(
        kindDomain: WorkspaceFileKindDomain,
        inventory: FakeWorkspaceInventory,
    ): WorkspaceFileSnapshotToken = when (val issued = workspaceSnapshots.issue(
        query = FakeWorkspaceSnapshotIdentity(kindDomain),
        state = FakeWorkspaceSnapshotState(inventory),
    )) {
        is ContinuationIssueResult.Issued -> issued.token
        is ContinuationIssueResult.Rejected ->
            throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE)
    }

    private fun leaseWorkspaceSnapshot(
        token: WorkspaceFileSnapshotToken,
        kindDomain: WorkspaceFileKindDomain,
    ): FakeWorkspaceInventory = when (val leased = workspaceSnapshots.lease(
        token = token,
        query = FakeWorkspaceSnapshotIdentity(kindDomain),
        projection = ContinuationStateProjection { state ->
            val current = workspaceInventory(kindDomain)
            if (current != state.inventory) throw WorkspaceInventoryStaleException()
            state.inventory
        },
    )) {
        is ContinuationLeaseResult.Granted -> leased.output
        is ContinuationLeaseResult.Rejected ->
            throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE)
    }

    private fun firstWorkspacePage(
        inventory: FakeWorkspaceInventory,
        identity: FakeWorkspacePageIdentity,
    ): FakeWorkspacePage {
        val page = FakeWorkspacePage.from(inventory, offset = 0, pageSize = identity.pageSize)
        val nextPageToken = if (page.hasMore) {
            issueWorkspacePage(
                identity = identity,
                state = FakeWorkspacePageState(inventory, page.nextOffset),
            ).value
        } else {
            null
        }
        return page.copy(nextPageToken = nextPageToken)
    }

    private fun issueWorkspacePage(
        identity: FakeWorkspacePageIdentity,
        state: FakeWorkspacePageState,
    ): WorkspaceFilePageToken = when (val issued = workspacePages.issue(identity, state)) {
        is ContinuationIssueResult.Issued -> issued.token
        is ContinuationIssueResult.Rejected ->
            throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
    }

    private fun consumeWorkspacePage(
        token: WorkspaceFilePageToken,
        identity: FakeWorkspacePageIdentity,
        inventory: FakeWorkspaceInventory,
    ): FakeWorkspacePage = when (val consumed = workspacePages.consume(
        token = token,
        query = identity,
        transition = ContinuationStateTransition { state ->
            if (state.inventory != inventory) throw WorkspaceInventoryStaleException()
            val page = FakeWorkspacePage.from(state.inventory, state.nextOffset, identity.pageSize)
            if (page.hasMore) {
                state.nextOffset = page.nextOffset
                ContinuationTransition.Reissue(page, identity)
            } else {
                ContinuationTransition.Complete(page)
            }
        },
    )) {
        is ContinuationConsumeResult.Completed -> consumed.output
        is ContinuationConsumeResult.Reissued -> consumed.output.copy(nextPageToken = consumed.token.value)
        is ContinuationConsumeResult.Rejected ->
            throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
    }

    private fun workspaceMetadataModules(
        inventory: FakeWorkspaceInventory,
        requestedModule: String?,
    ): List<WorkspaceModule> = if (requestedModule == null || requestedModule == FAKE_MODULE_NAME) {
        listOf(workspaceModule(inventory, FakeWorkspacePage.empty()))
    } else {
        emptyList()
    }

    private fun workspaceModule(
        inventory: FakeWorkspaceInventory,
        page: FakeWorkspacePage,
    ): WorkspaceModule = WorkspaceModule(
        name = FAKE_MODULE_NAME,
        sourceRoots = listOf(workspaceRoot.resolve("src").toString()),
        contentRoots = listOf(workspaceRoot.toString()),
        dependencyModuleNames = emptyList(),
        files = page.files,
        nextPageToken = page.nextPageToken,
        filesTruncated = page.hasMore,
        fileCount = inventory.files.size,
    )

    private fun workspaceFilesResult(
        snapshot: FakeWorkspaceSnapshot,
        modules: List<WorkspaceModule>,
    ): WorkspaceFilesResult = WorkspaceFilesResult(
        modules = modules,
        snapshotToken = snapshot.token.value,
    )

    private fun requireAnchor(position: ParsedFilePosition) {
        requireKnownFile(position.filePath.value)
        if (!hasMatchingAnchor(symbolAnchors, position)) {
            throw missingSymbol(position)
        }
    }

    private fun requireTypeHierarchyAnchor(position: ParsedFilePosition) {
        requireKnownFile(position.filePath.value)
        if (!hasMatchingAnchor(typeHierarchyAnchors, position)) {
            throw missingSymbol(position)
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

    private fun hasMatchingAnchor(
        anchors: List<Location>,
        position: ParsedFilePosition,
    ): Boolean = anchors.any { anchor ->
        anchor.filePath == position.filePath.value &&
            position.offset.value in anchor.startOffset until anchor.endOffset
    }

    private fun missingSymbol(position: ParsedFilePosition): NotFoundException = NotFoundException(
        message = "No symbol was found at the requested offset",
        details = mapOf(
            "filePath" to position.filePath.value,
            "offset" to position.offset.value.toString(),
        ),
    )

    private fun Symbol.withDeclarationScopeIfRequested(query: ParsedSymbolQuery): Symbol {
        if (!query.includeDeclarationScope || declarationScope != null) {
            return this
        }
        val content = Files.readString(Path.of(location.filePath))
        val startOffset = lineStartOffsetForOffset(content, location.startOffset)
        val endOffset = lineEndOffsetForOffset(content, location.startOffset)
        val startLine = content.take(startOffset).count { it == '\n' } + 1
        val endLine = content.take(endOffset).count { it == '\n' } + 1
        return copy(
            declarationScope = DeclarationScope(
                startOffset = startOffset,
                endOffset = endOffset,
                startLine = startLine,
                endLine = endLine,
                sourceText = content.substring(startOffset, endOffset),
            ),
        )
    }

    private fun lineStartOffsetForOffset(content: String, offset: Int): Int =
        content.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { index ->
            if (index >= 0) index + 1 else 0
        }

    private fun lineEndOffsetForOffset(content: String, offset: Int): Int {
        val newline = content.indexOf('\n', offset)
        return if (newline >= 0) newline else content.length
    }

    private fun compileWorkspaceSearchRegex(query: ParsedWorkspaceSearchQuery): Regex? =
        if (query.regex) {
            Regex(
                query.pattern.value,
                if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
            )
        } else {
            null
        }

    private fun searchColumns(
        line: String,
        query: ParsedWorkspaceSearchQuery,
        regex: Regex?,
    ): Sequence<Int> = sequence {
        if (regex != null) {
            regex.findAll(line).forEach { match -> yield(match.range.first) }
            return@sequence
        }

        var searchFrom = 0
        while (true) {
            val occurrence = line.indexOf(
                query.pattern.value,
                startIndex = searchFrom,
                ignoreCase = !query.caseSensitive,
            )
            if (occurrence < 0) break
            yield(occurrence)
            searchFrom = occurrence + query.pattern.value.length.coerceAtLeast(1)
        }
    }

    private fun matchesFileGlob(filePath: String, fileGlob: String): Boolean {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$fileGlob")
        val path = Path.of(filePath)
        val relative = runCatching { workspaceRoot.relativize(path) }.getOrNull()
        return listOfNotNull(relative, relative?.fileName, path.fileName).any(matcher::matches)
    }

    private fun fakeReferenceIdentity(query: ParsedReferencesQuery): FakeReferenceIdentity = FakeReferenceIdentity(
        filePath = query.position.filePath.value,
        offset = query.position.offset.value,
        includeDeclaration = query.includeDeclaration,
        includeUsageSiteScope = query.includeUsageSiteScope,
        maxResults = query.maxResults.value,
    )

    private fun WorkspaceFileKindDomain.admits(filePath: String): Boolean = when (this) {
        WorkspaceFileKindDomain.SOURCE_ONLY -> filePath.endsWith(".kt")
        WorkspaceFileKindDomain.SCRIPT_ONLY -> filePath.endsWith(".kts")
        WorkspaceFileKindDomain.MIXED -> filePath.endsWith(".kt") || filePath.endsWith(".kts")
    }

    private data class FakeWorkspaceSnapshotIdentity(
        val kindDomain: WorkspaceFileKindDomain,
    )

    private data class FakeWorkspaceSnapshotState(
        val inventory: FakeWorkspaceInventory,
    ) : ContinuationOwnedState()

    private data class FakeWorkspaceSnapshot(
        val token: WorkspaceFileSnapshotToken,
        val inventory: FakeWorkspaceInventory,
    )

    private data class FakeWorkspaceInventory(
        val files: List<String>,
    ) : ContinuationProjection() {
        init {
            require(files == files.distinct().sorted()) {
                "Fake workspace inventory must be sorted and deduplicated"
            }
        }
    }

    private data class FakeWorkspacePageIdentity(
        val snapshotToken: WorkspaceFileSnapshotToken,
        val kindDomain: WorkspaceFileKindDomain,
        val moduleName: String?,
        val pageSize: Int,
    )

    private data class FakeWorkspacePageState(
        val inventory: FakeWorkspaceInventory,
        var nextOffset: Int,
    ) : ContinuationOwnedState()

    private data class FakeWorkspacePage(
        val files: List<String>,
        val nextOffset: Int,
        val hasMore: Boolean,
        val nextPageToken: String? = null,
    ) : ContinuationProjection() {
        companion object {
            fun empty(): FakeWorkspacePage = FakeWorkspacePage(
                files = emptyList(),
                nextOffset = 0,
                hasMore = false,
            )

            fun from(
                inventory: FakeWorkspaceInventory,
                offset: Int,
                pageSize: Int,
            ): FakeWorkspacePage {
                val files = inventory.files.drop(offset).take(pageSize)
                val nextOffset = Math.addExact(offset, files.size)
                return FakeWorkspacePage(
                    files = files,
                    nextOffset = nextOffset,
                    hasMore = nextOffset < inventory.files.size,
                )
            }
        }
    }

    private fun fakeReferencePage(
        allReferences: List<Location>,
        pageStart: Int,
        maxResults: Int,
    ): FakeReferencePage {
        val probeLimit = if (maxResults == Int.MAX_VALUE) maxResults else maxResults + 1
        val pageProbe = allReferences.drop(pageStart).take(probeLimit)
        val references = pageProbe.take(maxResults)
        return FakeReferencePage(
            references = references,
            nextOffset = Math.addExact(pageStart, references.size),
            hasMore = pageProbe.size > references.size,
        )
    }

    private fun issueReferenceContinuation(
        identity: FakeReferenceIdentity,
        continuation: FakeReferenceContinuation,
    ): ReferencePageToken = when (val issued = referenceContinuations.issue(identity, continuation)) {
        is ContinuationIssueResult.Issued -> issued.token
        is ContinuationIssueResult.Rejected -> throwReferenceContinuationFailure(issued.failure)
    }

    private fun throwReferenceContinuationFailure(failure: ContinuationAccessFailure): Nothing =
        if (failure == ContinuationAccessFailure.QueryMismatch) {
            throw ConflictException("Reference continuation token belongs to another query")
        } else {
            throw ConflictException("Unknown or consumed reference continuation token")
        }

    private fun issueDiagnosticContinuation(
        identity: FakeDiagnosticIdentity,
        continuation: FakeDiagnosticContinuation,
    ): DiagnosticPageToken = when (val issued = diagnosticContinuations.issue(identity, continuation)) {
        is ContinuationIssueResult.Issued -> issued.token
        is ContinuationIssueResult.Rejected -> throwDiagnosticContinuationFailure(issued.failure)
    }

    private fun throwDiagnosticContinuationFailure(failure: ContinuationAccessFailure): Nothing =
        if (failure == ContinuationAccessFailure.QueryMismatch) {
            throw ConflictException("Diagnostic continuation token belongs to another query")
        } else {
            throw ConflictException("Unknown or consumed diagnostic continuation token")
        }

    private data class FakeReferenceIdentity(
        val filePath: String,
        val offset: Int,
        val includeDeclaration: Boolean,
        val includeUsageSiteScope: Boolean,
        val maxResults: Int,
    )

    private data class FakeReferenceContinuation(
        var offset: Int,
    ) : ContinuationOwnedState()

    private data class FakeReferencePage(
        val references: List<Location>,
        val nextOffset: Int,
        val hasMore: Boolean,
    ) : ContinuationProjection() {
        fun toResult(
            declaration: Symbol?,
            totalCount: Int,
            nextPageToken: String?,
        ): ReferencesResult = ReferencesResult(
            declaration = declaration,
            references = references.map { location ->
                ReferenceOccurrence(
                    location = location,
                    containingSymbol = ContainingSymbolEvidence.TopLevel,
                )
            },
            cardinality = io.github.amichne.kast.api.contract.result.ResultCardinality.Exact(totalCount),
            page = if (hasMore) {
                PageInfo(
                    truncated = true,
                    nextPageToken = checkNotNull(nextPageToken),
                )
            } else {
                null
            },
        )
    }

    private data class FakeDiagnosticIdentity(
        val filePaths: List<String>,
        val maxResults: Int,
    )

    private data class FakeDiagnosticContinuation(
        val diagnostics: List<Diagnostic>,
        val fileStatuses: List<FileAnalysisStatus>,
        var offset: Int,
    ) : ContinuationOwnedState() {
        fun page(maxResults: Int): FakeDiagnosticPage = FakeDiagnosticPage(
            diagnostics = diagnostics,
            fileStatuses = fileStatuses,
            pageOffset = offset,
            maxResults = maxResults,
        )
    }

    private data class FakeDiagnosticPage(
        val diagnostics: List<Diagnostic>,
        val fileStatuses: List<FileAnalysisStatus>,
        val pageOffset: Int,
        val maxResults: Int,
    ) : ContinuationProjection() {
        val nextOffset: Int = Math.addExact(
            pageOffset,
            minOf(maxResults, diagnostics.size - pageOffset),
        )
        val hasMore: Boolean = nextOffset < diagnostics.size

        fun toResult(nextPageToken: String?): DiagnosticsResult = DiagnosticsResult.paged(
            diagnostics = diagnostics,
            fileStatuses = fileStatuses,
            pageOffset = pageOffset,
            maxResults = maxResults,
            nextPageToken = nextPageToken,
        )
    }

    companion object {
        private const val FAKE_MODULE_NAME = "fake-module"

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
            val typeFile = sourceDirectory.resolve("Types.kt")
            val typeContent = """
                package sample

                interface Greeter
                open class FriendlyGreeter : Greeter
                class LoudGreeter : FriendlyGreeter()
            """.trimIndent() + "\n"
            typeFile.writeText(typeContent)

            val declarationOffset = content.indexOf("greet")
            val referenceOffset = content.lastIndexOf("greet")
            val symbolLocation = referenceLocation(file.toString(), declarationOffset)
            val referenceLocation = referenceLocation(file.toString(), referenceOffset)
            val typeHierarchySupertypeLocation = declarationLocation(
                filePath = typeFile.toString(),
                token = "Greeter",
                content = typeContent,
                line = 3,
                column = 11,
            )
            val typeHierarchyRootLocation = declarationLocation(
                filePath = typeFile.toString(),
                token = "FriendlyGreeter",
                content = typeContent,
                line = 4,
                column = 12,
            )
            val typeHierarchySubtypeLocation = declarationLocation(
                filePath = typeFile.toString(),
                token = "LoudGreeter",
                content = typeContent,
                line = 5,
                column = 7,
            )
            val symbol = Symbol(
                fqName = "sample.greet",
                kind = SymbolKind.FUNCTION,
                location = symbolLocation,
                returnType = "String",
                parameters = listOf(
                    ParameterInfo(
                        name = "name",
                        type = "String",
                    ),
                ),
                documentation = "/** Greets the provided name. */",
                containingDeclaration = "sample",
            )
            val typeHierarchyRootSymbol = Symbol(
                fqName = "sample.FriendlyGreeter",
                kind = SymbolKind.CLASS,
                location = typeHierarchyRootLocation,
                containingDeclaration = "sample",
                supertypes = listOf("sample.Greeter"),
            )
            val typeHierarchySupertypeSymbol = Symbol(
                fqName = "sample.Greeter",
                kind = SymbolKind.INTERFACE,
                location = typeHierarchySupertypeLocation,
                containingDeclaration = "sample",
            )
            val typeHierarchySubtypeSymbol = Symbol(
                fqName = "sample.LoudGreeter",
                kind = SymbolKind.CLASS,
                location = typeHierarchySubtypeLocation,
                containingDeclaration = "sample",
                supertypes = listOf("sample.FriendlyGreeter"),
            )

            return FakeAnalysisBackend(
                workspaceRoot = workspaceRoot,
                symbol = symbol,
                symbolAnchors = listOf(symbolLocation, referenceLocation),
                referenceLocations = listOf(referenceLocation),
                diagnosticsByFile = emptyMap(),
                typeHierarchyRootSymbol = typeHierarchyRootSymbol,
                typeHierarchyAnchors = listOf(typeHierarchyRootLocation),
                typeHierarchySupertypeSymbol = typeHierarchySupertypeSymbol,
                typeHierarchySubtypeSymbol = typeHierarchySubtypeSymbol,
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
                returnType = "String",
                parameters = listOf(ParameterInfo(name = "name", type = "String")),
                documentation = "/** Contract fixture symbol. */",
                containingDeclaration = "sample",
            )
            val typeHierarchyRootSymbol = Symbol(
                fqName = fixture.typeHierarchyRootFqName,
                kind = SymbolKind.CLASS,
                location = fixture.typeHierarchyRootLocation,
                containingDeclaration = "sample",
                supertypes = fixture.typeHierarchyRootSupertypes,
            )
            val typeHierarchySupertypeSymbol = Symbol(
                fqName = "sample.Greeter",
                kind = SymbolKind.INTERFACE,
                location = fixture.typeHierarchySupertypeLocation,
                containingDeclaration = "sample",
            )
            val typeHierarchySubtypeSymbol = Symbol(
                fqName = "sample.LoudGreeter",
                kind = SymbolKind.CLASS,
                location = fixture.typeHierarchySubtypeLocation,
                containingDeclaration = "sample",
                supertypes = listOf(fixture.typeHierarchyRootFqName),
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
                typeHierarchyRootSymbol = typeHierarchyRootSymbol,
                typeHierarchyAnchors = listOf(fixture.typeHierarchyRootLocation),
                typeHierarchySupertypeSymbol = typeHierarchySupertypeSymbol,
                typeHierarchySubtypeSymbol = typeHierarchySubtypeSymbol,
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

        private fun declarationLocation(
            filePath: String,
            token: String,
            content: String,
            line: Int,
            column: Int,
        ): Location {
            val offset = content.indexOf(token)
            return Location(
                filePath = filePath,
                startOffset = offset,
                endOffset = offset + token.length,
                startLine = line,
                startColumn = column,
                preview = content.lineSequence().drop(line - 1).first().trimEnd(),
            )
        }

        private fun afterImportsOffset(content: String): Int {
            val importMatch = Regex("^import .*$", RegexOption.MULTILINE).findAll(content).lastOrNull()
            if (importMatch != null) {
                return offsetAfterLineBreak(content, importMatch.range.last + 1)
            }
            val packageMatch = Regex("^package .*$", RegexOption.MULTILINE).find(content)
            if (packageMatch != null) {
                return offsetAfterLineBreak(content, packageMatch.range.last + 1)
            }
            return 0
        }

        private fun offsetAfterLineBreak(
            content: String,
            offset: Int,
        ): Int {
            var cursor = offset
            if (content.getOrNull(cursor) == '\r') {
                cursor += 1
            }
            if (content.getOrNull(cursor) == '\n') {
                cursor += 1
            }
            return cursor
        }
    }
}
