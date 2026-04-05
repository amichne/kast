package io.github.amichne.kast.standalone

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.PsiManager
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.java.JavaParserDefinition
import com.intellij.lang.java.syntax.JavaElementTypeConverterExtension
import com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory
import com.intellij.platform.syntax.psi.ElementTypeConverters
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.RefreshResult
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.extension

class StandaloneAnalysisSession(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
    private val initialSourceIndexBuilder: (() -> Map<String, List<String>>)? = null,
) : AutoCloseable {
    private val disposable: Disposable = Disposer.newDisposable("kast-standalone")
    private val ktFilesByPath = ConcurrentHashMap<String, KtFile>()
    private val targetedKtFilesByPath = ConcurrentHashMap<String, KtFile>()
    private val targetedCandidatePathsByLookupKey = ConcurrentHashMap<CandidateLookupKey, List<String>>()
    private val sourceIdentifierIndex = AtomicReference<MutableSourceIdentifierIndex?>(null)
    private val initialSourceIndexReady = CompletableFuture<Unit>()
    private val pendingSourceIndexRefreshPaths = ConcurrentHashMap.newKeySet<String>()
    private val fullKtFileMapLoadLock = Any()
    private val analysisSessionLock = ReentrantReadWriteLock()
    @Volatile
    private var fullKtFileMapLoaded = false

    private val sourceModuleSpecs: List<StandaloneSourceModuleSpec>
    private val dependentModuleNamesBySourceModuleName: Map<String, Set<String>>
    lateinit var sourceModules: List<KaSourceModule>
        private set
    val resolvedSourceRoots: List<Path>
    private val resolvedClasspathRoots: List<Path>
    private lateinit var sessionStateDisposable: Disposable
    private lateinit var session: StandaloneAnalysisAPISession

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
        sourceModuleSpecs = workspaceLayout.sourceModules
        dependentModuleNamesBySourceModuleName = buildDependentModuleNamesBySourceModuleName(sourceModuleSpecs)

        resolvedSourceRoots = workspaceLayout.sourceModules
            .flatMap { module -> module.sourceRoots }
            .distinct()
            .sorted()
        val defaultClasspathRoots = defaultClasspathRoots()
        resolvedClasspathRoots = (
            defaultClasspathRoots +
                workspaceLayout.sourceModules.flatMap { module -> module.binaryRoots }
        ).distinct().sorted()

        val initialAnalysisState = buildAnalysisState()
        sessionStateDisposable = initialAnalysisState.disposable
        session = initialAnalysisState.session
        initializeJvmDecompilerServices()
        sourceModules = initialAnalysisState.sourceModules
        check(sourceModules.isNotEmpty()) {
            "The standalone Analysis API session did not create any source modules"
        }
        startInitialSourceIndex()
    }

    fun allKtFiles(): List<KtFile> = analysisSessionLock.read {
        ensureFullKtFileMapLoaded(session)
        ktFilesByPath.values.sortedBy(::normalizeFileLookupPath)
    }

    fun findKtFile(filePath: String): KtFile {
        val normalizedPath = normalizePath(Path.of(filePath)).toString()
        if (fullKtFileMapLoaded) {
            return ktFilesByPath[normalizedPath]
                ?: throw NotFoundException(
                    message = "The requested file is not part of the standalone analysis session",
                    details = mapOf("filePath" to normalizedPath),
                )
        }

        return targetedKtFilesByPath[normalizedPath]
            ?: loadKtFileByPath(normalizedPath)
                ?.also { file ->
                    targetedKtFilesByPath[normalizedPath] = file
                    ktFilesByPath[normalizedPath] = file
                }
            ?: throw NotFoundException(
                message = "The requested file is not part of the standalone analysis session",
                details = mapOf("filePath" to normalizedPath),
            )
    }

    fun refreshFiles(paths: Set<String>): RefreshResult {
        val normalizedPaths = paths.asSequence()
            .map { path -> normalizePath(Path.of(path)).toString() }
            .distinct()
            .filter { normalizedPath -> isTrackedKotlinFilePath(Path.of(normalizedPath)) }
            .toList()
        if (normalizedPaths.isEmpty()) {
            return RefreshResult(
                refreshedFiles = emptyList(),
                removedFiles = emptyList(),
                fullRefresh = false,
            )
        }

        analysisSessionLock.write {
            normalizedPaths.forEach { normalizedPath ->
                VirtualFileManager.getInstance().refreshAndFindFileByNioPath(Path.of(normalizedPath))
            }

            val previousSessionDisposable = sessionStateDisposable
            val rebuiltAnalysisState = buildAnalysisState()
            session = rebuiltAnalysisState.session
            sourceModules = rebuiltAnalysisState.sourceModules
            sessionStateDisposable = rebuiltAnalysisState.disposable
            targetedKtFilesByPath.clear()
            ktFilesByPath.clear()
            fullKtFileMapLoaded = false

            normalizedPaths.forEach { normalizedPath ->
                val refreshedFile = loadKtFileByPath(
                    normalizedPath = normalizedPath,
                    analysisSession = rebuiltAnalysisState.session,
                )
                if (refreshedFile != null) {
                    targetedKtFilesByPath[normalizedPath] = refreshedFile
                    ktFilesByPath[normalizedPath] = refreshedFile
                }
            }

            Disposer.dispose(previousSessionDisposable)
        }

        sourceIdentifierIndex.get()?.let { index ->
            normalizedPaths.forEach { normalizedPath ->
                refreshSourceIdentifierIndex(index, normalizedPath)
            }
        } ?: pendingSourceIndexRefreshPaths.addAll(normalizedPaths)
        targetedCandidatePathsByLookupKey.clear()
        return RefreshResult(
            refreshedFiles = normalizedPaths.filter { normalizedPath -> Files.isRegularFile(Path.of(normalizedPath)) }.sorted(),
            removedFiles = normalizedPaths.filterNot { normalizedPath -> Files.isRegularFile(Path.of(normalizedPath)) }.sorted(),
            fullRefresh = false,
        )
    }

    fun refreshWorkspace(): RefreshResult {
        val currentPaths = allTrackedKotlinSourcePaths()
        val knownPaths = buildSet {
            addAll(ktFilesByPath.keys)
            addAll(targetedKtFilesByPath.keys)
            addAll(sourceIdentifierIndex.get()?.knownPaths().orEmpty())
            addAll(pendingSourceIndexRefreshPaths)
        }
        val removedPaths = (knownPaths - currentPaths).sorted()
        refreshFiles(currentPaths + removedPaths)
        return RefreshResult(
            refreshedFiles = currentPaths.sorted(),
            removedFiles = removedPaths,
            fullRefresh = true,
        )
    }

    internal fun awaitInitialSourceIndex() {
        initialSourceIndexReady.join()
    }

    internal inline fun <T> withReadAccess(action: () -> T): T = analysisSessionLock.read { action() }

    internal fun candidateKotlinFilePaths(identifier: String): List<String> {
        return candidateKotlinFilePaths(identifier = identifier, anchorFilePath = null)
    }

    internal fun candidateKotlinFilePaths(
        identifier: String,
        anchorFilePath: String?,
    ): List<String> {
        if (!identifier.isIndexableIdentifier()) {
            return emptyList()
        }

        val anchorSourceModuleName = anchorFilePath?.let { filePath ->
            sourceModuleNameForFile(normalizePath(Path.of(filePath)).toString())
        }
        val lookupKey = CandidateLookupKey(
            identifier = identifier,
            anchorSourceModuleName = anchorSourceModuleName,
        )

        val readyIndex = sourceIdentifierIndex.get()
        if (readyIndex != null) {
            return filterCandidatePathsByAnchorScope(
                candidatePaths = readyIndex.candidatePathsFor(identifier),
                anchorSourceModuleName = anchorSourceModuleName,
            )
        }

        return targetedCandidatePathsByLookupKey.computeIfAbsent(lookupKey) { key ->
            buildTargetedCandidatePaths(
                identifier = key.identifier,
                anchorSourceModuleName = key.anchorSourceModuleName,
            )
        }
    }

    internal fun isInitialSourceIndexReady(): Boolean = initialSourceIndexReady.isDone

    internal fun isFullKtFileMapLoaded(): Boolean = fullKtFileMapLoaded

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

    private fun ensureFullKtFileMapLoaded(analysisSession: StandaloneAnalysisAPISession) {
        if (fullKtFileMapLoaded) {
            return
        }

        synchronized(fullKtFileMapLoadLock) {
            if (fullKtFileMapLoaded) {
                return
            }

            val loadedFiles = loadKtFilesByPath(analysisSession)
            ktFilesByPath.clear()
            ktFilesByPath.putAll(loadedFiles)
            targetedKtFilesByPath.clear()
            targetedKtFilesByPath.putAll(loadedFiles)
            fullKtFileMapLoaded = true
        }
    }

    private fun loadKtFilesByPath(analysisSession: StandaloneAnalysisAPISession): Map<String, KtFile> {
        val loadedFiles = linkedMapOf<String, KtFile>()

        resolvedSourceRoots.forEach { sourceRoot ->
            if (!Files.isDirectory(sourceRoot)) {
                return@forEach
            }

            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                    .forEach { file ->
                        val normalizedPath = normalizePath(file).toString()
                        loadKtFileByPath(normalizedPath, analysisSession)?.let { ktFile ->
                            loadedFiles[normalizedPath] = ktFile
                        }
                    }
            }
        }

        return loadedFiles
    }

    private fun loadKtFileByPath(normalizedPath: String): KtFile? {
        return analysisSessionLock.read {
            loadKtFileByPath(normalizedPath, session)
        }
    }

    private fun loadKtFileByPath(
        normalizedPath: String,
        analysisSession: StandaloneAnalysisAPISession,
    ): KtFile? {
        val filePath = Path.of(normalizedPath)
        if (!isTrackedKotlinFilePath(filePath)) {
            return null
        }

        val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(filePath)
            ?: VirtualFileManager.getInstance().refreshAndFindFileByNioPath(filePath)
            ?: return null

        return PsiManager.getInstance(analysisSession.project)
            .findFile(virtualFile) as? KtFile
    }

    private fun buildAnalysisState(): AnalysisState {
        val jdkHome = normalizePath(Path.of(System.getProperty("java.home")))
        val defaultClasspathRoots = defaultClasspathRoots()
        val analysisDisposable = Disposer.newDisposable("kast-standalone-analysis")
        val createdSourceModules = mutableListOf<KaSourceModule>()
        val createdSourceModulesByName = linkedMapOf<String, KaSourceModule>()
        val createdSession = buildStandaloneAnalysisAPISession(
            projectDisposable = analysisDisposable,
            unitTestMode = false,
        ) {
            initializeJavaSourceSupport()
            buildKtModuleProvider {
                val platform = JvmPlatforms.defaultJvmPlatform
                val sdkModule = buildKtSdkModule {
                    this.platform = platform
                    addBinaryRootsFromJdkHome(jdkHome, isJre = false)
                    libraryName = "JDK for ${sourceModuleSpecs.first().name}"
                }

                for (moduleSpec in topologicallySortSourceModules(sourceModuleSpecs)) {
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

        return AnalysisState(
            disposable = analysisDisposable,
            session = createdSession,
            sourceModules = createdSourceModules,
        )
    }

    private fun isTrackedKotlinFilePath(filePath: Path): Boolean {
        return filePath.extension == "kt" && resolvedSourceRoots.any(filePath::startsWith)
    }

    private fun startInitialSourceIndex() {
        thread(
            start = true,
            isDaemon = true,
            name = "kast-initial-source-index",
        ) {
            runCatching {
                initialSourceIndexBuilder
                    ?.invoke()
                    ?.let(MutableSourceIdentifierIndex::fromCandidatePathsByIdentifier)
                    ?: buildSourceIdentifierIndex()
            }
                .onSuccess { builtIndex ->
                    applyPendingSourceIndexRefreshes(builtIndex)
                    sourceIdentifierIndex.set(builtIndex)
                    initialSourceIndexReady.complete(Unit)
                }
                .onFailure(initialSourceIndexReady::completeExceptionally)
        }
    }

    private fun buildSourceIdentifierIndex(): MutableSourceIdentifierIndex {
        val candidatePathsByIdentifier = ConcurrentHashMap<String, MutableSet<String>>()
        val identifiersByPath = ConcurrentHashMap<String, Set<String>>()

        resolvedSourceRoots.forEach { sourceRoot ->
            if (!Files.isDirectory(sourceRoot)) {
                return@forEach
            }

            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                    .forEach { file ->
                        val normalizedFilePath = normalizePath(file).toString()
                        val identifiers = identifierRegex.findAll(Files.readString(file)).map { match -> match.value }.toSet()
                        identifiersByPath[normalizedFilePath] = identifiers
                        identifiers.forEach { identifier ->
                            candidatePathsByIdentifier
                                .computeIfAbsent(identifier) { ConcurrentHashMap.newKeySet() }
                                .add(normalizedFilePath)
                        }
                    }
            }
        }

        return MutableSourceIdentifierIndex(
            pathsByIdentifier = candidatePathsByIdentifier,
            identifiersByPath = identifiersByPath,
        )
    }

    private fun applyPendingSourceIndexRefreshes(index: MutableSourceIdentifierIndex) {
        pendingSourceIndexRefreshPaths.toList().forEach { normalizedPath ->
            refreshSourceIdentifierIndex(index, normalizedPath)
            pendingSourceIndexRefreshPaths.remove(normalizedPath)
        }
    }

    private fun refreshSourceIdentifierIndex(
        index: MutableSourceIdentifierIndex,
        normalizedPath: String,
    ) {
        val filePath = Path.of(normalizedPath)
        if (!Files.isRegularFile(filePath)) {
            index.removeFile(normalizedPath)
            return
        }

        index.updateFile(normalizedPath, Files.readString(filePath))
    }

    private fun allTrackedKotlinSourcePaths(): Set<String> = buildSet {
        resolvedSourceRoots.forEach { sourceRoot ->
            if (!Files.isDirectory(sourceRoot)) {
                return@forEach
            }

            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                    .forEach { file -> add(normalizePath(file).toString()) }
            }
        }
    }

    private fun buildTargetedCandidatePaths(identifier: String): List<String> = buildList {
        resolvedSourceRoots.forEach { sourceRoot ->
            if (!Files.isDirectory(sourceRoot)) {
                return@forEach
            }

            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                    .forEach { file ->
                        val content = Files.readString(file)
                        if (content.identifierOccurrenceOffsets(identifier).any()) {
                            add(normalizePath(file).toString())
                        }
                    }
            }
        }
    }.distinct().sorted()

    private fun buildTargetedCandidatePaths(
        identifier: String,
        anchorSourceModuleName: String?,
    ): List<String> = buildList {
        val allowedSourceModuleNames = anchorSourceModuleName
            ?.let(dependentModuleNamesBySourceModuleName::get)

        sourceModuleSpecs
            .asSequence()
            .filter { moduleSpec -> allowedSourceModuleNames == null || moduleSpec.name in allowedSourceModuleNames }
            .flatMap { moduleSpec -> moduleSpec.sourceRoots.asSequence() }
            .distinct()
            .sorted()
            .forEach { sourceRoot ->
                if (!Files.isDirectory(sourceRoot)) {
                    return@forEach
                }

                Files.walk(sourceRoot).use { paths ->
                    paths
                        .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                        .forEach { file ->
                            val content = Files.readString(file)
                            if (content.identifierOccurrenceOffsets(identifier).any()) {
                                add(normalizePath(file).toString())
                            }
                        }
                }
            }
    }.distinct().sorted()

    private fun filterCandidatePathsByAnchorScope(
        candidatePaths: List<String>,
        anchorSourceModuleName: String?,
    ): List<String> {
        if (anchorSourceModuleName == null) {
            return candidatePaths
        }

        val allowedSourceModuleNames = dependentModuleNamesBySourceModuleName[anchorSourceModuleName].orEmpty()
        return candidatePaths.filter { candidatePath ->
            sourceModuleNameForFile(candidatePath) in allowedSourceModuleNames
        }
    }

    private fun sourceModuleNameForFile(normalizedPath: String): String? {
        val filePath = Path.of(normalizedPath)
        return sourceModuleSpecs.firstOrNull { moduleSpec ->
            moduleSpec.sourceRoots.any(filePath::startsWith)
        }?.name
    }

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

