package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.IndexingConfig
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.client.fields.RelationshipIndexingBatchSize
import io.github.amichne.kast.api.client.fields.RelationshipIndexingParallelism
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteException
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteReason
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageOutcome
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleName
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.indexing.RelationshipScanResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import io.github.amichne.kast.shared.analysis.PsiRelationshipScanResult
import io.github.amichne.kast.shared.analysis.PsiSourceIndexScanner
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import java.nio.file.Path
import java.util.concurrent.CancellationException

private const val SOURCE_INDEX_BATCH_SIZE = 50
private val FOCUSED_RELATIONSHIP_BATCH_SIZE = RelationshipIndexingBatchSize(50)
private val FOCUSED_RELATIONSHIP_PARALLELISM = RelationshipIndexingParallelism(1)

internal class IdeaProjectIndexer(
    private val project: Project,
    workspaceRoot: Path,
    private val store: SqliteSourceIndexStore,
    private val cancelled: () -> Boolean,
    private val workspaceIdentity: WorkspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot),
    private val readGradleWorkspaceModel: () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = {
        IdeaGradleProjectLoadBridge.readWorkspaceModel(project)
    },
    private val indexingProgress: WorkspaceIndexingProgressSink = WorkspaceIndexingProgressAuthority(),
    private val onSourceFileScan: (String) -> Unit = {},
    private val onRelationshipFileScan: (String) -> Unit = {},
    private val scopeCache: WorkspaceIndexingScopeCache = WorkspaceIndexingScopeCache(),
) {
    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    private val sourceFilePolicy = SourceIndexFilePolicy.forWorkspace(this.workspaceRoot)
    private val moduleResolver = IdeaSourceIndexModuleResolver(project, this.workspaceRoot, sourceFilePolicy)
    private val environment = IdeaReferenceIndexEnvironment(
        project = project,
        workspaceIdentity = workspaceIdentity,
        cancelled = cancelled,
    )
    private val ideaWorkspaceIdentity = workspaceIdentityForIdea()
    private val inventory = IdeaProjectModelWorkspaceFileInventory(
        project = project,
        workspaceIdentity = ideaWorkspaceIdentity,
        workspaceModelReader = readGradleWorkspaceModel,
    )

    fun indexProject(
        config: KastConfig,
        candidate: WorkspaceIndexingCandidate = captureCandidate(config.indexing),
        semanticContextIdentity: WorkspaceStateIdentity? = null,
        onSourceScopeReady: (IndexedSourceIdentifiers) -> Unit = {},
    ): IndexedSourceIdentifiers {
        store.ensureSchema()
        val indexedSources = indexSourceIdentifiersAndScope(
            candidate = candidate,
            stageVersions = semanticContextStageVersions(semanticContextIdentity),
        )
        onSourceScopeReady(indexedSources)
        if (config.indexing.relationships.enabled.value && !environment.isCancelled()) {
            val moduleSpecs = runIdeaReadAction { moduleResolver.discoverModuleSpecs() }
            val modulePriorityOrder = computeModulePriorityOrder(
                activeModule = null,
                moduleSpecs = moduleSpecs,
                dependentModuleGraph = moduleResolver.dependencyGraph(moduleSpecs),
                depth = config.indexing.relationships.modulePriorityDepth,
            ).map(SourceIndexModuleName::parse)
            indexSymbolRelationships(
                currentFilePaths = indexedSources.paths,
                criticalFilePaths = indexedSources.criticalPaths,
                moduleOrder = modulePriorityOrder,
                batchSize = config.indexing.relationships.batchSize,
                parallelism = config.indexing.relationships.parallelism,
            )
        }
        return indexedSources
    }

    fun refreshSymbolRelationships(
        filePaths: Collection<WorkspaceSourcePath>,
        removedFilePaths: Collection<WorkspaceSourcePath> = emptyList(),
        indexingConfig: IndexingConfig = KastConfig.defaults().indexing,
    ): List<FileStageOutcome> {
        store.ensureSchema()
        requireActive()
        store.reconcileRemovedFileInventory(removedFilePaths)
        requireActive()
        val scopedFilePaths = scopeCache.resolve(
            workspaceRoot = workspaceRoot,
            config = indexingConfig,
            candidates = filePaths.map { path -> path.absolute.value.toJavaPath() },
        ).includedPaths
        val requestedPaths = reconcileFocusedSourceFacts(scopedFilePaths)
        requireActive()
        val previousFailureIds = requestedPaths.associateWith { path ->
            store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS)?.failure?.id
        }
        indexSymbolRelationships(
            currentFilePaths = requestedPaths,
            criticalFilePaths = emptySet(),
            moduleOrder = emptyList(),
            batchSize = FOCUSED_RELATIONSHIP_BATCH_SIZE,
            parallelism = FOCUSED_RELATIONSHIP_PARALLELISM,
        )
        requireActive()

        val failures = requestedPaths.mapNotNull { path ->
            store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS)
                ?.takeIf { outcome -> outcome.status == FileStageOutcomeStatus.FAILED }
                ?.takeIf { outcome -> outcome.failure?.id != previousFailureIds[path] }
        }
        val failedPaths = failures.mapTo(mutableSetOf(), FileStageOutcome::path)
        val unfinishedPaths = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
            .mapTo(mutableSetOf()) { work -> work.path }
            .intersect(requestedPaths.toSet())
            .minus(failedPaths)
        check(unfinishedPaths.isEmpty()) {
            "Focused relationship refresh did not commit current facts for: ${unfinishedPaths.sorted().joinToString()}"
        }
        return failures
    }

    private fun reconcileFocusedSourceFacts(
        filePaths: Collection<WorkspaceSourcePath>,
    ): List<WorkspaceSourcePath> {
        val requestedPaths = filePaths.distinct()
        if (requestedPaths.isEmpty()) return emptyList()
        val manifestPaths = store.knownSourcePaths().mapNotNullTo(linkedSetOf(), store::sourcePath)
        val missingManifestPaths = requestedPaths.minus(manifestPaths)
        check(missingManifestPaths.isEmpty()) {
            "Focused source refresh requires current manifest entries for: " +
                missingManifestPaths.sorted().joinToString()
        }
        val workByPath = store.pendingFileStages(FileIndexStage.SOURCE)
            .associateBy { work -> work.path }
        val pendingWork = requestedPaths.mapNotNull(workByPath::get)
        if (pendingWork.isEmpty()) return requestedPaths

        val gradleModel = readAvailableGradleWorkspaceModel()
        val gradleProvenance = IdeaGradleFileProvenance.fromWorkspaceModel(gradleModel, ideaWorkspaceIdentity)
        val scanner = PsiSourceIndexScanner(
            environment = environment,
            moduleNameForFile = moduleResolver::moduleNameForFile,
        )
        val updates = pendingWork.mapNotNull { work ->
            requireActive()
            indexingProgress.record(WorkspaceIndexingActivity.derive(work))
            val absolutePath = work.path.absolute.value.value
            onSourceFileScan(absolutePath)
            val result = scanner.scanFile(absolutePath)
            requireActive()
            if (result == null || result.contentHash != work.contentHash) return@mapNotNull null
            SourceFileStageUpdate(
                work = work,
                scannedContentHash = result.contentHash,
                update = gradleProvenance.applyTo(
                    update = result.update,
                    ownerModuleNames = focusedOwnerModuleNames(gradleModel, work.path),
                ),
            )
        }
        requireActive()
        if (updates.isNotEmpty()) store.commitSourceBatch(updates)
        requireActive()

        val stillPending = store.pendingFileStages(FileIndexStage.SOURCE)
            .mapTo(mutableSetOf()) { work -> work.path }
        val unfinishedPaths = requestedPaths.filter { path ->
            path in stillPending ||
                store.fileStageOutcome(path, FileIndexStage.SOURCE)?.status != FileStageOutcomeStatus.COMPLETE
        }
        check(unfinishedPaths.isEmpty()) {
            "Focused source refresh did not commit current facts for: ${unfinishedPaths.sorted().joinToString()}"
        }
        return requestedPaths
    }

    fun indexSourceIdentifiers(): Collection<WorkspaceSourcePath> {
        return indexSourceIdentifiersAndScope(
            candidate = captureCandidate(KastConfig.defaults().indexing),
            stageVersions = FileStageVersions.CURRENT,
        ).paths
    }

    fun captureCandidate(config: IndexingConfig): WorkspaceIndexingCandidate {
        val captured = inventory.snapshotWithGradleModel(WorkspaceFileKindDomain.MIXED)
        val ownerModuleNamesByPath = moduleResolver.referenceIndexOwnersByPath(captured.inventory)
        val scope = scopeCache.resolve(
            workspaceRoot = workspaceRoot,
            config = config,
            candidates = ownerModuleNamesByPath.keys.map { path -> path.absolute.value.toJavaPath() },
        )
        val includedPaths = scope.includedPaths.toSet()
        val inventoryEntries = buildFileInventoryEntries(
            ownerModuleNamesByPath = ownerModuleNamesByPath.filterKeys(includedPaths::contains),
            isCancelled = environment::isCancelled,
            sourceSetForPath = moduleResolver::legacySourceSetLabelForFile,
        )
        return WorkspaceIndexingCandidate(
            gradleModel = captured.gradleModel,
            scope = scope,
            ownerModuleNamesByPath = ownerModuleNamesByPath.filterKeys(includedPaths::contains),
            inventoryEntries = inventoryEntries,
        )
    }

    private fun indexSourceIdentifiersAndScope(
        candidate: WorkspaceIndexingCandidate,
        stageVersions: FileStageVersions,
    ): IndexedSourceIdentifiers {
        store.ensureSchema()
        val previousPaths = store.knownSourcePaths().mapNotNullTo(linkedSetOf(), store::sourcePath)
        val gradleProvenance = IdeaGradleFileProvenance.fromWorkspaceModel(
            candidate.gradleModel,
            ideaWorkspaceIdentity,
        )
        val ownerModuleNamesByPath = candidate.ownerModuleNamesByPath
        val scope = candidate.scope
        val inventoryEntries = candidate.inventoryEntries
        requireActive()
        store.reconcileFileInventory(inventoryEntries, stageVersions)

        val inventoryByPath = inventoryEntries.associateBy(FileInventoryEntry::path)
        val workByPath = store.pendingFileStages(FileIndexStage.SOURCE).associateBy { work -> work.path }
        val orderedPendingPaths = prioritizeIndexingPaths(
            pathsByModule = workByPath.keys.map { path ->
                IndexingPriorityEntry(path, inventoryByPath[path]?.module)
            },
            moduleOrder = emptyList(),
            criticalPaths = scope.criticalPaths.toSet(),
        )
        val scanner = PsiSourceIndexScanner(
            environment = environment,
            moduleNameForFile = moduleResolver::moduleNameForFile,
        )
        for (batch in orderedPendingPaths.map(workByPath::getValue).chunked(SOURCE_INDEX_BATCH_SIZE)) {
            requireActive()
            val updates = batch.mapNotNull { work ->
                indexingProgress.record(WorkspaceIndexingActivity.derive(work))
                val absolutePath = work.path.absolute.value.value
                onSourceFileScan(absolutePath)
                scanner.scanFile(absolutePath)?.let { result ->
                    if (result.contentHash != work.contentHash) return@mapNotNull null
                    val update = result.update
                    SourceFileStageUpdate(
                        work = work,
                        scannedContentHash = result.contentHash,
                        update = gradleProvenance.applyTo(
                            update = update,
                            ownerModuleNames = ownerModuleNamesByPath.getValue(work.path),
                        ),
                    )
                }
            }
            requireActive()
            if (updates.isNotEmpty()) {
                store.commitSourceBatch(updates)
            }
        }
        requireActive()
        val currentPaths = prioritizeIndexingPaths(
            pathsByModule = inventoryEntries.map { entry -> IndexingPriorityEntry(entry.path, entry.module) },
            moduleOrder = emptyList(),
            criticalPaths = scope.criticalPaths.toSet(),
        )
        return IndexedSourceIdentifiers(
            paths = currentPaths,
            criticalPaths = scope.criticalPaths.toSet(),
            unmatchedCriticalPatterns = scope.unmatchedCriticalPatterns,
            removedPaths = previousPaths.minus(inventoryEntries.mapTo(linkedSetOf(), FileInventoryEntry::path)).sorted(),
        )
    }

    private fun requireActive() {
        ProgressManager.checkCanceled()
        if (environment.isCancelled()) throw ProcessCanceledException()
    }

    private fun indexSymbolRelationships(
        currentFilePaths: Collection<WorkspaceSourcePath>,
        criticalFilePaths: Set<WorkspaceSourcePath>,
        moduleOrder: List<SourceIndexModuleName>,
        batchSize: RelationshipIndexingBatchSize,
        parallelism: RelationshipIndexingParallelism,
    ) {
        val workByPath = (
            store.pendingFileStages(FileIndexStage.RELATIONSHIPS) +
                store.retryableLimitedRelationshipStages()
            )
            .associateBy { work -> work.path }
        val pendingFilePaths = currentFilePaths.filter(workByPath::containsKey)
        if (pendingFilePaths.isEmpty()) return
        val fileModuleByPath = pendingFilePaths
            .associateWith { filePath ->
                environment.findPsiFile(filePath.absolute.value.value)
                    ?.let { psiFile -> moduleResolver.moduleIdentityForFile(psiFile, filePath) }
            }
        val filesByModule = pendingFilePaths
            .map { filePath -> IndexingPriorityEntry(filePath, fileModuleByPath[filePath]) }

        val orderedFilePaths = prioritizeIndexingPaths(
            pathsByModule = filesByModule,
            moduleOrder = moduleOrder,
            criticalPaths = criticalFilePaths,
        )

        val scanner = PsiReferenceScanner(
            environment = environment,
            moduleNameForFile = { path ->
                environment.findPsiFile(path)?.let(moduleResolver::moduleNameForFile)
            },
        )
        ReferenceIndexer(
            store = store,
            batchSize = batchSize,
            parallelism = parallelism,
        ).indexPendingSymbolRelationships(
            work = orderedFilePaths.map(workByPath::getValue),
            scanner = { path ->
                indexingProgress.record(WorkspaceIndexingActivity.derive(workByPath.getValue(path)))
                val absolutePath = path.absolute.value.value
                onRelationshipFileScan(absolutePath)
                when (val result = scanner.scanFileRelationships(absolutePath)) {
                    is PsiRelationshipScanResult.Indexed -> RelationshipScanResult.Indexed(
                        contentHash = result.contentHash,
                        references = result.references,
                        declarations = result.declarations,
                        limitations = result.limitations,
                    )
                    PsiRelationshipScanResult.PsiUnavailable -> RelationshipScanResult.Failed(
                        contentHash = workByPath.getValue(path).contentHash,
                        code = FileStageFailureCode.PSI_UNAVAILABLE,
                        message = "Kotlin PSI is unavailable for this file",
                    )
                }
            },
            isCancelled = environment::isCancelled,
        )
    }

    private fun readAvailableGradleWorkspaceModel(): IdeaGradleProjectLoadBridge.GradleWorkspaceModel {
        requireActive()
        val model = try {
            readGradleWorkspaceModel()
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: WorkspaceProjectModelIncompleteException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw WorkspaceProjectModelIncompleteException(
                reason = WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE,
                message = "Gradle project model is unavailable: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
        if (!model.importedModelComplete()) {
            throw WorkspaceProjectModelIncompleteException(
                reason = WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE,
                message = "Gradle project model is incomplete during focused source refresh",
            )
        }
        requireActive()
        return model
    }

    private fun workspaceIdentityForIdea(): IdeaWorkspaceIdentity = IdeaWorkspaceIdentity.fromProject(
        project = project,
        workspaceRoot = workspaceRoot,
    ).copy(workspaceIdentity = workspaceIdentity)
}
