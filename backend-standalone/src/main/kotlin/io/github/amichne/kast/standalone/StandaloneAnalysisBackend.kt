package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.FileOutlineQuery
import io.github.amichne.kast.api.FileOutlineResult
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.ImportOptimizeQuery
import io.github.amichne.kast.api.ImportOptimizeResult
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.RefreshResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.api.SearchScope
import io.github.amichne.kast.api.SearchScopeKind
import io.github.amichne.kast.api.SemanticInsertionQuery
import io.github.amichne.kast.api.SemanticInsertionResult
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.SymbolVisibility
import io.github.amichne.kast.api.TextEdit
import io.github.amichne.kast.api.TypeHierarchyQuery
import io.github.amichne.kast.api.TypeHierarchyResult
import io.github.amichne.kast.api.WorkspaceSymbolQuery
import io.github.amichne.kast.api.WorkspaceSymbolResult
import io.github.amichne.kast.shared.analysis.FileOutlineBuilder
import io.github.amichne.kast.shared.analysis.ImportAnalysis
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.SymbolSearchMatcher
import io.github.amichne.kast.shared.analysis.callHierarchyDeclaration
import io.github.amichne.kast.shared.analysis.declarationEdit
import io.github.amichne.kast.shared.analysis.referenceSearchIdentifier
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.supertypeNames
import io.github.amichne.kast.shared.analysis.targetFqNameAndPackage
import io.github.amichne.kast.shared.analysis.toApiDiagnostics
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.standalone.analysis.CandidateFileResolver
import io.github.amichne.kast.standalone.analysis.CandidateSearchResult
import io.github.amichne.kast.standalone.hierarchy.CallHierarchyTraversal
import io.github.amichne.kast.standalone.hierarchy.TypeHierarchyTraversal
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetrySpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

