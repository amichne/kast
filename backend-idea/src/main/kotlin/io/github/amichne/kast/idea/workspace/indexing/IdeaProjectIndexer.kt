package io.github.amichne.kast.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import io.github.amichne.kast.api.client.IndexingConfig
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.client.WorkspaceRelativePath
import io.github.amichne.kast.api.client.fields.RelationshipIndexingBatchSize
import io.github.amichne.kast.api.client.fields.RelationshipIndexingParallelism
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageOutcome
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleIdentity
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleName
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.indexing.RelationshipScanResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import io.github.amichne.kast.shared.analysis.PsiRelationshipScanResult
import io.github.amichne.kast.shared.analysis.PsiSourceIndexScanner
import java.nio.file.Path

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
    private val onSourceFileScan: (String) -> Unit = {},
    private val onRelationshipFileScan: (String) -> Unit = {},
    private val scopeCache: WorkspaceIndexingScopeCache = WorkspaceIndexingScopeCache(),
) {
    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    private val sourceFilePolicy = SourceIndexFilePolicy.forWorkspace(this.workspaceRoot)
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
        onSourceScopeReady: (IndexedSourceIdentifiers) -> Unit = {},
    ): IndexedSourceIdentifiers {
        store.ensureSchema()
        val indexedSources = indexSourceIdentifiersAndScope(config.indexing)
        onSourceScopeReady(indexedSources)
        if (config.indexing.relationships.enabled.value && !environment.isCancelled()) {
            val moduleSpecs = runIdeaReadAction { discoverModuleSpecs() }
            val modulePriorityOrder = computeModulePriorityOrder(
                activeModule = null,
                moduleSpecs = moduleSpecs,
                dependentModuleGraph = buildIdeaDependencyGraph(moduleSpecs),
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
        val requestedPaths = currentSourcePaths(scopedFilePaths) ?: run {
            val currentFilePaths = indexSourceIdentifiers().toSet()
            scopedFilePaths.distinct().filter(currentFilePaths::contains)
        }
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

    private fun currentSourcePaths(filePaths: Collection<WorkspaceSourcePath>): List<WorkspaceSourcePath>? {
        val scanner = PsiSourceIndexScanner(
            environment = environment,
            moduleNameForFile = ::moduleNameForFile,
        )
        return buildList {
            for (path in filePaths.distinct()) {
                requireActive()
                val absolutePath = path.absolute.value.value
                onSourceFileScan(absolutePath)
                val result = scanner.scanFile(absolutePath) ?: return null
                if (
                    store.pendingFileStage(
                        path = path,
                        contentHash = result.contentHash,
                        stage = FileIndexStage.SOURCE,
                        version = FileStageVersions.CURRENT.source,
                    ) != null
                ) {
                    return null
                }
                add(path)
            }
        }
    }

    fun indexSourceIdentifiers(): Collection<WorkspaceSourcePath> {
        return indexSourceIdentifiersAndScope(KastConfig.defaults().indexing).paths
    }

    private fun indexSourceIdentifiersAndScope(config: IndexingConfig): IndexedSourceIdentifiers {
        store.ensureSchema()
        val previousPaths = store.knownSourcePaths().mapNotNullTo(linkedSetOf(), store::sourcePath)
        val captured = inventory.snapshotWithGradleModel(WorkspaceFileKindDomain.MIXED)
        val gradleProvenance = IdeaGradleFileProvenance.fromWorkspaceModel(captured.gradleModel, ideaWorkspaceIdentity)
        val inventorySnapshot = captured.inventory
        val ownerModuleNamesByPath = referenceIndexOwnersByPath(inventorySnapshot)
        val scope = scopeCache.resolve(
            workspaceRoot = workspaceRoot,
            config = config,
            candidates = ownerModuleNamesByPath.keys.map { path -> path.absolute.value.toJavaPath() },
        )
        val includedPaths = scope.includedPaths.toSet()
        val inventoryEntries = buildFileInventoryEntries(
            ownerModuleNamesByPath = ownerModuleNamesByPath.filterKeys(includedPaths::contains),
            isCancelled = environment::isCancelled,
            sourceSetForPath = ::legacySourceSetLabelForFile,
        )
        if (environment.isCancelled()) return IndexedSourceIdentifiers(emptyList(), emptySet(), emptyList())
        store.reconcileFileInventory(inventoryEntries, FileStageVersions.CURRENT)

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
            moduleNameForFile = ::moduleNameForFile,
        )
        for (batch in orderedPendingPaths.map(workByPath::getValue).chunked(SOURCE_INDEX_BATCH_SIZE)) {
            if (environment.isCancelled()) break
            val updates = batch.mapNotNull { work ->
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
            if (environment.isCancelled()) break
            if (updates.isNotEmpty()) {
                store.commitSourceBatch(updates)
            }
        }
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
                    ?.let { psiFile -> moduleIdentityForFile(psiFile, filePath) }
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
                environment.findPsiFile(path)?.let(::moduleNameForFile)
            },
        )
        ReferenceIndexer(
            store = store,
            batchSize = batchSize,
            parallelism = parallelism,
        ).indexPendingSymbolRelationships(
            work = orderedFilePaths.map(workByPath::getValue),
            scanner = { path ->
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

    private fun discoverModuleSpecs(): List<IdeaModuleSpec> {
        val moduleSpecs = ModuleManager.getInstance(project).modules
            .sortedBy(::indexedModuleNameForModule)
            .map { module ->
                val rootManager = ModuleRootManager.getInstance(module)
                IdeaModuleSpec(
                    name = indexedModuleNameForModule(module),
                    dependencyModuleNames = rootManager.dependencies.map(::indexedModuleNameForModule).sorted(),
                )
            }
        return mergeModuleSpecsByName(moduleSpecs)
    }

    private fun buildIdeaDependencyGraph(moduleSpecs: List<IdeaModuleSpec>): Map<String, Set<String>> =
        mergeModuleSpecsByName(moduleSpecs)
            .associate { module ->
                module.name to module.dependencyModuleNames.toSet()
            }

    private fun moduleNameForFile(psiFile: PsiFile): String? = runIdeaReadAction {
        val virtualFile = psiFile.virtualFile
        val sourcePath = sourceFilePolicy.sourcePath(Path.of(virtualFile.path)) ?: return@runIdeaReadAction null
        moduleIdentityForFile(psiFile, sourcePath)?.toLegacyModuleName()
    }

    private fun moduleIdentityForFile(
        psiFile: PsiFile,
        sourcePath: WorkspaceSourcePath,
    ): SourceIndexModuleIdentity? = runIdeaReadAction {
        val module = ModuleUtilCore.findModuleForFile(psiFile.virtualFile, project) ?: return@runIdeaReadAction null
        indexedModuleIdentityForFilePath(
            ideaModule = IdeaWorkspaceModuleIdentity.of(module.name),
            filePath = sourcePath,
            sourceSet = legacySourceSetLabelForFile(sourcePath),
        )
    }

    private fun indexedModuleNameForModule(module: Module): String {
        val rootManager = ModuleRootManager.getInstance(module)
        return rootManager.sourceRoots
            .asSequence()
            .mapNotNull { root -> WorkspaceRelativePath.resolve(workspaceRoot, Path.of(root.path)) }
            .mapNotNull(::legacyGradleProjectPathForWorkspacePath)
            .sorted()
            .firstOrNull()
            ?.value
            ?: module.name
    }

    private fun referenceIndexOwnersByPath(
        snapshot: IdeaWorkspaceFileInventorySnapshot,
    ): Map<WorkspaceSourcePath, Set<IdeaWorkspaceModuleIdentity>> {
        val ownersByPath = sortedMapOf<WorkspaceSourcePath, MutableSet<IdeaWorkspaceModuleIdentity>>()
        snapshot.modules.forEach { module ->
            module.allFilePaths
                .asSequence()
                .map { filePath -> Path.of(filePath).toAbsolutePath().normalize() }
                .mapNotNull(sourceFilePolicy::sourcePath)
                .forEach { path ->
                    ownersByPath
                        .getOrPut(path, ::linkedSetOf)
                        .add(module.identity)
                }
        }
        return ownersByPath.mapValues { (_, owners) -> owners.toSortedSet() }
    }

    private fun legacySourceSetLabelForFile(path: WorkspaceSourcePath): GradleSourceSetName? {
        val normalizedPath = path.relative.value
        return when {
            normalizedPath.startsWith("src/main/") || "/src/main/" in normalizedPath ->
                GradleSourceSetName.parse("main")
            normalizedPath.startsWith("src/testFixtures/") || "/src/testFixtures/" in normalizedPath ->
                GradleSourceSetName.parse("testFixtures")
            normalizedPath.startsWith("src/test/") || "/src/test/" in normalizedPath ->
                GradleSourceSetName.parse("test")
            else -> runIdeaReadAction {
                val virtualFile = LocalFileSystem.getInstance()
                    .findFileByNioFile(path.absolute.value.toJavaPath())
                    ?: return@runIdeaReadAction null
                ProjectFileIndex.getInstance(project)
                    .getSourceRootForFile(virtualFile)
                    ?.name
                    ?.let(GradleSourceSetName::parse)
            }
        }
    }

    private fun SourceIndexModuleIdentity.toLegacyModuleName(): String =
        sourceSet?.let { "${name.value}[${it.value}]" } ?: name.value

    private fun workspaceIdentityForIdea(): IdeaWorkspaceIdentity = IdeaWorkspaceIdentity.fromProject(
        project = project,
        workspaceRoot = workspaceRoot,
    ).copy(workspaceIdentity = workspaceIdentity)
}
