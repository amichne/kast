package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionItem
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.validation.LocalDiskEditApplier
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.shared.analysis.FileOutlineBuilder
import io.github.amichne.kast.shared.analysis.ImportAnalysis
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.SymbolSearchMatcher
import io.github.amichne.kast.shared.analysis.declarationEdit
import io.github.amichne.kast.shared.analysis.referenceSearchIdentifier
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.supertypeNames
import io.github.amichne.kast.shared.analysis.targetFqNameAndPackage
import io.github.amichne.kast.shared.analysis.toApiDiagnostics
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.typeHierarchyDeclaration
import io.github.amichne.kast.shared.analysis.usageSiteDeclarationScope
import io.github.amichne.kast.standalone.analysis.CandidateFileResolver
import io.github.amichne.kast.standalone.analysis.CandidateSearchResult
import io.github.amichne.kast.standalone.hierarchy.CallHierarchyTraversal
import io.github.amichne.kast.standalone.hierarchy.TypeHierarchyTraversal
import io.github.amichne.kast.standalone.hierarchy.namedTypeDeclarations
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetrySpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool
import java.util.stream.Collectors

@OptIn(KaExperimentalApi::class)
internal class StandaloneAnalysisBackend internal constructor(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
    private val telemetry: StandaloneTelemetry,
) : AnalysisBackend, AutoCloseable {
    constructor(
        workspaceRoot: Path,
        limits: ServerLimits,
        session: StandaloneAnalysisSession,
    ) : this(
        workspaceRoot = workspaceRoot,
        limits = limits,
        session = session,
        telemetry = StandaloneTelemetry.fromConfig(workspaceRoot),
    )

    private val readDispatcher = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests)

    /**
     * Dedicated [ForkJoinPool] for parallel file scanning. Bounded to
     * [ServerLimits.maxConcurrentRequests] threads, named with the `kast-parallel-` prefix
     * so they are identifiable in JVM thread dumps and heap profiles.
     *
     * Using a dedicated pool instead of [ForkJoinPool.commonPool] ensures that long-running
     * PSI walks cannot starve library code (e.g. coroutines, Compose) that also relies on
     * the common pool.
     */
    private val parallelPool = ForkJoinPool(
        limits.maxConcurrentRequests,
        ForkJoinPool.ForkJoinWorkerThreadFactory { pool ->
            ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool).also {
                it.name = "kast-parallel-${it.poolIndex}"
            }
        },
        null,
        false,
    )

    override fun close() {
        parallelPool.shutdown()
    }
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
            referenceIndexReady = session.isReferenceIndexReady(),
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

    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.resolveSymbol",
                attributes = mapOf(
                    "kast.symbolResolve.filePath" to query.position.filePath.value,
                    "kast.symbolResolve.offset" to query.position.offset.value,
                ),
            ) {
                val file = session.findKtFile(query.position.filePath.value)
                val target = resolveTarget(file, query.position.offset.value)
                SymbolResult(
                    analyze(file) {
                        target.toSymbolModel(
                            containingDeclaration = null,
                            supertypes = supertypeNames(target),
                            includeDeclarationScope = query.includeDeclarationScope,
                            includeDocumentation = query.includeDocumentation,
                        )
                    },
                )
            }
        }
    }

    override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.REFERENCES,
                name = "kast.findReferences",
                attributes = mapOf(
                    "kast.references.filePath" to query.position.filePath.value,
                    "kast.references.offset" to query.position.offset.value,
                ),
            ) { span ->
                val file = session.findKtFile(query.position.filePath.value)
                val target = resolveTarget(file, query.position.offset.value)
                val (candidateFiles, searchScope) = resolveCandidateFilesForReferences(target, span)
                span.setAttribute("kast.references.candidateFileCount", candidateFiles.size)
                span.setAttribute("kast.references.searchScope", searchScope.scope.name)

                val skippedFiles = java.util.concurrent.ConcurrentLinkedQueue<String>()
                val references = candidateFiles
                    .parallelMapFlat { candidateFile ->
                        val fileResult = candidateFile.findReferenceLocations(
                            target = target,
                            includeUsageSiteScope = query.includeUsageSiteScope,
                            budgetMillis = limits.perFileScanBudgetMillis,
                        )
                        if (fileResult.timedOut) {
                            skippedFiles.add(candidateFile.virtualFile?.path ?: candidateFile.name)
                        }
                        fileResult.locations
                    }
                    .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
                span.setAttribute("kast.references.resultCount", references.size)
                span.setAttribute("kast.references.skippedFileCount", skippedFiles.size)
                if (skippedFiles.isNotEmpty()) {
                    span.addEvent(
                        name = "file-scan-timeout",
                        attributes = mapOf(
                            "count" to skippedFiles.size,
                            "files" to skippedFiles.joinToString("|"),
                        ),
                    )
                }

                ReferencesResult(
                    declaration = if (query.includeDeclaration) analyze(file) { target.toSymbolModel(containingDeclaration = null) } else null,
                    references = references,
                    searchScope = searchScope,
                )
            }
        }
    }

    override suspend fun callHierarchy(query: ParsedCallHierarchyQuery): CallHierarchyResult = withContext(readDispatcher) {
        session.withReadAccess {
            callHierarchyTraversal.build(query)
        }
    }

    override suspend fun typeHierarchy(query: ParsedTypeHierarchyQuery): TypeHierarchyResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.typeHierarchy",
                attributes = mapOf(
                    "kast.typeHierarchy.filePath" to query.position.filePath.value,
                    "kast.typeHierarchy.offset" to query.position.offset.value,
                ),
            ) {
                typeHierarchyTraversal.build(query)
            }
        }
    }

    override suspend fun implementations(query: ParsedImplementationsQuery): ImplementationsResult = withContext(readDispatcher) {
        session.withReadAccess {
            val file = session.findKtFile(query.position.filePath.value)
            val resolvedTarget = resolveTarget(file, query.position.offset.value)
            val declaration = resolvedTarget.typeHierarchyDeclaration() ?: resolvedTarget
            val declarationSymbol = analyze(file) {
                declaration.toSymbolModel(
                    containingDeclaration = null,
                    supertypes = supertypeNames(declaration),
                )
            }
            val targetFqName = declarationSymbol.fqName
            if (session.isReferenceIndexReady() && targetFqName != null) {
                // Fast path: O(k) transitive expansion via declaration index
                val discoveredFqNames = mutableSetOf(targetFqName)
                val discoveredRows = mutableListOf<DeclarationRow>()
                var frontier = setOf(targetFqName)
                while (frontier.isNotEmpty()) {
                    val newRows = frontier
                        .flatMap { fqn -> session.sqliteStore.declarationsWithSupertype(fqn) }
                        .filter { it.fqName !in discoveredFqNames }
                    discoveredRows += newRows
                    val newFrontier = newRows.map { it.fqName }.toSet()
                    discoveredFqNames += newFrontier
                    frontier = newFrontier
                }
                val subtypeFqNames = discoveredRows.map { it.fqName }.toSet()
                val relevantFilePaths = discoveredRows.map { it.filePath }.toSet()
                val relevantKtFiles = relevantFilePaths.mapNotNull { path ->
                    try { session.findKtFile(path) } catch (_: Exception) { null }
                }
                val implementations = relevantKtFiles
                    .flatMap { ktFile -> ktFile.namedTypeDeclarations() }
                    .filter { type ->
                        val fqName = type.fqName?.asString() ?: return@filter false
                        fqName in subtypeFqNames && isConcreteType(type)
                    }
                    .map { type ->
                        analyze(type.containingKtFile) {
                            type.toSymbolModel(
                                containingDeclaration = null,
                                supertypes = supertypeNames(type),
                            )
                        }
                    }
                    .sortedWith(compareBy({ it.fqName }, { it.location.filePath }, { it.location.startOffset }))
                val capped = implementations.take(query.maxResults.value)
                ImplementationsResult(
                    declaration = declarationSymbol,
                    implementations = capped,
                    exhaustive = implementations.size <= capped.size,
                )
            } else {
                // Slow path: scan all KtFiles (fallback when index not yet ready)
                val allTypes = session.allKtFiles().flatMap(KtFile::namedTypeDeclarations)
                val directSupertypesByType = allTypes.associateWith { type ->
                    analyze(type.containingKtFile) { supertypeNames(type).orEmpty() }
                }
                val discovered = linkedSetOf(declarationSymbol.fqName)
                var changed = true
                while (changed) {
                    changed = false
                    for ((type, supertypes) in directSupertypesByType) {
                        if (type.fqName?.asString() in discovered) continue
                        if (supertypes.any(discovered::contains)) {
                            val fqName = type.fqName?.asString() ?: continue
                            if (discovered.add(fqName)) changed = true
                        }
                    }
                }
                val implementations = allTypes
                    .filter { type ->
                        val fqName = type.fqName?.asString() ?: return@filter false
                        fqName != declarationSymbol.fqName &&
                            fqName in discovered &&
                            isConcreteType(type)
                    }
                    .map { type ->
                        analyze(type.containingKtFile) {
                            type.toSymbolModel(
                                containingDeclaration = null,
                                supertypes = supertypeNames(type),
                            )
                        }
                    }
                    .sortedWith(compareBy({ it.fqName }, { it.location.filePath }, { it.location.startOffset }))
                val capped = implementations.take(query.maxResults.value)
                ImplementationsResult(
                    declaration = declarationSymbol,
                    implementations = capped,
                    exhaustive = implementations.size <= capped.size,
                )
            }
        }
    }

    override suspend fun codeActions(query: ParsedCodeActionsQuery): CodeActionsResult = withContext(readDispatcher) {
        session.withReadAccess {
            session.findKtFile(query.position.filePath.value)
            CodeActionsResult(actions = emptyList())
        }
    }

    override suspend fun completions(query: ParsedCompletionsQuery): CompletionsResult = withContext(readDispatcher) {
        session.withReadAccess {
            val file = session.findKtFile(query.position.filePath.value)
            val kindFilter = query.kindFilter
            val symbols = mutableListOf<CompletionItem>()
            file.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is KtNamedDeclaration &&
                        element !is KtParameter &&
                        element.name != null &&
                        element.textOffset <= query.position.offset.value
                    ) {
                        val symbol = analyze(file) {
                            element.toSymbolModel(
                                containingDeclaration = null,
                                includeDocumentation = true,
                            )
                        }
                        if (kindFilter == null || symbol.kind in kindFilter) {
                            symbols += CompletionItem(
                                name = element.name ?: symbol.fqName.substringAfterLast('.'),
                                fqName = symbol.fqName,
                                kind = symbol.kind,
                                type = symbol.type ?: symbol.returnType,
                                parameters = symbol.parameters,
                                documentation = symbol.documentation,
                            )
                        }
                    }
                    super.visitElement(element)
                }
            })
            val deduped = symbols
                .distinctBy { Triple(it.fqName, it.kind, it.name) }
                .sortedWith(compareBy({ it.name }, { it.fqName }))
            val capped = deduped.take(query.maxResults.value)
            CompletionsResult(
                items = capped,
                exhaustive = deduped.size <= capped.size,
            )
        }
    }

    override suspend fun semanticInsertionPoint(
        query: ParsedSemanticInsertionQuery,
    ): SemanticInsertionResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.semanticInsertionPoint",
                attributes = mapOf("kast.insertionPoint.filePath" to query.position.filePath.value),
            ) {
                val file = session.findKtFile(query.position.filePath.value)
                SemanticInsertionPointResolver.resolve(file, query)
            }
        }
    }

    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.diagnostics",
                attributes = mapOf("kast.diagnostics.fileCount" to query.filePaths.value.size),
            ) {
                val diagnostics = query.filePaths.value
                    .map { it.value }
                    .sorted()
                    .flatMap { filePath ->
                        runCatching {
                            val file = session.findKtFile(filePath)
                            analyze(file) {
                                file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                                    .flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
                            }
                        }.getOrElse { ex ->
                            listOf(
                                Diagnostic(
                                    location = Location(
                                        filePath = filePath,
                                        startOffset = 0,
                                        endOffset = 0,
                                        startLine = 0,
                                        startColumn = 0,
                                        preview = "",
                                    ),
                                    severity = DiagnosticSeverity.ERROR,
                                    message = ex.message ?: ex.toString(),
                                    code = "ANALYSIS_FAILURE",
                                ),
                            )
                        }
                    }
                    .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" }))

                DiagnosticsResult(diagnostics = diagnostics)
            }
        }
    }

    override suspend fun rename(query: ParsedRenameQuery): RenameResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.RENAME,
                name = "kast.rename",
                attributes = mapOf(
                    "kast.rename.filePath" to query.position.filePath.value,
                    "kast.rename.newName" to query.newName.value,
                ),
            ) { renameSpan ->
                val file = traceRenamePhase(
                    phaseName = "findKtFile",
                    attributes = mapOf("kast.rename.filePath" to query.position.filePath.value),
                ) {
                    session.findKtFile(query.position.filePath.value)
                }
                val target = traceRenamePhase(
                    phaseName = "resolveTarget",
                    attributes = mapOf("kast.rename.offset" to query.position.offset.value),
                ) {
                    resolveTarget(file, query.position.offset.value)
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
                    val referenceEdits = (listOf(target.declarationEdit(query.newName.value)) + candidateFiles
                        .parallelMapFlat { candidateFile ->
                            candidateFile.referenceEdits(target, query.newName.value, searchIdentifier)
                        })

                    val importEdits = run {
                        val oldFqn = target.targetFqNameAndPackage()?.first?.value
                        if (oldFqn != null && oldFqn.isNotBlank()) {
                            val lastDot = oldFqn.lastIndexOf('.')
                            val newFqn = if (lastDot < 0) query.newName.value else "${oldFqn.substring(0, lastDot)}.${query.newName.value}"
                            candidateFiles.flatMap { candidateFile ->
                                candidateFile.importDirectives.mapNotNull { directive ->
                                    ImportAnalysis.renameImportFqnEdit(directive, oldFqn, newFqn)
                                }
                            }
                        } else {
                            emptyList()
                        }
                    }

                    (referenceEdits + importEdits)
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

    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SYMBOL_RESOLVE,
                name = "kast.optimizeImports",
                attributes = mapOf("kast.imports.fileCount" to query.filePaths.value.size),
            ) {
                val edits = query.filePaths.value
                    .map { it.value }
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

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        return telemetry.inSpan(
            scope = StandaloneTelemetryScope.SESSION_LIFECYCLE,
            name = "kast.applyEdits",
        ) {
            val result = LocalDiskEditApplier.apply(query.toWire())
            if (result.createdFiles.isNotEmpty() || result.deletedFiles.isNotEmpty()) {
                session.refreshWorkspace()
            } else {
                session.refreshFiles(result.affectedFiles.toSet())
            }
            result
        }
    }

    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult {
        return telemetry.inSpan(
            scope = StandaloneTelemetryScope.SESSION_LIFECYCLE,
            name = "kast.refresh",
            attributes = mapOf("kast.refresh.fileCount" to query.filePaths.size),
        ) {
            val filePaths = query.filePaths.map { it.value }
            if (filePaths.isEmpty()) {
                session.refreshWorkspace(invalidateCaches = true)
            } else {
                session.refreshTargetedPaths(filePaths.toSet())
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
                            visibility = SymbolVisibility.PUBLIC,
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

    private fun KtFile.findReferenceLocations(
        target: PsiElement,
        includeUsageSiteScope: Boolean,
        budgetMillis: Long,
    ): FileReferenceResult {
        val references = mutableListOf<Location>()
        val deadlineNano = System.nanoTime() + budgetMillis * 1_000_000L
        var elementCount = 0
        var timedOut = false

        // The standalone Analysis API session does not register the ReferencesSearch extension point,
        // so resolve references directly across the loaded PSI files.
        // Every 100 elements we check the per-file scan budget; if the deadline has
        // passed we stop the walk early and mark the file as timed-out.
        accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (++elementCount % 100 == 0 && System.nanoTime() >= deadlineNano) {
                        timedOut = true
                        stopWalking()
                        return
                    }
                    element.references.forEach { reference ->
                        val resolved = reference.resolve()
                        if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                            val location = reference.element.toKastLocation(
                                com.intellij.openapi.util.TextRange(
                                    reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                    reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                                ),
                            )
                            references += if (includeUsageSiteScope) {
                                location.copy(usageSiteScope = reference.element.usageSiteDeclarationScope())
                            } else {
                                location
                            }
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return FileReferenceResult(
            locations = if (timedOut) emptyList() else references,
            timedOut = timedOut,
        )
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

    override suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.FILE_OUTLINE,
                name = "kast.fileOutline",
                attributes = mapOf("kast.fileOutline.filePath" to query.filePath.value),
            ) {
                val file = session.findKtFile(query.filePath.value)
                FileOutlineResult(symbols = FileOutlineBuilder.build(file))
            }
        }
    }

    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult = withContext(readDispatcher) {
        session.withReadAccess {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.WORKSPACE_SYMBOL_SEARCH,
                name = "kast.workspaceSymbolSearch",
                attributes = mapOf(
                    "kast.workspaceSymbol.pattern" to query.pattern.value,
                    "kast.workspaceSymbol.regex" to query.regex,
                    "kast.workspaceSymbol.kind" to (query.kind?.name ?: "ALL"),
                ),
            ) { span ->
                val matcher = SymbolSearchMatcher.create(query.pattern.value, query.regex)
                val files = session.allKtFiles()
                span.setAttribute("kast.workspaceSymbol.fileCount", files.size)

                val symbols = mutableListOf<Symbol>()
                for (file in files) {
                    file.accept(object : PsiRecursiveElementWalkingVisitor() {
                        override fun visitElement(element: PsiElement) {
                            if (symbols.size >= query.maxResults.value) {
                                stopWalking()
                                return
                            }
                            if (element is org.jetbrains.kotlin.psi.KtNamedDeclaration &&
                                element !is org.jetbrains.kotlin.psi.KtParameter &&
                                isWorkspaceSymbolDeclaration(element)
                            ) {
                                val name = element.name
                                if (name != null && matcher.matches(name)) {
                                    val symbol = element.toSymbolModel(
                                        containingDeclaration = null,
                                        includeDeclarationScope = query.includeDeclarationScope,
                                    )
                                    if (query.kind == null || symbol.kind == query.kind) {
                                        symbols += symbol
                                    }
                                }
                            }
                            super.visitElement(element)
                        }
                    })
                    if (symbols.size >= query.maxResults.value) break
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

    override suspend fun workspaceFiles(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult = withContext(readDispatcher) {
        val fileLimit = query.maxFilesPerModule?.value ?: limits.maxResults
        telemetry.inSpan(
            scope = StandaloneTelemetryScope.WORKSPACE_FILES,
            name = "kast.workspaceFiles",
            attributes = mapOf(
                "kast.workspaceFiles.moduleName" to query.moduleName?.value,
                "kast.workspaceFiles.includeFiles" to query.includeFiles,
                "kast.workspaceFiles.maxFilesPerModule" to fileLimit,
            ),
        ) { span ->
            session.withReadAccess {
                val specs = session.moduleSpecs()
                val filtered = if (query.moduleName?.value != null) {
                    specs.filter { it.name.value == query.moduleName?.value }
                } else {
                    specs
                }
                val modules = filtered.map { spec ->
                    val listing = collectWorkspaceFiles(
                        sourceRoots = spec.sourceRoots,
                        includeFiles = query.includeFiles,
                        maxFiles = fileLimit,
                    )
                    WorkspaceModule(
                        name = spec.name.value,
                        sourceRoots = spec.sourceRoots.map { it.toString() },
                        dependencyModuleNames = spec.dependencyModuleNames.map { it.value },
                        files = listing.files,
                        filesTruncated = listing.filesTruncated,
                        fileCount = listing.fileCount,
                    )
                }
                span.setAttribute("kast.workspaceFiles.moduleCount", modules.size)
                span.setAttribute("kast.workspaceFiles.totalFileCount", modules.sumOf { it.fileCount })
                span.setAttribute("kast.workspaceFiles.returnedFileCount", modules.sumOf { it.files.size })
                span.setAttribute("kast.workspaceFiles.truncatedModuleCount", modules.count { it.filesTruncated })
                WorkspaceFilesResult(modules = modules)
            }
        }
    }

    companion object {
        private fun readBackendVersion(): String =
            StandaloneAnalysisBackend::class.java
                .getResource("/kast-backend-version.txt")
                ?.readText()?.trim()
                ?: "unknown"
    }

    private data class WorkspaceFileListing(
        val files: List<String>,
        val fileCount: Int,
        val filesTruncated: Boolean,
    )

    private fun collectWorkspaceFiles(
        sourceRoots: List<Path>,
        includeFiles: Boolean,
        maxFiles: Int,
    ): WorkspaceFileListing {
        val files = mutableListOf<String>()
        var fileCount = 0
        sourceRoots.forEach { root ->
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        fileCount += 1
                        if (includeFiles && files.size < maxFiles) {
                            files += file.toRealPath().toString()
                        }
                    }
            }
        }
        return WorkspaceFileListing(
            files = files.sorted(),
            fileCount = fileCount,
            filesTruncated = includeFiles && fileCount > files.size,
        )
    }

    private fun isConcreteType(type: PsiElement): Boolean = when (type) {
        is KtClass -> !type.isInterface() && !type.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        is KtObjectDeclaration -> !type.isCompanion()
        else -> false
    }

    /**
     * Parallel `flatMap` over a list using [parallelPool] (a dedicated [ForkJoinPool]).
     * Safe to call while the caller holds [StandaloneAnalysisSession.withReadAccess] —
     * the parent thread's read lock prevents any writer from acquiring the write lock,
     * so fork-join pool threads read PSI safely without their own lock acquisition.
     */
    private fun <T, R> List<T>.parallelMapFlat(transform: (T) -> List<R>): List<R> =
        if (size <= 1) {
            flatMap(transform)
        } else {
            parallelPool.submit(Callable {
                parallelStream()
                    .flatMap { element -> transform(element).stream() }
                    .collect(Collectors.toList())
            }).get()
        }
}

/**
 * Holds the result of a per-file PSI reference walk, including whether the walk was
 * terminated early because it exceeded [ServerLimits.perFileScanBudgetMillis].
 */
private data class FileReferenceResult(
    val locations: List<Location>,
    val timedOut: Boolean,
)
