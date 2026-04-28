package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.ApplyEditsQuery
import io.github.amichne.kast.api.contract.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.CallHierarchyQuery
import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.contract.CodeActionsQuery
import io.github.amichne.kast.api.contract.CodeActionsResult
import io.github.amichne.kast.api.contract.CompletionItem
import io.github.amichne.kast.api.contract.CompletionsQuery
import io.github.amichne.kast.api.contract.CompletionsResult
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.DiagnosticsQuery
import io.github.amichne.kast.api.contract.DiagnosticsResult
import io.github.amichne.kast.api.contract.FileOutlineQuery
import io.github.amichne.kast.api.contract.FileOutlineResult
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.ImportOptimizeResult
import io.github.amichne.kast.api.contract.ImplementationsQuery
import io.github.amichne.kast.api.contract.ImplementationsResult

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RefreshQuery
import io.github.amichne.kast.api.contract.RefreshResult
import io.github.amichne.kast.api.contract.RenameQuery
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.api.contract.SymbolResult
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.TypeHierarchyResult
import io.github.amichne.kast.api.contract.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.WorkspaceModule
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.WorkspaceSymbolResult
import io.github.amichne.kast.shared.analysis.FileOutlineBuilder
import io.github.amichne.kast.shared.analysis.ImportAnalysis
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.SymbolSearchMatcher
import io.github.amichne.kast.shared.analysis.declarationEdit
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.supertypeNames
import io.github.amichne.kast.shared.analysis.toApiDiagnostics
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.typeHierarchyDeclaration
import io.github.amichne.kast.shared.analysis.visibility
import io.github.amichne.kast.shared.hierarchy.CallHierarchyEngine
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyBudget
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyEngine
import io.github.amichne.kast.shared.hierarchy.ReadAccessScope
import io.github.amichne.kast.shared.hierarchy.TraversalBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import java.nio.file.Path

