package io.github.amichne.kast.standalone

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.util.Disposer
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.java.JavaParserDefinition
import com.intellij.lang.java.syntax.JavaElementTypeConverterExtension
import com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory
import com.intellij.platform.syntax.psi.ElementTypeConverters
import io.github.amichne.kast.api.NotFoundException
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

class StandaloneAnalysisSession(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
) : AutoCloseable {
    private val disposable: Disposable = Disposer.newDisposable("kast-standalone")
    private val ktFilesByPath: Map<String, KtFile> by lazy(::loadKtFilesByPath)

    private val session: StandaloneAnalysisAPISession
    val sourceModules: List<KaSourceModule>
    val resolvedSourceRoots: List<Path>
    private val resolvedClasspathRoots: List<Path>

    init {
        val workspaceLayout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots,
            classpathRoots = classpathRoots,
            moduleName = moduleName,
        )
        require(workspaceLayout.sourceModules.isNotEmpty()) {
            "No source roots were found under ${normalizeStandalonePath(workspaceRoot)}"
        }

        resolvedSourceRoots = workspaceLayout.sourceModules
            .flatMap { module -> module.sourceRoots }
            .distinct()
            .sorted()
        val defaultClasspathRoots = defaultClasspathRoots()
        resolvedClasspathRoots = (
            defaultClasspathRoots +
                workspaceLayout.sourceModules.flatMap { module -> module.binaryRoots }
        ).distinct().sorted()

        val jdkHome = normalizePath(Path.of(System.getProperty("java.home")))
        val createdSourceModules = mutableListOf<KaSourceModule>()
        val createdSourceModulesByName = linkedMapOf<String, KaSourceModule>()
        val createdSession = buildStandaloneAnalysisAPISession(
            projectDisposable = disposable,
            unitTestMode = false,
        ) {
            initializeJavaSourceSupport()
            buildKtModuleProvider {
                val platform = JvmPlatforms.defaultJvmPlatform
                val sdkModule = buildKtSdkModule {
                    this.platform = platform
                    addBinaryRootsFromJdkHome(jdkHome, isJre = false)
                    libraryName = "JDK for ${workspaceLayout.sourceModules.first().name}"
                }

                for (moduleSpec in topologicallySortSourceModules(workspaceLayout.sourceModules)) {
                    val libraryModule = buildLibraryModule(
                        moduleName = moduleSpec.name,
                        platform = platform,
                        binaryRoots = (defaultClasspathRoots + moduleSpec.binaryRoots).distinct().sorted(),
                    )
                    val builtSourceModule = buildKtSourceModule {
                        this.platform = platform
                        this.moduleName = moduleSpec.name
                        moduleSpec.sourceRoots.forEach(::addSourceRoot)
                        addRegularDependency(sdkModule)
                        libraryModule?.let(::addRegularDependency)
                        moduleSpec.dependencyModuleNames.forEach { dependencyName ->
                            addRegularDependency(
                                checkNotNull(createdSourceModulesByName[dependencyName]) {
                                    "The standalone session could not resolve source module dependency $dependencyName for ${moduleSpec.name}"
                                },
                            )
                        }
                    }
                    addModule(builtSourceModule)
                    createdSourceModulesByName[moduleSpec.name] = builtSourceModule
                    createdSourceModules += builtSourceModule
                }
                this.platform = platform
            }
        }

        session = createdSession
        initializeJvmDecompilerServices()
        sourceModules = createdSourceModules
        check(sourceModules.isNotEmpty()) {
            "The standalone Analysis API session did not create any source modules"
        }
    }

    fun allKtFiles(): List<KtFile> = ktFilesByPath.values.sortedBy(::normalizeFileLookupPath)

    fun findKtFile(filePath: String): KtFile {
        val normalizedPath = normalizePath(Path.of(filePath)).toString()
        return ktFilesByPath[normalizedPath]
            ?: throw NotFoundException(
                message = "The requested file is not part of the standalone analysis session",
                details = mapOf("filePath" to normalizedPath),
            )
    }

    override fun close() {
        Disposer.dispose(disposable)
    }

    /**
     * `ClassFileDecompilers` notifies `BinaryFileTypeDecompilers` on extension changes.
     * If the binary decompiler service is still lazy when the application starts disposing,
     * IntelliJ tries to instantiate it under an already-disposed parent and fails loudly.
     */
    private fun initializeJvmDecompilerServices() {
        ClassFileDecompilers.getInstance()
        BinaryFileTypeDecompilers.getInstance()
    }

    private fun initializeJavaSourceSupport() {
        val parserDefinitions = LanguageParserDefinitions.INSTANCE
        if (parserDefinitions.forLanguage(JavaLanguage.INSTANCE) == null) {
            val parserDefinition = JavaParserDefinition()
            parserDefinitions.addExplicitExtension(JavaLanguage.INSTANCE, parserDefinition)
            Disposer.register(disposable) {
                parserDefinitions.removeExplicitExtension(JavaLanguage.INSTANCE, parserDefinition)
            }
        }

        val converterFactories = ElementTypeConverters.instance
        var addedConverterFactory = false
        if (converterFactories.allForLanguage(JavaLanguage.INSTANCE).none { factory ->
                factory is CommonElementTypeConverterFactory
            }
        ) {
            val commonConverterFactory = CommonElementTypeConverterFactory()
            converterFactories.addExplicitExtension(JavaLanguage.INSTANCE, commonConverterFactory)
            addedConverterFactory = true
            Disposer.register(disposable) {
                converterFactories.removeExplicitExtension(JavaLanguage.INSTANCE, commonConverterFactory)
            }
        }
        if (converterFactories.allForLanguage(JavaLanguage.INSTANCE).none { factory ->
                factory is JavaElementTypeConverterExtension
            }
        ) {
            val converterFactory = JavaElementTypeConverterExtension()
            converterFactories.addExplicitExtension(JavaLanguage.INSTANCE, converterFactory)
            addedConverterFactory = true
            Disposer.register(disposable) {
                converterFactories.removeExplicitExtension(JavaLanguage.INSTANCE, converterFactory)
            }
        }
        if (addedConverterFactory) {
            converterFactories.clearCache()
        }
    }

    private fun normalizeFileLookupPath(file: KtFile): String {
        val virtualPath = file.virtualFile?.path
            ?: throw NotFoundException("The standalone analysis session produced a KtFile without a virtual path")
        return normalizePath(Path.of(virtualPath)).toString()
    }

    private fun loadKtFilesByPath(): Map<String, KtFile> = sourceModules
        .asSequence()
        .flatMap { sourceModule -> session.modulesWithFiles[sourceModule].orEmpty().asSequence() }
        .filterIsInstance<KtFile>()
        .associateBy(::normalizeFileLookupPath)

    private fun org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder.buildLibraryModule(
        moduleName: String,
        platform: org.jetbrains.kotlin.platform.TargetPlatform,
        binaryRoots: List<Path>,
    ) = binaryRoots
        .takeIf(List<Path>::isNotEmpty)
        ?.let { roots ->
            buildKtLibraryModule {
                this.platform = platform
                addBinaryRoots(roots)
                libraryName = "Library for $moduleName"
            }
        }

    private fun defaultClasspathRoots(): List<Path> = buildList {
        classpathRootOf(Unit::class.java)?.let(::add)
    }.distinct()

    private fun classpathRootOf(type: Class<*>): Path? {
        val location = type.protectionDomain?.codeSource?.location ?: return null
        return runCatching { normalizePath(Path.of(location.toURI())) }.getOrNull()
    }

    private fun normalizePath(path: Path): Path {
        return normalizeStandalonePath(path)
    }
}

