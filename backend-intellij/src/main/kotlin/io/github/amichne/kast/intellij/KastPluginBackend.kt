package io.github.amichne.kast.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
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
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import io.github.amichne.kast.api.SymbolVisibility
import io.github.amichne.kast.shared.analysis.ImportAnalysis
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.declarationEdit
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.supertypeNames
import io.github.amichne.kast.shared.analysis.toApiDiagnostics
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.visibility
import io.github.amichne.kast.shared.hierarchy.CallHierarchyEngine
import io.github.amichne.kast.shared.hierarchy.ReadAccessScope
import io.github.amichne.kast.shared.hierarchy.TraversalBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
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
            ReadCapability.SEMANTIC_INSERTION_POINT,
            ReadCapability.DIAGNOSTICS,
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
                    )
                },
            )
        }
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        readAction {
            val file = findKtFile(query.position.filePath)
            val target = resolveTarget(file, query.position.offset)
            val searchScope = GlobalSearchScope.projectScope(project)
            val references = ReferencesSearch.search(target, searchScope)
                .mapNotNull { ref ->
                    val element = ref.element
                    val location = element.toKastLocation()
                    // Filter to files within the workspace root
                    if (location.filePath.startsWith(workspacePrefix) ||
                        location.filePath == workspaceRoot.toString()
                    ) {
                        location
                    } else {
                        null
                    }
                }
                .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

            ReferencesResult(
                declaration = if (query.includeDeclaration) {
                    analyze(file) { target.toSymbolModel(containingDeclaration = null) }
                } else {
                    null
                },
                references = references,
                searchScope = SearchScope(
                    visibility = target.visibility(),
                    scope = SearchScopeKind.DEPENDENT_MODULES,
                    exhaustive = true,
                    candidateFileCount = references.size,
                    searchedFileCount = references.size,
                ),
            )
        }
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
        readAction {
            val file = findKtFile(query.position.filePath)
            val target = resolveTarget(file, query.position.offset)
            val searchScope = GlobalSearchScope.projectScope(project)

            val declarationEdit = target.declarationEdit(query.newName)
            val referenceEdits = ReferencesSearch.search(target, searchScope)
                .mapNotNull { ref ->
                    val element = ref.element
                    val refFilePath = element.resolvedFilePath().value
                    // Filter to workspace files
                    if (!refFilePath.startsWith(workspacePrefix)) return@mapNotNull null
                    TextEdit(
                        filePath = refFilePath,
                        startOffset = ref.rangeInElement.startOffset + element.textRange.startOffset,
                        endOffset = ref.rangeInElement.endOffset + element.textRange.startOffset,
                        newText = query.newName,
                    )
                }

            val edits = (listOf(declarationEdit) + referenceEdits)
                .distinctBy { Triple(it.filePath, it.startOffset, it.endOffset) }
                .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

            val affectedFiles = edits.map(TextEdit::filePath).distinct()
            val fileHashes = LocalDiskEditApplier.currentHashes(affectedFiles)

            RenameResult(
                edits = edits,
                fileHashes = fileHashes,
                affectedFiles = affectedFiles,
                searchScope = SearchScope(
                    visibility = target.visibility(),
                    scope = SearchScopeKind.DEPENDENT_MODULES,
                    exhaustive = true,
                    candidateFileCount = edits.size,
                    searchedFileCount = edits.size,
                ),
            )
        }
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

    private fun findKtFile(filePath: String): KtFile {
        val normalizedPath = Path.of(filePath).toAbsolutePath().normalize().toString()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
            ?: throw NotFoundException("File not found: $filePath")
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: throw NotFoundException("Cannot resolve PSI for: $filePath")
        return psiFile as? KtFile
            ?: throw NotFoundException("Not a Kotlin file: $filePath")
    }

    companion object {
        private const val BACKEND_VERSION = "0.1.0-SNAPSHOT"
    }
}
