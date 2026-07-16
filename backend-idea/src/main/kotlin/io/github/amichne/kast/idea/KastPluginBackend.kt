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
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.continuation.ContinuationConsumeResult
import io.github.amichne.kast.api.continuation.ContinuationAccessFailure
import io.github.amichne.kast.api.continuation.ContinuationIssueResult
import io.github.amichne.kast.api.continuation.ContinuationOwnedState
import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.continuation.ContinuationStateDisposer
import io.github.amichne.kast.api.continuation.ContinuationStateTransition
import io.github.amichne.kast.api.continuation.ContinuationTokenIssuer
import io.github.amichne.kast.api.continuation.ContinuationTransition
import io.github.amichne.kast.api.continuation.ServerHeldContinuationStore as SharedContinuationStore
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CallRelation
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionItem
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelation
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ContainingSymbolUnavailableReason
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
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
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyRelation
import io.github.amichne.kast.api.contract.result.RelationTraversalFamily
import io.github.amichne.kast.api.contract.result.RelationTraversalHandle
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
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
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import kotlinx.coroutines.Dispatchers
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
    private val semanticAdmissionAwaiter: IdeaSemanticAdmissionAwaiter =
        IdeaSemanticAdmissionAwaiter.forRequestBudget(limits.requestTimeoutMillis),
    private val semanticAdmissionOperations: IdeaSemanticAdmissionOperations =
        IdeaSemanticAdmissionOperations.idea(),
    private val psiGeneration: () -> Long = { PsiModificationTracker.getInstance(project).modificationCount },
    private val readEpochObserver: IdeaReadEpochObserver = IdeaReadEpochObserver.Disabled,
    private val referenceTraversalObserver: ReferenceTraversalObserver = ReferenceTraversalObserver.Disabled,
    private val indexSemanticAdmissionStatus: () -> IdeaIndexSemanticAdmission.Status = {
        IdeaIndexSemanticAdmission.Status.Ready
    },
) : CloseableAnalysisBackend {

    private val readDispatcher = Dispatchers.Default.limitedParallelism(limits.maxConcurrentRequests)
    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    private val sharedWorkspaceIdentity = workspaceIdentity.workspaceIdentity
    private val referenceContinuations = SharedContinuationStore<
        ReferencePageToken,
        ReferenceQueryIdentity,
        ReferenceContinuationState,
        ReferenceContinuationProjection,
    >(
        capacity = limits.typedContinuationCapacity,
        timeToLive = limits.typedContinuationTtl,
        tokenIssuer = ContinuationTokenIssuer(ReferencePageToken::random),
        stateDisposer = ContinuationStateDisposer(ReferenceContinuationState::close),
    )
    private val diagnosticContinuations = SharedContinuationStore<
        DiagnosticPageToken,
        DiagnosticQueryIdentity,
        DiagnosticContinuationState,
        DiagnosticContinuationProjection,
    >(
        capacity = limits.typedContinuationCapacity,
        timeToLive = limits.typedContinuationTtl,
        tokenIssuer = ContinuationTokenIssuer(DiagnosticPageToken::random),
        stateDisposer = ContinuationStateDisposer { },
    )
    private val relationshipContinuations = RelationshipContinuationStore(limits)
    private val workspaceFilePaging = IdeaWorkspaceFilePaging(
        workspaceId = sharedWorkspaceIdentity.canonicalWorkspaceId,
        inventory = IdeaProjectModelWorkspaceFileInventory(project, workspaceIdentity),
        limits = limits,
    )
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

    private fun referenceSearchRoots(plan: ReferenceSearchPlan): List<Path> {
        val targetFile = plan.target.element
            ?.containingFile
            ?.virtualFile
            ?.path
            ?.let(Path::of)
        if (plan.scopeKind == SearchScopeKind.FILE && targetFile != null) {
            return listOf(targetFile)
        }

        val moduleRoots = ModuleManager.getInstance(project).modules
            .asSequence()
            .flatMap { module -> ModuleRootManager.getInstance(module).sourceRoots.asSequence() }
            .filter { root -> root.isValid && root.isDirectory && isWorkspaceFile(root.path) }
            .map { root -> Path.of(root.path).toAbsolutePath().normalize() }
            .distinct()
            .sortedBy(Path::toString)
            .toList()
        if (moduleRoots.isNotEmpty()) return moduleRoots

        val targetDirectory = targetFile?.parent
        return listOfNotNull(targetDirectory)
    }

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
        val admission = indexSemanticAdmissionStatus()
        val state = when {
            admission is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeState.DEGRADED
            isDumb || admission is IdeaIndexSemanticAdmission.Status.Pending -> RuntimeState.INDEXING
            else -> RuntimeState.READY
        }
        val moduleNames = ModuleManager.getInstance(project).modules.map { it.name }.sorted()
        return RuntimeStatusResponse(
            state = state,
            healthy = state != RuntimeState.DEGRADED,
            active = true,
            indexing = state == RuntimeState.INDEXING,
            backendName = caps.backendName,
            backendVersion = caps.backendVersion,
            workspaceRoot = caps.workspaceRoot,
            message = when {
                admission is IdeaIndexSemanticAdmission.Status.Failed ->
                    "IDEA compiler-backed semantic admission failed: ${admission.detail}"
                isDumb -> "IDEA is indexing — analysis results may be incomplete"
                admission is IdeaIndexSemanticAdmission.Status.Pending ->
                    "IDEA compiler-backed semantic admission is pending: ${admission.detail}"
                else -> "IDEA analysis backend is ready"
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
            val identity = ReferenceQueryIdentity.from(query)
            val pageToken = query.pageToken
            val (projection, nextPageToken) = if (pageToken != null) {
                val token = pageToken
                when (val consumed = referenceContinuations.consume(
                    token = token,
                    query = identity,
                    transition = ContinuationStateTransition { state ->
                        val page = referenceContinuationPage(query, state, span)
                        page.outcome.nextPosition?.let { nextPosition ->
                            state.advanceTo(page.knownCount, nextPosition)
                            ContinuationTransition.Reissue(page, identity)
                        } ?: ContinuationTransition.Complete(page)
                    },
                )) {
                    is ContinuationConsumeResult.Completed -> consumed.output to null
                    is ContinuationConsumeResult.Reissued -> consumed.output to consumed.token
                    is ContinuationConsumeResult.Rejected -> throw ConflictException(
                        message = "The reference page token is unknown, expired, consumed, or belongs to another query",
                        details = mapOf(
                            "pageToken" to token.value,
                            "continuationFailure" to when (consumed.failure) {
                                ContinuationAccessFailure.ExpiredToken -> "expired"
                                ContinuationAccessFailure.QueryMismatch -> "queryMismatch"
                                ContinuationAccessFailure.StoreClosed,
                                ContinuationAccessFailure.TokenCollision,
                                ContinuationAccessFailure.UnknownToken,
                                -> "unknown"
                            },
                        ),
                    )
                }
            } else {
                val plan = referenceSearchPlan(query, span)
                val outcome = indexedReferenceSearch(query, plan, null, span)
                    ?: ideaReferenceSearch(query, plan, null, span)
                val knownCount = outcome.references.size
                val page = ReferenceContinuationProjection(plan, outcome, knownCount)
                val token = outcome.nextPosition?.let { nextPosition ->
                    when (val issued = referenceContinuations.issue(
                        query = identity,
                        state = ReferenceContinuationState(
                            plan = plan,
                            returnedBefore = knownCount,
                            position = nextPosition,
                        ),
                    )) {
                        is ContinuationIssueResult.Issued -> issued.token
                        is ContinuationIssueResult.Rejected -> throw ConflictException(
                            message = "Reference continuation store is unavailable",
                            details = mapOf("continuationFailure" to "boundSourceUnavailable"),
                        )
                    }
                }
                page to token
            }
            val plan = projection.plan
            val outcome = projection.outcome
            val cardinality = if (outcome.hasMoreEvidence || !outcome.completion.exhaustive) {
                ResultCardinality.KnownMinimum(projection.knownCount)
            } else {
                ResultCardinality.Exact(projection.knownCount)
            }
            val page = nextPageToken?.let { token ->
                PageInfo(
                    truncated = true,
                    nextPageToken = token.value,
                )
            }

            span.setAttribute("kast.references.source", outcome.source.name.lowercase())
            span.setAttribute("kast.references.visibility", plan.visibility.name)
            span.setAttribute("kast.references.scope", plan.scopeKind.name)
            span.setAttribute("kast.references.candidateFileCount", outcome.candidateFileCount)
            span.setAttribute("kast.references.searchedFileCount", outcome.searchedFileCount)
            span.setAttribute("kast.references.evidenceCount", outcome.consumedEvidence)
            span.setAttribute("kast.references.observedEvidenceCount", outcome.observedEvidence)
            span.setAttribute("kast.references.knownMinimumCount", cardinality.knownMinimum())
            span.setAttribute("kast.references.resultCount", outcome.references.size)
            span.setAttribute("kast.references.exhaustive", outcome.completion.exhaustive)
            span.setAttribute("kast.references.partialReason", outcome.completion.partialReason)

            ReferencesResult(
                declaration = plan.declaration,
                references = outcome.references,
                cardinality = cardinality,
                page = page,
                searchScope = SearchScope(
                    visibility = plan.visibility,
                    scope = plan.scopeKind,
                    exhaustive = outcome.completion.exhaustive && !outcome.hasMoreEvidence,
                    candidateCoverage = if (outcome.completion.exhaustive) {
                        SearchScope.CandidateCoverage.COMPLETE
                    } else {
                        SearchScope.CandidateCoverage.PARTIAL
                    },
                    candidateFileCount = outcome.candidateFileCount,
                    searchedFileCount = outcome.searchedFileCount,
                ),
            )
        }
    }

    private fun referenceContinuationPage(
        query: ParsedReferencesQuery,
        continuation: ReferenceContinuationState,
        span: IdeaTelemetrySpan,
    ): ReferenceContinuationProjection {
        val outcome = when (val position = continuation.position) {
            is ReferenceContinuationPosition.Index -> indexedReferenceSearch(query, continuation.plan, position, span)
                ?: error("Indexed continuation must return an indexed outcome or throw a typed conflict")
            is ReferenceContinuationPosition.Idea -> ideaReferenceSearch(query, continuation.plan, position, span)
        }
        return ReferenceContinuationProjection(
            plan = continuation.plan,
            outcome = outcome,
            knownCount = Math.addExact(continuation.returnedBefore, outcome.references.size),
        )
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
                val symbol = analyze(file) {
                    element.toSymbolModel(containingDeclaration = compilerContainingDeclarationName(element))
                }
                query.selector?.let { selector ->
                    val selectorMatches = selector.fqName == symbol.fqName &&
                        NormalizedPath.parse(selector.declarationFile) == NormalizedPath.parse(symbol.location.filePath) &&
                        selector.declarationStartOffset == symbol.location.startOffset &&
                        (selector.kind == null || selector.kind == symbol.kind) &&
                        (selector.containingType == null || selector.containingType == symbol.containingDeclaration)
                    if (!selectorMatches) {
                        throw ConflictException(
                            message = "The resolved reference target does not match its exact selector",
                            details = mapOf("referenceTarget" to "identityMismatch"),
                        )
                    }
                }
                ReferenceResolvedTarget(
                    pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
                    targetFqName = targetFqName,
                    exactTarget = targetFqName?.let { fqName ->
                        ExactReferenceTarget(
                            fqName = fqName,
                            declarationFile = NormalizedPath.parse(symbol.location.filePath),
                            declarationStartOffset = NonNegativeInt(symbol.location.startOffset),
                        )
                    },
                    declaration = if (query.includeDeclaration) {
                        symbol
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
            exactTarget = target.exactTarget,
            declaration = target.declaration,
            visibility = target.visibility,
            searchScope = scope.searchScope,
            scopeKind = scope.scopeKind,
        )
    }

    private fun indexedReferenceSearch(
        query: ParsedReferencesQuery,
        plan: ReferenceSearchPlan,
        continuation: ReferenceContinuationPosition.Index?,
        span: IdeaTelemetrySpan,
    ): ReferenceSearchOutcome? = span.child("kast.idea.findReferences.indexLookup") { indexSpan ->
        val target = plan.exactTarget ?: return@child null
        when (
            val lookup = referenceIndexLookup.referencesTo(
                target,
                continuation?.offset ?: io.github.amichne.kast.api.contract.NonNegativeInt(0),
                query.maxResults,
            )
        ) {
            IndexedReferenceLookupResult.NotReady -> {
                indexSpan.setAttribute("kast.references.indexReady", false)
                if (continuation != null) {
                    throw ConflictException(
                        message = "The source index became unavailable while continuing an indexed reference page",
                        details = mapOf(
                            "pageTokenSource" to "INDEX",
                            "continuationFailure" to "boundSourceUnavailable",
                        ),
                    )
                }
                null
            }
            is IndexedReferenceLookupResult.IdentityUnavailable -> {
                indexSpan.setAttribute("kast.references.indexReady", true)
                indexSpan.setAttribute("kast.references.indexIdentityAvailable", false)
                if (continuation != null) {
                    throw ConflictException(
                        message = "The source index cannot prove the exact reference target identity",
                        details = mapOf(
                            "pageTokenSource" to "INDEX",
                            "continuationFailure" to "indexIdentityUnavailable",
                        ),
                    )
                }
                null
            }
            is IndexedReferenceLookupResult.Ready -> {
                if (continuation != null && continuation.generation != lookup.generation) {
                    throw ConflictException(
                        message = "The source index changed after the preceding reference page",
                        details = mapOf(
                            "pageTokenSource" to "INDEX",
                            "continuationFailure" to "generationChanged",
                        ),
                    )
                }
                if (
                    continuation == null &&
                    lookup.page.references.isEmpty() &&
                    lookup.page.nextOffset == null
                ) {
                    indexSpan.setAttribute("kast.references.indexReady", true)
                    indexSpan.setAttribute("kast.references.indexEmptyFallback", true)
                    return@child null
                }
                val indexedRows = runIdeaReadAction {
                    lookup.page.references.filter { row -> indexedReferenceRowInScope(row, plan.searchScope) }
                }
                val indexedSourcePaths = indexedRows.mapTo(mutableSetOf()) { row -> row.sourcePath }
                val cumulativeCandidateFilePaths = continuation?.candidateFilePaths.orEmpty() + indexedSourcePaths
                val cumulativeSearchedFilePaths = continuation?.searchedFilePaths.orEmpty() + indexedSourcePaths
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
                    consumedEvidence = lookup.page.references.size,
                    observedEvidence = lookup.page.references.size,
                    nextPosition = lookup.page.nextOffset?.let { nextOffset ->
                        ReferenceContinuationPosition.Index(
                            offset = nextOffset,
                            generation = lookup.generation,
                            candidateFilePaths = cumulativeCandidateFilePaths,
                            searchedFilePaths = cumulativeSearchedFilePaths,
                        )
                    },
                    candidateFileCount = cumulativeCandidateFilePaths.size,
                    searchedFileCount = cumulativeSearchedFilePaths.size,
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
    ): List<ReferenceOccurrence> {
        val locations = mutableListOf<ReferenceOccurrence>()
        for (batch in rows.chunked(READ_ACTION_BATCH_SIZE)) {
            val batchLocations = runIdeaReadAction {
                batch.mapNotNull { row -> indexedReferenceLocationOrNull(row, includeUsageSiteScope) }
            }
            locations.addAll(batchLocations)
        }
        return locations
            .distinctBy { it.location.key() }
            .sortedWith(referenceOccurrenceOrder)
    }

    private fun indexedReferenceLocationOrNull(
        row: SymbolReferenceRow,
        includeUsageSiteScope: Boolean,
    ): ReferenceOccurrence? = try {
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
    ): ReferenceOccurrence? {
        if (!isWorkspaceFile(row.sourcePath)) return null
        val file = findKtFile(row.sourcePath)
        val sourceOffset = row.sourceOffset.coerceIn(0, file.textLength)
        val anchor = file.findElementAt(sourceOffset) ?: return null
        val reference = anchor.referenceAtOffset(sourceOffset)
        val element = reference?.element ?: anchor
        if (!element.isValid) return null
        val range = reference?.absoluteTextRange() ?: indexedFallbackRange(file, row)
        val location = element.toKastLocation(range)
        val enrichedLocation = if (includeUsageSiteScope) {
            location.copy(usageSiteScope = element.usageSiteDeclarationScope())
        } else {
            location
        }
        return ReferenceOccurrence(
            location = enrichedLocation,
            containingSymbol = element.containingSymbolEvidence(),
        )
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
        continuation: ReferenceContinuationPosition.Idea?,
        span: IdeaTelemetrySpan,
    ): ReferenceSearchOutcome = span.child("kast.idea.findReferences.findUsagesFallback") { fallbackSpan ->
        val budget = try {
            ReferenceSearchBudget.start(limits, referenceSearchClock)
        } catch (failure: Throwable) {
            continuation?.traversal?.close()
            throw failure
        }
        fallbackSpan.setAttribute("kast.references.fallbackApi", "server-held-psi-traversal")
        val locations = mutableListOf<ReferenceOccurrence>()
        var completion: ReferenceSearchCompletion = ReferenceSearchCompletion.Exhaustive
        var pathProbes = 0
        var psiFileProbes = 0
        var elementProbes = 0
        var referenceProbes = 0
        var compilerProviderProbes = 0
        val compilerProviderProbeLimit = Math.addExact(query.maxResults.value, 1)
        var resolutionFailed = false
        var position: ReferenceContinuationPosition.Idea? = continuation
        try {
            runIdeaReadAction {
                val currentGeneration = psiGeneration()
                readEpochObserver.entered(IdeaReadEpochKind.REFERENCES)
                if (continuation != null && continuation.generation != currentGeneration) {
                    continuation.traversal.close()
                    throw ConflictException(
                        message = "Kotlin PSI changed after the preceding reference page",
                        details = mapOf(
                            "pageTokenSource" to "IDEA",
                            "continuationFailure" to "generationChanged",
                        ),
                    )
                }
                if (position == null) {
                    val searchRoots = referenceSearchRoots(plan)
                    if (searchRoots.isEmpty()) {
                        throw NotFoundException("The reference target has no searchable source root")
                    }
                    position = ReferenceContinuationPosition.Idea(
                        traversal = IdeaReferenceTraversal(searchRoots, referenceTraversalObserver),
                        pending = null,
                        generation = currentGeneration,
                        candidateFilePaths = linkedSetOf(),
                        searchedFilePaths = linkedSetOf(),
                        seenLocations = linkedSetOf(),
                    )
                }
                val activePosition = requireNotNull(position)
                activePosition.pending?.let(locations::add)
                val target = plan.target.element
                    ?: run {
                        completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.TARGET_INVALIDATED)
                        activePosition.traversal.exhausted = true
                        activePosition.traversal.close()
                        return@runIdeaReadAction
                    }
                search@ while (
                    locations.size <= query.maxResults.value &&
                    (activePosition.traversal.currentFile != null || pathProbes < REFERENCE_DISCOVERY_PATH_LIMIT)
                ) {
                    ProgressManager.checkCanceled()
                    if (budget.requestExhausted()) {
                        completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                        break
                    }
                    var currentFile = activePosition.traversal.currentFile
                    if (currentFile == null) {
                        while (pathProbes < REFERENCE_DISCOVERY_PATH_LIMIT) {
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            if (!activePosition.traversal.paths.hasNext()) {
                                activePosition.traversal.exhausted = true
                                activePosition.traversal.close()
                                break
                            }
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            val path = activePosition.traversal.paths.next()
                            pathProbes += 1
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            val fileName = path.fileName.toString()
                            if (
                                !Files.isRegularFile(path) ||
                                !(fileName.endsWith(".kt") || fileName.endsWith(".kts"))
                            ) continue
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path.toString()) ?: continue
                            if (!plan.searchScope.contains(virtualFile)) continue
                            currentFile = virtualFile
                            activePosition.traversal.currentFile = virtualFile
                            activePosition.traversal.nextOffset = 0
                            activePosition.traversal.nextReferenceIndex = 0
                            activePosition.candidateFilePaths += virtualFile.path
                            break
                        }
                        if (currentFile == null) {
                            if (!activePosition.traversal.exhausted && pathProbes >= REFERENCE_DISCOVERY_PATH_LIMIT) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.PATH_BUDGET_EXHAUSTED,
                                )
                            }
                            break
                        }
                    }
                    if (budget.requestExhausted()) {
                        completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                        break
                    }
                    psiFileProbes += 1
                    val file = PsiManager.getInstance(project).findFile(currentFile)
                    if (file == null) {
                        activePosition.searchedFilePaths += currentFile.path
                        activePosition.traversal.currentFile = null
                        activePosition.traversal.nextOffset = 0
                        activePosition.traversal.nextReferenceIndex = 0
                        continue
                    }
                    val fileStartedNanos = budget.fileStarted()
                    var leaf = file.findElementAt(activePosition.traversal.nextOffset)
                    while (leaf != null) {
                        if (locations.size > query.maxResults.value) break@search
                        if (budget.requestExhausted()) {
                            completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                            break@search
                        }
                        if (budget.fileExhausted(fileStartedNanos)) {
                            completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.FILE_BUDGET_EXHAUSTED)
                            break@search
                        }
                        val leafStart = leaf.textRange.startOffset
                        val nextLeaf = PsiTreeUtil.nextLeaf(leaf, true)
                        elementProbes += 1
                        val references = referencesAtLeaf(file, leaf, leafStart)
                        var referenceIndex = activePosition.traversal.nextReferenceIndex
                        activePosition.traversal.nextOffset = leafStart
                        while (referenceIndex < references.size) {
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            if (budget.fileExhausted(fileStartedNanos)) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.FILE_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            val reference = references[referenceIndex]
                            referenceProbes += 1
                            val resolved = try {
                                reference.resolve()
                            } catch (failure: ProcessCanceledException) {
                                throw failure
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                resolutionFailed = true
                                null
                            }
                            if (
                                resolved != null &&
                                (resolved == target || resolved.navigationElement == target.navigationElement)
                            ) {
                                reference.toReferenceOccurrence(query.includeUsageSiteScope)?.let { occurrence ->
                                    if (activePosition.seenLocations.add(occurrence.location.key())) {
                                        locations += occurrence
                                    }
                                }
                            }
                            referenceTraversalObserver.referenceProcessed(
                                filePath = currentFile.path,
                                leafOffset = leafStart,
                                referenceIndex = referenceIndex,
                                referenceCount = references.size,
                            )
                            referenceIndex += 1
                            activePosition.traversal.nextReferenceIndex = referenceIndex
                            if (locations.size > query.maxResults.value) break@search
                        }
                        activePosition.traversal.nextOffset = nextLeaf?.textRange?.startOffset ?: file.textLength
                        activePosition.traversal.nextReferenceIndex = 0
                        leaf = nextLeaf
                    }
                    var providerStoppedForBudget = false
                    var providerStoppedForPage = false
                    var providerStoppedForLimit = false
                    val providerCompleted = ReferencesSearch.search(target, LocalSearchScope(file)).forEach(
                        Processor { reference ->
                            if (budget.requestExhausted() || budget.fileExhausted(fileStartedNanos)) {
                                providerStoppedForBudget = true
                                return@Processor false
                            }
                            compilerProviderProbes += 1
                            if (compilerProviderProbes > compilerProviderProbeLimit) {
                                providerStoppedForLimit = true
                                return@Processor false
                            }
                            reference.toReferenceOccurrence(query.includeUsageSiteScope)?.let { occurrence ->
                                if (activePosition.seenLocations.add(occurrence.location.key())) {
                                    locations += occurrence
                                }
                            }
                            if (locations.size > query.maxResults.value) {
                                providerStoppedForPage = true
                                false
                            } else {
                                true
                            }
                        },
                    )
                    if (!providerCompleted) {
                        completion = when {
                            providerStoppedForBudget -> ReferenceSearchCompletion.Partial(
                                ReferencePartialReason.FILE_BUDGET_EXHAUSTED,
                            )
                            providerStoppedForLimit -> ReferenceSearchCompletion.Partial(
                                ReferencePartialReason.COMPILER_PROVIDER_LIMIT_EXHAUSTED,
                            )
                            providerStoppedForPage -> completion
                            else -> ReferenceSearchCompletion.Partial(ReferencePartialReason.PSI_RESOLUTION_FAILED)
                        }
                        if (providerStoppedForLimit) {
                            activePosition.traversal.exhausted = true
                            activePosition.traversal.close()
                        }
                        break@search
                    }
                    activePosition.searchedFilePaths += currentFile.path
                    activePosition.traversal.currentFile = null
                    activePosition.traversal.nextOffset = 0
                    activePosition.traversal.nextReferenceIndex = 0
                }
            }
        } catch (failure: Throwable) {
            position?.traversal?.close()
            throw failure
        }
        val completedPosition = requireNotNull(position)
        if (resolutionFailed && completion == ReferenceSearchCompletion.Exhaustive) {
            completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.PSI_RESOLUTION_FAILED)
        }
        val pageReferences = locations.take(query.maxResults.value).sortedWith(referenceOccurrenceOrder)
        val pending = locations.getOrNull(query.maxResults.value)
        val nextPosition = if (completedPosition.traversal.exhausted) {
            null
        } else {
            completedPosition.copy(pending = pending)
        }
        fallbackSpan.setAttribute("kast.references.pathProbeCount", pathProbes)
        fallbackSpan.setAttribute("kast.references.psiFileProbeCount", psiFileProbes)
        fallbackSpan.setAttribute("kast.references.elementProbeCount", elementProbes)
        fallbackSpan.setAttribute("kast.references.referenceProbeCount", referenceProbes)
        fallbackSpan.setAttribute("kast.references.compilerProviderProbeCount", compilerProviderProbes)
        fallbackSpan.setAttribute("kast.references.candidateFileCount", completedPosition.candidateFilePaths.size)
        fallbackSpan.setAttribute("kast.references.searchedFileCount", completedPosition.searchedFilePaths.size)
        fallbackSpan.setAttribute("kast.references.partialReason", completion.partialReason)
        fallbackSpan.child("kast.idea.findReferences.candidateDiscovery") { candidateSpan ->
            candidateSpan.setAttribute("kast.references.candidateFileCount", completedPosition.candidateFilePaths.size)
            candidateSpan.setAttribute("kast.references.pathProbeCount", pathProbes)
        }
        fallbackSpan.child("kast.idea.findReferences.referenceResolution") { resolutionSpan ->
            resolutionSpan.setAttribute("kast.references.elementProbeCount", elementProbes)
            resolutionSpan.setAttribute("kast.references.resultCount", pageReferences.size)
        }

        ReferenceSearchOutcome(
            source = ReferenceSearchSource.IDEA,
            references = pageReferences,
            consumedEvidence = pageReferences.size,
            observedEvidence = locations.size,
            nextPosition = nextPosition,
            candidateFileCount = completedPosition.candidateFilePaths.size,
            searchedFileCount = completedPosition.searchedFilePaths.size,
            completion = completion,
        )
    }

    private fun PsiReference.toReferenceOccurrence(includeUsageSiteScope: Boolean): ReferenceOccurrence? {
        val referenceElement = element
        if (!referenceElement.isValid) return null
        val location = referenceElement.toKastLocation(absoluteTextRange())
        if (!isWorkspaceFile(location.filePath)) return null
        val enrichedLocation = if (includeUsageSiteScope) {
            location.copy(usageSiteScope = referenceElement.usageSiteDeclarationScope())
        } else {
            location
        }
        return ReferenceOccurrence(
            location = enrichedLocation,
            containingSymbol = referenceElement.containingSymbolEvidence(),
        )
    }

    private fun PsiElement.containingSymbolEvidence(): ContainingSymbolEvidence {
        val owner = PsiTreeUtil.getParentOfType(this, KtNamedDeclaration::class.java, false)
            ?: return ContainingSymbolEvidence.TopLevel
        return try {
            val symbol = analyze(owner.containingKtFile) {
                owner.toSymbolModel(containingDeclaration = compilerContainingDeclarationName(owner))
            }
            ContainingSymbolEvidence.Known(
                io.github.amichne.kast.api.contract.SymbolIdentity(
                    fqName = symbol.fqName,
                    kind = symbol.kind,
                    declarationFile = NormalizedPath.parse(symbol.location.filePath),
                    declarationStartOffset = NonNegativeInt(symbol.location.startOffset),
                    containingType = symbol.containingDeclaration,
                ),
            )
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            ContainingSymbolEvidence.Unavailable(ContainingSymbolUnavailableReason.NO_SEMANTIC_OWNER)
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

    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult =
        withContext(readDispatcher) {
            val continuationQuery = RelationshipContinuationStore.CallQuery(
                selector = query.selector,
                direction = query.direction,
                depth = query.depth,
                limit = query.maxResults,
            )
            val handle = query.pageToken?.let(RelationTraversalHandle::parse)
            if (handle != null) {
                return@withContext timedReadAction(
                    telemetry,
                    IdeaTelemetryScope.CALL_HIERARCHY,
                    "kast.idea.callRelations.continue",
                ) {
                    relationshipContinuations.calls(
                        continuationQuery,
                        handle,
                        null,
                        psiGeneration(),
                    )
                }
            }
            val generation = psiGeneration()
            val direction = when (query.direction) {
                io.github.amichne.kast.api.contract.skill.WrapperCallDirection.INCOMING ->
                    io.github.amichne.kast.api.contract.CallDirection.INCOMING
                io.github.amichne.kast.api.contract.skill.WrapperCallDirection.OUTGOING ->
                    io.github.amichne.kast.api.contract.CallDirection.OUTGOING
            }
            val result = callHierarchy(
                io.github.amichne.kast.api.contract.query.CallHierarchyQuery(
                    position = io.github.amichne.kast.api.contract.FilePosition(
                        filePath = query.selector.declarationFile,
                        offset = query.selector.declarationStartOffset,
                    ),
                    direction = direction,
                    depth = query.depth,
                    maxTotalCalls = RELATIONSHIP_STATE_CAPACITY,
                    maxChildrenPerNode = RELATIONSHIP_STATE_CAPACITY,
                    timeoutMillis = limits.requestTimeoutMillis,
                ).parsed(),
            )
            if (result.stats.timeoutReached) throw continuationConflict("timeout")
            if (result.stats.truncatedNodes > 0 ||
                result.stats.maxTotalCallsReached ||
                result.stats.maxChildrenPerNodeReached
            ) {
                throw continuationConflict("candidateBudgetReached")
            }
            val records = flattenCallRelations(result.root, direction)
            if (records.size > RELATIONSHIP_STATE_CAPACITY) {
                throw continuationConflict("traversalStateBudgetReached")
            }
            timedReadAction(
                telemetry,
                IdeaTelemetryScope.CALL_HIERARCHY,
                "kast.idea.callRelations.commit",
            ) {
                if (psiGeneration() != generation) throw continuationConflict("generationChanged")
                relationshipContinuations.calls(
                    continuationQuery,
                    null,
                    records,
                    generation,
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

    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult =
        withContext(readDispatcher) {
            val continuationQuery = RelationshipContinuationStore.HierarchyQuery(
                selector = query.selector,
                direction = query.direction,
                depth = query.depth,
                limit = query.maxResults,
            )
            val handle = query.pageToken?.let(RelationTraversalHandle::parse)
            if (handle != null) {
                return@withContext timedReadAction(
                    telemetry,
                    IdeaTelemetryScope.TYPE_HIERARCHY,
                    "kast.idea.hierarchyRelations.continue",
                ) {
                    relationshipContinuations.hierarchy(
                        continuationQuery,
                        handle,
                        null,
                        psiGeneration(),
                    )
                }
            }
            val generation = psiGeneration()
            val directions = when (query.direction) {
                io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUPERTYPES ->
                    listOf(io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUPERTYPES)
                io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUBTYPES ->
                    listOf(io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUBTYPES)
                io.github.amichne.kast.api.contract.TypeHierarchyDirection.BOTH -> listOf(
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUPERTYPES,
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUBTYPES,
                )
            }
            val records = directions.flatMap { direction ->
                val result = typeHierarchy(
                    io.github.amichne.kast.api.contract.query.TypeHierarchyQuery(
                        position = io.github.amichne.kast.api.contract.FilePosition(
                            filePath = query.selector.declarationFile,
                            offset = query.selector.declarationStartOffset,
                        ),
                        direction = direction,
                        depth = query.depth,
                        maxResults = RELATIONSHIP_STATE_CAPACITY,
                    ).parsed(),
                )
                if (result.stats.truncated) throw continuationConflict("candidateBudgetReached")
                flattenHierarchyRelations(result.root, direction)
            }.sortedWith(
                compareBy<TypeHierarchyRelation>(
                    TypeHierarchyRelation::depth,
                    { relation -> relation.relatedSymbol.fqName },
                    { relation -> relation.relatedSymbol.kind.name },
                    { relation -> relation.relatedSymbol.declarationFile.value },
                    { relation -> relation.relatedSymbol.declarationStartOffset.value },
                    { relation -> relation.relation.name },
                ),
            )
            if (records.size > RELATIONSHIP_STATE_CAPACITY) {
                throw continuationConflict("traversalStateBudgetReached")
            }
            timedReadAction(
                telemetry,
                IdeaTelemetryScope.TYPE_HIERARCHY,
                "kast.idea.hierarchyRelations.commit",
            ) {
                if (psiGeneration() != generation) throw continuationConflict("generationChanged")
                relationshipContinuations.hierarchy(
                    continuationQuery,
                    null,
                    records,
                    generation,
                )
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

    override suspend fun implementationRelations(
        query: KastImplementationsQuery,
    ): ImplementationRelationsResult = withContext(readDispatcher) {
        val continuationQuery = RelationshipContinuationStore.ImplementationQuery(
            selector = query.selector,
            limit = query.maxResults,
        )
        val handle = query.pageToken?.let(RelationTraversalHandle::parse)
        if (handle != null) {
            return@withContext timedReadAction(
                telemetry,
                IdeaTelemetryScope.IMPLEMENTATIONS,
                "kast.idea.implementationRelations.continue",
            ) {
                relationshipContinuations.implementations(
                    continuationQuery,
                    handle,
                    null,
                    psiGeneration(),
                )
            }
        }
        val generation = psiGeneration()
        val result = implementations(
            io.github.amichne.kast.api.contract.query.ImplementationsQuery(
                position = io.github.amichne.kast.api.contract.FilePosition(
                    filePath = query.selector.declarationFile,
                    offset = query.selector.declarationStartOffset,
                ),
                maxResults = RELATIONSHIP_STATE_CAPACITY,
            ).parsed(),
        )
        if (!result.exhaustive) throw continuationConflict("candidateBudgetReached")
        val records = result.implementations.map { symbol ->
            ImplementationRelation(
                implementation = symbol.relationshipIdentity(),
                declarationLocation = symbol.location,
            )
        }
        if (records.size > RELATIONSHIP_STATE_CAPACITY) {
            throw continuationConflict("traversalStateBudgetReached")
        }
        timedReadAction(
            telemetry,
            IdeaTelemetryScope.IMPLEMENTATIONS,
            "kast.idea.implementationRelations.commit",
        ) {
            if (psiGeneration() != generation) throw continuationConflict("generationChanged")
            relationshipContinuations.implementations(
                continuationQuery,
                null,
                records,
                generation,
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
        telemetry.inSpan(
            IdeaTelemetryScope.WORKSPACE_FILES,
            "kast.idea.workspaceFiles",
            attributes = mapOf(
                "kast.workspaceFiles.moduleName" to query.moduleName?.value,
                "kast.workspaceFiles.includeFiles" to query.includeFiles,
                "kast.workspaceFiles.maxFilesPerModule" to query.maxFilesPerModule?.value,
                "kast.workspaceFiles.kindDomain" to query.kindDomain.name,
                "kast.workspaceFiles.hasSnapshotToken" to (query.snapshotToken != null),
                "kast.workspaceFiles.hasPageToken" to (query.pageToken != null),
            ),
        ) { span ->
            val result = workspaceFilePaging.query(query)
            val modules = result.modules
            span.setAttribute("kast.workspaceFiles.moduleCount", modules.size)
            span.setAttribute("kast.workspaceFiles.totalFileCount", modules.sumOf { it.fileCount })
            span.setAttribute("kast.workspaceFiles.returnedFileCount", modules.sumOf { it.files.size })
            span.setAttribute("kast.workspaceFiles.truncatedModuleCount", modules.count { it.filesTruncated })
            result
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
            val identity = DiagnosticQueryIdentity.from(query)
            val pageToken = query.pageToken
            val (projection, nextPageToken) = if (pageToken != null) {
                val token = pageToken
                when (val consumed = diagnosticContinuations.consume(
                    token = token,
                    query = identity,
                    transition = ContinuationStateTransition { state ->
                        val currentGeneration = runIdeaReadAction {
                            readEpochObserver.entered(IdeaReadEpochKind.DIAGNOSTICS)
                            psiGeneration()
                        }
                        if (state.generation != currentGeneration) {
                            throw ConflictException(
                                message = "Kotlin PSI changed after the preceding diagnostic page",
                                details = mapOf("pageToken" to token.value),
                            )
                        }
                        val page = DiagnosticContinuationProjection(
                            snapshot = state.snapshot,
                            pageOffset = state.nextOffset,
                        )
                        val nextOffset = diagnosticNextOffset(
                            state.snapshot,
                            state.nextOffset,
                            query.maxResults.value,
                        )
                        if (nextOffset < state.snapshot.diagnostics.size) {
                            state.advanceTo(nextOffset)
                            ContinuationTransition.Reissue(page, identity)
                        } else {
                            ContinuationTransition.Complete(page)
                        }
                    },
                )) {
                    is ContinuationConsumeResult.Completed -> consumed.output to null
                    is ContinuationConsumeResult.Reissued -> consumed.output to consumed.token.value
                    is ContinuationConsumeResult.Rejected -> throw ConflictException(
                        message = "The diagnostic page token is unknown, expired, consumed, or belongs to another query",
                        details = mapOf("pageToken" to token.value),
                    )
                }
            } else {
                val epoch = timedReadAction(
                    telemetry,
                    IdeaTelemetryScope.DIAGNOSTICS,
                    "kast.idea.diagnostics.snapshot",
                ) {
                    val currentGeneration = psiGeneration()
                    readEpochObserver.entered(IdeaReadEpochKind.DIAGNOSTICS)
                    val fileAnalyses = query.filePaths.value.map(::analyzeDiagnosticsFileInReadEpoch)
                    DiagnosticReadEpoch(
                        generation = currentGeneration,
                        snapshot = DiagnosticSnapshot(
                            diagnostics = fileAnalyses
                                .flatMap(DiagnosticsFileAnalysis::diagnostics)
                                .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" })),
                            fileStatuses = fileAnalyses.map(DiagnosticsFileAnalysis::status),
                        ),
                    )
                }
                val projection = DiagnosticContinuationProjection(epoch.snapshot, pageOffset = 0)
                val nextOffset = diagnosticNextOffset(epoch.snapshot, 0, query.maxResults.value)
                val nextToken = if (nextOffset < epoch.snapshot.diagnostics.size) {
                    when (val issued = diagnosticContinuations.issue(
                        query = identity,
                        state = DiagnosticContinuationState(
                            generation = epoch.generation,
                            snapshot = epoch.snapshot,
                            nextOffset = nextOffset,
                        ),
                    )) {
                        is ContinuationIssueResult.Issued -> issued.token.value
                        is ContinuationIssueResult.Rejected -> throw ConflictException(
                            "Diagnostic continuation store is unavailable",
                        )
                    }
                } else {
                    null
                }
                projection to nextToken
            }

            DiagnosticsResult.paged(
                diagnostics = projection.snapshot.diagnostics,
                fileStatuses = projection.snapshot.fileStatuses,
                pageOffset = projection.pageOffset,
                maxResults = query.maxResults.value,
                nextPageToken = nextPageToken,
            )
        }
    }

    private fun diagnosticNextOffset(
        snapshot: DiagnosticSnapshot,
        pageOffset: Int,
        maxResults: Int,
    ): Int {
        if (pageOffset !in 0..snapshot.diagnostics.size) {
            throw ConflictException("Server-held diagnostic continuation offset exceeded exact cardinality")
        }
        return Math.addExact(pageOffset, minOf(maxResults, snapshot.diagnostics.size - pageOffset))
    }

    private fun analyzeDiagnosticsFileInReadEpoch(filePath: NormalizedPath): DiagnosticsFileAnalysis {
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
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath.value)
                ?: return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "File exists on disk but is not available in the IDEA virtual file system",
                    )
            if (!ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
                return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.OUTSIDE_SOURCE_MODULES,
                        message = "File is not contained in an IDEA source module",
                    )
            }
            if (DumbService.isDumb(project)) {
                return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "IDEA indexing is still in progress",
                    )
            }
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "IDEA has not created PSI for the file yet",
                    )
            val file = psiFile as? KtFile
                ?: return skippedDiagnostics(
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

        RenameResult.of(
            edits = edits,
            fileHashes = fileHashes,
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
            if (query.filePaths.isEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    VirtualFileManager.getInstance().asyncRefresh(null)
                }
                return@inSpan RefreshResult.full()
            }

            val admission = semanticAdmissionAwaiter.await(query.filePaths, ::probeSemanticAdmission)
            RefreshResult.focused(
                fileStatuses = admission.fileStatuses,
                attemptCount = admission.attemptCount,
                elapsedMillis = admission.elapsedMillis,
            )
        }
    }

    private suspend fun probeSemanticAdmission(filePath: NormalizedPath): SemanticAdmissionStatus {
        val nioPath = filePath.toJavaPath()
        val fileSystem = LocalFileSystem.getInstance()
        if (Files.notExists(nioPath)) {
            fileSystem.findFileByNioFile(nioPath)?.refresh(false, false)
            nioPath.parent
                ?.let(fileSystem::refreshAndFindFileByNioFile)
                ?.refresh(false, false)
            return SemanticAdmissionStatus.removed(filePath)
        }

        val virtualFile = semanticAdmissionOperations.refreshAndFind(nioPath)
            ?: return pendingSemanticAdmission(
                filePath = filePath,
                fileSystemDiscovery = FileSystemDiscoveryState.PENDING,
                sourceModuleOwnership = SourceModuleOwnershipState.NOT_APPLICABLE,
                indexAdmission = IndexAdmissionState.NOT_APPLICABLE,
                message = "File exists on disk but IDEA has not discovered it in the virtual file system",
            )

        return timedReadAction(
            telemetry,
            IdeaTelemetryScope.REFRESH,
            "kast.idea.refresh.semanticAdmission",
        ) {
            if (!ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
                return@timedReadAction SemanticAdmissionStatus.incomplete(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OUTSIDE_SOURCE_MODULES,
                    indexAdmission = IndexAdmissionState.NOT_APPLICABLE,
                    analysisAvailability = AnalysisAvailabilityState.NOT_APPLICABLE,
                    analysisStatus = FileAnalysisStatus.skipped(
                        filePath,
                        FileAnalysisState.OUTSIDE_SOURCE_MODULES,
                        "File is not contained in an IDEA source module",
                    ),
                )
            }
            if (DumbService.isDumb(project)) {
                return@timedReadAction pendingSemanticAdmission(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.PENDING,
                    message = "IDEA indexing is still in progress",
                )
            }
            val kotlinFileType = kotlinFileType()
            val indexScope = GlobalSearchScope.fileScope(project, virtualFile)
            val admittedToKotlinIndex = kotlinFileType != null &&
                FileTypeIndex.getFiles(kotlinFileType, indexScope).any { indexedFile -> indexedFile == virtualFile }
            if (!admittedToKotlinIndex) {
                return@timedReadAction pendingSemanticAdmission(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.PENDING,
                    message = "IDEA has not admitted the file to the Kotlin index",
                )
            }
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@timedReadAction pendingSemanticAdmission(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.ADMITTED,
                    message = "IDEA has not created PSI for the file yet",
                )
            val ktFile = psiFile as? KtFile
                ?: return@timedReadAction failedSemanticAdmission(
                    filePath,
                    "Semantic admission requires a Kotlin source file",
                )
            try {
                semanticAdmissionOperations.collectDiagnostics(ktFile)
            } catch (ex: ProcessCanceledException) {
                throw ex
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                return@timedReadAction failedSemanticAdmission(
                    filePath,
                    ex.message?.takeIf(String::isNotBlank) ?: ex.toString(),
                )
            }
            SemanticAdmissionStatus.admitted(filePath)
        }
    }

    private fun pendingSemanticAdmission(
        filePath: NormalizedPath,
        fileSystemDiscovery: FileSystemDiscoveryState,
        sourceModuleOwnership: SourceModuleOwnershipState,
        indexAdmission: IndexAdmissionState,
        message: String,
    ): SemanticAdmissionStatus = SemanticAdmissionStatus.incomplete(
        filePath = filePath,
        fileSystemDiscovery = fileSystemDiscovery,
        sourceModuleOwnership = sourceModuleOwnership,
        indexAdmission = indexAdmission,
        analysisAvailability = AnalysisAvailabilityState.PENDING,
        analysisStatus = FileAnalysisStatus.skipped(
            filePath,
            FileAnalysisState.PENDING_INDEX,
            message,
        ),
    )

    private fun failedSemanticAdmission(
        filePath: NormalizedPath,
        message: String,
    ): SemanticAdmissionStatus = SemanticAdmissionStatus.incomplete(
        filePath = filePath,
        fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
        sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
        indexAdmission = IndexAdmissionState.ADMITTED,
        analysisAvailability = AnalysisAvailabilityState.FAILED,
        analysisStatus = FileAnalysisStatus.skipped(
            filePath,
            FileAnalysisState.BACKEND_FAILURE,
            message,
        ),
    )

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

    private data class DiagnosticSnapshot(
        val diagnostics: List<Diagnostic>,
        val fileStatuses: List<FileAnalysisStatus>,
    )

    private data class DiagnosticReadEpoch(
        val generation: Long,
        val snapshot: DiagnosticSnapshot,
    )

    private data class DiagnosticQueryIdentity(
        val filePaths: List<String>,
        val maxResults: Int,
    ) {
        companion object {
            fun from(query: ParsedDiagnosticsQuery): DiagnosticQueryIdentity = DiagnosticQueryIdentity(
                filePaths = query.filePaths.value.map { path -> path.value },
                maxResults = query.maxResults.value,
            )
        }
    }

    private class DiagnosticContinuationState(
        val generation: Long,
        val snapshot: DiagnosticSnapshot,
        nextOffset: Int,
    ) : ContinuationOwnedState() {
        var nextOffset: Int = nextOffset
            private set

        fun advanceTo(offset: Int) {
            require(offset > nextOffset) { "Diagnostic continuation offset must advance" }
            nextOffset = offset
        }
    }

    private data class DiagnosticContinuationProjection(
        val snapshot: DiagnosticSnapshot,
        val pageOffset: Int,
    ) : ContinuationProjection()

    private fun flattenCallRelations(
        root: io.github.amichne.kast.api.contract.CallNode,
        direction: io.github.amichne.kast.api.contract.CallDirection,
    ): List<CallRelation> {
        data class PendingCall(
            val node: io.github.amichne.kast.api.contract.CallNode,
            val depth: Int,
            val parent: io.github.amichne.kast.api.contract.SymbolIdentity,
        )

        val records = mutableListOf<CallRelation>()
        val rootIdentity = root.symbol.relationshipIdentity()
        val queue = ArrayDeque<PendingCall>()
        root.children.forEach { child -> queue += PendingCall(child, 1, rootIdentity) }
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val related = pending.node.symbol.relationshipIdentity()
            val callSite = pending.node.callSite
                ?: throw continuationConflict("malformedEvidence")
            val containing = if (direction == io.github.amichne.kast.api.contract.CallDirection.INCOMING) {
                related
            } else {
                pending.parent
            }
            records += CallRelation(
                relation = if (direction == io.github.amichne.kast.api.contract.CallDirection.INCOMING) {
                    CallRelation.Kind.CALLER
                } else {
                    CallRelation.Kind.CALLEE
                },
                relatedSymbol = related,
                callSite = callSite,
                depth = pending.depth,
                containingSymbol = ContainingSymbolEvidence.Known(containing),
            )
            pending.node.children.forEach { child ->
                queue += PendingCall(child, pending.depth + 1, related)
            }
        }
        return records
    }

    private fun flattenHierarchyRelations(
        root: TypeHierarchyNode,
        direction: io.github.amichne.kast.api.contract.TypeHierarchyDirection,
    ): List<TypeHierarchyRelation> {
        data class PendingType(val node: TypeHierarchyNode, val depth: Int)

        val records = mutableListOf<TypeHierarchyRelation>()
        val queue = ArrayDeque<PendingType>()
        root.children.forEach { child -> queue += PendingType(child, 1) }
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val identity = pending.node.symbol.relationshipIdentity()
            records += TypeHierarchyRelation(
                relation = when (direction) {
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUPERTYPES ->
                        TypeHierarchyRelation.Kind.SUPERTYPE
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUBTYPES ->
                        TypeHierarchyRelation.Kind.SUBTYPE
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.BOTH ->
                        error("BOTH hierarchy traversal must be split before flattening")
                },
                relatedSymbol = identity,
                declarationLocation = pending.node.symbol.location,
                depth = pending.depth,
            )
            pending.node.children.forEach { child ->
                queue += PendingType(child, pending.depth + 1)
            }
        }
        return records
    }

    private fun Symbol.relationshipIdentity(): io.github.amichne.kast.api.contract.SymbolIdentity =
        io.github.amichne.kast.api.contract.SymbolIdentity(
            fqName = fqName,
            kind = kind,
            declarationFile = NormalizedPath.parse(location.filePath),
            declarationStartOffset = NonNegativeInt(location.startOffset),
            containingType = containingDeclaration,
        )

    private fun continuationConflict(reason: String): ConflictException = ConflictException(
        message = "Relationship traversal could not preserve bounded exact evidence",
        details = mapOf("continuationFailure" to reason),
    )

    override fun close() {
        val failures = listOf(
            runCatching(referenceContinuations::close).exceptionOrNull(),
            runCatching(diagnosticContinuations::close).exceptionOrNull(),
            runCatching(relationshipContinuations::close).exceptionOrNull(),
            runCatching(workspaceFilePaging::close).exceptionOrNull(),
        ).filterNotNull()
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    companion object {
        private const val RELATIONSHIP_STATE_CAPACITY: Int = 16_384
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
    val exactTarget: ExactReferenceTarget?,
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
    val exactTarget: ExactReferenceTarget?,
    val declaration: Symbol?,
    val visibility: SymbolVisibility,
    val searchScope: GlobalSearchScope,
    val scopeKind: SearchScopeKind,
)

private data class ReferenceSearchOutcome(
    val source: ReferenceSearchSource,
    val references: List<ReferenceOccurrence>,
    val consumedEvidence: Int,
    val observedEvidence: Int,
    val nextPosition: ReferenceContinuationPosition?,
    val candidateFileCount: Int,
    val searchedFileCount: Int,
    val completion: ReferenceSearchCompletion,
) {
    val hasMoreEvidence: Boolean
        get() = nextPosition != null
}

private data class ReferenceQueryIdentity(
    val filePath: String,
    val offset: Int,
    val fqName: String?,
    val kind: String?,
    val containingType: String?,
    val includeDeclaration: Boolean,
    val includeUsageSiteScope: Boolean,
    val maxResults: Int,
) {
    companion object {
        fun from(query: ParsedReferencesQuery): ReferenceQueryIdentity = ReferenceQueryIdentity(
            filePath = query.position.filePath.value,
            offset = query.position.offset.value,
            fqName = query.selector?.fqName,
            kind = query.selector?.kind?.name,
            containingType = query.selector?.containingType,
            includeDeclaration = query.includeDeclaration,
            includeUsageSiteScope = query.includeUsageSiteScope,
            maxResults = query.maxResults.value,
        )
    }
}

private class ReferenceContinuationState(
    val plan: ReferenceSearchPlan,
    returnedBefore: Int,
    position: ReferenceContinuationPosition,
) : ContinuationOwnedState() {
    var returnedBefore: Int = returnedBefore
        private set
    var position: ReferenceContinuationPosition = position
        private set

    fun advanceTo(returnedBefore: Int, position: ReferenceContinuationPosition) {
        require(returnedBefore >= this.returnedBefore) { "Reference continuation cardinality must not regress" }
        this.returnedBefore = returnedBefore
        this.position = position
    }

    fun close() {
        (position as? ReferenceContinuationPosition.Idea)?.traversal?.close()
    }
}

private data class ReferenceContinuationProjection(
    val plan: ReferenceSearchPlan,
    val outcome: ReferenceSearchOutcome,
    val knownCount: Int,
) : ContinuationProjection()

private sealed interface ReferenceContinuationPosition {
    data class Index(
        val offset: io.github.amichne.kast.api.contract.NonNegativeInt,
        val generation: SourceIndexGeneration,
        val candidateFilePaths: Set<String>,
        val searchedFilePaths: Set<String>,
    ) : ReferenceContinuationPosition

    data class Idea(
        val traversal: IdeaReferenceTraversal,
        val pending: ReferenceOccurrence?,
        val generation: Long,
        val candidateFilePaths: MutableSet<String>,
        val searchedFilePaths: MutableSet<String>,
        val seenLocations: MutableSet<ReferenceLocationKey>,
    ) : ReferenceContinuationPosition
}

private class IdeaReferenceTraversal(
    searchRoots: List<Path>,
    private val observer: ReferenceTraversalObserver,
) : AutoCloseable {
    private var closed: Boolean = false
    val paths = WorkspacePathTraversal(searchRoots)
    var currentFile: VirtualFile? = null
    var nextOffset: Int = 0
    var nextReferenceIndex: Int = 0
    var exhausted: Boolean = false

    override fun close() {
        if (!closed) {
            closed = true
            paths.close()
            observer.closed()
        }
    }
}

private class WorkspacePathTraversal(searchRoots: List<Path>) : Iterator<Path>, AutoCloseable {
    private val roots = searchRoots.iterator()
    private var currentStream: java.util.stream.Stream<Path>? = null
    private var currentPaths: Iterator<Path>? = null

    override fun hasNext(): Boolean {
        while (true) {
            if (currentPaths?.hasNext() == true) return true
            currentStream?.close()
            currentStream = null
            currentPaths = null
            if (!roots.hasNext()) return false
            currentStream = Files.walk(roots.next())
            currentPaths = currentStream?.iterator()
        }
    }

    override fun next(): Path {
        if (!hasNext()) throw NoSuchElementException("No source path remains")
        return requireNotNull(currentPaths).next()
    }

    override fun close() {
        currentStream?.close()
        currentStream = null
        currentPaths = null
    }
}

private data class ReferenceLocationKey(
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
)

private fun Location.key(): ReferenceLocationKey = ReferenceLocationKey(
    filePath = filePath,
    startOffset = startOffset,
    endOffset = endOffset,
)

private val referenceOccurrenceOrder = compareBy<ReferenceOccurrence>(
    { it.location.filePath },
    { it.location.startOffset },
    { it.location.endOffset },
    {
        when (val evidence = it.containingSymbol) {
            is ContainingSymbolEvidence.Known -> evidence.symbol.fqName
            ContainingSymbolEvidence.TopLevel -> ""
            is ContainingSymbolEvidence.Unavailable -> evidence.reason.name
        }
    },
)

private enum class ReferenceSearchSource {
    INDEX,
    IDEA,
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

private enum class ReferencePartialReason {
    REQUEST_BUDGET_EXHAUSTED,
    PATH_BUDGET_EXHAUSTED,
    PSI_RESOLUTION_FAILED,
    COMPILER_PROVIDER_LIMIT_EXHAUSTED,
    FILE_BUDGET_EXHAUSTED,
    TARGET_INVALIDATED,
    INDEX_LOCATION_UNRESOLVED,
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

private fun referencesAtLeaf(
    file: PsiFile,
    leaf: PsiElement,
    leafStart: Int,
): List<PsiReference> = buildList {
    file.findReferenceAt(leafStart)?.let(::add)
    generateSequence(leaf as PsiElement?) { element -> element.parent }
        .takeWhile { element -> element != file }
        .forEach { element -> addAll(element.references) }
}.distinctBy { reference ->
    ReferenceProbeKey(
        elementStartOffset = reference.element.textRange.startOffset,
        rangeStartOffset = reference.rangeInElement.startOffset,
        rangeEndOffset = reference.rangeInElement.endOffset,
        implementationName = reference.javaClass.name,
    )
}

private data class ReferenceProbeKey(
    val elementStartOffset: Int,
    val rangeStartOffset: Int,
    val rangeEndOffset: Int,
    val implementationName: String,
)

private fun PsiReference.absoluteTextRange(): TextRange =
    rangeInElement.shiftRight(element.textRange.startOffset)

private const val NANOS_PER_MILLI = 1_000_000L
private const val READ_ACTION_BATCH_SIZE = 50
private const val REFERENCE_DISCOVERY_PATH_LIMIT = 64

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
