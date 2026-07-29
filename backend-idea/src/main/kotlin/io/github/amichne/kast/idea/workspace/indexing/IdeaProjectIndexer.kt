package io.github.amichne.kast.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
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

    fun indexSourceIdentifiers(): Collection<String> {
        store.ensureSchema()
        val (gradleProvenance, inventorySnapshot) = runIdeaReadAction {
            val gradleModel = readGradleWorkspaceModel()
            IdeaGradleFileProvenance.fromWorkspaceModel(gradleModel, ideaWorkspaceIdentity) to
                inventory.snapshot(WorkspaceFileKindDomain.MIXED, gradleModel)
        }
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

internal data class IdeaModuleSpec(
    val name: String,
    val dependencyModuleNames: List<String>,
)

internal fun mergeModuleSpecsByName(moduleSpecs: List<IdeaModuleSpec>): List<IdeaModuleSpec> =
    moduleSpecs
        .groupBy(IdeaModuleSpec::name)
        .map { (name, specs) ->
            IdeaModuleSpec(
                name = name,
                dependencyModuleNames = specs
                    .flatMap(IdeaModuleSpec::dependencyModuleNames)
                    .filterNot { dependencyName -> dependencyName == name }
                    .toSortedSet()
                    .toList(),
            )
        }
        .sortedBy(IdeaModuleSpec::name)

internal fun computeModulePriorityOrder(
    activeModule: String?,
    moduleSpecs: List<IdeaModuleSpec>,
    dependentModuleGraph: Map<String, Set<String>>,
    depth: Int,
): List<String> {
    if (depth < 0) return emptyList()

    val mergedModuleSpecs = mergeModuleSpecsByName(moduleSpecs)
    val moduleNames = mergedModuleSpecs.mapTo(mutableSetOf()) { it.name }.sorted()
    if (activeModule == null || activeModule !in moduleNames) {
        return topologicallySortModules(mergedModuleSpecs)
    }

    val priorityModules = linkedSetOf<String>()
    val queue: ArrayDeque<Pair<String, Int>> = ArrayDeque()
    queue += activeModule to 0
    while (queue.isNotEmpty()) {
        val (moduleName, moduleDepth) = queue.removeFirst()
        if (!priorityModules.add(moduleName) || moduleDepth >= depth) {
            continue
        }
        dependentModuleGraph[moduleName]
            .orEmpty()
            .sorted()
            .forEach { dependencyModuleName ->
                queue += dependencyModuleName to moduleDepth + 1
            }
    }

    return (priorityModules + topologicallySortModules(mergedModuleSpecs).filterNot { it in priorityModules }).toList()
}

private fun topologicallySortModules(moduleSpecs: List<IdeaModuleSpec>): List<String> {
    val mergedModuleSpecs = mergeModuleSpecsByName(moduleSpecs)
    val modulesByName = mergedModuleSpecs.associateBy(IdeaModuleSpec::name)
    val incomingEdges = mergedModuleSpecs
        .associate { spec -> spec.name to spec.dependencyModuleNames.toMutableSet() }
        .toMutableMap()

    val outgoingEdges = linkedMapOf<String, MutableSet<String>>()
    for (spec in mergedModuleSpecs) {
        for (dependencyName in spec.dependencyModuleNames) {
            if (!modulesByName.containsKey(dependencyName)) {
                continue
            }
            outgoingEdges
                .getOrPut(dependencyName) { linkedSetOf() }
                .add(spec.name)
        }
    }

    val readyNames = ArrayDeque(
        mergedModuleSpecs
            .filter { spec -> incomingEdges.getValue(spec.name).isEmpty() }
            .map(IdeaModuleSpec::name)
            .sorted(),
    )
    val ordered = mutableListOf<String>()
    while (readyNames.isNotEmpty()) {
        val moduleName = readyNames.removeFirst()
        ordered += moduleName
        for (dependentName in outgoingEdges[moduleName].orEmpty().sorted()) {
            val dependencies = incomingEdges.getValue(dependentName)
            dependencies.remove(moduleName)
            if (dependencies.isEmpty()) {
                readyNames.addLast(dependentName)
            }
        }
    }

    return ordered
}
