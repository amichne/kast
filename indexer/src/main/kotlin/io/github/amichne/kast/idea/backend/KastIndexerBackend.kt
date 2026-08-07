@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.client.IndexingConfig
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.GraphIndexingBatchSize
import io.github.amichne.kast.api.continuation.ContinuationStateDisposer
import io.github.amichne.kast.api.continuation.ContinuationTokenIssuer
import io.github.amichne.kast.api.continuation.ServerHeldContinuationStore as SharedContinuationStore
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ExactFileImageResult
import io.github.amichne.kast.api.contract.result.AddFilePlanResult
import io.github.amichne.kast.api.contract.result.AddDeclarationPlanResult
import io.github.amichne.kast.api.contract.result.MutationPostconditionResult
import io.github.amichne.kast.api.contract.result.RawExactFileObservationResult
import io.github.amichne.kast.api.contract.result.MutationScratchInspectResult
import io.github.amichne.kast.api.contract.result.MutationScratchRecoveryResult
import io.github.amichne.kast.api.contract.result.SemanticGraphResult
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.api.contract.selector.DigestSelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.shared.analysis.visibility
import io.github.amichne.kast.shared.hierarchy.ReadAccessScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import java.util.UUID
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.mutation.SecureWorkspaceMutation
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.semantic.*
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal class KastIndexerBackend(
    internal val project: Project,
    workspaceRoot: Path,
    internal val limits: ServerLimits,
    internal val telemetry: IdeaBackendTelemetry = IdeaBackendTelemetry.disabled(),
    internal val workspaceIdentity: IdeaWorkspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot),
    internal val exactFileImageMutation: SecureWorkspaceMutation =
        SecureWorkspaceMutation(workspaceIdentity.canonicalWorkspaceRootPath),
    internal val mutationAttemptGate: MutationAttemptGate =
        MutationAttemptGateRegistry.forWorkspaceRoot(workspaceIdentity.canonicalWorkspaceRootPath),
    internal val exactFileImageCasObserver: ExactFileImageCasObserver = ExactFileImageCasObserver.Disabled,
    internal val referenceIndexLookup: ReferenceIndexLookup = ReferenceIndexLookup.Unavailable,
    internal val semanticGraphStore: SqliteSourceIndexStore? = null,
    initialIndexingConfig: IndexingConfig = KastConfig.defaults().indexing,
    internal val indexingConfigLoader: () -> IndexingConfig = { initialIndexingConfig },
    internal val workspaceIndexingScopeCache: WorkspaceIndexingScopeCache = WorkspaceIndexingScopeCache(),
    internal val referenceSearchClock: ReferenceSearchClock = ReferenceSearchClock.System,
    internal val semanticAdmissionAwaiter: IdeaSemanticAdmissionAwaiter =
        IdeaSemanticAdmissionAwaiter.forRequestBudget(limits.requestTimeoutMillis),
    internal val semanticAdmissionOperations: IdeaSemanticAdmissionOperations =
        IdeaSemanticAdmissionOperations.idea(),
    internal val psiGeneration: () -> Long = { PsiModificationTracker.getInstance(project).modificationCount },
    internal val readEpochObserver: IdeaReadEpochObserver = IdeaReadEpochObserver.Disabled,
    internal val referenceTraversalObserver: ReferenceTraversalObserver = ReferenceTraversalObserver.Disabled,
    @Volatile internal var semanticGraphBatchSize: GraphIndexingBatchSize = GraphIndexingBatchSize(32),
    internal val workspaceSemanticReadAuthority: WorkspaceSemanticReadAuthority,
    internal val workspaceTransitionRequester: WorkspaceTransitionRequester,
    internal val workspaceModelReader: () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = {
        IdeaGradleProjectLoadBridge.readWorkspaceModel(project)
    },
    internal val relationshipCoverageAuthority: RelationshipCoverageAuthority =
        IdeaRelationshipCoverageAuthority(
            project = project,
            workspaceIdentity = workspaceIdentity,
            indexSemanticAdmissionStatus = workspaceSemanticReadAuthority::status,
            workspaceModelReader = workspaceModelReader,
            sourceIndexStore = semanticGraphStore,
        ),
) : CloseableAnalysisBackend {

    @Volatile
    private var lastValidIndexingConfig: IndexingConfig = initialIndexingConfig
    private val psiSupport = KastIndexerPsiSupport(this)
    internal val workspaceSemanticGate = WorkspaceSemanticGate(
        readAuthority = workspaceSemanticReadAuthority,
    )

    internal fun updateSemanticGraphBatchSize(batchSize: GraphIndexingBatchSize) {
        semanticGraphBatchSize = batchSize
    }

    internal fun currentPersistedIndexingConfig(): IndexingConfig = try {
        indexingConfigLoader().also { lastValidIndexingConfig = it }
    } catch (_: Exception) {
        lastValidIndexingConfig
    }

    internal fun persistedIndexingScope(paths: Collection<String>): WorkspaceIndexingScope = try {
        workspaceIndexingScopeCache.resolve(
            workspaceRoot = workspaceRoot,
            config = currentPersistedIndexingConfig(),
            candidates = paths.map(Path::of),
        )
    } catch (error: IndexingScopeConfigurationException) {
        throw io.github.amichne.kast.api.protocol.ValidationException(
            error.message ?: "Persisted-index scope configuration is invalid",
            details = mapOf("scopeError" to error.code),
        )
    }

    internal val readDispatcher = Dispatchers.Default.limitedParallelism(limits.maxConcurrentRequests)
    internal val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    internal val sharedWorkspaceIdentity = workspaceIdentity.workspaceIdentity
    override val selectorHandles: SelectorHandleAuthority =
        DigestSelectorHandleAuthority(
            workspaceRoot = workspaceRoot.toString(),
            backendName = INDEXER_NAME,
            backendVersion = INDEXER_VERSION,
            backendInstanceId = UUID.randomUUID().toString(),
            semanticGeneration = psiGeneration,
        )
    internal val referenceContinuations = SharedContinuationStore<
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
    internal val diagnosticContinuations = SharedContinuationStore<
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
    internal val relationshipContinuations = RelationshipContinuationStore(limits)
    internal val workspaceFilePaging = IdeaWorkspaceFilePaging(
        workspaceId = sharedWorkspaceIdentity.canonicalWorkspaceId,
        inventory = IdeaProjectModelWorkspaceFileInventory(
            project = project,
            workspaceIdentity = workspaceIdentity,
            workspaceModelReader = workspaceModelReader,
        ),
        limits = limits,
    )
    internal val ideaReadAccess = object : ReadAccessScope {
        override fun <T> run(action: () -> T): T =
            ApplicationManager.getApplication().runReadAction<T> { action() }
    }

    internal fun kotlinFileType(): FileType? =
        FileTypeManager.getInstance().findFileTypeByName("Kotlin")

    internal fun kotlinCandidateFiles(scope: GlobalSearchScope): List<VirtualFile> =
        kotlinFileType()?.let { fileType ->
            FileTypeIndex.getFiles(fileType, scope)
                .asSequence()
                .filter { file -> file.isValid && !file.isDirectory && isWorkspaceFile(file.path) }
                .sortedBy { file -> file.path }
                .toList()
        } ?: emptyList()

    internal fun referenceSearchRoots(plan: ReferenceSearchPlan): List<Path> {
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
        backendName = INDEXER_NAME,
        backendVersion = INDEXER_VERSION,
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
        ) + setOfNotNull(
            ReadCapability.SEMANTIC_GRAPH.takeIf {
                semanticGraphStore != null
            },
        ),
        mutationCapabilities = INDEXER_MUTATION_CAPABILITIES,
        limits = limits,
    )

    override suspend fun runtimeStatus(): RuntimeStatusResponse {
        val caps = capabilities()
        val isDumb = DumbService.isDumb(project)
        val admission = workspaceSemanticReadAuthority.status()
        val state = when {
            admission is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeState.DEGRADED
            isDumb || admission is IdeaIndexSemanticAdmission.Status.Pending -> RuntimeState.INDEXING
            else -> RuntimeState.READY
        }
        val moduleNames = ModuleManager.getInstance(project).modules.map { it.name }.sorted()
        val readiness = kastRuntimeReadiness(admission, isDumb, moduleNames.size)
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
                else -> "Kast compiler-backed indexer is ready"
            },
            sourceModuleNames = moduleNames,
            publishedWorkspaceGeneration = (admission as? IdeaIndexSemanticAdmission.Status.Ready)?.generation?.toRuntimeStatus(),
            readiness = readiness,
            ready = readiness.readySummary,
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
    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult = workspaceSemanticGate.current { resolveSymbolOperation(query) }
    override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult = workspaceSemanticGate.current { findReferencesOperation(query) }
    override suspend fun callHierarchy(query: ParsedCallHierarchyQuery): CallHierarchyResult = workspaceSemanticGate.current { callHierarchyOperation(query) }
    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult = workspaceSemanticGate.current { callRelationsOperation(query) }
    override suspend fun typeHierarchy(query: ParsedTypeHierarchyQuery): TypeHierarchyResult = workspaceSemanticGate.current { typeHierarchyOperation(query) }
    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult = workspaceSemanticGate.current { hierarchyRelationsOperation(query) }
    override suspend fun implementations(query: ParsedImplementationsQuery): ImplementationsResult = workspaceSemanticGate.current { implementationsOperation(query) }
    override suspend fun implementationRelations(query: KastImplementationsQuery): ImplementationRelationsResult = workspaceSemanticGate.current { implementationRelationsOperation(query) }
    override suspend fun codeActions(query: ParsedCodeActionsQuery): CodeActionsResult = workspaceSemanticGate.current { codeActionsOperation(query) }
    override suspend fun completions(query: ParsedCompletionsQuery): CompletionsResult = workspaceSemanticGate.current { completionsOperation(query) }
    override suspend fun workspaceFiles(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult = workspaceSemanticGate.current { workspaceFilesOperation(query) }
    override suspend fun semanticGraph(query: ParsedSemanticGraphQuery): SemanticGraphResult = coordinatedSemanticGraph(query)
    /** Internal transition writer. External graph requests remain guarded by [WorkspaceSemanticGate.current]. */
    internal suspend fun reconcileSemanticGraph(
        query: ParsedSemanticGraphQuery,
        token: IdeaIndexSemanticAdmission.ReconciliationToken,
    ): SemanticGraphResult = semanticGraphOperation(query, token)
    override suspend fun semanticInsertionPoint(query: ParsedSemanticInsertionQuery): SemanticInsertionResult = workspaceSemanticGate.current { semanticInsertionPointOperation(query) }
    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult = workspaceSemanticGate.current { diagnosticsOperation(query) }
    override suspend fun rename(query: ParsedRenameQuery): RenameResult = workspaceSemanticGate.current { renameOperation(query) }
    override suspend fun planReplacement(query: ParsedReplacementPlanQuery): ReplacementPlanResult =
        workspaceSemanticGate.current { planReplacementOperation(query) }
    override suspend fun planAddFile(query: ParsedAddFilePlanQuery): AddFilePlanResult =
        workspaceSemanticGate.current { planAddFileOperation(query) }
    override suspend fun planAddDeclaration(query: ParsedAddDeclarationPlanQuery): AddDeclarationPlanResult =
        workspaceSemanticGate.current { planAddDeclarationOperation(query) }
    override suspend fun verifyMutationPostcondition(
        query: ParsedMutationPostconditionQuery,
    ): MutationPostconditionResult = workspaceSemanticGate.current { verifyMutationPostconditionOperation(query) }
    override suspend fun observeExactFile(
        query: ParsedRawExactFileObservationQuery,
    ): RawExactFileObservationResult = workspaceSemanticGate.current { rawExactFileObservationOperation(query) }
    override suspend fun exactFileImageCas(query: ParsedExactFileImageQuery): ExactFileImageResult =
        coordinatedExactFileImageCas(query)
    override suspend fun inspectMutationScratch(
        query: ParsedMutationScratchInspectQuery,
    ): MutationScratchInspectResult = workspaceSemanticGate.current { inspectMutationScratchOperation(query) }
    override suspend fun recoverMutationScratch(
        query: ParsedMutationScratchRecoveryQuery,
    ): MutationScratchRecoveryResult = coordinatedMutationScratchRecovery(query)
    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult = coordinatedApplyEdits(query)
    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult = workspaceSemanticGate.current { optimizeImportsOperation(query) }
    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult = coordinatedRefresh(query)
    override suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult = workspaceSemanticGate.current { fileOutlineOperation(query) }
    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult = workspaceSemanticGate.current { workspaceSymbolSearchOperation(query) }
    override suspend fun workspaceSearch(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult = workspaceSemanticGate.current { workspaceSearchOperation(query) }

    internal fun PsiReference.toReferenceOccurrence(includeUsageSiteScope: Boolean): ReferenceOccurrence? =
        psiSupport.toReferenceOccurrence(this, includeUsageSiteScope)

    internal fun PsiElement.containingSymbolEvidence(): ContainingSymbolEvidence =
        psiSupport.containingSymbolEvidence(this)
    internal fun isConcreteType(target: PsiElement): Boolean = psiSupport.isConcreteType(target)
    internal fun findKtFile(filePath: String): KtFile = psiSupport.findKtFile(filePath)
    internal fun visibilityScopedSearch(
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
    internal fun moduleWithDependentsScope(target: PsiElement): GlobalSearchScope? {
        val file = target.containingFile as? KtFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(virtualFile) ?: return null
        return GlobalSearchScope.moduleWithDependentsScope(module)
    }

    override fun close() = closeResources()

    companion object {
        internal const val INDEXER_NAME: String = "indexer"
        internal val INDEXER_MUTATION_CAPABILITIES: Set<MutationCapability> = MutationCapability.entries.toSet()
        internal const val RELATIONSHIP_STATE_CAPACITY: Int = 16_384
        internal val INDEXER_VERSION = readIndexerVersion()

        internal fun readIndexerVersion(): String = loadIndexerVersion()
    }
}