@OptIn(KaExperimentalApi::class)
internal class KastPluginBackend(
    private val project: Project,
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val telemetry: IntelliJBackendTelemetry = IntelliJBackendTelemetry.disabled(),
) : AnalysisBackend {

    private val readDispatcher = Dispatchers.Default.limitedParallelism(limits.maxConcurrentRequests)
    private val workspacePrefix = workspaceRoot.toString() + "/"
    private val intellijReadAccess = object : ReadAccessScope {
        override fun <T> run(action: () -> T): T =
            ApplicationManager.getApplication().runReadAction<T> { action() }
    }

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = "intellij",
        backendVersion = BACKEND_VERSION,
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
        val caps = capabilities()
        val isDumb = DumbService.isDumb(project)
        val state = if (isDumb) RuntimeState.INDEXING else RuntimeState.READY
        val moduleNames = ModuleManager.getInstance(project).modules.map { it.name }.sorted()
        return RuntimeStatusResponse(
            state = state,
            healthy = true,
            active = true,
            indexing = isDumb,
            backendName = caps.backendName,
            backendVersion = caps.backendVersion,
            workspaceRoot = caps.workspaceRoot,
            message = if (isDumb) {
                "IntelliJ is indexing — analysis results may be incomplete"
            } else {
                "IntelliJ analysis backend is ready"
            },
            sourceModuleNames = moduleNames,
        )
    }

    override suspend fun health(): HealthResponse {
        val caps = capabilities()
        return HealthResponse(
            backendName = caps.backendName,
            backendVersion = caps.backendVersion,
            workspaceRoot = caps.workspaceRoot,
        )
    }

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.RESOLVE, "kast.intellij.resolveSymbol") {
            timedReadAction(telemetry, IntelliJTelemetryScope.RESOLVE, "kast.intellij.resolveSymbol.readAction") {
                val file = findKtFile(query.position.filePath)
                val target = resolveTarget(file, query.position.offset)
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

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.REFERENCES, "kast.intellij.findReferences") {
        val (snapshot, references) = collectInShortReadActions(
            collectSnapshot = {
                val file = findKtFile(query.position.filePath)
                val target = resolveTarget(file, query.position.offset)
                val visibility = target.visibility()
                val (searchScope, scopeKind) = visibilityScopedSearch(target, visibility)
                val refs = mutableListOf<PsiReference>()
                ReferencesSearch.search(target, searchScope).forEach { ref ->
                    ProgressManager.checkCanceled()
                    refs.add(ref)
                    true
                }
                ReferenceSearchSnapshot(
                    declaration = if (query.includeDeclaration) {
                        analyze(file) { target.toSymbolModel(containingDeclaration = null) }
                    } else {
                        null
                    },
                    visibility = visibility,
                    scopeKind = scopeKind,
                    candidateFileCount = searchScope.let { scope ->
                        FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)
                            .count { it.path.startsWith(workspacePrefix) }
                    },
                ) to refs
            },
            processItem = { ref ->
                val element = ref.element
                if (!element.isValid) return@collectInShortReadActions null
                val location = element.toKastLocation()
                if (isWorkspaceFile(location.filePath)) location else null
            },
            runInitialReadAction = { action -> runIntellijReadAction(action) },
            runBatchReadAction = { action -> runIntellijReadAction(action) },
        )
        val sortedReferences = references.sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        val searchedFileCount = snapshot.candidateFileCount

        ReferencesResult(
            declaration = snapshot.declaration,
            references = sortedReferences,
            searchScope = SearchScope(
                visibility = snapshot.visibility,
                scope = snapshot.scopeKind,
                exhaustive = true,
                candidateFileCount = snapshot.candidateFileCount,
                searchedFileCount = searchedFileCount,
            ),
        )
        }
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.CALL_HIERARCHY, "kast.intellij.callHierarchy") {
        // Resolve the root target under a short read lock; the recursive
        // traversal acquires per-level read locks inside the edge resolver
        // so the IDE write lock is not starved for the full duration.
        val rootTarget = timedReadAction(telemetry, IntelliJTelemetryScope.CALL_HIERARCHY, "kast.intellij.callHierarchy.resolveTarget") {
            val file = findKtFile(query.position.filePath)
            resolveTarget(file, query.position.offset)
        }

        val budget = TraversalBudget(
            maxTotalCalls = query.maxTotalCalls,
            maxChildrenPerNode = query.maxChildrenPerNode,
            timeoutMillis = query.timeoutMillis ?: limits.requestTimeoutMillis,
        )
        val resolver = IntelliJCallEdgeResolver(
            project = project,
            workspacePrefix = workspacePrefix,
        )
        val engine = CallHierarchyEngine(edgeResolver = resolver, readAccess = intellijReadAccess)
        val root = engine.buildNode(
            target = rootTarget,
            parentCallSite = null,
            direction = query.direction,
            depthRemaining = query.depth,
            pathKeys = emptySet(),
            budget = budget,
            currentDepth = 0,
        )

        CallHierarchyResult(
            root = root,
            stats = budget.toStats(),
        )
        }
    }

    override suspend fun typeHierarchy(query: TypeHierarchyQuery): TypeHierarchyResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.TYPE_HIERARCHY, "kast.intellij.typeHierarchy") {
        val rootTarget = readAction {
            val file = findKtFile(query.position.filePath)
            val resolved = resolveTarget(file, query.position.offset)
            resolved.typeHierarchyDeclaration() ?: resolved
        }
        val resolver = IntelliJTypeEdgeResolver(project = project)
        val engine = TypeHierarchyEngine(edgeResolver = resolver, readAccess = intellijReadAccess)
        val budget = TypeHierarchyBudget(maxResults = query.maxResults.coerceAtLeast(1))
        val root = engine.buildNode(
            target = rootTarget,
            direction = query.direction,
            depthRemaining = query.depth.coerceAtLeast(0),
            pathKeys = emptySet(),
            budget = budget,
            currentDepth = 0,
        )
        TypeHierarchyResult(root = root, stats = budget.toStats())
        }
    }

    override suspend fun implementations(query: ImplementationsQuery): ImplementationsResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.IMPLEMENTATIONS, "kast.intellij.implementations") {
        val rootTarget = readAction {
            val file = findKtFile(query.position.filePath)
            val resolved = resolveTarget(file, query.position.offset)
            resolved.typeHierarchyDeclaration() ?: resolved
        }
        val resolver = IntelliJTypeEdgeResolver(project = project)
        val declarationSymbol = resolver.symbolFor(rootTarget)
        val queue = ArrayDeque<PsiElement>()
        val visited = mutableSetOf<String>()
        val implementations = mutableListOf<Symbol>()
        queue += rootTarget
        var exhaustive = true
        val limit = query.maxResults.coerceAtLeast(1)

        while (queue.isNotEmpty() && implementations.size < limit) {
            val current = queue.removeFirst()
            val edges = resolver.subtypeEdges(current)
            for (edge in edges) {
                val key = "${edge.symbol.fqName}|${edge.symbol.location.filePath}:${edge.symbol.location.startOffset}"
                if (!visited.add(key)) continue
                queue += edge.target
                if (isConcreteType(edge.target)) {
                    implementations += edge.symbol
                    if (implementations.size >= limit) {
                        exhaustive = false
                        break
                    }
                }
            }
        }

        if (queue.isNotEmpty()) exhaustive = false
        ImplementationsResult(
            declaration = declarationSymbol,
            implementations = implementations.sortedWith(
                compareBy({ it.fqName }, { it.location.filePath }, { it.location.startOffset }),
            ),
            exhaustive = exhaustive,
        )
        }
    }

    override suspend fun codeActions(query: CodeActionsQuery): CodeActionsResult = withContext(readDispatcher) {
        readAction {
            findKtFile(query.position.filePath)
            CodeActionsResult(actions = emptyList())
        }
    }

    override suspend fun completions(query: CompletionsQuery): CompletionsResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.COMPLETIONS, "kast.intellij.completions") {
        readAction {
            val file = findKtFile(query.position.filePath)
            val kindFilter = query.kindFilter
            val items = mutableListOf<CompletionItem>()
            file.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is KtNamedDeclaration &&
                        element !is KtParameter &&
                        element.name != null &&
                        element.textOffset <= query.position.offset
                    ) {
                        val symbol = element.toSymbolModel(
                            containingDeclaration = null,
                            includeDocumentation = true,
                        )
                        if (kindFilter == null || symbol.kind in kindFilter) {
                            items += CompletionItem(
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
            val deduped = items
                .distinctBy { Triple(it.fqName, it.kind, it.name) }
                .sortedWith(compareBy({ it.name }, { it.fqName }))
            val capped = deduped.take(query.maxResults.coerceAtLeast(1))
            CompletionsResult(
                items = capped,
                exhaustive = deduped.size <= capped.size,
            )
        }
        }
    }

    override suspend fun workspaceFiles(query: WorkspaceFilesQuery): WorkspaceFilesResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.WORKSPACE_FILES, "kast.intellij.workspaceFiles") {
            val allModules = timedReadAction(telemetry, IntelliJTelemetryScope.WORKSPACE_FILES, "kast.intellij.workspaceFiles.listModules") {
                ModuleManager.getInstance(project).modules.toList()
            }
            val targetModules = if (query.moduleName != null) {
                allModules.filter { timedReadAction(telemetry, IntelliJTelemetryScope.WORKSPACE_FILES, "kast.intellij.workspaceFiles.filterModule") { it.name } == query.moduleName }
            } else {
                allModules
            }
            val modules = targetModules.map { module ->
                timedReadAction(telemetry, IntelliJTelemetryScope.WORKSPACE_FILES, "kast.intellij.workspaceFiles.module") {
                val rootManager = ModuleRootManager.getInstance(module)
                val sourceRoots = rootManager.sourceRoots
                    .map { it.path }
                    .filter { it.startsWith(workspacePrefix) }
                val depNames = rootManager.dependencies.map { it.name }
                val moduleScope = GlobalSearchScope.moduleScope(module)
                val kotlinFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, moduleScope)
                val filteredPaths = if (query.includeFiles) {
                    kotlinFiles
                        .map { it.path }
                        .filter { it.startsWith(workspacePrefix) }
                        .sorted()
                } else {
                    emptyList()
                }
                WorkspaceModule(
                    name = module.name,
                    sourceRoots = sourceRoots,
                    dependencyModuleNames = depNames,
                    files = filteredPaths,
                    fileCount = if (query.includeFiles) {
                        filteredPaths.size
                    } else {
                        kotlinFiles.count { it.path.startsWith(workspacePrefix) }
                    },
                )
            }
            }
            WorkspaceFilesResult(modules = modules)
        }
    }

    override suspend fun semanticInsertionPoint(
        query: SemanticInsertionQuery,
    ): SemanticInsertionResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.SEMANTIC_INSERTION_POINT, "kast.intellij.semanticInsertionPoint") {
        readAction {
            val file = findKtFile(query.position.filePath)
            SemanticInsertionPointResolver.resolve(file, query)
        }
        }
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.DIAGNOSTICS, "kast.intellij.diagnostics") {
            val diagnostics = coroutineScope {
                query.filePaths.sorted().map { filePath ->
                    async(readDispatcher) {
                        runCatching {
                            timedReadAction(telemetry, IntelliJTelemetryScope.DIAGNOSTICS, "kast.intellij.diagnostics.file") {
                                val file = findKtFile(filePath)
                                analyze(file) {
                                    file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                                        .flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
                                }
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
                }.awaitAll().flatten()
            }.sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" }))

            DiagnosticsResult(diagnostics = diagnostics)
        }
    }

    // Note: Unlike the standalone backend, IntelliJ's ReferencesSearch.search() resolves
    // import directives as reference sites, so explicit import FQN handling is not needed here.
    override suspend fun rename(query: RenameQuery): RenameResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.RENAME, "kast.intellij.rename") {
        val (snapshot, referenceEdits) = collectInShortReadActions(
            collectSnapshot = {
                val file = findKtFile(query.position.filePath)
                val target = resolveTarget(file, query.position.offset)
                val visibility = target.visibility()
                val (searchScope, scopeKind) = visibilityScopedSearch(target, visibility)
                val candidateFileCount = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, searchScope)
                    .count { it.path.startsWith(workspacePrefix) }
                val refs = mutableListOf<PsiReference>()
                ReferencesSearch.search(target, searchScope).forEach { ref ->
                    ProgressManager.checkCanceled()
                    refs.add(ref)
                    true
                }
                RenameSnapshot(
                    declarationEdit = target.declarationEdit(query.newName),
                    visibility = visibility,
                    scopeKind = scopeKind,
                    candidateFileCount = candidateFileCount,
                ) to refs
            },
            processItem = { ref ->
                val element = ref.element
                if (!element.isValid) return@collectInShortReadActions null
                val refFilePath = element.resolvedFilePath().value
                if (!isWorkspaceFile(refFilePath)) return@collectInShortReadActions null
                TextEdit(
                    filePath = refFilePath,
                    startOffset = ref.rangeInElement.startOffset + element.textRange.startOffset,
                    endOffset = ref.rangeInElement.endOffset + element.textRange.startOffset,
                    newText = query.newName,
                )
            },
            runInitialReadAction = { action -> runIntellijReadAction(action) },
            runBatchReadAction = { action -> runIntellijReadAction(action) },
        )

        val edits = (listOf(snapshot.declarationEdit) + referenceEdits)
            .distinctBy { Triple(it.filePath, it.startOffset, it.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        val affectedFiles = edits.map(TextEdit::filePath).distinct()
        val fileHashes = IntelliJFileHashComputer.currentHashes(affectedFiles)

        RenameResult(
            edits = edits,
            fileHashes = fileHashes,
            affectedFiles = affectedFiles,
            searchScope = SearchScope(
                visibility = snapshot.visibility,
                scope = snapshot.scopeKind,
                exhaustive = true,
                candidateFileCount = snapshot.candidateFileCount,
                searchedFileCount = snapshot.candidateFileCount,
            ),
        )
        }
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult {
        return telemetry.inSpan(IntelliJTelemetryScope.APPLY_EDITS, "kast.intellij.applyEdits") {
            val applier = IntelliJEditApplier(project)
            applier.apply(query)
            // No asyncRefresh needed - IntelliJ APIs handle VFS updates automatically
        }
    }

    override suspend fun optimizeImports(query: ImportOptimizeQuery): ImportOptimizeResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.OPTIMIZE_IMPORTS, "kast.intellij.optimizeImports") {
        val edits = query.filePaths
            .distinct()
            .sorted()
            .flatMap { filePath ->
                timedReadAction(telemetry, IntelliJTelemetryScope.OPTIMIZE_IMPORTS, "kast.intellij.optimizeImports.file") {
                    ImportAnalysis.optimizeImportEdits(findKtFile(filePath))
                }
            }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        val affectedFiles = edits.map(TextEdit::filePath).distinct()
        ImportOptimizeResult(
            edits = edits,
            fileHashes = IntelliJFileHashComputer.currentHashes(affectedFiles),
            affectedFiles = affectedFiles,
        )
        }
    }

    override suspend fun refresh(query: RefreshQuery): RefreshResult {
        return telemetry.inSpan(IntelliJTelemetryScope.REFRESH, "kast.intellij.refresh") {
            ApplicationManager.getApplication().invokeLater {
                VirtualFileManager.getInstance().asyncRefresh(null)
            }
            if (query.filePaths.isEmpty()) {
                RefreshResult(refreshedFiles = emptyList(), fullRefresh = true)
            } else {
                RefreshResult(refreshedFiles = query.filePaths, fullRefresh = false)
            }
        }
    }

    override suspend fun fileOutline(query: FileOutlineQuery): FileOutlineResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.FILE_OUTLINE, "kast.intellij.fileOutline") {
            timedReadAction(telemetry, IntelliJTelemetryScope.FILE_OUTLINE, "kast.intellij.fileOutline.readAction") {
                val file = findKtFile(query.filePath)
                FileOutlineResult(symbols = FileOutlineBuilder.build(file))
            }
        }
    }

    override suspend fun workspaceSymbolSearch(query: WorkspaceSymbolQuery): WorkspaceSymbolResult = withContext(readDispatcher) {
        telemetry.inSpan(IntelliJTelemetryScope.WORKSPACE_SYMBOL_SEARCH, "kast.intellij.workspaceSymbolSearch") {
        val matcher = SymbolSearchMatcher.create(query.pattern, query.regex)
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        val symbols = mutableListOf<Symbol>()

        timedReadAction(telemetry, IntelliJTelemetryScope.WORKSPACE_SYMBOL_SEARCH, "kast.intellij.workspaceSymbolSearch.readAction") {
            collectMatchingSymbols(
                scope = scope,
                matcher = matcher,
                query = query,
                symbols = symbols,
                allNames = cache.allClassNames,
                lookupByName = cache::getClassesByName,
            )
            collectMatchingSymbols(
                scope = scope,
                matcher = matcher,
                query = query,
                symbols = symbols,
                allNames = cache.allMethodNames,
                lookupByName = cache::getMethodsByName,
            )
            collectMatchingSymbols(
                scope = scope,
                matcher = matcher,
                query = query,
                symbols = symbols,
                allNames = cache.allFieldNames,
                lookupByName = cache::getFieldsByName,
            )
        }

        WorkspaceSymbolResult(symbols = symbols)
        }
    }

    private fun <T : PsiElement> collectMatchingSymbols(
        scope: GlobalSearchScope,
        matcher: SymbolSearchMatcher,
        query: WorkspaceSymbolQuery,
        symbols: MutableList<Symbol>,
        allNames: Array<String>,
        lookupByName: (String, GlobalSearchScope) -> Array<out T>,
    ) {
        for (name in allNames) {
            if (symbols.size >= query.maxResults) break
            if (!matcher.matches(name)) continue
            for (element in lookupByName(name, scope)) {
                if (symbols.size >= query.maxResults) break
                val ktElement = element.navigationElement as? KtNamedDeclaration ?: continue
                val filePath = ktElement.containingFile?.virtualFile?.path ?: continue
                if (!isWorkspaceFile(filePath)) continue
                val symbol = ktElement.toSymbolModel(
                    containingDeclaration = null,
                    includeDeclarationScope = query.includeDeclarationScope,
                )
                if (query.kind == null || symbol.kind == query.kind) {
                    symbols += symbol
                }
            }
        }
    }

    private fun isWorkspaceFile(filePath: String): Boolean =
        filePath.startsWith(workspacePrefix) || filePath == workspaceRoot.toString()

    private fun isConcreteType(target: PsiElement): Boolean = when (target) {
        is KtClass -> !target.isInterface() && !target.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        is KtObjectDeclaration -> !target.isCompanion()
        is com.intellij.psi.PsiClass -> !target.isInterface && !target.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)
        else -> false
    }

    private fun findKtFile(filePath: String): KtFile {
        val normalizedPath = Path.of(filePath).toAbsolutePath().normalize().toString()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
            ?: throw NotFoundException("File not found: $filePath")
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: throw NotFoundException("Cannot resolve PSI for: $filePath")
        return psiFile as? KtFile
            ?: throw NotFoundException("Not a Kotlin file: $filePath")
    }

    private fun visibilityScopedSearch(
        target: PsiElement,
        visibility: SymbolVisibility,
    ): Pair<GlobalSearchScope, SearchScopeKind> = when (visibility) {
        SymbolVisibility.PRIVATE, SymbolVisibility.LOCAL -> {
            val file = target.containingFile as? KtFile
                ?: return GlobalSearchScope.projectScope(project) to SearchScopeKind.DEPENDENT_MODULES
            val vf = file.virtualFile
            GlobalSearchScope.fileScope(project, vf) to SearchScopeKind.FILE
        }
        SymbolVisibility.INTERNAL -> {
            val file = target.containingFile as? KtFile
                ?: return GlobalSearchScope.projectScope(project) to SearchScopeKind.DEPENDENT_MODULES
            val vf = file.virtualFile
            val module = ProjectFileIndex.getInstance(project).getModuleForFile(vf)
            if (module != null) {
                GlobalSearchScope.moduleWithDependentsScope(module) to SearchScopeKind.DEPENDENT_MODULES
            } else {
                GlobalSearchScope.projectScope(project) to SearchScopeKind.DEPENDENT_MODULES
            }
        }
        SymbolVisibility.PUBLIC, SymbolVisibility.PROTECTED, SymbolVisibility.UNKNOWN ->
            GlobalSearchScope.projectScope(project) to SearchScopeKind.DEPENDENT_MODULES
    }

    private data class ReferenceSearchSnapshot(
        val declaration: Symbol?,
        val visibility: SymbolVisibility,
        val scopeKind: SearchScopeKind,
        val candidateFileCount: Int,
    )

    private data class RenameSnapshot(
        val declarationEdit: TextEdit,
        val visibility: SymbolVisibility,
        val scopeKind: SearchScopeKind,
        val candidateFileCount: Int,
    )

    companion object {
        private val BACKEND_VERSION = readBackendVersion()

        private fun readBackendVersion(): String =
            KastPluginBackend::class.java
                .getResource("/kast-backend-version.txt")
                ?.readText()
                ?.trim()
                ?: "unknown"
    }
}

