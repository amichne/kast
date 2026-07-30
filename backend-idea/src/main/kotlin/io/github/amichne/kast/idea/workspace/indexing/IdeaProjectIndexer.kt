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
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageOutcome
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.indexing.RelationshipScanResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import io.github.amichne.kast.shared.analysis.PsiRelationshipScanResult
import io.github.amichne.kast.shared.analysis.PsiSourceIndexScanner
import java.nio.file.Path

private const val SOURCE_INDEX_BATCH_SIZE = 50

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
) {
    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
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

    fun indexProject(config: KastConfig) {
        store.ensureSchema()
        val currentFilePaths = indexSourceIdentifiers()
        if (config.indexing.relationships.enabled.value && !environment.isCancelled()) {
            val moduleSpecs = runIdeaReadAction { discoverModuleSpecs() }
            val modulePriorityOrder = computeModulePriorityOrder(
                activeModule = null,
                moduleSpecs = moduleSpecs,
                dependentModuleGraph = buildIdeaDependencyGraph(moduleSpecs),
                depth = config.indexing.relationships.modulePriorityDepth.value,
            )
            indexSymbolRelationships(
                currentFilePaths = currentFilePaths,
                moduleOrder = modulePriorityOrder,
                batchSize = config.indexing.relationships.batchSize.value,
                parallelism = config.indexing.relationships.parallelism.value,
            )
        }
    }

    fun refreshSymbolRelationships(filePaths: Collection<String>): List<FileStageOutcome> {
        require(filePaths.all(SourceIndexFilePolicy::isEligible)) {
            "Focused relationship refresh accepts Kotlin source files only"
        }
        requireActive()
        val currentFilePaths = indexSourceIdentifiers().toSet()
        requireActive()
        val requestedPaths = filePaths.distinct().filter(currentFilePaths::contains)
        val previousFailureIds = requestedPaths.associateWith { path ->
            store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS)?.failure?.id
        }
        indexSymbolRelationships(
            currentFilePaths = requestedPaths,
            moduleOrder = emptyList(),
            batchSize = SOURCE_INDEX_BATCH_SIZE,
            parallelism = 1,
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

    fun indexSourceIdentifiers(): Collection<String> {
        store.ensureSchema()
        val captured = inventory.snapshotWithGradleModel(WorkspaceFileKindDomain.MIXED)
        val gradleProvenance = IdeaGradleFileProvenance.fromWorkspaceModel(captured.gradleModel, ideaWorkspaceIdentity)
        val inventorySnapshot = captured.inventory
        val ownerModuleNamesByPath = referenceIndexOwnersByPath(inventorySnapshot)
        val inventoryEntries = buildFileInventoryEntries(
            ownerModuleNamesByPath = ownerModuleNamesByPath,
            workspaceRoot = workspaceRoot,
            isCancelled = environment::isCancelled,
            sourceSetForPath = ::legacySourceSetLabelForFile,
        )
        if (environment.isCancelled()) return emptyList()
        store.reconcileFileInventory(inventoryEntries, FileStageVersions.CURRENT)

        val scanner = PsiSourceIndexScanner(
            environment = environment,
            moduleNameForFile = ::moduleNameForFile,
        )
        for (batch in store.pendingFileStages(FileIndexStage.SOURCE).chunked(SOURCE_INDEX_BATCH_SIZE)) {
            if (environment.isCancelled()) break
            val updates = batch.mapNotNull { work ->
                onSourceFileScan(work.path)
                scanner.scanFile(work.path)?.let { result ->
                    if (result.contentHash != work.contentHash) return@mapNotNull null
                    val update = result.update
                    SourceFileStageUpdate(
                        work = work,
                        scannedContentHash = result.contentHash,
                        update = gradleProvenance.applyTo(
                            update = update,
                            ownerModuleNames = ownerModuleNamesByPath.getValue(update.path),
                        ),
                    )
                }
            }
            if (environment.isCancelled()) break
            if (updates.isNotEmpty()) {
                store.commitSourceBatch(updates)
            }
        }
        return inventoryEntries.map(FileInventoryEntry::path)
    }

    private fun requireActive() {
        ProgressManager.checkCanceled()
        if (environment.isCancelled()) throw ProcessCanceledException()
    }

    private fun indexSymbolRelationships(
        currentFilePaths: Collection<String>,
        moduleOrder: List<String>,
        batchSize: Int,
        parallelism: Int,
    ) {
        val workByPath = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
            .associateBy { work -> work.path }
        val pendingFilePaths = currentFilePaths.filter(workByPath::containsKey)
        if (pendingFilePaths.isEmpty()) return
        val fileModuleByPath = pendingFilePaths
            .associateWith { filePath ->
                environment.findPsiFile(filePath)
                    ?.let(::moduleNameForFile)
                    ?.let(::canonicalModuleName)
            }
        val filesByModule = pendingFilePaths
            .associateWith { filePath -> fileModuleByPath[filePath] }
            .toList()

        val orderedFilePaths = prioritizeFilesByModule(pathsByModule = filesByModule, moduleOrder = moduleOrder)

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
                onRelationshipFileScan(path)
                when (val result = scanner.scanFileRelationships(path)) {
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

    private fun prioritizeFilesByModule(
        pathsByModule: Collection<Pair<String, String?>>,
        moduleOrder: List<String>,
    ): Collection<String> {
        if (moduleOrder.isEmpty()) return pathsByModule.map(Pair<String, String?>::first)

        val modulePriorityByName = moduleOrder
            .withIndex()
            .associate { (index, moduleName) -> moduleName to index }

        fun priorityFor(moduleName: String?): Int = moduleName
            .let(::canonicalModuleName)
            ?.let(modulePriorityByName::get)
            ?: Int.MAX_VALUE

        return pathsByModule
            .sortedWith(
                compareBy<Pair<String, String?>>(
                    { (path, moduleName) -> priorityFor(moduleName) },
                    { (_, moduleName) -> canonicalModuleName(moduleName) ?: "" },
                    { (path) -> path },
                ),
            ).map { (path, _) -> path }
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

    private fun canonicalModuleName(moduleName: String?): String? =
        moduleName?.substringBefore("[")

    private fun moduleNameForFile(psiFile: PsiFile): String? = runIdeaReadAction {
        val virtualFile = psiFile.virtualFile
        val module = ModuleUtilCore.findModuleForFile(virtualFile, project) ?: return@runIdeaReadAction null
        val sourceSet = legacySourceSetLabelForFile(virtualFile.path)
        indexedModuleNameForFilePath(
            ideaModuleName = module.name,
            filePath = virtualFile.path,
            workspaceRoot = workspaceRoot,
            sourceSet = sourceSet,
        )
    }

    private fun indexedModuleNameForModule(module: Module): String {
        val rootManager = ModuleRootManager.getInstance(module)
        return rootManager.sourceRoots
            .asSequence()
            .mapNotNull { root -> legacyGradleProjectPathForFile(root.path, workspaceRoot) }
            .sorted()
            .firstOrNull()
            ?: module.name
    }

    private fun referenceIndexOwnersByPath(
        snapshot: IdeaWorkspaceFileInventorySnapshot,
    ): Map<String, Set<IdeaWorkspaceModuleIdentity>> {
        val ownersByPath = sortedMapOf<String, MutableSet<IdeaWorkspaceModuleIdentity>>()
        snapshot.modules.forEach { module ->
            module.allFilePaths
                .asSequence()
                .map { filePath -> Path.of(filePath).toAbsolutePath().normalize() }
                .filter(workspaceIdentity::contains)
                .filter(SourceIndexFilePolicy::isEligible)
                .forEach { path ->
                    ownersByPath
                        .getOrPut(path.toString(), ::linkedSetOf)
                        .add(module.identity)
                }
        }
        return ownersByPath.mapValues { (_, owners) -> owners.toSortedSet() }
    }

    private fun legacySourceSetLabelForFile(path: String): String? {
        val normalizedPath = path.replace('\\', '/')
        return when {
            "/src/main/" in normalizedPath -> "main"
            "/src/testFixtures/" in normalizedPath -> "testFixtures"
            "/src/test/" in normalizedPath -> "test"
            else -> runIdeaReadAction {
                val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(Path.of(path)) ?: return@runIdeaReadAction null
                ProjectFileIndex.getInstance(project).getSourceRootForFile(virtualFile)?.name
            }
        }
    }

    private fun workspaceIdentityForIdea(): IdeaWorkspaceIdentity = IdeaWorkspaceIdentity.fromProject(
        project = project,
        workspaceRoot = workspaceRoot,
    ).copy(workspaceIdentity = workspaceIdentity)
}

internal fun indexedModuleNameForFilePath(
    ideaModuleName: String,
    filePath: String,
    workspaceRoot: Path,
    sourceSet: String?,
): String {
    val modulePath = legacyGradleProjectPathForFile(filePath, workspaceRoot) ?: ideaModuleName
    return if (sourceSet == null) modulePath else "$modulePath[$sourceSet]"
}

private fun legacyGradleProjectPathForFile(
    filePath: String,
    workspaceRoot: Path,
): String? {
    val root = workspaceRoot.toAbsolutePath().normalize()
    val path = Path.of(filePath).toAbsolutePath().normalize()
    if (!path.startsWith(root)) return null

    val segments = root.relativize(path).map { segment -> segment.toString() }
    val srcIndex = segments.indexOf("src")
    if (srcIndex < 0) return null

    val projectSegments = segments.take(srcIndex)
    return if (projectSegments.isEmpty()) ":" else projectSegments.joinToString(
        separator = ":",
        prefix = ":",
    )
}
