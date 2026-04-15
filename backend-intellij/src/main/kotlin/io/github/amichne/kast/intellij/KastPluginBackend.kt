package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CapabilityNotSupportedException
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileOutlineQuery
import io.github.amichne.kast.api.FileOutlineResult
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.ImportOptimizeQuery
import io.github.amichne.kast.api.ImportOptimizeResult
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.NotFoundException
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
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.SymbolVisibility
import io.github.amichne.kast.api.TextEdit
import io.github.amichne.kast.api.TypeHierarchyQuery
import io.github.amichne.kast.api.TypeHierarchyResult
import io.github.amichne.kast.api.WorkspaceFilesQuery
import io.github.amichne.kast.api.WorkspaceFilesResult
import io.github.amichne.kast.api.WorkspaceModule
import io.github.amichne.kast.api.WorkspaceSymbolQuery
import io.github.amichne.kast.api.WorkspaceSymbolResult
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
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Path

@OptIn(KaExperimentalApi::class)
internal class KastPluginBackend(
    private val project: Project,
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
) : AnalysisBackend {

    private val readDispatcher = Dispatchers.Default.limitedParallelism(limits.maxConcurrentRequests)
    private val workspacePrefix = workspaceRoot.toString() + "/"

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
        readAction {
            val file = findKtFile(query.position.filePath)
            val target = resolveTarget(file, query.position.offset)
            SymbolResult(
                analyze(file) {
                    target.toSymbolModel(
                        containingDeclaration = null,
                        supertypes = supertypeNames(target),
                        includeDeclarationScope = query.includeDeclarationScope,
                    )
                },
            )
        }
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        val (snapshot, references) = collectInShortReadActions(
            collectSnapshot = {
                val file = findKtFile(query.position.filePath)
                val target = resolveTarget(file, query.position.offset)
                val searchScope = GlobalSearchScope.projectScope(project)
                ReferenceSearchSnapshot(
                    declaration = if (query.includeDeclaration) {
                        analyze(file) { target.toSymbolModel(containingDeclaration = null) }
                    } else {
                        null
                    },
                    visibility = target.visibility(),
                ) to ReferencesSearch.search(target, searchScope).findAll()
            },
            processItem = { ref ->
                val element = ref.element
                if (!element.isValid) return@collectInShortReadActions null
                val location = element.toKastLocation()
                if (isWorkspaceFile(location.filePath)) location else null
            },
            runInitialReadAction = { action -> runIntellijReadAction(action) },
            runPerItemReadAction = { action -> runIntellijReadAction(action) },
        )
        val sortedReferences = references.sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        ReferencesResult(
            declaration = snapshot.declaration,
            references = sortedReferences,
            searchScope = SearchScope(
                visibility = snapshot.visibility,
                scope = SearchScopeKind.DEPENDENT_MODULES,
                exhaustive = true,
                candidateFileCount = sortedReferences.size,
                searchedFileCount = sortedReferences.size,
            ),
        )
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult = withContext(readDispatcher) {
        // Resolve the root target under a short read lock; the recursive
        // traversal acquires per-level read locks inside the edge resolver
        // so the IDE write lock is not starved for the full duration.
        val rootTarget = readAction {
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
        val intellijReadAccess = object : ReadAccessScope {
            override fun <T> run(action: () -> T): T =
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .runReadAction<T> { action() }
        }
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

    override suspend fun typeHierarchy(query: TypeHierarchyQuery): TypeHierarchyResult = withContext(readDispatcher) {
        val rootTarget = readAction {
            val file = findKtFile(query.position.filePath)
            val resolved = resolveTarget(file, query.position.offset)
            resolved.typeHierarchyDeclaration() ?: resolved
        }
        val resolver = IntelliJTypeEdgeResolver(project = project)
        val intellijReadAccess = object : ReadAccessScope {
            override fun <T> run(action: () -> T): T =
                ApplicationManager.getApplication().runReadAction<T> { action() }
        }
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

    override suspend fun workspaceFiles(query: WorkspaceFilesQuery): WorkspaceFilesResult = withContext(readDispatcher) {
        readAction {
            val allModules = ModuleManager.getInstance(project).modules
            val targetModules = if (query.moduleName != null) {
                allModules.filter { it.name == query.moduleName }
            } else {
                allModules.toList()
            }
            val modules = targetModules.map { module ->
                val rootManager = ModuleRootManager.getInstance(module)
                val sourceRoots = rootManager.sourceRoots
                    .map { it.path }
                    .filter { it.startsWith(workspacePrefix) }
                val depNames = rootManager.dependencies.map { it.name }
                val files = if (query.includeFiles) {
                    FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.moduleScope(module))
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
                    files = files,
                    fileCount = if (query.includeFiles) {
                        files.size
                    } else {
                        FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.moduleScope(module))
                            .count { it.path.startsWith(workspacePrefix) }
                    },
                )
            }
            WorkspaceFilesResult(modules = modules)
        }
    }

    override suspend fun semanticInsertionPoint(
        query: SemanticInsertionQuery,
    ): SemanticInsertionResult = withContext(readDispatcher) {
        readAction {
            val file = findKtFile(query.position.filePath)
            SemanticInsertionPointResolver.resolve(file, query)
        }
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult = withContext(readDispatcher) {
        readAction {
            val diagnostics = query.filePaths
                .sorted()
                .flatMap { filePath ->
                    val file = findKtFile(filePath)
                    analyze(file) {
                        file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    }.flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
                }
                .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" }))

            DiagnosticsResult(diagnostics = diagnostics)
        }
    }

    override suspend fun rename(query: RenameQuery): RenameResult = withContext(readDispatcher) {
        val (snapshot, referenceEdits) = collectInShortReadActions(
            collectSnapshot = {
                val file = findKtFile(query.position.filePath)
                val target = resolveTarget(file, query.position.offset)
                val searchScope = GlobalSearchScope.projectScope(project)
                RenameSnapshot(
                    declarationEdit = target.declarationEdit(query.newName),
                    visibility = target.visibility(),
                ) to ReferencesSearch.search(target, searchScope).findAll()
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
            runPerItemReadAction = { action -> runIntellijReadAction(action) },
        )

        val edits = (listOf(snapshot.declarationEdit) + referenceEdits)
            .distinctBy { Triple(it.filePath, it.startOffset, it.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        val affectedFiles = edits.map(TextEdit::filePath).distinct()
        val fileHashes = LocalDiskEditApplier.currentHashes(affectedFiles)

        RenameResult(
            edits = edits,
            fileHashes = fileHashes,
            affectedFiles = affectedFiles,
            searchScope = SearchScope(
                visibility = snapshot.visibility,
                scope = SearchScopeKind.DEPENDENT_MODULES,
                exhaustive = true,
                candidateFileCount = edits.size,
                searchedFileCount = edits.size,
            ),
        )
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult {
        val result = LocalDiskEditApplier.apply(query)
        withContext(Dispatchers.IO) {
            VirtualFileManager.getInstance().syncRefresh()
        }
        return result
    }

    override suspend fun optimizeImports(query: ImportOptimizeQuery): ImportOptimizeResult = withContext(readDispatcher) {
        readAction {
            val edits = query.filePaths
                .distinct()
                .sorted()
                .flatMap { filePath -> ImportAnalysis.optimizeImportEdits(findKtFile(filePath)) }
                .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
            val affectedFiles = edits.map(TextEdit::filePath).distinct()
            ImportOptimizeResult(
                edits = edits,
                fileHashes = LocalDiskEditApplier.currentHashes(affectedFiles),
                affectedFiles = affectedFiles,
            )
        }
    }

    override suspend fun refresh(query: RefreshQuery): RefreshResult {
        return withContext(Dispatchers.IO) {
            VirtualFileManager.getInstance().syncRefresh()
            if (query.filePaths.isEmpty()) {
                RefreshResult(refreshedFiles = emptyList(), fullRefresh = true)
            } else {
                RefreshResult(refreshedFiles = query.filePaths, fullRefresh = false)
            }
        }
    }

    override suspend fun fileOutline(query: FileOutlineQuery): FileOutlineResult = withContext(readDispatcher) {
        readAction {
            val file = findKtFile(query.filePath)
            FileOutlineResult(symbols = FileOutlineBuilder.build(file))
        }
    }

    override suspend fun workspaceSymbolSearch(query: WorkspaceSymbolQuery): WorkspaceSymbolResult = withContext(readDispatcher) {
        readAction {
            val matcher = SymbolSearchMatcher.create(query.pattern, query.regex)
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val symbols = mutableListOf<Symbol>()

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

    private fun findKtFile(filePath: String): KtFile {
        val normalizedPath = Path.of(filePath).toAbsolutePath().normalize().toString()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
            ?: throw NotFoundException("File not found: $filePath")
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: throw NotFoundException("Cannot resolve PSI for: $filePath")
        return psiFile as? KtFile
            ?: throw NotFoundException("Not a Kotlin file: $filePath")
    }

    private data class ReferenceSearchSnapshot(
        val declaration: Symbol?,
        val visibility: SymbolVisibility,
    )

    private data class RenameSnapshot(
        val declarationEdit: TextEdit,
        val visibility: SymbolVisibility,
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

internal inline fun <S, T, R : Any> collectInShortReadActions(
    crossinline collectSnapshot: () -> Pair<S, Collection<T>>,
    crossinline processItem: (T) -> R?,
    crossinline runInitialReadAction: (() -> Pair<S, Collection<T>>) -> Pair<S, Collection<T>>,
    crossinline runPerItemReadAction: (() -> R?) -> R?,
): Pair<S, List<R>> {
    val (snapshot, items) = runInitialReadAction { collectSnapshot() }
    val results = items.mapNotNull { item -> runPerItemReadAction { processItem(item) } }
    return snapshot to results
}

internal inline fun <T> runIntellijReadAction(crossinline action: () -> T): T =
    ApplicationManager.getApplication().runReadAction<T> { action() }
