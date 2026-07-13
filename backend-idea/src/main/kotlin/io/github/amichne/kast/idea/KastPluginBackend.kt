package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
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
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.NotFoundException
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
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.shared.analysis.FileOutlineBuilder
import io.github.amichne.kast.shared.analysis.ImportAnalysis
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.SymbolSearchMatcher
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.declarationEdit
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.supertypeNames
import io.github.amichne.kast.shared.analysis.targetFqNameAndPackage
import io.github.amichne.kast.shared.analysis.toApiDiagnostics
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.typeHierarchyDeclaration
import io.github.amichne.kast.shared.analysis.usageSiteDeclarationScope
import io.github.amichne.kast.shared.analysis.visibility
import io.github.amichne.kast.shared.hierarchy.CallHierarchyEngine
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyBudget
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyEngine
import io.github.amichne.kast.shared.hierarchy.ReadAccessScope
import io.github.amichne.kast.shared.hierarchy.TraversalBudget
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

@OptIn(KaExperimentalApi::class)
internal class KastPluginBackend(
    private val project: Project,
    workspaceRoot: Path,
    private val limits: ServerLimits,
    private val telemetry: IdeaBackendTelemetry = IdeaBackendTelemetry.disabled(),
    private val backendName: String? = null,
    private val workspaceIdentity: IdeaWorkspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot),
    private val referenceIndexLookup: ReferenceIndexLookup = ReferenceIndexLookup.Unavailable,
    private val referenceSearchClock: ReferenceSearchClock = ReferenceSearchClock.System,
) : AnalysisBackend {

    private val readDispatcher = Dispatchers.Default.limitedParallelism(limits.maxConcurrentRequests)
    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    private val sharedWorkspaceIdentity = workspaceIdentity.workspaceIdentity
    private val ideaReadAccess = object : ReadAccessScope {
        override fun <T> run(action: () -> T): T =
            ApplicationManager.getApplication().runReadAction<T> { action() }
    }

    private fun kotlinFileType(): FileType? =
        FileTypeManager.getInstance().findFileTypeByName("Kotlin")

    private fun kotlinCandidateFiles(scope: GlobalSearchScope): List<VirtualFile> =
        kotlinFileType()?.let { fileType ->
            FileTypeIndex.getFiles(fileType, scope)
                .asSequence()
                .filter { file -> file.isValid && !file.isDirectory && isWorkspaceFile(file.path) }
                .sortedBy { file -> file.path }
                .toList()
        } ?: emptyList()

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = backendName ?: defaultBackendName(),
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

    private fun defaultBackendName(): String = when (ApplicationInfo.getInstance().build.productCode) {
        "AI" -> "android-studio"
        else -> "idea"
    }

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
                "IDEA is indexing — analysis results may be incomplete"
            } else {
                "IDEA analysis backend is ready"
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

    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.RESOLVE, "kast.idea.resolveSymbol") {
            timedReadAction(telemetry, IdeaTelemetryScope.RESOLVE, "kast.idea.resolveSymbol.readAction") {
                val file = findKtFile(query.position.filePath.value)
                val target = resolveTarget(file, query.position.offset.value)
                SymbolResult(
                    analyze(file) {
                        target.toSymbolModel(
                            containingDeclaration = compilerContainingDeclarationName(target),
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
        telemetry.inSpan(IdeaTelemetryScope.REFERENCES, "kast.idea.findReferences") { span ->
            val plan = referenceSearchPlan(query, span)
            val outcome = indexedReferenceSearch(query, plan, span)
                ?: ideaReferenceSearch(query, plan, span)
            val sortedReferences = outcome.references
                .distinctBy { reference -> ReferenceLocationKey(reference.filePath, reference.startOffset, reference.endOffset) }
                .sortedWith(compareBy({ it.filePath }, { it.startOffset }, { it.endOffset }))

            span.setAttribute("kast.references.source", outcome.source.name.lowercase())
            span.setAttribute("kast.references.visibility", plan.visibility.name)
            span.setAttribute("kast.references.scope", plan.scopeKind.name)
            span.setAttribute("kast.references.candidateFileCount", outcome.candidateFileCount)
            span.setAttribute("kast.references.searchedFileCount", outcome.searchedFileCount)
            span.setAttribute("kast.references.resultCount", sortedReferences.size)
            span.setAttribute("kast.references.exhaustive", outcome.completion.exhaustive)
            span.setAttribute("kast.references.partialReason", outcome.completion.partialReason)

            ReferencesResult(
                declaration = plan.declaration,
                references = sortedReferences,
                searchScope = SearchScope(
                    visibility = plan.visibility,
                    scope = plan.scopeKind,
                    exhaustive = outcome.completion.exhaustive,
                    candidateFileCount = outcome.candidateFileCount,
                    searchedFileCount = outcome.searchedFileCount,
                ),
            )
        }
    }

    private suspend fun referenceSearchPlan(
        query: ParsedReferencesQuery,
        span: IdeaTelemetrySpan,
    ): ReferenceSearchPlan {
        val target = span.child("kast.idea.findReferences.targetResolution") {
            timedReadAction(
                telemetry = telemetry,
                scope = IdeaTelemetryScope.REFERENCES,
                name = "kast.idea.findReferences.targetResolution.readAction",
            ) {
                val file = findKtFile(query.position.filePath.value)
                val element = resolveTarget(file, query.position.offset.value)
                val targetFqName = element.targetFqNameAndPackage()?.first?.value
                ReferenceResolvedTarget(
                    pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
                    targetFqName = targetFqName,
                    searchNeedle = ReferenceSearchNeedle.from(element, targetFqName),
                    declaration = if (query.includeDeclaration) {
                        analyze(file) { element.toSymbolModel(containingDeclaration = null) }
                    } else {
                        null
                    },
                    visibility = element.visibility(),
                )
            }
        }
        val scope = span.child("kast.idea.findReferences.scopeCalculation") {
            timedReadAction(
                telemetry = telemetry,
                scope = IdeaTelemetryScope.REFERENCES,
                name = "kast.idea.findReferences.scopeCalculation.readAction",
            ) {
                val element = target.pointer.element
                    ?: throw NotFoundException(
                        "Cannot resolve symbol at ${query.position.filePath.value}:${query.position.offset.value}",
                    )
                val (searchScope, scopeKind) = visibilityScopedSearch(element, target.visibility)
                ReferenceScopePlan(
                    searchScope = searchScope,
                    scopeKind = scopeKind,
                )
            }
        }

        return ReferenceSearchPlan(
            target = target.pointer,
            targetFqName = target.targetFqName,
            searchNeedle = target.searchNeedle,
            declaration = target.declaration,
            visibility = target.visibility,
            searchScope = scope.searchScope,
            scopeKind = scope.scopeKind,
        )
    }

    private fun indexedReferenceSearch(
        query: ParsedReferencesQuery,
        plan: ReferenceSearchPlan,
        span: IdeaTelemetrySpan,
    ): ReferenceSearchOutcome? = span.child("kast.idea.findReferences.indexLookup") { indexSpan ->
        val targetFqName = plan.targetFqName ?: return@child null
        when (val lookup = referenceIndexLookup.referencesTo(targetFqName)) {
            IndexedReferenceLookupResult.NotReady -> {
                indexSpan.setAttribute("kast.references.indexReady", false)
                null
            }
            is IndexedReferenceLookupResult.Ready -> {
                val indexedRows = runIdeaReadAction {
                    lookup.references
                        .filter { row -> indexedReferenceRowInScope(row, plan.searchScope) }
                        .sortedWith(compareBy({ it.sourcePath }, { it.sourceOffset }))
                }
                val indexedSourcePathCount = indexedRows.mapTo(mutableSetOf()) { row -> row.sourcePath }.size
                val locations = indexedReferenceLocations(
                    rows = indexedRows,
                    includeUsageSiteScope = query.includeUsageSiteScope,
                )
                indexSpan.setAttribute("kast.references.indexReady", true)
                indexSpan.setAttribute("kast.references.indexRowCount", indexedRows.size)
                indexSpan.setAttribute("kast.references.indexLocationCount", locations.size)
                ReferenceSearchOutcome(
                    source = ReferenceSearchSource.INDEX,
                    references = locations,
                    candidateFileCount = indexedSourcePathCount,
                    searchedFileCount = indexedSourcePathCount,
                    completion = if (indexedRows.size == locations.size) {
                        ReferenceSearchCompletion.Exhaustive
                    } else {
                        ReferenceSearchCompletion.Partial(ReferencePartialReason.INDEX_LOCATION_UNRESOLVED)
                    },
                )
            }
        }
    }

    private fun indexedReferenceRowInScope(
        row: SymbolReferenceRow,
        searchScope: GlobalSearchScope,
    ): Boolean {
        if (!isWorkspaceFile(row.sourcePath)) return false
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(row.sourcePath) ?: return false
        return virtualFile.isValid && !virtualFile.isDirectory && searchScope.contains(virtualFile)
    }

    private fun indexedReferenceLocations(
        rows: List<SymbolReferenceRow>,
        includeUsageSiteScope: Boolean,
    ): List<Location> {
        val locations = mutableListOf<Location>()
        for (batch in rows.chunked(READ_ACTION_BATCH_SIZE)) {
            val batchLocations = runIdeaReadAction {
                batch.mapNotNull { row -> indexedReferenceLocationOrNull(row, includeUsageSiteScope) }
            }
            locations.addAll(batchLocations)
        }
        return locations
    }

    private fun indexedReferenceLocationOrNull(
        row: SymbolReferenceRow,
        includeUsageSiteScope: Boolean,
    ): Location? = try {
        indexedReferenceLocation(row, includeUsageSiteScope)
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun indexedReferenceLocation(
        row: SymbolReferenceRow,
        includeUsageSiteScope: Boolean,
    ): Location? {
        if (!isWorkspaceFile(row.sourcePath)) return null
        val file = findKtFile(row.sourcePath)
        val sourceOffset = row.sourceOffset.coerceIn(0, file.textLength)
        val anchor = file.findElementAt(sourceOffset) ?: return null
        val reference = anchor.referenceAtOffset(sourceOffset)
        val element = reference?.element ?: anchor
        if (!element.isValid) return null
        val range = reference?.absoluteTextRange() ?: indexedFallbackRange(file, row)
        val location = element.toKastLocation(range)
        return if (includeUsageSiteScope) {
            location.copy(usageSiteScope = element.usageSiteDeclarationScope())
        } else {
            location
        }
    }

    private fun indexedFallbackRange(
        file: KtFile,
        row: SymbolReferenceRow,
    ): TextRange {
        val start = row.sourceOffset.coerceIn(0, file.textLength)
        val nameLength = row.targetFqName.substringAfterLast('.').length.coerceAtLeast(1)
        val end = (start + nameLength).coerceAtMost(file.textLength)
        return TextRange(start, end)
    }

    private fun ideaReferenceSearch(
        query: ParsedReferencesQuery,
        plan: ReferenceSearchPlan,
        span: IdeaTelemetrySpan,
    ): ReferenceSearchOutcome = span.child("kast.idea.findReferences.findUsagesFallback") { fallbackSpan ->
        val budget = ReferenceSearchBudget.start(limits, referenceSearchClock)
        fallbackSpan.setAttribute("kast.references.fallbackApi", "PsiSearchHelper.processCandidateFilesForText")
        fallbackSpan.setAttribute("kast.references.resolutionApi", "ReferencesSearch.search(fileScope)")

        val candidateDiscovery = referenceCandidateFiles(plan, budget, fallbackSpan)
        val resolution = fallbackSpan.child("kast.idea.findReferences.referenceResolution") { resolutionSpan ->
            val locations = mutableListOf<Location>()
            var searchedFileCount = 0
            var completion: ReferenceSearchCompletion = ReferenceSearchCompletion.Exhaustive

            for (candidateFile in candidateDiscovery.files) {
                ProgressManager.checkCanceled()
                if (budget.requestExhausted()) {
                    completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                    break
                }
                val fileStartedNanos = budget.fileStarted()
                val fileOutcome = runIdeaReadAction {
                    val target = plan.target.element
                        ?: return@runIdeaReadAction ReferenceFileSearchOutcome(
                            references = emptyList(),
                            completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.TARGET_INVALIDATED),
                        )
                    val fileLocations = mutableListOf<Location>()
                    var fileCompletion: ReferenceSearchCompletion = ReferenceSearchCompletion.Exhaustive
                    ReferencesSearch.search(target, GlobalSearchScope.fileScope(project, candidateFile)).forEach(
                        object : Processor<PsiReference> {
                            override fun process(ref: PsiReference): Boolean {
                                ProgressManager.checkCanceled()
                                fileCompletion = when {
                                    budget.requestExhausted() ->
                                        ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                                    budget.fileExhausted(fileStartedNanos) ->
                                        ReferenceSearchCompletion.Partial(ReferencePartialReason.FILE_BUDGET_EXHAUSTED)
                                    else -> ReferenceSearchCompletion.Exhaustive
                                }
                                if (!fileCompletion.exhaustive) {
                                    return false
                                }
                                ref.toReferenceLocation(query.includeUsageSiteScope)?.let(fileLocations::add)
                                return true
                            }
                        }
                    )
                    ReferenceFileSearchOutcome(
                        references = fileLocations,
                        completion = fileCompletion,
                    )
                }

                searchedFileCount += 1
                locations.addAll(fileOutcome.references)
                if (!fileOutcome.completion.exhaustive) {
                    completion = fileOutcome.completion
                    break
                }
            }

            resolutionSpan.setAttribute("kast.references.resolvedFileCount", searchedFileCount)
            ReferenceResolutionOutcome(
                references = locations,
                searchedFileCount = searchedFileCount,
                completion = completion,
            )
        }

        val completion = candidateDiscovery.completion.combine(resolution.completion)
        fallbackSpan.setAttribute("kast.references.candidateFileCount", candidateDiscovery.candidateFileCount)
        fallbackSpan.setAttribute("kast.references.searchedFileCount", resolution.searchedFileCount)
        fallbackSpan.setAttribute("kast.references.partialReason", completion.partialReason)

        ReferenceSearchOutcome(
            source = ReferenceSearchSource.IDEA,
            references = resolution.references,
            candidateFileCount = candidateDiscovery.candidateFileCount,
            searchedFileCount = resolution.searchedFileCount,
            completion = completion,
        )
    }

    private fun referenceCandidateFiles(
        plan: ReferenceSearchPlan,
        budget: ReferenceSearchBudget,
        span: IdeaTelemetrySpan,
    ): ReferenceCandidateDiscovery = span.child("kast.idea.findReferences.candidateDiscovery") { discoverySpan ->
        val needle = plan.searchNeedle
        discoverySpan.setAttribute("kast.references.candidateApi", if (needle == null) "FileTypeIndex.getFiles" else "PsiSearchHelper.processCandidateFilesForText")
        discoverySpan.setAttribute("kast.references.searchNeedle", needle?.value)

        val discovery = if (needle == null) {
            fileTypeCandidateFiles(plan, budget)
        } else {
            textIndexedCandidateFiles(plan, budget, needle)
        }

        discoverySpan.setAttribute("kast.references.candidateFileCount", discovery.candidateFileCount)
        discoverySpan.setAttribute("kast.references.candidateDiscoveryExhaustive", discovery.completion.exhaustive)
        discoverySpan.setAttribute("kast.references.partialReason", discovery.completion.partialReason)
        discovery
    }

    private fun textIndexedCandidateFiles(
        plan: ReferenceSearchPlan,
        budget: ReferenceSearchBudget,
        needle: ReferenceSearchNeedle,
    ): ReferenceCandidateDiscovery {
        val files = mutableListOf<VirtualFile>()
        var candidateFileCount = 0
        var completion: ReferenceSearchCompletion = ReferenceSearchCompletion.Exhaustive
        val helper = PsiSearchHelper.getInstance(project)
        val processor = object : Processor<VirtualFile> {
            override fun process(file: VirtualFile): Boolean {
                ProgressManager.checkCanceled()
                candidateFileCount += 1
                if (budget.requestExhausted()) {
                    completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                    return false
                }
                if (file.isValid && !file.isDirectory && isWorkspaceFile(file.path)) {
                    files += file
                }
                return true
            }
        }
        val continued = runIdeaReadAction {
            helper.processCandidateFilesForText(
                plan.searchScope,
                UsageSearchContext.IN_CODE,
                false,
                needle.value,
                processor,
            )
        }
        if (!continued && completion.exhaustive) {
            completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.CANDIDATE_DISCOVERY_STOPPED)
        }
        return ReferenceCandidateDiscovery(
            files = files.sortedBy { file -> file.path },
            candidateFileCount = candidateFileCount,
            completion = completion,
        )
    }

    private fun fileTypeCandidateFiles(
        plan: ReferenceSearchPlan,
        budget: ReferenceSearchBudget,
    ): ReferenceCandidateDiscovery {
        val files = mutableListOf<VirtualFile>()
        var candidateFileCount = 0
        var completion: ReferenceSearchCompletion = ReferenceSearchCompletion.Exhaustive
        val allKotlinFiles = runIdeaReadAction {
            kotlinCandidateFiles(plan.searchScope)
        }
        for (file in allKotlinFiles) {
            ProgressManager.checkCanceled()
            candidateFileCount += 1
            if (budget.requestExhausted()) {
                completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                break
            }
            files += file
        }
        return ReferenceCandidateDiscovery(
            files = files,
            candidateFileCount = candidateFileCount,
            completion = completion,
        )
    }

    private fun PsiReference.toReferenceLocation(includeUsageSiteScope: Boolean): Location? {
        val referenceElement = element
        if (!referenceElement.isValid) return null
        val location = referenceElement.toKastLocation(absoluteTextRange())
        if (!isWorkspaceFile(location.filePath)) return null
        return if (includeUsageSiteScope) {
            location.copy(usageSiteScope = referenceElement.usageSiteDeclarationScope())
        } else {
            location
        }
    }

    override suspend fun callHierarchy(query: ParsedCallHierarchyQuery): CallHierarchyResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.CALL_HIERARCHY, "kast.idea.callHierarchy") {
        // Resolve the root target under a short read lock; the recursive
        // traversal acquires per-level read locks inside the edge resolver
        // so the IDE write lock is not starved for the full duration.
        val rootTarget = timedReadAction(telemetry, IdeaTelemetryScope.CALL_HIERARCHY, "kast.idea.callHierarchy.resolveTarget") {
            val file = findKtFile(query.position.filePath.value)
            resolveTarget(file, query.position.offset.value)
        }

        val budget = TraversalBudget(
            maxTotalCalls = query.maxTotalCalls.value,
            maxChildrenPerNode = query.maxChildrenPerNode.value,
            timeoutMillis = query.timeoutMillis?.value ?: limits.requestTimeoutMillis,
        )
        val resolver = IdeaCallEdgeResolver(
            project = project,
            workspaceIdentity = sharedWorkspaceIdentity,
        )
        val engine = CallHierarchyEngine(edgeResolver = resolver, readAccess = ideaReadAccess)
        val root = engine.buildNode(
            target = rootTarget,
            parentCallSite = null,
            direction = query.direction,
            depthRemaining = query.depth.value,
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

    override suspend fun typeHierarchy(query: ParsedTypeHierarchyQuery): TypeHierarchyResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.TYPE_HIERARCHY, "kast.idea.typeHierarchy") {
        val rootTarget = readAction {
            val file = findKtFile(query.position.filePath.value)
            val resolved = resolveTarget(file, query.position.offset.value)
            resolved.typeHierarchyDeclaration() ?: resolved
        }
        val resolver = IdeaTypeEdgeResolver(project = project)
        val engine = TypeHierarchyEngine(edgeResolver = resolver, readAccess = ideaReadAccess)
        val budget = TypeHierarchyBudget(maxResults = query.maxResults.value)
        val root = engine.buildNode(
            target = rootTarget,
            direction = query.direction,
            depthRemaining = query.depth.value,
            pathKeys = emptySet(),
            budget = budget,
            currentDepth = 0,
        )
        TypeHierarchyResult(root = root, stats = budget.toStats())
        }
    }

    override suspend fun implementations(query: ParsedImplementationsQuery): ImplementationsResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.IMPLEMENTATIONS, "kast.idea.implementations") {
        val rootTarget = readAction {
            val file = findKtFile(query.position.filePath.value)
            val resolved = resolveTarget(file, query.position.offset.value)
            resolved.typeHierarchyDeclaration() ?: resolved
        }
        val resolver = IdeaTypeEdgeResolver(project = project)
        val declarationSymbol = resolver.symbolFor(rootTarget)
        val queue = ArrayDeque<PsiElement>()
        val visited = mutableSetOf<String>()
        val implementations = mutableListOf<Symbol>()
        queue += rootTarget
        var exhaustive = true
        val limit = query.maxResults.value

        while (queue.isNotEmpty() && implementations.size < limit) {
            val current = queue.removeFirst()
            val edges = resolver.subtypeEdges(current)
            for (edge in edges) {
                val key = "${edge.symbol.fqName}|${edge.symbol.location.filePath}:${edge.symbol.location.startOffset}"
                if (!visited.add(key)) continue
                queue += edge.target
                if (ideaReadAccess.run { isConcreteType(edge.target) }) {
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

    override suspend fun codeActions(query: ParsedCodeActionsQuery): CodeActionsResult = withContext(readDispatcher) {
        readAction {
            findKtFile(query.position.filePath.value)
            CodeActionsResult(actions = emptyList())
        }
    }

    override suspend fun completions(query: ParsedCompletionsQuery): CompletionsResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.COMPLETIONS, "kast.idea.completions") {
        readAction {
            val file = findKtFile(query.position.filePath.value)
            val kindFilter = query.kindFilter
            val items = mutableListOf<CompletionItem>()
            file.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is KtNamedDeclaration &&
                        element !is KtParameter &&
                        element.name != null &&
                        element.textOffset <= query.position.offset.value
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
            val capped = deduped.take(query.maxResults.value)
            CompletionsResult(
                items = capped,
                exhaustive = deduped.size <= capped.size,
            )
        }
        }
    }

    override suspend fun workspaceFiles(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult = withContext(readDispatcher) {
        val fileLimit = query.maxFilesPerModule?.value ?: limits.maxResults
        telemetry.inSpan(
            IdeaTelemetryScope.WORKSPACE_FILES,
            "kast.idea.workspaceFiles",
            attributes = mapOf(
                "kast.workspaceFiles.moduleName" to query.moduleName?.value,
                "kast.workspaceFiles.includeFiles" to query.includeFiles,
                "kast.workspaceFiles.maxFilesPerModule" to fileLimit,
            ),
        ) { span ->
            val allModules = timedReadAction(telemetry, IdeaTelemetryScope.WORKSPACE_FILES, "kast.idea.workspaceFiles.listModules") {
                ModuleManager.getInstance(project).modules.toList()
            }
            val targetModules = if (query.moduleName?.value != null) {
                allModules.filter { timedReadAction(telemetry, IdeaTelemetryScope.WORKSPACE_FILES, "kast.idea.workspaceFiles.filterModule") { it.name } == query.moduleName?.value }
            } else {
                allModules
            }
            val modules = targetModules.map { module ->
                timedReadAction(telemetry, IdeaTelemetryScope.WORKSPACE_FILES, "kast.idea.workspaceFiles.module") {
                val rootManager = ModuleRootManager.getInstance(module)
                val sourceRoots = rootManager.sourceRoots
                    .map { it.path }
                    .filter(::isWorkspaceFile)
                val depNames = rootManager.dependencies.map { it.name }
                val moduleScope = GlobalSearchScope.moduleScope(module)
                val kotlinFiles = kotlinFileType()?.let { fileType ->
                    FileTypeIndex.getFiles(fileType, moduleScope)
                } ?: emptyList()
                val filteredPaths = mutableListOf<String>()
                var fileCount = 0
                kotlinFiles.forEach { file ->
                    val path = file.path
                    if (isWorkspaceFile(path)) {
                        fileCount += 1
                        if (query.includeFiles && filteredPaths.size < fileLimit) {
                            filteredPaths += path
                        }
                    }
                }
                WorkspaceModule(
                    name = module.name,
                    sourceRoots = sourceRoots,
                    dependencyModuleNames = depNames,
                    files = filteredPaths.sorted(),
                    filesTruncated = query.includeFiles && fileCount > filteredPaths.size,
                    fileCount = fileCount,
                )
            }
            }
            span.setAttribute("kast.workspaceFiles.moduleCount", modules.size)
            span.setAttribute("kast.workspaceFiles.totalFileCount", modules.sumOf { it.fileCount })
            span.setAttribute("kast.workspaceFiles.returnedFileCount", modules.sumOf { it.files.size })
            span.setAttribute("kast.workspaceFiles.truncatedModuleCount", modules.count { it.filesTruncated })
            WorkspaceFilesResult(modules = modules)
        }
    }

    override suspend fun semanticInsertionPoint(
        query: ParsedSemanticInsertionQuery,
    ): SemanticInsertionResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.SEMANTIC_INSERTION_POINT, "kast.idea.semanticInsertionPoint") {
        readAction {
            val file = findKtFile(query.position.filePath.value)
            SemanticInsertionPointResolver.resolve(file, query)
        }
        }
    }

    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.DIAGNOSTICS, "kast.idea.diagnostics") {
            val fileAnalyses = coroutineScope {
                query.filePaths.value.map { filePath ->
                    async(readDispatcher) {
                        analyzeDiagnosticsFile(filePath)
                    }
                }.awaitAll()
            }
            val diagnostics = fileAnalyses
                .flatMap(DiagnosticsFileAnalysis::diagnostics)
                .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" }))

            DiagnosticsResult.of(
                diagnostics = diagnostics,
                fileStatuses = fileAnalyses.map(DiagnosticsFileAnalysis::status),
            )
        }
    }

    private suspend fun analyzeDiagnosticsFile(filePath: NormalizedPath): DiagnosticsFileAnalysis {
        if (Files.notExists(Path.of(filePath.value))) {
            return skippedDiagnostics(
                filePath = filePath,
                state = FileAnalysisState.MISSING_ON_DISK,
                message = "File not found: ${filePath.value}",
            )
        }
        if (!isWorkspaceFile(filePath.value)) {
            return skippedDiagnostics(
                filePath = filePath,
                state = FileAnalysisState.OUTSIDE_SOURCE_MODULES,
                message = "File is outside the active workspace: ${filePath.value}",
            )
        }

        return try {
            timedReadAction(
                telemetry,
                IdeaTelemetryScope.DIAGNOSTICS,
                "kast.idea.diagnostics.file",
            ) {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath.value)
                    ?: return@timedReadAction skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "File exists on disk but is not available in the IDEA virtual file system",
                    )
                if (!ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
                    return@timedReadAction skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.OUTSIDE_SOURCE_MODULES,
                        message = "File is not contained in an IDEA source module",
                    )
                }
                if (DumbService.isDumb(project)) {
                    return@timedReadAction skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "IDEA indexing is still in progress",
                    )
                }
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: return@timedReadAction skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "IDEA has not created PSI for the file yet",
                    )
                val file = psiFile as? KtFile
                    ?: return@timedReadAction skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.BACKEND_FAILURE,
                        message = "Semantic diagnostics require a Kotlin source file",
                    )
                val fileDiagnostics = analyze(file) {
                    file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                        .flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
                }
                DiagnosticsFileAnalysis(
                    status = FileAnalysisStatus.analyzed(filePath),
                    diagnostics = fileDiagnostics,
                )
            }
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            skippedDiagnostics(
                filePath = filePath,
                state = FileAnalysisState.BACKEND_FAILURE,
                message = ex.message?.takeIf(String::isNotBlank) ?: ex.toString(),
            )
        }
    }

    private fun skippedDiagnostics(
        filePath: NormalizedPath,
        state: FileAnalysisState,
        message: String,
    ): DiagnosticsFileAnalysis = DiagnosticsFileAnalysis(
        status = FileAnalysisStatus.skipped(filePath, state, message),
        diagnostics = listOf(
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
                message = message,
                code = "ANALYSIS_FAILURE",
            ),
        ),
    )

    // Note: Unlike the headless backend, IDEA's ReferencesSearch.search() resolves
    // import directives as reference sites, so explicit import FQN handling is not needed here.
    override suspend fun rename(query: ParsedRenameQuery): RenameResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.RENAME, "kast.idea.rename") {
        val (snapshot, referenceEdits) = collectInShortReadActions(
            collectSnapshot = {
                val file = findKtFile(query.position.filePath.value)
                val target = resolveTarget(file, query.position.offset.value)
                val visibility = target.visibility()
                val (searchScope, scopeKind) = visibilityScopedSearch(target, visibility)
                val candidateFileCount = kotlinFileType()?.let { fileType ->
                    FileTypeIndex.getFiles(fileType, searchScope)
                        .count { isWorkspaceFile(it.path) }
                } ?: 0
                val refs = mutableListOf<PsiReference>()
                ReferencesSearch.search(target, searchScope).forEach(
                    object : Processor<PsiReference> {
                        override fun process(ref: PsiReference): Boolean {
                            ProgressManager.checkCanceled()
                            refs.add(ref)
                            return true
                        }
                    },
                )
                RenameSnapshot(
                    declarationEdit = target.declarationEdit(query.newName.value),
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
                    newText = query.newName.value,
                )
            },
            runInitialReadAction = { action -> runIdeaReadAction(action) },
            runBatchReadAction = { action -> runIdeaReadAction(action) },
        )

        val edits = (listOf(snapshot.declarationEdit) + referenceEdits)
            .distinctBy { Triple(it.filePath, it.startOffset, it.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        val affectedFiles = edits.map(TextEdit::filePath).distinct()
        val fileHashes = IdeaFileHashComputer.currentHashes(affectedFiles)

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

    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        return telemetry.inSpan(IdeaTelemetryScope.APPLY_EDITS, "kast.idea.applyEdits") {
            val applier = IdeaEditApplier(project, workspaceRoot, workspaceIdentity)
            applier.apply(query.toWire())
            // No asyncRefresh needed - IDEA APIs handle VFS updates automatically
        }
    }

    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.OPTIMIZE_IMPORTS, "kast.idea.optimizeImports") {
        val edits = query.filePaths.value
            .map { it.value }
            .distinct()
            .sorted()
            .flatMap { filePath ->
                timedReadAction(telemetry, IdeaTelemetryScope.OPTIMIZE_IMPORTS, "kast.idea.optimizeImports.file") {
                    ImportAnalysis.optimizeImportEdits(findKtFile(filePath))
                }
            }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        val affectedFiles = edits.map(TextEdit::filePath).distinct()
        ImportOptimizeResult(
            edits = edits,
            fileHashes = IdeaFileHashComputer.currentHashes(affectedFiles),
            affectedFiles = affectedFiles,
        )
        }
    }

    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult {
        return telemetry.inSpan(IdeaTelemetryScope.REFRESH, "kast.idea.refresh") {
            ApplicationManager.getApplication().invokeLater {
                VirtualFileManager.getInstance().asyncRefresh(null)
            }
            val filePaths = query.filePaths.map { it.value }
            if (filePaths.isEmpty()) {
                RefreshResult(refreshedFiles = emptyList(), fullRefresh = true)
            } else {
                RefreshResult(refreshedFiles = filePaths, fullRefresh = false)
            }
        }
    }

    override suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.FILE_OUTLINE, "kast.idea.fileOutline") {
            timedReadAction(telemetry, IdeaTelemetryScope.FILE_OUTLINE, "kast.idea.fileOutline.readAction") {
                val file = findKtFile(query.filePath.value)
                FileOutlineResult(symbols = FileOutlineBuilder.build(file))
            }
        }
    }

    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.WORKSPACE_SYMBOL_SEARCH, "kast.idea.workspaceSymbolSearch") {
        val matcher = SymbolSearchMatcher.create(query.pattern.value, query.regex)
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        val symbols = mutableListOf<Symbol>()

        timedReadAction(telemetry, IdeaTelemetryScope.WORKSPACE_SYMBOL_SEARCH, "kast.idea.workspaceSymbolSearch.readAction") {
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

    override suspend fun workspaceSearch(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.WORKSPACE_SEARCH, "kast.idea.workspaceSearch") { span ->
            val candidateFiles = timedReadAction(
                telemetry,
                IdeaTelemetryScope.WORKSPACE_SEARCH,
                "kast.idea.workspaceSearch.listFiles",
            ) {
                val scope = GlobalSearchScope.projectScope(project)
                val fileGlob = query.fileGlob?.value
                kotlinFileType()?.let { fileType ->
                    FileTypeIndex.getFiles(fileType, scope)
                        .asSequence()
                        .filter { file -> isWorkspaceFile(file.path) }
                        .filter { file -> fileGlob == null || matchesFileGlob(file.path, fileGlob) }
                        .sortedBy { it.path }
                        .toList()
                } ?: emptyList()
            }
            span.setAttribute("kast.workspaceSearch.candidateFileCount", candidateFiles.size)
            val regex = compileWorkspaceSearchRegex(query)
            val matches = mutableListOf<SearchMatch>()
            var truncated = false

            outer@ for (file in candidateFiles) {
                ProgressManager.checkCanceled()
                val content = timedReadAction(
                    telemetry,
                    IdeaTelemetryScope.WORKSPACE_SEARCH,
                    "kast.idea.workspaceSearch.readFile",
                ) {
                    String(file.contentsToByteArray(), file.charset)
                }
                for ((lineIndex, line) in content.lineSequence().withIndex()) {
                    for (column in searchColumns(line, query, regex)) {
                        if (matches.size >= query.maxResults.value) {
                            truncated = true
                            break@outer
                        }
                        matches += SearchMatch(
                            filePath = file.path,
                            lineNumber = lineIndex + 1,
                            columnNumber = column + 1,
                            preview = line.trimEnd(),
                        )
                    }
                }
            }

            span.setAttribute("kast.workspaceSearch.resultCount", matches.size)
            span.setAttribute("kast.workspaceSearch.truncated", truncated)
            WorkspaceSearchResult(matches = matches, truncated = truncated)
        }
    }

    private fun <T : PsiElement> collectMatchingSymbols(
        scope: GlobalSearchScope,
        matcher: SymbolSearchMatcher,
        query: ParsedWorkspaceSymbolQuery,
        symbols: MutableList<Symbol>,
        allNames: Array<String>,
        lookupByName: (String, GlobalSearchScope) -> Array<out T>,
    ) {
        for (name in allNames) {
            if (symbols.size >= query.maxResults.value) break
            if (!matcher.matches(name)) continue
            for (element in lookupByName(name, scope)) {
                if (symbols.size >= query.maxResults.value) break
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
        sharedWorkspaceIdentity.contains(filePath)

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
        val relative = sharedWorkspaceIdentity.relativizeIfContained(path)
        return listOfNotNull(relative, relative?.fileName, path.fileName).any(matcher::matches)
    }

    private fun isConcreteType(target: PsiElement): Boolean = when (target) {
        is KtClass -> !target.isInterface() && !target.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        is KtObjectDeclaration -> !target.isCompanion()
        is com.intellij.psi.PsiClass -> !target.isInterface && !target.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)
        else -> false
    }

    private fun findKtFile(filePath: String): KtFile {
        val normalizedPath = Path.of(filePath).toAbsolutePath().normalize().toString()
        if (!isWorkspaceFile(normalizedPath)) {
            throw NotFoundException("File is outside the active workspace: $filePath")
        }
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
        SymbolVisibility.INTERNAL, SymbolVisibility.PUBLIC, SymbolVisibility.PROTECTED ->
            (moduleWithDependentsScope(target) ?: GlobalSearchScope.projectScope(project)) to
                SearchScopeKind.DEPENDENT_MODULES
        SymbolVisibility.UNKNOWN ->
            GlobalSearchScope.projectScope(project) to SearchScopeKind.DEPENDENT_MODULES
    }

    private fun moduleWithDependentsScope(target: PsiElement): GlobalSearchScope? {
        val file = target.containingFile as? KtFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(virtualFile) ?: return null
        return GlobalSearchScope.moduleWithDependentsScope(module)
    }

    private data class RenameSnapshot(
        val declarationEdit: TextEdit,
        val visibility: SymbolVisibility,
        val scopeKind: SearchScopeKind,
        val candidateFileCount: Int,
    )

    private data class DiagnosticsFileAnalysis(
        val status: FileAnalysisStatus,
        val diagnostics: List<Diagnostic>,
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

private data class ReferenceResolvedTarget(
    val pointer: SmartPsiElementPointer<PsiElement>,
    val targetFqName: String?,
    val searchNeedle: ReferenceSearchNeedle?,
    val declaration: Symbol?,
    val visibility: SymbolVisibility,
)

private data class ReferenceScopePlan(
    val searchScope: GlobalSearchScope,
    val scopeKind: SearchScopeKind,
)

private data class ReferenceSearchPlan(
    val target: SmartPsiElementPointer<PsiElement>,
    val targetFqName: String?,
    val searchNeedle: ReferenceSearchNeedle?,
    val declaration: Symbol?,
    val visibility: SymbolVisibility,
    val searchScope: GlobalSearchScope,
    val scopeKind: SearchScopeKind,
)

private data class ReferenceSearchOutcome(
    val source: ReferenceSearchSource,
    val references: List<Location>,
    val candidateFileCount: Int,
    val searchedFileCount: Int,
    val completion: ReferenceSearchCompletion,
)

private data class ReferenceCandidateDiscovery(
    val files: List<VirtualFile>,
    val candidateFileCount: Int,
    val completion: ReferenceSearchCompletion,
)

private data class ReferenceResolutionOutcome(
    val references: List<Location>,
    val searchedFileCount: Int,
    val completion: ReferenceSearchCompletion,
)

private data class ReferenceFileSearchOutcome(
    val references: List<Location>,
    val completion: ReferenceSearchCompletion,
)

private data class ReferenceLocationKey(
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
)

private enum class ReferenceSearchSource {
    INDEX,
    IDEA,
}

@JvmInline
private value class ReferenceSearchNeedle private constructor(val value: String) {
    companion object {
        fun from(
            target: PsiElement,
            targetFqName: String?,
        ): ReferenceSearchNeedle? {
            val candidate = (target as? PsiNamedElement)?.name
                ?: targetFqName?.substringAfterLast('.')
                ?: target.text?.takeIf { text -> text.length in 1..MAX_NEEDLE_LENGTH }
            return candidate
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::ReferenceSearchNeedle)
        }

        private const val MAX_NEEDLE_LENGTH = 128
    }
}

private sealed interface ReferenceSearchCompletion {
    val exhaustive: Boolean
    val partialReason: String?

    object Exhaustive : ReferenceSearchCompletion {
        override val exhaustive: Boolean = true
        override val partialReason: String? = null
    }

    data class Partial(
        val reason: ReferencePartialReason,
    ) : ReferenceSearchCompletion {
        override val exhaustive: Boolean = false
        override val partialReason: String = reason.name.lowercase()
    }
}

private fun ReferenceSearchCompletion.combine(
    other: ReferenceSearchCompletion,
): ReferenceSearchCompletion =
    if (this.exhaustive) other else this

private enum class ReferencePartialReason {
    REQUEST_BUDGET_EXHAUSTED,
    FILE_BUDGET_EXHAUSTED,
    TARGET_INVALIDATED,
    CANDIDATE_DISCOVERY_STOPPED,
    INDEX_LOCATION_UNRESOLVED,
}

internal fun interface ReferenceSearchClock {
    fun nanoTime(): Long

    companion object {
        val System: ReferenceSearchClock = ReferenceSearchClock { java.lang.System.nanoTime() }
    }
}

private class ReferenceSearchBudget(
    private val requestStartedNanos: Long,
    private val requestBudgetNanos: Long,
    private val perFileBudgetNanos: Long,
    private val clock: ReferenceSearchClock,
) {
    fun requestExhausted(): Boolean =
        clock.nanoTime() - requestStartedNanos >= requestBudgetNanos

    fun fileStarted(): Long = clock.nanoTime()

    fun fileExhausted(fileStartedNanos: Long): Boolean =
        clock.nanoTime() - fileStartedNanos >= perFileBudgetNanos

    companion object {
        fun start(
            limits: ServerLimits,
            clock: ReferenceSearchClock,
        ): ReferenceSearchBudget = ReferenceSearchBudget(
            requestStartedNanos = clock.nanoTime(),
            requestBudgetNanos = limits.requestTimeoutMillis.toBudgetNanos(),
            perFileBudgetNanos = limits.perFileScanBudgetMillis.toBudgetNanos(),
            clock = clock,
        )
    }
}

private fun Long.toBudgetNanos(): Long {
    val millis = coerceAtLeast(1L)
    return if (millis > Long.MAX_VALUE / NANOS_PER_MILLI) {
        Long.MAX_VALUE
    } else {
        millis * NANOS_PER_MILLI
    }
}

private fun PsiElement.referenceAtOffset(offset: Int): PsiReference? =
    generateSequence(this as PsiElement?) { element -> element.parent }
        .flatMap { element -> element.references.asSequence() }
        .filter { reference -> reference.absoluteTextRange().containsOffset(offset) }
        .minByOrNull { reference -> reference.absoluteTextRange().length }

private fun PsiReference.absoluteTextRange(): TextRange =
    rangeInElement.shiftRight(element.textRange.startOffset)

private const val NANOS_PER_MILLI = 1_000_000L
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

internal inline fun <T> runIdeaReadAction(crossinline action: () -> T): T =
    ApplicationManager.getApplication().runReadAction<T> { action() }

internal suspend inline fun <T> timedReadAction(
    telemetry: IdeaBackendTelemetry,
    scope: IdeaTelemetryScope,
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