private data class AnalysisState(
    val disposable: Disposable,
    val session: StandaloneAnalysisAPISession,
    val sourceModules: List<KaSourceModule>,
)

internal class MutableSourceIdentifierIndex(
    private val pathsByIdentifier: ConcurrentHashMap<String, MutableSet<String>>,
    private val identifiersByPath: ConcurrentHashMap<String, Set<String>>,
) {
    fun candidatePathsFor(identifier: String): List<String> =
        pathsByIdentifier[identifier]?.toList()?.sorted().orEmpty()

    fun updateFile(
        normalizedPath: String,
        newContent: String,
    ) {
        replaceIdentifiers(
            normalizedPath = normalizedPath,
            identifiers = identifierRegex.findAll(newContent).map { match -> match.value }.toSet(),
        )
    }

    fun removeFile(normalizedPath: String) {
        replaceIdentifiers(normalizedPath = normalizedPath, identifiers = emptySet())
    }

    fun knownPaths(): Set<String> = identifiersByPath.keys.toSet()

    private fun replaceIdentifiers(
        normalizedPath: String,
        identifiers: Set<String>,
    ) {
        val previousIdentifiers = identifiersByPath.remove(normalizedPath).orEmpty()
        previousIdentifiers.forEach { identifier ->
            val paths = pathsByIdentifier[identifier] ?: return@forEach
            paths.remove(normalizedPath)
            if (paths.isEmpty()) {
                pathsByIdentifier.remove(identifier, paths)
            }
        }
        if (identifiers.isEmpty()) {
            return
        }

        identifiersByPath[normalizedPath] = identifiers
        identifiers.forEach { identifier ->
            pathsByIdentifier.computeIfAbsent(identifier) { ConcurrentHashMap.newKeySet() }
                .add(normalizedPath)
        }
    }

    companion object {
        fun fromCandidatePathsByIdentifier(candidatePathsByIdentifier: Map<String, List<String>>): MutableSourceIdentifierIndex {
            val pathsByIdentifier = ConcurrentHashMap<String, MutableSet<String>>()
            val identifiersByPath = ConcurrentHashMap<String, Set<String>>()
            candidatePathsByIdentifier.forEach { (identifier, paths) ->
                val normalizedPaths = paths.toCollection(ConcurrentHashMap.newKeySet())
                pathsByIdentifier[identifier] = normalizedPaths
                normalizedPaths.forEach { normalizedPath ->
                    identifiersByPath.compute(normalizedPath) { _, existingIdentifiers ->
                        (existingIdentifiers.orEmpty() + identifier)
                    }
                }
            }
            return MutableSourceIdentifierIndex(
                pathsByIdentifier = pathsByIdentifier,
                identifiersByPath = identifiersByPath,
            )
        }
    }
}

