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
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import io.github.amichne.kast.shared.analysis.PsiSourceIndexScanner
import java.nio.file.Files
import java.nio.file.Path

internal class IdeaProjectIndexer(
    private val project: Project,
    private val workspaceRoot: Path,
    private val store: SqliteSourceIndexStore,
    private val cancelled: () -> Boolean,
) {
    private val environment = IdeaReferenceIndexEnvironment(
        project = project,
        workspaceRoot = workspaceRoot,
        cancelled = cancelled,
    )

    fun indexProject(config: KastConfig) {
        store.ensureSchema()
        val currentFilePaths = indexSourceIdentifiers()
        if (config.indexing.phase2Enabled.value && !environment.isCancelled()) {
            val moduleSpecs = runIdeaReadAction { discoverModuleSpecs() }
            val modulePriorityOrder = computeModulePriorityOrder(
                activeModule = null,
                moduleSpecs = moduleSpecs,
                dependentModuleGraph = buildIdeaDependencyGraph(moduleSpecs),
                depth = config.indexing.phase2PriorityDepth.value,
            )
            indexReferences(currentFilePaths, modulePriorityOrder, config.indexing.referenceBatchSize.value)
        }
    }

    fun indexSourceIdentifiers(): Collection<String> {
        store.ensureSchema()
        val scanner = PsiSourceIndexScanner(
            environment = environment,
            moduleNameForFile = ::moduleNameForFile,
        )
        val updates = environment.allFilePaths().mapNotNull(scanner::scanFile)
        val manifest = updates.associate { update ->
            update.path to lastModifiedMillis(update.path)
        }
        store.saveFullIndex(updates = updates, manifest = manifest)
        return manifest.keys
    }

    private fun indexReferences(
        currentFilePaths: Collection<String>,
        moduleOrder: List<String>,
        referenceBatchSize: Int,
    ) {
        if (currentFilePaths.isEmpty()) return
        val fileModuleByPath = currentFilePaths
            .associateWith { filePath ->
                environment.findPsiFile(filePath)
                    ?.let(::moduleNameForFile)
                    ?.let(::canonicalModuleName)
            }
        store.removeReferencesOutsideSources(currentFilePaths)
        val moduleFileCountByName = moduleOrder
            .associateWith { 0 }
            .toMutableMap<String, Int>()
        for ((_, moduleName) in fileModuleByPath) {
            if (moduleName != null) {
                moduleFileCountByName[moduleName] = moduleFileCountByName.getOrDefault(moduleName, 0) + 1
            }
        }
        store.initializeModuleProgress(moduleFileCountByName)

        for ((moduleName, fileCount) in moduleFileCountByName) {
            if (fileCount == 0) {
                store.markModuleComplete(moduleName, fileCount)
            }
        }

        val filesByModule = currentFilePaths
            .associateWith { filePath -> fileModuleByPath[filePath] }
            .toList()

        val orderedFilePaths = prioritizeFilesByModule(pathsByModule = filesByModule, moduleOrder = moduleOrder)

        val completedByModule = mutableMapOf<String, Int>()
        val scanner = PsiReferenceScanner(
            environment = environment,
            moduleNameForFile = { path ->
                environment.findPsiFile(path)?.let(::moduleNameForFile)
            },
        )
        ReferenceIndexer(store, batchSize = referenceBatchSize).indexReferences(
            filePaths = orderedFilePaths,
            referenceScanner = scanner::scanFileReferences,
            declarationScanner = scanner::scanFileDeclarations,
            isCancelled = environment::isCancelled,
            onFilesIndexed = { indexedPaths ->
                for (path in indexedPaths) {
                    val moduleName = fileModuleByPath[path] ?: ""
                    if (moduleName.isEmpty()) continue
                    val count = completedByModule.getOrDefault(moduleName, 0) + 1
                    completedByModule[moduleName] = count
                    if (count == 1) {
                        store.markModuleIndexing(moduleName)
                    }
                    if (count == moduleFileCountByName.getValue(moduleName)) {
                        store.markModuleComplete(moduleName, count)
                    }
                }
            },
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
        val sourceSet = sourceSetForFile(virtualFile.path)
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
            .mapNotNull { root -> gradleProjectPathForFile(root.path, workspaceRoot) }
            .sorted()
            .firstOrNull()
            ?: module.name
    }

    private fun sourceSetForFile(path: String): String? {
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

    private fun lastModifiedMillis(filePath: String): Long {
        val path = Path.of(filePath)
        return if (Files.isRegularFile(path)) Files.getLastModifiedTime(path).toMillis() else 0L
    }
}

internal fun indexedModuleNameForFilePath(
    ideaModuleName: String,
    filePath: String,
    workspaceRoot: Path,
    sourceSet: String?,
): String {
    val modulePath = gradleProjectPathForFile(filePath, workspaceRoot) ?: ideaModuleName
    return if (sourceSet == null) modulePath else "$modulePath[$sourceSet]"
}

private fun gradleProjectPathForFile(
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
