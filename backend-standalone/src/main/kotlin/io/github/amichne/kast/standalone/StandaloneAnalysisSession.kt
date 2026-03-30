package io.github.amichne.kast.standalone

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.psi.compiled.ClassFileDecompilers
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
import kotlin.io.path.name

class StandaloneAnalysisSession(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
) : AutoCloseable {
    private val disposable: Disposable = Disposer.newDisposable("kast-standalone")
    private val ktFilesByPath: Map<String, KtFile>

    val session: StandaloneAnalysisAPISession
    val sourceModule: KaSourceModule
    val resolvedSourceRoots: List<Path>
    val resolvedClasspathRoots: List<Path>

    init {
        resolvedSourceRoots = sourceRoots
            .ifEmpty { discoverSourceRoots(workspaceRoot) }
            .map(::normalizePath)
            .distinct()
            .sorted()
        require(resolvedSourceRoots.isNotEmpty()) {
            "No Kotlin source roots were found under $workspaceRoot"
        }

        resolvedClasspathRoots = (defaultClasspathRoots() + classpathRoots.map(::normalizePath))
            .distinct()
            .sorted()

        val jdkHome = normalizePath(Path.of(System.getProperty("java.home")))
        var createdSourceModule: KaSourceModule? = null
        val createdSession = buildStandaloneAnalysisAPISession(
            projectDisposable = disposable,
            unitTestMode = false,
        ) {
            buildKtModuleProvider {
                val platform = JvmPlatforms.defaultJvmPlatform
                val libraryModule = buildKtLibraryModule {
                    this.platform = platform
                    addBinaryRoots(resolvedClasspathRoots)
                    libraryName = "Library for $moduleName"
                }
                val sdkModule = buildKtSdkModule {
                    this.platform = platform
                    addBinaryRootsFromJdkHome(jdkHome, isJre = false)
                    libraryName = "JDK for $moduleName"
                }
                val builtSourceModule = buildKtSourceModule {
                    this.platform = platform
                    this.moduleName = moduleName
                    resolvedSourceRoots.forEach(::addSourceRoot)
                    addRegularDependency(libraryModule)
                    addRegularDependency(sdkModule)
                }
                addModule(builtSourceModule)
                createdSourceModule = builtSourceModule
                this.platform = platform
            }
        }

        session = createdSession
        initializeJvmDecompilerServices()
        sourceModule = checkNotNull(createdSourceModule) {
            "The standalone Analysis API session did not create a source module"
        }
        ktFilesByPath = createdSession.modulesWithFiles[sourceModule]
            .orEmpty()
            .filterIsInstance<KtFile>()
            .associateBy(::normalizeFileLookupPath)
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

    private fun normalizeFileLookupPath(file: KtFile): String {
        val virtualPath = file.virtualFile?.path
            ?: throw NotFoundException("The standalone analysis session produced a KtFile without a virtual path")
        return normalizePath(Path.of(virtualPath)).toString()
    }

    private fun defaultClasspathRoots(): List<Path> = buildList {
        classpathRootOf(kotlin.Unit::class.java)?.let(::add)
    }.distinct()

    private fun classpathRootOf(type: Class<*>): Path? {
        val location = type.protectionDomain?.codeSource?.location ?: return null
        return runCatching { normalizePath(Path.of(location.toURI())) }.getOrNull()
    }

    private fun discoverSourceRoots(workspaceRoot: Path): List<Path> {
        val conventionalRoots = listOf(
            workspaceRoot.resolve("src/main/kotlin"),
            workspaceRoot.resolve("src/test/kotlin"),
        ).filter(Files::isDirectory)
        if (conventionalRoots.isNotEmpty()) {
            return conventionalRoots
        }

        val discoveredRoots = linkedSetOf<Path>()
        Files.walk(workspaceRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.name.endsWith(".kt") }
                .forEach { file -> discoveredRoots.add(file.parent) }
        }
        return discoveredRoots.toList()
    }

    private fun normalizePath(path: Path): Path {
        val absolutePath = path.toAbsolutePath().normalize()
        return runCatching { absolutePath.toRealPath().normalize() }.getOrDefault(absolutePath)
    }
}