internal data class StandaloneWorkspaceLayout(
    val sourceModules: List<StandaloneSourceModuleSpec>,
)

internal data class StandaloneSourceModuleSpec(
    val name: String,
    val sourceRoots: List<Path>,
    val binaryRoots: List<Path>,
    val dependencyModuleNames: List<String>,
)

internal fun discoverStandaloneWorkspaceLayout(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
): StandaloneWorkspaceLayout {
    val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
    if (sourceRoots.isNotEmpty()) {
        return StandaloneWorkspaceLayout(
            sourceModules = listOf(
                StandaloneSourceModuleSpec(
                    name = moduleName,
                    sourceRoots = normalizeStandaloneSourceRoots(sourceRoots),
                    binaryRoots = normalizeStandalonePaths(classpathRoots),
                    dependencyModuleNames = emptyList(),
                ),
            ),
        )
    }
    if (looksLikeGradleWorkspace(normalizedWorkspaceRoot)) {
        return GradleWorkspaceDiscovery.discover(
            workspaceRoot = normalizedWorkspaceRoot,
            extraClasspathRoots = normalizeStandalonePaths(classpathRoots),
        )
    }

    return StandaloneWorkspaceLayout(
        sourceModules = listOf(
            StandaloneSourceModuleSpec(
                name = moduleName,
                sourceRoots = discoverSourceRoots(normalizedWorkspaceRoot),
                binaryRoots = normalizeStandalonePaths(classpathRoots),
                dependencyModuleNames = emptyList(),
            ),
        ),
    )
}

