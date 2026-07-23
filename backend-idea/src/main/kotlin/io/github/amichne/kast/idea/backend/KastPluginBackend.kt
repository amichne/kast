@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.continuation.ContinuationStateDisposer
import io.github.amichne.kast.api.continuation.ContinuationClock
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
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ContainingSymbolUnavailableReason
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.SemanticGraphResult
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.Symbol
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
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.usageSiteDeclarationScope
import io.github.amichne.kast.shared.analysis.visibility
import io.github.amichne.kast.shared.hierarchy.ReadAccessScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import java.nio.file.Path
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CancellationException
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.semantic.semanticGraphOperation
import io.github.amichne.kast.idea.backend.semantic.SemanticGraphContinuationProjection
import io.github.amichne.kast.idea.backend.semantic.SemanticGraphContinuationState
import io.github.amichne.kast.idea.backend.semantic.SemanticGraphQueryIdentity
import io.github.amichne.kast.api.contract.query.SemanticGraphPageToken
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint

internal class KastPluginBackend(
    internal val project: Project,
    workspaceRoot: Path,
    internal val limits: ServerLimits,
    internal val telemetry: IdeaBackendTelemetry = IdeaBackendTelemetry.disabled(),
    internal val backendName: String? = null,
    internal val workspaceIdentity: IdeaWorkspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot),
    internal val referenceIndexLookup: ReferenceIndexLookup = ReferenceIndexLookup.Unavailable,
    internal val semanticGraphStore: SqliteSourceIndexStore? = null,
    internal val semanticGraphConfigurationFingerprint: BuildClasspathFingerprint? = null,
    internal val semanticGraphContinuationClock: ContinuationClock = ContinuationClock.System,
    internal val referenceSearchClock: ReferenceSearchClock = ReferenceSearchClock.System,
    internal val semanticAdmissionAwaiter: IdeaSemanticAdmissionAwaiter =
        IdeaSemanticAdmissionAwaiter.forRequestBudget(limits.requestTimeoutMillis),
    internal val semanticAdmissionOperations: IdeaSemanticAdmissionOperations =
        IdeaSemanticAdmissionOperations.idea(),
    internal val psiGeneration: () -> Long = { PsiModificationTracker.getInstance(project).modificationCount },
    internal val readEpochObserver: IdeaReadEpochObserver = IdeaReadEpochObserver.Disabled,
    internal val referenceTraversalObserver: ReferenceTraversalObserver = ReferenceTraversalObserver.Disabled,
    internal val indexSemanticAdmissionStatus: () -> IdeaIndexSemanticAdmission.Status = {
        IdeaIndexSemanticAdmission.Status.Ready
    },
    internal val relationshipCoverageAuthority: RelationshipCoverageAuthority =
        IdeaRelationshipCoverageAuthority(
            project = project,
            workspaceIdentity = workspaceIdentity,
            indexSemanticAdmissionStatus = indexSemanticAdmissionStatus,
        ),
) : CloseableAnalysisBackend {

    internal val readDispatcher = Dispatchers.Default.limitedParallelism(limits.maxConcurrentRequests)
    internal val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    internal val sharedWorkspaceIdentity = workspaceIdentity.workspaceIdentity
    override val selectorHandles: SelectorHandleAuthority =
        DigestSelectorHandleAuthority(
            workspaceRoot = workspaceRoot.toString(),
            backendName = backendName ?: defaultBackendName(),
            backendVersion = BACKEND_VERSION,
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
    internal val semanticGraphContinuations = SharedContinuationStore<
        SemanticGraphPageToken,
        SemanticGraphQueryIdentity,
        SemanticGraphContinuationState,
        SemanticGraphContinuationProjection,
    >(
        capacity = limits.typedContinuationCapacity,
        timeToLive = limits.typedContinuationTtl,
        tokenIssuer = ContinuationTokenIssuer(SemanticGraphPageToken::random),
        stateDisposer = ContinuationStateDisposer { },
        clock = semanticGraphContinuationClock,
    )
    internal val semanticGraphContinuationIssuedAtNanos: MutableMap<SemanticGraphPageToken, Long> =
        Collections.synchronizedMap(
            object : LinkedHashMap<SemanticGraphPageToken, Long>() {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<SemanticGraphPageToken, Long>?,
                ): Boolean = size > Math.multiplyExact(limits.continuationCapacity, 2)
            },
        )
    internal val relationshipContinuations = RelationshipContinuationStore(limits)
    internal val workspaceFilePaging = IdeaWorkspaceFilePaging(
        workspaceId = sharedWorkspaceIdentity.canonicalWorkspaceId,
        inventory = IdeaProjectModelWorkspaceFileInventory(project, workspaceIdentity),
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
        ) + setOfNotNull(ReadCapability.SEMANTIC_GRAPH.takeIf { semanticGraphStore != null }),
        mutationCapabilities = setOf(
            MutationCapability.RENAME,
            MutationCapability.APPLY_EDITS,
            MutationCapability.FILE_OPERATIONS,
            MutationCapability.OPTIMIZE_IMPORTS,
            MutationCapability.REFRESH_WORKSPACE,
        ),
        limits = limits,
    )

    internal fun defaultBackendName(): String = when (ApplicationInfo.getInstance().build.productCode) {
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


    override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult = resolveSymbolOperation(query)
    override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult = findReferencesOperation(query)
    override suspend fun callHierarchy(query: ParsedCallHierarchyQuery): CallHierarchyResult = callHierarchyOperation(query)
    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult = callRelationsOperation(query)
    override suspend fun typeHierarchy(query: ParsedTypeHierarchyQuery): TypeHierarchyResult = typeHierarchyOperation(query)
    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult = hierarchyRelationsOperation(query)
    override suspend fun implementations(query: ParsedImplementationsQuery): ImplementationsResult = implementationsOperation(query)
    override suspend fun implementationRelations(query: KastImplementationsQuery): ImplementationRelationsResult = implementationRelationsOperation(query)
    override suspend fun codeActions(query: ParsedCodeActionsQuery): CodeActionsResult = codeActionsOperation(query)
    override suspend fun completions(query: ParsedCompletionsQuery): CompletionsResult = completionsOperation(query)
    override suspend fun workspaceFiles(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult = workspaceFilesOperation(query)
    override suspend fun semanticGraph(query: ParsedSemanticGraphQuery): SemanticGraphResult = semanticGraphOperation(query)
    override suspend fun semanticInsertionPoint(query: ParsedSemanticInsertionQuery): SemanticInsertionResult = semanticInsertionPointOperation(query)
    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult = diagnosticsOperation(query)
    override suspend fun rename(query: ParsedRenameQuery): RenameResult = renameOperation(query)
    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult = applyEditsOperation(query)
    override suspend fun optimizeImports(query: ParsedImportOptimizeQuery): ImportOptimizeResult = optimizeImportsOperation(query)
    override suspend fun refresh(query: ParsedRefreshQuery): RefreshResult = refreshOperation(query)
    override suspend fun fileOutline(query: ParsedFileOutlineQuery): FileOutlineResult = fileOutlineOperation(query)
    override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult = workspaceSymbolSearchOperation(query)
    override suspend fun workspaceSearch(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult = workspaceSearchOperation(query)

    internal fun PsiReference.toReferenceOccurrence(includeUsageSiteScope: Boolean): ReferenceOccurrence? {
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

    internal fun PsiElement.containingSymbolEvidence(): ContainingSymbolEvidence {
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

    internal fun isConcreteType(target: PsiElement): Boolean = when (target) {
        is KtClass -> !target.isInterface() && !target.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        is KtObjectDeclaration -> !target.isCompanion()
        is com.intellij.psi.PsiClass -> !target.isInterface && !target.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)
        else -> false
    }

    internal fun findKtFile(filePath: String): KtFile {
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

    internal fun Symbol.relationshipIdentity(): io.github.amichne.kast.api.contract.SymbolIdentity =
        io.github.amichne.kast.api.contract.SymbolIdentity(
            fqName = fqName,
            kind = kind,
            declarationFile = NormalizedPath.parse(location.filePath),
            declarationStartOffset = NonNegativeInt(location.startOffset),
            containingType = containingDeclaration,
        )

    override fun close() {
        semanticGraphContinuationIssuedAtNanos.clear()
        val failures = listOf(
            runCatching(referenceContinuations::close).exceptionOrNull(),
            runCatching(diagnosticContinuations::close).exceptionOrNull(),
            runCatching(semanticGraphContinuations::close).exceptionOrNull(),
            runCatching(relationshipContinuations::close).exceptionOrNull(),
            runCatching(workspaceFilePaging::close).exceptionOrNull(),
        ).filterNotNull()
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    companion object {
        internal const val RELATIONSHIP_STATE_CAPACITY: Int = 16_384
        internal val BACKEND_VERSION = readBackendVersion()

        internal fun readBackendVersion(): String =
            KastPluginBackend::class.java
                .getResource("/kast-backend-version.txt")
                ?.readText()
                ?.trim()
                ?: "unknown"
    }
}