private const val READ_ACTION_BATCH_SIZE = 50

internal inline fun <S, T, R : Any> collectInShortReadActions(
    crossinline collectSnapshot: () -> Pair<S, Collection<T>>,
    crossinline processItem: (T) -> R?,
    crossinline runInitialReadAction: (() -> Pair<S, Collection<T>>) -> Pair<S, Collection<T>>,
    crossinline runBatchReadAction: (() -> List<R>) -> List<R>,
): Pair<S, List<R>> {
    val (snapshot, items) = runInitialReadAction { collectSnapshot() }
    val itemList = items.toList()
    val results = mutableListOf<R>()
    for (batch in itemList.chunked(READ_ACTION_BATCH_SIZE)) {
        val batchResults = runBatchReadAction {
            batch.mapNotNull { item -> processItem(item) }
        }
        results.addAll(batchResults)
    }
    return snapshot to results
}

internal inline fun <T> runIntellijReadAction(crossinline action: () -> T): T =
    ApplicationManager.getApplication().runReadAction<T> { action() }

internal suspend inline fun <T> timedReadAction(
    telemetry: IntelliJBackendTelemetry,
    scope: IntelliJTelemetryScope,
    name: String,
    crossinline block: () -> T,
): T {
    val waitStart = System.nanoTime()
    return readAction {
        val holdStart = System.nanoTime()
        val waitNanos = holdStart - waitStart
        try {
            block()
        } finally {
            val holdNanos = System.nanoTime() - holdStart
            telemetry.recordReadAction(scope, name, waitNanos, holdNanos)
        }
    }
}