private data class CandidateLookupKey(
    val identifier: String,
    val anchorSourceModuleName: String?,
)

private val identifierRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

private fun String.isIndexableIdentifier(): Boolean = identifierRegex.matches(this)

private fun String.identifierOccurrenceOffsets(identifier: String): Sequence<Int> = sequence {
    var searchFrom = 0
    while (true) {
        val occurrenceOffset = indexOf(identifier, startIndex = searchFrom)
        if (occurrenceOffset == -1) {
            break
        }

        val before = getOrNull(occurrenceOffset - 1)
        val after = getOrNull(occurrenceOffset + identifier.length)
        val startsIdentifier = before?.isKastIdentifierPart() != true
        val endsIdentifier = after?.isKastIdentifierPart() != true
        if (startsIdentifier && endsIdentifier) {
            yield(occurrenceOffset)
        }

        searchFrom = occurrenceOffset + identifier.length
    }
}

private fun Char.isKastIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()

private fun buildDependentModuleNamesBySourceModuleName(
    sourceModules: List<StandaloneSourceModuleSpec>,
): Map<String, Set<String>> {
    val reverseDependencies = linkedMapOf<String, MutableSet<String>>()
    sourceModules.forEach { sourceModule ->
        sourceModule.dependencyModuleNames.forEach { dependencyModuleName ->
            reverseDependencies.getOrPut(dependencyModuleName) { linkedSetOf() }.add(sourceModule.name)
        }
    }

    return sourceModules.associate { sourceModule ->
        val visitedModuleNames = linkedSetOf(sourceModule.name)
        val pendingModuleNames = ArrayDeque(listOf(sourceModule.name))
        while (pendingModuleNames.isNotEmpty()) {
            val currentModuleName = pendingModuleNames.removeFirst()
            reverseDependencies[currentModuleName].orEmpty().forEach { dependentModuleName ->
                if (visitedModuleNames.add(dependentModuleName)) {
                    pendingModuleNames += dependentModuleName
                }
            }
        }
        sourceModule.name to visitedModuleNames.toSet()
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