internal fun normalizeStandalonePath(path: Path): Path {
    val absolutePath = path.toAbsolutePath().normalize()
    return runCatching { absolutePath.toRealPath().normalize() }.getOrDefault(absolutePath)
}

internal fun normalizeStandaloneModelPath(path: Path): Path = path.toAbsolutePath().normalize()

internal fun normalizeStandalonePaths(paths: Iterable<Path>): List<Path> = paths
    .map(::normalizeStandalonePath)
    .distinct()
    .sorted()

internal fun normalizeStandaloneSourceRoots(paths: Iterable<Path>): List<Path> = paths
    .map(::normalizeStandalonePath)
    .distinct()
    .sorted()

private fun discoverSourceRoots(workspaceRoot: Path): List<Path> {
    val conventionalRoots = listOf(
        workspaceRoot.resolve("src/main/kotlin"),
        workspaceRoot.resolve("src/main/java"),
        workspaceRoot.resolve("src/test/kotlin"),
        workspaceRoot.resolve("src/test/java"),
    ).filter(Files::isDirectory)
    if (conventionalRoots.isNotEmpty()) {
        return conventionalRoots.map(::normalizeStandalonePath).distinct().sorted()
    }

    val discoveredRoots = linkedSetOf<Path>()
    Files.walk(workspaceRoot).use { paths ->
        paths
            .filter { path ->
                Files.isRegularFile(path) && path.extension in setOf("kt", "kts", "java")
            }
            .forEach { file -> discoveredRoots.add(normalizeStandalonePath(file.parent)) }
    }
    return discoveredRoots.toList().sorted()
}

private fun looksLikeGradleWorkspace(workspaceRoot: Path): Boolean = listOf(
    "settings.gradle.kts",
    "settings.gradle",
    "build.gradle.kts",
    "build.gradle",
).any { fileName -> Files.isRegularFile(workspaceRoot.resolve(fileName)) }

private fun topologicallySortSourceModules(sourceModules: List<StandaloneSourceModuleSpec>): List<StandaloneSourceModuleSpec> {
    val sourceModulesByName = sourceModules.associateBy(StandaloneSourceModuleSpec::name)
    val incomingEdges = sourceModules.associate { module ->
        module.name to module.dependencyModuleNames.toMutableSet()
    }.toMutableMap()
    val outgoingEdges = linkedMapOf<String, MutableSet<String>>()
    for (module in sourceModules) {
        for (dependencyName in module.dependencyModuleNames) {
            require(sourceModulesByName.containsKey(dependencyName)) {
                "The standalone workspace layout referenced an unknown source module dependency $dependencyName"
            }
            outgoingEdges.getOrPut(dependencyName) { linkedSetOf() }.add(module.name)
        }
    }

    val readyNames = ArrayDeque(
        sourceModules
            .filter { module -> incomingEdges.getValue(module.name).isEmpty() }
            .map(StandaloneSourceModuleSpec::name)
            .sorted(),
    )
    val orderedModules = mutableListOf<StandaloneSourceModuleSpec>()
    while (readyNames.isNotEmpty()) {
        val moduleName = readyNames.removeFirst()
        orderedModules += checkNotNull(sourceModulesByName[moduleName])
        for (dependentName in outgoingEdges[moduleName].orEmpty().sorted()) {
            val dependencies = incomingEdges.getValue(dependentName)
            dependencies.remove(moduleName)
            if (dependencies.isEmpty()) {
                readyNames.addLast(dependentName)
            }
        }
    }

    require(orderedModules.size == sourceModules.size) {
        val unresolvedModuleNames = incomingEdges
            .filterValues(Set<String>::isNotEmpty)
            .keys
            .sorted()
        "The standalone workspace layout contains cyclic source module dependencies: ${unresolvedModuleNames.joinToString(", ")}"
    }
    return orderedModules
}