@OptIn(KaExperimentalApi::class)
internal class StandaloneAnalysisBackend internal constructor(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
    private val telemetry: StandaloneTelemetry,
) : AnalysisBackend {
    constructor(
        workspaceRoot: Path,
        limits: ServerLimits,
        session: StandaloneAnalysisSession,
    ) : this(
        workspaceRoot = workspaceRoot,
        limits = limits,
        session = session,
        telemetry = StandaloneTelemetry.fromEnvironment(workspaceRoot),
    )

    private val readDispatcher = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests)
    private val callHierarchyTraversal = CallHierarchyTraversal(
        workspaceRoot = workspaceRoot,
        limits = limits,
        session = session,
        telemetry = telemetry,
    )
    private val typeHierarchyTraversal = TypeHierarchyTraversal(session = session)
    private val candidateFileResolver = CandidateFileResolver(session = session, telemetry = telemetry)

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = "standalone",
        backendVersion = readBackendVersion(),
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

    override suspend fun runtimeStatus(): RuntimeStatusResponse {
        val capabilities = capabilities()
        val warnings = session.workspaceDiagnostics
        val isIndexing = !session.isEnrichmentComplete() || !session.isInitialSourceIndexReady()
        val state = if (isIndexing) RuntimeState.INDEXING else RuntimeState.READY
        val statusMessage = if (isIndexing) {
            "Standalone analysis session is indexing"
        } else {
            "Standalone analysis session is initialized"
        }
        val moduleGraph = session.dependentModuleGraph
        return RuntimeStatusResponse(
            state = state,
            healthy = true,
            active = true,
            indexing = isIndexing,
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
            message = if (warnings.isEmpty()) {
                statusMessage
            } else {
                "$statusMessage with warnings: ${warnings.joinToString(separator = " ")}"
            },
            warnings = warnings,
            sourceModuleNames = moduleGraph.keys.map { it.value }.sorted(),
            dependentModuleNamesBySourceModuleName = moduleGraph.entries.associate { (module, dependents) ->
                module.value to dependents.map { it.value }.sorted()
            },
        )
    }

    override suspend fun health(): HealthResponse {
        val capabilities = capabilities()
        return HealthResponse(
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
        )
    }

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.resolveSymbol",
                attributes = mapOf(
                    "kast.symbolResolve.filePath" to query.position.filePath,
                    "kast.symbolResolve.offset" to query.position.offset,
                ),
            ) {
                val file = session.findKtFile(query.position.filePath)
                val target = resolveTarget(file, query.position.offset)
                SymbolResult(
                    analyze(file) {
                        target.toSymbolModel(
                            containingDeclaration = null,
                            supertypes = supertypeNames(target),
                        )
                    },
                )
            }
        }
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.REFERENCES,
                name = "kast.findReferences",
                attributes = mapOf(
                    "kast.references.filePath" to query.position.filePath,
                    "kast.references.offset" to query.position.offset,
                ),
            ) { span ->
                val file = session.findKtFile(query.position.filePath)
                val target = resolveTarget(file, query.position.offset)
                val (candidateFiles, searchScope) = resolveCandidateFilesForReferences(target, span)
                span.setAttribute("kast.references.candidateFileCount", candidateFiles.size)
                span.setAttribute("kast.references.searchScope", searchScope.scope.name)

                val references = candidateFiles
                    .parallelMapFlat { candidateFile -> candidateFile.findReferenceLocations(target) }
                    .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
                span.setAttribute("kast.references.resultCount", references.size)

                ReferencesResult(
                    declaration = if (query.includeDeclaration) analyze(file) { target.toSymbolModel(containingDeclaration = null) } else null,
                    references = references,
                    searchScope = searchScope,
                )
            }
        }
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult = withContext(readDispatcher) {
        session.withReadAccess {
            callHierarchyTraversal.build(query)
        }
    }

    override suspend fun typeHierarchy(query: TypeHierarchyQuery): TypeHierarchyResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.typeHierarchy",
                attributes = mapOf(
                    "kast.typeHierarchy.filePath" to query.position.filePath,
                    "kast.typeHierarchy.offset" to query.position.offset,
                ),
            ) {
                typeHierarchyTraversal.build(query)
            }
        }
    }

    override suspend fun semanticInsertionPoint(
        query: SemanticInsertionQuery,
    ): SemanticInsertionResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.semanticInsertionPoint",
                attributes = mapOf("kast.insertionPoint.filePath" to query.position.filePath),
            ) {
                val file = session.findKtFile(query.position.filePath)
                SemanticInsertionPointResolver.resolve(file, query)
            }
        }
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.diagnostics",
                attributes = mapOf("kast.diagnostics.fileCount" to query.filePaths.size),
            ) {
                val diagnostics = query.filePaths
                    .sorted()
                    .flatMap { filePath ->
                        val file = session.findKtFile(filePath)
                        analyze(file) {
                            file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                        }.flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
                    }
                    .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" }))

                DiagnosticsResult(diagnostics = diagnostics)
            }
        }
    }

    override suspend fun rename(query: RenameQuery): RenameResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.RENAME,
                name = "kast.rename",
                attributes = mapOf(
                    "kast.rename.filePath" to query.position.filePath,
                    "kast.rename.newName" to query.newName,
                ),
            ) { renameSpan ->
                val file = traceRenamePhase(
                    phaseName = "findKtFile",
                    attributes = mapOf("kast.rename.filePath" to query.position.filePath),
                ) {
                    session.findKtFile(query.position.filePath)
                }
                val target = traceRenamePhase(
                    phaseName = "resolveTarget",
                    attributes = mapOf("kast.rename.offset" to query.position.offset),
                ) {
                    resolveTarget(file, query.position.offset)
                }
                val searchIdentifier = target.referenceSearchIdentifier()
                val candidateSearch = traceRenamePhase(
                    phaseName = "candidateReferenceFiles",
                    attributes = mapOf("kast.rename.identifier" to (searchIdentifier ?: "<fallback>")),
                ) {
                    candidateFileResolver.resolve(target)
                }
                val candidateFiles = candidateSearch.files
                val searchScope = candidateSearch.scope
                renameSpan.setAttribute("kast.rename.candidateFileCount", candidateFiles.size)
                renameSpan.addEvent(
                    name = "candidate-files",
                    attributes = mapOf(
                        "count" to candidateFiles.size,
                        "identifier" to (searchIdentifier ?: "<fallback>"),
                        "files" to candidateFiles.joinToString(separator = "|") { candidateFile ->
                            candidateFile.virtualFile?.path ?: candidateFile.name
                        },
                    ),
                    verboseOnly = true,
                )

                val edits = traceRenamePhase("collectReferenceEdits") {
                    // A future import-aware rename pass can append import edits here once qualified-reference tracking is added.
                    (listOf(target.declarationEdit(query.newName)) + candidateFiles
                        .parallelMapFlat { candidateFile ->
                            candidateFile.referenceEdits(target, query.newName, searchIdentifier)
                        })
                        .distinctBy { edit -> Triple(edit.filePath, edit.startOffset, edit.endOffset) }
                        .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
                }
                renameSpan.setAttribute("kast.rename.editCount", edits.size)
                val fileHashes = traceRenamePhase("currentFileHashes") {
                    currentFileHashes(edits.map(TextEdit::filePath))
                }

                RenameResult(
                    edits = edits,
                    fileHashes = fileHashes,
                    affectedFiles = fileHashes.map(FileHash::filePath),
                    searchScope = searchScope,
                )
            }
        }
    }

    override suspend fun optimizeImports(query: ImportOptimizeQuery): ImportOptimizeResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.optimizeImports",
                attributes = mapOf("kast.imports.fileCount" to query.filePaths.size),
            ) {
                val edits = query.filePaths
                    .distinct()
                    .sorted()
                    .flatMap { filePath ->
                        ImportAnalysis.optimizeImportEdits(session.findKtFile(filePath))
                    }
                    .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
                val affectedFiles = edits.map(TextEdit::filePath).distinct()
                ImportOptimizeResult(
                    edits = edits,
                    fileHashes = currentFileHashes(affectedFiles),
                    affectedFiles = affectedFiles,
                )
            }
        }
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult {
        return telemetry.inSpan(
            scope = StandaloneTelemetryScope.SESSION_LIFECYCLE,
            name = "kast.applyEdits",
        ) {
            val result = LocalDiskEditApplier.apply(query)
            if (result.createdFiles.isNotEmpty() || result.deletedFiles.isNotEmpty()) {
                session.refreshWorkspace()
            } else {
                session.refreshFiles(result.affectedFiles.toSet())
            }
            result
        }
    }

    override suspend fun refresh(query: RefreshQuery): RefreshResult {
        return telemetry.inSpan(
            scope = StandaloneTelemetryScope.SESSION_LIFECYCLE,
            name = "kast.refresh",
            attributes = mapOf("kast.refresh.fileCount" to query.filePaths.size),
        ) {
            if (query.filePaths.isEmpty()) {
                session.refreshWorkspace(invalidateCaches = true)
            } else {
                session.refreshFiles(query.filePaths.toSet())
            }
        }
    }

    /**
     * Resolve candidate files for a reference search. When the cached symbol reference index is
     * complete, use it to narrow candidates; otherwise fall back to the standard resolver.
     */
    private fun resolveCandidateFilesForReferences(
        target: PsiElement,
        span: StandaloneTelemetrySpan,
    ): CandidateSearchResult {
        if (session.isReferenceIndexReady()) {
            val fqNameAndPkg = target.targetFqNameAndPackage()
            if (fqNameAndPkg != null) {
                val (fqName, _) = fqNameAndPkg
                val cachedRefs = session.sqliteStore.referencesToSymbol(fqName.value)
                if (cachedRefs.isNotEmpty()) {
                    val cachedPaths = cachedRefs.mapTo(mutableSetOf()) { it.sourcePath }
                    val ktFiles = cachedPaths.mapNotNull { path ->
                        runCatching { session.findKtFile(path) }.getOrNull()
                    }
                    span.setAttribute("kast.references.cacheHit", true)
                    span.setAttribute("kast.references.cachedPathCount", cachedPaths.size)
                    return CandidateSearchResult(
                        files = ktFiles,
                        scope = SearchScope(
                            visibility = io.github.amichne.kast.api.SymbolVisibility.PUBLIC,
                            scope = SearchScopeKind.DEPENDENT_MODULES,
                            exhaustive = true,
                            candidateFileCount = ktFiles.size,
                            searchedFileCount = ktFiles.size,
                        ),
                    )
                }
            }
        }
        span.setAttribute("kast.references.cacheHit", false)
        return candidateFileResolver.resolve(target)
    }

    private fun KtFile.findReferenceLocations(target: PsiElement): List<io.github.amichne.kast.api.Location> {
        val references = mutableListOf<io.github.amichne.kast.api.Location>()

        // The standalone Analysis API session does not register the ReferencesSearch extension point,
        // so resolve references directly across the loaded PSI files.
        accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val resolved = reference.resolve()
                        if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                            references += reference.element.toKastLocation(
                                com.intellij.openapi.util.TextRange(
                                    reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                    reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                                )
                            )
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return references
    }

    private fun KtFile.referenceEdits(
        target: PsiElement,
        newName: String,
        searchIdentifier: String?,
    ): List<TextEdit> {
        if (searchIdentifier != null) {
            return referenceEditsAtIdentifierOccurrences(target, newName, searchIdentifier)
        }

        val edits = mutableListOf<TextEdit>()

        accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val resolved = reference.resolve()
                        if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                            val elementStart = reference.element.textRange.startOffset
                            edits += TextEdit(
                                filePath = reference.element.resolvedFilePath().value,
                                startOffset = elementStart + reference.rangeInElement.startOffset,
                                endOffset = elementStart + reference.rangeInElement.endOffset,
                                newText = newName,
                            )
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return edits
    }

    private fun KtFile.referenceEditsAtIdentifierOccurrences(
        target: PsiElement,
        newName: String,
        searchIdentifier: String,
    ): List<TextEdit> = renameReferenceCandidateElements(searchIdentifier)
        .flatMap { element ->
            element.references.mapNotNull { reference ->
                val resolved = reference.resolve()
                if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                    val elementStart = reference.element.textRange.startOffset
                    TextEdit(
                        filePath = reference.element.resolvedFilePath().value,
                        startOffset = elementStart + reference.rangeInElement.startOffset,
                        endOffset = elementStart + reference.rangeInElement.endOffset,
                        newText = newName,
                    )
                } else {
                    null
                }
            }
        }

    private fun KtFile.renameReferenceCandidateElements(searchIdentifier: String): List<PsiElement> {
        val candidates = linkedSetOf<PsiElement>()
        text.identifierOccurrenceOffsets(searchIdentifier).forEach { occurrenceOffset ->
            val leaf = findElementAt(occurrenceOffset) ?: return@forEach
            generateSequence(leaf as PsiElement?) { element -> element.parent }
                .firstOrNull { element ->
                    element.references.isNotEmpty() &&
                        element.textRange.startOffset <= occurrenceOffset &&
                        element.textRange.endOffset >= occurrenceOffset + searchIdentifier.length
                }
                ?.let(candidates::add)
        }
        return candidates.toList()
    }

    private fun currentFileHashes(filePaths: Collection<String>): List<FileHash> = LocalDiskEditApplier.currentHashes(filePaths)

    private inline fun <T> traceRenamePhase(
        phaseName: String,
        attributes: Map<String, Any?> = emptyMap(),
        action: (StandaloneTelemetrySpan) -> T,
    ): T = telemetry.inSpan(
        scope = StandaloneTelemetryScope.RENAME,
        name = "kast.rename.$phaseName",
        attributes = attributes,
        verboseOnly = true,
        block = action,
    )

    override suspend fun fileOutline(query: FileOutlineQuery): FileOutlineResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.FILE_OUTLINE,
                name = "kast.fileOutline",
                attributes = mapOf("kast.fileOutline.filePath" to query.filePath),
            ) {
                val file = session.findKtFile(query.filePath)
                FileOutlineResult(symbols = FileOutlineBuilder.build(file))
            }
        }
    }

    override suspend fun workspaceSymbolSearch(query: WorkspaceSymbolQuery): WorkspaceSymbolResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.WORKSPACE_SYMBOL_SEARCH,
                name = "kast.workspaceSymbolSearch",
                attributes = mapOf(
                    "kast.workspaceSymbol.pattern" to query.pattern,
                    "kast.workspaceSymbol.regex" to query.regex,
                    "kast.workspaceSymbol.kind" to (query.kind?.name ?: "ALL"),
                ),
            ) { span ->
                val matcher = SymbolSearchMatcher.create(query.pattern, query.regex)
                val files = session.allKtFiles()
                span.setAttribute("kast.workspaceSymbol.fileCount", files.size)

                val symbols = mutableListOf<io.github.amichne.kast.api.Symbol>()
                for (file in files) {
                    file.accept(object : PsiRecursiveElementWalkingVisitor() {
                        override fun visitElement(element: PsiElement) {
                            if (symbols.size >= query.maxResults) {
                                stopWalking()
                                return
                            }
                            if (element is org.jetbrains.kotlin.psi.KtNamedDeclaration &&
                                element !is org.jetbrains.kotlin.psi.KtParameter &&
                                isWorkspaceSymbolDeclaration(element)
                            ) {
                                val name = element.name
                                if (name != null && matcher.matches(name)) {
                                    val symbol = element.toSymbolModel(containingDeclaration = null)
                                    if (query.kind == null || symbol.kind == query.kind) {
                                        symbols += symbol
                                    }
                                }
                            }
                            super.visitElement(element)
                        }
                    })
                    if (symbols.size >= query.maxResults) break
                }
                span.setAttribute("kast.workspaceSymbol.resultCount", symbols.size)

                WorkspaceSymbolResult(symbols = symbols)
            }
        }
    }

    private fun isWorkspaceSymbolDeclaration(element: PsiElement): Boolean = when (element) {
        is org.jetbrains.kotlin.psi.KtClassOrObject,
        is org.jetbrains.kotlin.psi.KtNamedFunction,
        is org.jetbrains.kotlin.psi.KtProperty,
        -> true
        else -> false
    }

    companion object {
        private fun readBackendVersion(): String =
            StandaloneAnalysisBackend::class.java
                .getResource("/kast-backend-version.txt")
                ?.readText()?.trim()
                ?: "unknown"
    }
}

/**
 * Parallel `flatMap` over a list using Java parallel streams.
 * Safe to call while the caller holds [StandaloneAnalysisSession.withReadAccess] —
 * the parent thread's read lock prevents any writer from acquiring the write lock,
 * so fork-join pool threads read PSI safely without their own lock acquisition.
 */
private inline fun <T, R> List<T>.parallelMapFlat(crossinline transform: (T) -> List<R>): List<R> =
    if (size <= 1) {
        flatMap(transform)
    } else {
        parallelStream()
            .flatMap { element -> transform(element).stream() }
            .collect(java.util.stream.Collectors.toList())
    }
