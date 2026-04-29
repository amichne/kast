package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionItem
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.validation.LocalDiskEditApplier
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.contract.OutlineSymbol
import io.github.amichne.kast.api.contract.ParameterInfo
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.contract.result.TypeHierarchyTruncation
import io.github.amichne.kast.api.contract.result.TypeHierarchyTruncationReason
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import java.nio.file.Files
import java.nio.file.Path
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
) : AnalysisBackend {
    private val availableFiles: Set<String> = buildSet {
        addAll(symbolAnchors.map(Location::filePath))
        addAll(diagnosticsByFile.keys)
        addAll(typeHierarchyAnchors.map(Location::filePath))
    }

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

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult {
        requireKnownFile(query.position.filePath)
        return when {
            hasMatchingAnchor(symbolAnchors, query.position) -> SymbolResult(symbol)
            hasMatchingAnchor(typeHierarchyAnchors, query.position) -> SymbolResult(typeHierarchyRootSymbol)
            else -> throw missingSymbol(query.position)
        }
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

    override suspend fun typeHierarchy(query: TypeHierarchyQuery): TypeHierarchyResult {
        requireTypeHierarchyAnchor(query.position)
        val directChildren = when (query.direction) {
            TypeHierarchyDirection.SUPERTYPES -> listOf(typeHierarchySupertypeSymbol)
            TypeHierarchyDirection.SUBTYPES -> listOf(typeHierarchySubtypeSymbol)
            TypeHierarchyDirection.BOTH -> listOf(typeHierarchySupertypeSymbol, typeHierarchySubtypeSymbol)
        }
        val maxChildren = (query.maxResults - 1).coerceAtLeast(0)
        val children = if (query.depth == 0) {
            emptyList()
        } else {
            directChildren.take(maxChildren).map { childSymbol ->
                TypeHierarchyNode(
                    symbol = childSymbol,
                    children = emptyList(),
                )
            }
        }
        val truncated = query.depth > 0 && directChildren.size > children.size

        return TypeHierarchyResult(
            root = TypeHierarchyNode(
                symbol = typeHierarchyRootSymbol,
                truncation = if (truncated) {
                    TypeHierarchyTruncation(
                        reason = TypeHierarchyTruncationReason.MAX_RESULTS,
                        details = "Reached maxResults=${query.maxResults}",
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

    override suspend fun semanticInsertionPoint(query: SemanticInsertionQuery): SemanticInsertionResult {
        requireKnownFile(query.position.filePath)
        val content = Files.readString(Path.of(query.position.filePath))
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
            filePath = query.position.filePath,
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

    override suspend fun optimizeImports(query: ImportOptimizeQuery): ImportOptimizeResult {
        query.filePaths.forEach(::requireKnownFile)
        return ImportOptimizeResult(
            edits = emptyList(),
            fileHashes = emptyList(),
            affectedFiles = emptyList(),
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

    override suspend fun fileOutline(query: FileOutlineQuery): FileOutlineResult {
        requireKnownFile(query.filePath)
        val allSymbols = buildList {
            add(symbol)
            add(typeHierarchyRootSymbol)
            add(typeHierarchySupertypeSymbol)
            add(typeHierarchySubtypeSymbol)
        }
        val fileSymbols = allSymbols
            .filter { it.location.filePath == query.filePath }
            .map { OutlineSymbol(symbol = it) }
        return FileOutlineResult(symbols = fileSymbols)
    }

    override suspend fun workspaceSymbolSearch(query: WorkspaceSymbolQuery): WorkspaceSymbolResult {
        val allSymbols = buildList {
            add(symbol)
            add(typeHierarchyRootSymbol)
            add(typeHierarchySupertypeSymbol)
            add(typeHierarchySubtypeSymbol)
        }
        val pattern = query.pattern
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
            .take(query.maxResults)
        return WorkspaceSymbolResult(symbols = matched)
    }

    override suspend fun workspaceFiles(query: WorkspaceFilesQuery): WorkspaceFilesResult {
        val allFiles = availableFiles.filter { it.endsWith(".kt") }.sorted()
        val fileLimit = query.maxFilesPerModule ?: allFiles.size
        val files = if (query.includeFiles) {
            allFiles.take(fileLimit)
        } else {
            emptyList()
        }
        val module = WorkspaceModule(
            name = "fake-module",
            sourceRoots = listOf(workspaceRoot.resolve("src").toString()),
            dependencyModuleNames = emptyList(),
            files = files,
            filesTruncated = query.includeFiles && allFiles.size > files.size,
            fileCount = allFiles.size,
        )
        val modules = if (query.moduleName == null || query.moduleName == "fake-module") {
            listOf(module)
        } else {
            emptyList()
        }
        return WorkspaceFilesResult(modules = modules)
    }

    override suspend fun implementations(query: ImplementationsQuery): ImplementationsResult {
        requireTypeHierarchyAnchor(query.position)
        return ImplementationsResult(
            declaration = typeHierarchySupertypeSymbol,
            implementations = listOf(typeHierarchySubtypeSymbol).take(query.maxResults.coerceAtLeast(1)),
            exhaustive = query.maxResults >= 1,
        )
    }

    override suspend fun codeActions(query: CodeActionsQuery): CodeActionsResult {
        requireKnownFile(query.position.filePath)
        return CodeActionsResult(actions = emptyList())
    }

    override suspend fun completions(query: CompletionsQuery): CompletionsResult {
        requireKnownFile(query.position.filePath)
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
        val capped = items.take(query.maxResults.coerceAtLeast(1))
        return CompletionsResult(
            items = capped,
            exhaustive = items.size <= capped.size,
        )
    }

    private fun requireAnchor(position: FilePosition) {
        requireKnownFile(position.filePath)
        if (!hasMatchingAnchor(symbolAnchors, position)) {
            throw missingSymbol(position)
        }
    }

    private fun requireTypeHierarchyAnchor(position: FilePosition) {
        requireKnownFile(position.filePath)
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
        position: FilePosition,
    ): Boolean = anchors.any { anchor ->
        anchor.filePath == position.filePath &&
            position.offset in anchor.startOffset until anchor.endOffset
    }

    private fun missingSymbol(position: FilePosition): NotFoundException = NotFoundException(
        message = "No symbol was found at the requested offset",
        details = mapOf(
            "filePath" to position.filePath,
            "offset" to position.offset.toString(),
        ),
    )

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
