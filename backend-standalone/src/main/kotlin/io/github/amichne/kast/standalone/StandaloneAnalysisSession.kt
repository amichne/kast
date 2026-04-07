package io.github.amichne.kast.standalone

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.java.JavaParserDefinition
import com.intellij.lang.java.syntax.JavaElementTypeConverterExtension
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory
import com.intellij.platform.syntax.psi.ElementTypeConverters
import com.intellij.psi.PsiManager
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.impl.PsiManagerEx
import io.github.amichne.kast.api.FqName
import io.github.amichne.kast.api.KotlinIdentifier
import io.github.amichne.kast.api.ModuleName
import io.github.amichne.kast.api.NormalizedPath
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.PackageName
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write
import kotlin.io.path.extension

@Suppress("UnstableApiUsage")
internal class StandaloneAnalysisSession(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
    private val initialSourceIndexBuilder: (() -> Map<String, List<String>>)? = null,
    phasedDiscoveryResult: PhasedDiscoveryResult? = null,
    private val sourceIndexFileReader: (Path) -> String = Files::readString,
    private val sourceIndexCacheSaveDelayMillis: Long = defaultSourceIndexCacheSaveDelayMillis,
    cacheEnvReader: (String) -> String? = System::getenv,
) : AutoCloseable {
    private val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
    private val disposable: Disposable = Disposer.newDisposable("kast-standalone")
    private val ktFilesByPath = ConcurrentHashMap<NormalizedPath, KtFile>()
    private val targetedKtFilesByPath = ConcurrentHashMap<NormalizedPath, KtFile>()
    private val ktFileLastModifiedMillisByPath = ConcurrentHashMap<NormalizedPath, Long>()
    private val targetedCandidatePathsByLookupKey = ConcurrentHashMap<CandidateLookupKey, List<String>>()
    private val sourceIdentifierIndex = AtomicReference<MutableSourceIdentifierIndex?>(null)

    @Volatile
    private var initialSourceIndexReady = CompletableFuture<Unit>()
    private val pendingSourceIndexRefreshPaths = ConcurrentHashMap.newKeySet<String>()
    private val sourceIndexGeneration = AtomicInteger(0)
    private val analysisStateGeneration = AtomicInteger(0)
    private val enrichmentReady = CompletableFuture<Unit>()
    private val fullKtFileMapLoadLock = Any()
    private val analysisSessionLock = ReentrantReadWriteLock()
    private val cacheManager = CacheManager(normalizedWorkspaceRoot, envReader = cacheEnvReader)
    private val fileManifest = FileManifest(normalizedWorkspaceRoot, enabled = cacheManager.isEnabled())
    private val sourceIndexCache = SourceIndexCache(
        workspaceRoot = normalizedWorkspaceRoot,
        enabled = cacheManager.isEnabled(),
    )

    @Volatile
    private var workspaceRefreshWatcher: WorkspaceRefreshWatcher? = null

    @Volatile
    private var enrichmentComplete = false

    @Volatile
    private var closed = false

    @Volatile
    private var fullKtFileMapLoaded = false

    @Volatile
    private var sourceModuleSpecs: List<StandaloneSourceModuleSpec> = emptyList()

    @Volatile
    private var dependentModuleNamesBySourceModuleName: Map<ModuleName, Set<ModuleName>> = emptyMap()

    @Volatile
    var sourceModules: List<KaSourceModule> = emptyList()
        private set

    @Volatile
    var resolvedSourceRoots: List<Path> = emptyList()
        private set

    @Volatile
    var workspaceDiagnostics: List<String> = emptyList()
        private set

    @Volatile
    private var resolvedClasspathRoots: List<Path> = emptyList()
    private lateinit var sessionStateDisposable: Disposable
    private lateinit var session: StandaloneAnalysisAPISession

    init {
        val workspaceLayout = phasedDiscoveryResult?.initialLayout ?: discoverStandaloneWorkspaceLayout(
            workspaceRoot = normalizedWorkspaceRoot,
            sourceRoots = sourceRoots,
            classpathRoots = classpathRoots,
            moduleName = moduleName,
        )
        require(workspaceLayout.sourceModules.isNotEmpty()) {
            "No source roots were found under $normalizedWorkspaceRoot"
        }
        applyWorkspaceLayout(workspaceLayout)

        val initialAnalysisState = buildAnalysisState()
        sessionStateDisposable = initialAnalysisState.disposable
        session = initialAnalysisState.session
        analysisStateGeneration.incrementAndGet()
        initializeJvmDecompilerServices()
        sourceModules = initialAnalysisState.sourceModules
        check(sourceModules.isNotEmpty()) {
            "The standalone Analysis API session did not create any source modules"
        }
        startInitialSourceIndex()
        beginEnrichment(phasedDiscoveryResult?.enrichmentFuture)
    }

    fun allKtFiles(): List<KtFile> = analysisSessionLock.read {
        ensureFullKtFileMapLoaded(session)
        ktFilesByPath.values.sortedBy(::normalizeFileLookupPath)
    }

    fun findKtFile(filePath: String): KtFile {
        val normalizedPath = NormalizedPath.of(Path.of(filePath))
        if (fullKtFileMapLoaded) {
            return ktFilesByPath[normalizedPath]
                   ?: throw NotFoundException(
                       message = "The requested file is not part of the standalone analysis session",
                       details = mapOf("filePath" to normalizedPath.value),
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
                   details = mapOf("filePath" to normalizedPath.value),
               )
    }

    fun refreshFileContents(paths: Set<String>): RefreshResult {
        val normalizedPaths = normalizeTrackedKotlinPaths(paths)
        if (normalizedPaths.isEmpty()) {
            return RefreshResult(
                refreshedFiles = emptyList(),
                removedFiles = emptyList(),
                fullRefresh = false,
            )
        }

        analysisSessionLock.write {
            val cachedEntriesByPath = normalizedPaths.associateWith { normalizedPath ->
                ktFilesByPath[normalizedPath] to targetedKtFilesByPath[normalizedPath]
            }
            val virtualFileManager = VirtualFileManager.getInstance()
            normalizedPaths.forEach { normalizedPath ->
                virtualFileManager.refreshAndFindFileByNioPath(normalizedPath.toJavaPath())
            }
            val fileManager = PsiManagerEx.getInstanceEx(session.project).fileManager
            val cachedVirtualFilesToInvalidate = normalizedPaths.asSequence()
                .flatMap { normalizedPath ->
                    cachedEntriesByPath.getValue(normalizedPath)
                        .toList()
                        .filterNotNull()
                        .mapNotNull { ktFile -> ktFile.virtualFile }
                        .asSequence()
                }
                .distinct()
                .toList()
            if (cachedVirtualFilesToInvalidate.isNotEmpty()) {
                ApplicationManager.getApplication().runWriteAction {
                    cachedVirtualFilesToInvalidate.forEach { virtualFile ->
                        fileManager.setViewProvider(virtualFile, null)
                    }
                }
                PsiManager.getInstance(session.project).dropResolveCaches()
            }

            normalizedPaths.forEach { normalizedPath ->
                val (cachedKtFile, cachedTargetedKtFile) = cachedEntriesByPath.getValue(normalizedPath)
                val hadKtFileEntry = cachedKtFile != null
                val hadTargetedEntry = cachedTargetedKtFile != null
                ktFilesByPath.remove(normalizedPath)
                targetedKtFilesByPath.remove(normalizedPath)
                ktFileLastModifiedMillisByPath.remove(normalizedPath)
                if (!Files.isRegularFile(normalizedPath.toJavaPath())) {
                    return@forEach
                }
                if (!fullKtFileMapLoaded && !hadKtFileEntry && !hadTargetedEntry) {
                    return@forEach
                }

                if (fullKtFileMapLoaded || hadKtFileEntry || hadTargetedEntry) {
                    val refreshedKtFile = loadKtFileByPath(normalizedPath)
                    if (refreshedKtFile != null) {
                        if (fullKtFileMapLoaded || hadKtFileEntry) {
                            ktFilesByPath[normalizedPath] = refreshedKtFile
                        }
                        if (fullKtFileMapLoaded || hadTargetedEntry) {
                            targetedKtFilesByPath[normalizedPath] = refreshedKtFile
                        }
                    }
                }
            }
        }

        refreshSourceIdentifierIndex(normalizedPaths)
        targetedCandidatePathsByLookupKey.clear()
        return buildRefreshResult(normalizedPaths, fullRefresh = false)
    }

    fun refreshFiles(paths: Set<String>): RefreshResult {
        val normalizedPaths = normalizeTrackedKotlinPaths(paths)
        if (normalizedPaths.isEmpty()) {
            return RefreshResult(
                refreshedFiles = emptyList(),
                removedFiles = emptyList(),
                fullRefresh = false,
            )
        }

        analysisSessionLock.write {
            normalizedPaths.forEach { normalizedPath ->
                VirtualFileManager.getInstance().refreshAndFindFileByNioPath(normalizedPath.toJavaPath())
            }

            refreshStructureLocked()

            normalizedPaths.forEach { normalizedPath ->
                val refreshedFile = loadKtFileByPath(
                    normalizedPath = normalizedPath,
                    analysisSession = session,
                )
                if (refreshedFile != null) {
                    targetedKtFilesByPath[normalizedPath] = refreshedFile
                    ktFilesByPath[normalizedPath] = refreshedFile
                }
            }
        }

        refreshSourceIdentifierIndex(normalizedPaths)
        targetedCandidatePathsByLookupKey.clear()
        return buildRefreshResult(normalizedPaths, fullRefresh = false)
    }

    fun refreshWorkspace(invalidateCaches: Boolean = false): RefreshResult {
        if (invalidateCaches) {
            cacheManager.invalidateAll()
        }
        val currentPaths = allTrackedKotlinSourcePaths()
        val knownPaths = buildSet {
            addAll(ktFilesByPath.keys.map { it.value })
            addAll(targetedKtFilesByPath.keys.map { it.value })
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

    internal fun currentAnalysisStateGeneration(): Int = analysisStateGeneration.get()

    fun isEnrichmentComplete(): Boolean = enrichmentComplete

    fun awaitEnrichment() {
        enrichmentReady.join()
    }

    internal fun attachWorkspaceRefreshWatcher(watcher: WorkspaceRefreshWatcher) {
        workspaceRefreshWatcher = watcher
        watcher.refreshSourceRoots(resolvedSourceRoots)
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

    /**
     * Returns candidate file paths using import-aware filtering when the full source index
     * is ready and [targetFqName] is provided. Falls back to identifier-only lookup when
     * the enriched index is unavailable or when the enriched filter yields no results
     * (safety net for star-import indirection or other edge cases).
     */
    internal fun candidateKotlinFilePathsForFqName(
        identifier: String,
        anchorFilePath: String?,
        targetPackage: PackageName,
        targetFqName: FqName,
    ): List<String> {
        if (!identifier.isIndexableIdentifier()) {
            return emptyList()
        }

        val readyIndex = sourceIdentifierIndex.get()
        if (readyIndex != null) {
            val enrichedPaths = readyIndex.candidatePathsForFqName(
                identifier = identifier,
                targetPackage = targetPackage.value,
                targetFqName = targetFqName.value,
            )
            if (enrichedPaths.isNotEmpty()) {
                val anchorSourceModuleName = anchorFilePath?.let { filePath ->
                    sourceModuleNameForFile(normalizePath(Path.of(filePath)).toString())
                }
                return filterCandidatePathsByAnchorScope(
                    candidatePaths = enrichedPaths,
                    anchorSourceModuleName = anchorSourceModuleName,
                )
            }
        }

        return candidateKotlinFilePaths(identifier = identifier, anchorFilePath = anchorFilePath)
    }

    internal fun isInitialSourceIndexReady(): Boolean = initialSourceIndexReady.isDone

    internal fun isFullKtFileMapLoaded(): Boolean = fullKtFileMapLoaded

    override fun close() {
        closed = true
        sourceIdentifierIndex.get()?.let { index ->
            runCatching {
                sourceIndexCache.save(index = index, sourceRoots = resolvedSourceRoots)
            }
        }
        cacheManager.close()
        workspaceRefreshWatcher = null
        if (!enrichmentReady.isDone) {
            enrichmentReady.complete(Unit)
        }
        Disposer.dispose(disposable)
    }

    private fun applyWorkspaceLayout(workspaceLayout: StandaloneWorkspaceLayout) {
        sourceModuleSpecs = workspaceLayout.sourceModules
        workspaceDiagnostics = workspaceLayout.diagnostics.warnings
        dependentModuleNamesBySourceModuleName = workspaceLayout.dependentModuleNamesBySourceModuleName
                                                     .takeIf { it.isNotEmpty() }
                                                 ?: buildDependentModuleNamesBySourceModuleName(sourceModuleSpecs)
        resolvedSourceRoots = workspaceLayout.sourceModules
            .flatMap { module -> module.sourceRoots }
            .distinct()
            .sorted()
        resolvedClasspathRoots = (
            defaultClasspathRoots() +
            workspaceLayout.sourceModules.flatMap { module -> module.binaryRoots }
                                 ).distinct().sorted()
    }

    private fun beginEnrichment(enrichmentFuture: CompletableFuture<StandaloneWorkspaceLayout>?) {
        if (enrichmentFuture == null) {
            enrichmentComplete = true
            enrichmentReady.complete(Unit)
            return
        }


        enrichmentFuture.whenComplete { enrichedLayout, error ->
            if (closed) {
                enrichmentComplete = true
                enrichmentReady.complete(Unit)
                return@whenComplete
            }
            if (error != null) {
                logSessionEnrichmentWarning(error)
                enrichmentComplete = true
                enrichmentReady.complete(Unit)
                return@whenComplete
            }
            if (enrichedLayout == null) {
                enrichmentComplete = true
                enrichmentReady.complete(Unit)
                return@whenComplete
            }

            runCatching {
                rebuildWorkspaceLayout(enrichedLayout)
            }.onFailure(::logSessionEnrichmentWarning)
            enrichmentComplete = true
            enrichmentReady.complete(Unit)
            workspaceRefreshWatcher?.refreshSourceRoots(resolvedSourceRoots)
        }
    }

    private fun rebuildWorkspaceLayout(workspaceLayout: StandaloneWorkspaceLayout) {
        analysisSessionLock.write {
            val previousSessionDisposable = sessionStateDisposable
            applyWorkspaceLayout(workspaceLayout)
            buildAnalysisStateAndCache()
            sourceIdentifierIndex.set(null)
            initialSourceIndexReady = CompletableFuture()
            fullKtFileMapLoaded = false
            startInitialSourceIndex()
            Disposer.dispose(previousSessionDisposable)
        }
    }

    private fun logSessionEnrichmentWarning(error: Throwable) {
        val details = error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
        System.err.println("kast standalone enrichment warning: $details")
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
        return NormalizedPath.of(Path.of(virtualPath)).value
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

    private fun loadKtFilesByPath(analysisSession: StandaloneAnalysisAPISession): Map<NormalizedPath, KtFile> {
        val loadedFiles = linkedMapOf<NormalizedPath, KtFile>()
        val currentPathsByLastModifiedMillis = fileManifest.snapshot(resolvedSourceRoots).currentPathsByLastModifiedMillis
        currentPathsByLastModifiedMillis.forEach { (pathString, lastModifiedMillis) ->
            val normalizedPath = NormalizedPath.ofNormalized(pathString)
            val cachedKtFile = ktFilesByPath[normalizedPath]
            if (cachedKtFile != null && ktFileLastModifiedMillisByPath[normalizedPath] == lastModifiedMillis) {
                loadedFiles[normalizedPath] = cachedKtFile
            } else {
                loadKtFileByPath(normalizedPath, analysisSession)?.let { ktFile ->
                    loadedFiles[normalizedPath] = ktFile
                }
            }
        }
        val currentNormalizedKeys = currentPathsByLastModifiedMillis.keys.mapTo(mutableSetOf()) {
            NormalizedPath.ofNormalized(
                it
            )
        }
        (ktFileLastModifiedMillisByPath.keys - currentNormalizedKeys).forEach { removedPath ->
            ktFileLastModifiedMillisByPath.remove(removedPath)
        }

        return loadedFiles
    }

    private fun loadKtFileByPath(normalizedPath: NormalizedPath): KtFile? {
        return analysisSessionLock.read {
            loadKtFileByPath(normalizedPath, session)
        }
    }

    private fun loadKtFileByPath(
        normalizedPath: NormalizedPath,
        analysisSession: StandaloneAnalysisAPISession,
    ): KtFile? {
        val filePath = normalizedPath.toJavaPath()
        if (!isTrackedKotlinFilePath(filePath) || !Files.isRegularFile(filePath)) {
            return null
        }

        val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(filePath)
                          ?: VirtualFileManager.getInstance().refreshAndFindFileByNioPath(filePath)
                          ?: return null

        return (PsiManager.getInstance(analysisSession.project)
            .findFile(virtualFile) as? KtFile)
            ?.also { ktFileLastModifiedMillisByPath[normalizedPath] = Files.getLastModifiedTime(filePath).toMillis() }
    }

    private fun buildAnalysisState(): AnalysisState {
        val jdkHome = normalizePath(Path.of(System.getProperty("java.home")))
        val defaultClasspathRoots = defaultClasspathRoots()
        val analysisDisposable = Disposer.newDisposable("kast-standalone-analysis")
        val createdSourceModules = mutableListOf<KaSourceModule>()
        val createdSourceModulesByName = linkedMapOf<ModuleName, KaSourceModule>()
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
                    libraryName = "JDK for ${sourceModuleSpecs.first().name.value}"
                }

                for (moduleSpec in topologicallySortSourceModules(sourceModuleSpecs)) {
                    val libraryModule = buildLibraryModule(
                        moduleName = moduleSpec.name.value,
                        platform = platform,
                        binaryRoots = (defaultClasspathRoots + moduleSpec.binaryRoots).distinct().sorted(),
                    )
                    val builtSourceModule = buildKtSourceModule {
                        this.platform = platform
                        this.moduleName = moduleSpec.name.value
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
        val generation = sourceIndexGeneration.incrementAndGet()
        val readiness = initialSourceIndexReady
        thread(
            start = true,
            isDaemon = true,
            name = "kast-initial-source-index",
        ) {
            runCatching {
                initialSourceIndexBuilder
                    ?.invoke()
                    ?.let(MutableSourceIdentifierIndex::fromCandidatePathsByIdentifier)
                ?: loadOrBuildSourceIdentifierIndex()
            }
                .onSuccess { builtIndex ->
                    if (closed || sourceIndexGeneration.get() != generation) {
                        return@onSuccess
                    }
                    applyPendingSourceIndexRefreshes(builtIndex)
                    sourceIdentifierIndex.set(builtIndex)
                    persistSourceIndexCache(generation, builtIndex)
                    readiness.complete(Unit)
                }
                .onFailure { error ->
                    if (closed || sourceIndexGeneration.get() != generation) {
                        return@onFailure
                    }
                    readiness.completeExceptionally(error)
                }
        }
    }

    private fun loadOrBuildSourceIdentifierIndex(): MutableSourceIdentifierIndex {
        val incrementalIndex = runCatching {
            sourceIndexCache.load(resolvedSourceRoots)
        }.getOrNull()
        val index = incrementalIndex?.index ?: return buildSourceIdentifierIndex()
        incrementalIndex.deletedPaths.forEach(index::removeFile)
        (incrementalIndex.newPaths + incrementalIndex.modifiedPaths).forEach { pathString ->
            refreshSourceIdentifierIndex(index, NormalizedPath.ofNormalized(pathString))
        }
        return index
    }

    private fun buildSourceIdentifierIndex(): MutableSourceIdentifierIndex {
        val candidatePathsByIdentifier = ConcurrentHashMap<KotlinIdentifier, MutableSet<NormalizedPath>>()
        val identifiersByPath = ConcurrentHashMap<NormalizedPath, Set<KotlinIdentifier>>()

        val index = MutableSourceIdentifierIndex(
            pathsByIdentifier = candidatePathsByIdentifier,
            identifiersByPath = identifiersByPath,
        )

        allTrackedKotlinSourcePaths().forEach { normalizedFilePath ->
            val normalizedPath = NormalizedPath.ofNormalized(normalizedFilePath)
            val content = sourceIndexFileReader(Path.of(normalizedFilePath))
            val identifiers = identifierRegex.findAll(content)
                .map { match -> KotlinIdentifier(match.value) }
                .toSet()
            identifiersByPath[normalizedPath] = identifiers
            identifiers.forEach { identifier ->
                candidatePathsByIdentifier
                    .computeIfAbsent(identifier) { ConcurrentHashMap.newKeySet() }
                    .add(normalizedPath)
            }
            index.extractFileMetadata(normalizedFilePath, content)
        }

        return index
    }

    private fun applyPendingSourceIndexRefreshes(index: MutableSourceIdentifierIndex) {
        pendingSourceIndexRefreshPaths.toList().forEach { pathString ->
            refreshSourceIdentifierIndex(index, NormalizedPath.ofNormalized(pathString))
            pendingSourceIndexRefreshPaths.remove(pathString)
        }
    }

    private fun refreshSourceIdentifierIndex(
        index: MutableSourceIdentifierIndex,
        normalizedPath: NormalizedPath,
    ) {
        val filePath = normalizedPath.toJavaPath()
        if (!Files.isRegularFile(filePath)) {
            index.removeFile(normalizedPath.value)
            return
        }

        index.updateFile(normalizedPath.value, sourceIndexFileReader(filePath))
    }

    private fun refreshSourceIdentifierIndex(normalizedPaths: List<NormalizedPath>) {
        sourceIdentifierIndex.get()?.let { index ->
            normalizedPaths.forEach { normalizedPath ->
                refreshSourceIdentifierIndex(index, normalizedPath)
            }
            scheduleSourceIndexCacheWrite()
        } ?: pendingSourceIndexRefreshPaths.addAll(normalizedPaths.map { it.value })
    }

    private fun allTrackedKotlinSourcePaths(): Set<String> =
        fileManifest.snapshot(resolvedSourceRoots).currentPathsByLastModifiedMillis.keys

    private fun buildTargetedCandidatePaths(
        identifier: String,
        anchorSourceModuleName: ModuleName?,
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
        anchorSourceModuleName: ModuleName?,
    ): List<String> {
        if (anchorSourceModuleName == null) {
            return candidatePaths
        }

        val allowedSourceModuleNames = dependentModuleNamesBySourceModuleName[anchorSourceModuleName].orEmpty()
        return candidatePaths.filter { candidatePath ->
            sourceModuleNameForFile(candidatePath) in allowedSourceModuleNames
        }
    }

    internal fun sourceModuleNameForFile(normalizedPath: String): ModuleName? {
        val filePath = Path.of(normalizedPath)
        return sourceModuleSpecs.firstOrNull { moduleSpec ->
            moduleSpec.sourceRoots.any(filePath::startsWith)
        }?.name
    }

    /**
     * Returns all source module names that share the same Gradle project as the
     * given [moduleName]. Kotlin's `internal` visibility is accessible from test
     * and testFixtures source sets of the same project (friend modules), so
     * scoping by exact module name alone is too narrow.
     *
     * For non-Gradle module names (no `[` bracket), returns only the input name.
     */
    internal fun friendModuleNames(moduleName: ModuleName): Set<ModuleName> {
        val projectPrefix = moduleName.value.substringBefore('[', missingDelimiterValue = "")
        if (projectPrefix.isEmpty()) {
            return setOf(moduleName)
        }
        return sourceModuleSpecs
            .asSequence()
            .map(StandaloneSourceModuleSpec::name)
            .filter { name -> name.value.substringBefore('[', missingDelimiterValue = "") == projectPrefix }
            .toSet()
            .ifEmpty { setOf(moduleName) }
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

    private fun normalizeTrackedKotlinPaths(paths: Set<String>): List<NormalizedPath> = paths.asSequence()
        .map { path -> NormalizedPath.of(Path.of(path)) }
        .distinct()
        .filter { normalizedPath -> isTrackedKotlinFilePath(normalizedPath.toJavaPath()) }
        .toList()

    private fun buildRefreshResult(
        normalizedPaths: List<NormalizedPath>,
        fullRefresh: Boolean,
    ): RefreshResult = RefreshResult(
        refreshedFiles = normalizedPaths.filter { normalizedPath -> Files.isRegularFile(normalizedPath.toJavaPath()) }
            .map { it.value }
            .sorted(),
        removedFiles = normalizedPaths.filterNot { normalizedPath -> Files.isRegularFile(normalizedPath.toJavaPath()) }
            .map { it.value }
            .sorted(),
        fullRefresh = fullRefresh,
    )

    private fun refreshStructureLocked() {
        val previousSessionDisposable = sessionStateDisposable
        buildAnalysisStateAndCache()
        fullKtFileMapLoaded = false
        Disposer.dispose(previousSessionDisposable)
    }

    private fun buildAnalysisStateAndCache() {
        val rebuiltAnalysisState = buildAnalysisState()
        session = rebuiltAnalysisState.session
        analysisStateGeneration.incrementAndGet()
        sourceModules = rebuiltAnalysisState.sourceModules
        sessionStateDisposable = rebuiltAnalysisState.disposable
        targetedKtFilesByPath.clear()
        ktFilesByPath.clear()
        ktFileLastModifiedMillisByPath.clear()
        targetedCandidatePathsByLookupKey.clear()
    }

    private fun normalizePath(path: Path): Path = NormalizedPath.of(path).toJavaPath()

    private fun scheduleSourceIndexCacheWrite() {
        val generation = sourceIndexGeneration.get()
        cacheManager.schedule(
            key = "source-index-cache",
            delayMillis = sourceIndexCacheSaveDelayMillis,
        ) {
            sourceIdentifierIndex.get()?.let { index ->
                persistSourceIndexCache(generation, index)
            }
        }
    }

    private fun persistSourceIndexCache(
        generation: Int,
        index: MutableSourceIdentifierIndex,
    ) {
        if (closed || sourceIndexGeneration.get() != generation) {
            return
        }
        runCatching {
            sourceIndexCache.save(index = index, sourceRoots = resolvedSourceRoots)
        }
    }
}

private data class AnalysisState(
    val disposable: Disposable,
    val session: StandaloneAnalysisAPISession,
    val sourceModules: List<KaSourceModule>,
)

internal class MutableSourceIdentifierIndex(
    private val pathsByIdentifier: ConcurrentHashMap<KotlinIdentifier, MutableSet<NormalizedPath>>,
    private val identifiersByPath: ConcurrentHashMap<NormalizedPath, Set<KotlinIdentifier>>,
    private val packageByPath: ConcurrentHashMap<NormalizedPath, PackageName> = ConcurrentHashMap(),
    private val importsByPath: ConcurrentHashMap<NormalizedPath, Set<FqName>> = ConcurrentHashMap(),
    private val wildcardImportPackagesByPath: ConcurrentHashMap<NormalizedPath, Set<PackageName>> = ConcurrentHashMap(),
) {
    fun candidatePathsFor(identifier: String): List<String> =
        pathsByIdentifier[KotlinIdentifier(identifier)]?.map { it.value }?.sorted().orEmpty()

    /**
     * Returns file paths that contain [identifier] and are plausibly importing [targetFqName]:
     * same package, explicit import, or wildcard import of the target's package.
     */
    fun candidatePathsForFqName(
        identifier: String,
        targetPackage: String,
        targetFqName: String,
    ): List<String> {
        val id = KotlinIdentifier(identifier)
        val pkg = PackageName(targetPackage)
        val fqn = FqName(targetFqName)
        val rawCandidates = pathsByIdentifier[id] ?: return emptyList()
        return rawCandidates
            .filter { path ->
                packageByPath[path] == pkg ||
                importsByPath[path]?.contains(fqn) == true ||
                wildcardImportPackagesByPath[path]?.contains(pkg) == true
            }
            .map { it.value }
            .sorted()
    }

    fun toSerializableMap(): Map<String, List<String>> = pathsByIdentifier.entries
        .asSequence()
        .sortedBy { it.key.value }
        .associate { (identifier, paths) ->
            identifier.value to paths.map { it.value }.sorted()
        }

    fun toSerializableMetadata(): SourceIdentifierIndexMetadataSnapshot =
        SourceIdentifierIndexMetadataSnapshot(
            packageByPath = packageByPath.entries
                .asSequence()
                .sortedBy { it.key.value }
                .associate { (path, packageName) -> path.value to packageName.value },
            importsByPath = importsByPath.entries
                .asSequence()
                .sortedBy { it.key.value }
                .associate { (path, imports) -> path.value to imports.map { it.value }.sorted() },
            wildcardImportPackagesByPath = wildcardImportPackagesByPath.entries
                .asSequence()
                .sortedBy { it.key.value }
                .associate { (path, packages) -> path.value to packages.map { it.value }.sorted() },
        )

    fun updateFile(
        normalizedPath: String,
        newContent: String,
    ) {
        val path = NormalizedPath.ofNormalized(normalizedPath)
        replaceIdentifiers(
            normalizedPath = path,
            identifiers = identifierRegex.findAll(newContent).map { match -> KotlinIdentifier(match.value) }.toSet(),
        )
        extractFileMetadata(path, newContent)
    }

    fun removeFile(normalizedPath: String) {
        val path = NormalizedPath.ofNormalized(normalizedPath)
        replaceIdentifiers(normalizedPath = path, identifiers = emptySet())
        packageByPath.remove(path)
        importsByPath.remove(path)
        wildcardImportPackagesByPath.remove(path)
    }

    fun knownPaths(): Set<String> = identifiersByPath.keys.mapTo(mutableSetOf()) { it.value }

    internal fun extractFileMetadata(
        normalizedPath: String,
        content: String,
    ) {
        extractFileMetadata(NormalizedPath.ofNormalized(normalizedPath), content)
    }

    private fun extractFileMetadata(
        normalizedPath: NormalizedPath,
        content: String,
    ) {
        packageRegex.find(content)?.groupValues?.getOrNull(1)
            ?.let { packageByPath[normalizedPath] = PackageName(it) }
        ?: packageByPath.remove(normalizedPath)

        val imports = mutableSetOf<FqName>()
        val wildcardPackages = mutableSetOf<PackageName>()
        importRegex.findAll(content).forEach { match ->
            val fqn = match.groupValues[1]
            if (match.groupValues[2] == ".*") {
                wildcardPackages += PackageName(fqn)
            } else {
                imports += FqName(fqn)
            }
        }

        if (imports.isNotEmpty()) importsByPath[normalizedPath] = imports
        else importsByPath.remove(normalizedPath)

        if (wildcardPackages.isNotEmpty()) wildcardImportPackagesByPath[normalizedPath] = wildcardPackages
        else wildcardImportPackagesByPath.remove(normalizedPath)
    }

    private fun replaceIdentifiers(
        normalizedPath: NormalizedPath,
        identifiers: Set<KotlinIdentifier>,
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
        private val packageRegex = Regex("""^package\s+([\w]+(?:\.[\w]+)*)""", RegexOption.MULTILINE)
        private val importRegex = Regex("""^import\s+([\w]+(?:\.[\w]+)*)(\.\*)?""", RegexOption.MULTILINE)

        fun fromCandidatePathsByIdentifier(
            candidatePathsByIdentifier: Map<String, List<String>>,
            packageByPath: Map<String, String> = emptyMap(),
            importsByPath: Map<String, List<String>> = emptyMap(),
            wildcardImportPackagesByPath: Map<String, List<String>> = emptyMap(),
        ): MutableSourceIdentifierIndex {
            val typedPathsByIdentifier = ConcurrentHashMap<KotlinIdentifier, MutableSet<NormalizedPath>>()
            val typedIdentifiersByPath = ConcurrentHashMap<NormalizedPath, Set<KotlinIdentifier>>()
            candidatePathsByIdentifier.forEach { (identifier, paths) ->
                typedPathsByIdentifier[KotlinIdentifier(identifier)] = paths.mapTo(ConcurrentHashMap.newKeySet()) {
                    NormalizedPath.ofNormalized(it)
                }
                paths.mapTo<String, NormalizedPath, ConcurrentHashMap.KeySetView<NormalizedPath, Boolean>>(
                    ConcurrentHashMap.newKeySet()
                ) { NormalizedPath.ofNormalized(it) }
                    .forEach { normalizedPath ->
                        typedIdentifiersByPath.compute(normalizedPath) { _, existingIdentifiers ->
                            (existingIdentifiers.orEmpty() + KotlinIdentifier(identifier))
                        }
                    }
            }
            return MutableSourceIdentifierIndex(
                pathsByIdentifier = typedPathsByIdentifier,
                identifiersByPath = typedIdentifiersByPath,
                packageByPath = packageByPath.entries.associateTo(ConcurrentHashMap()) { (path, pkg) ->
                    NormalizedPath.ofNormalized(path) to PackageName(pkg)
                },
                importsByPath = importsByPath.entries.associateTo(ConcurrentHashMap()) { (path, imports) ->
                    NormalizedPath.ofNormalized(path) to imports.mapTo(mutableSetOf()) { FqName(it) }
                },
                wildcardImportPackagesByPath = wildcardImportPackagesByPath.entries.associateTo(ConcurrentHashMap()) { (path, packages) ->
                    NormalizedPath.ofNormalized(path) to packages.mapTo(mutableSetOf()) { PackageName(it) }
                },
            )
        }
    }
}

internal data class SourceIdentifierIndexMetadataSnapshot(
    val packageByPath: Map<String, String>,
    val importsByPath: Map<String, List<String>>,
    val wildcardImportPackagesByPath: Map<String, List<String>>,
)

private data class CandidateLookupKey(
    val identifier: String,
    val anchorSourceModuleName: ModuleName?,
)

private const val defaultSourceIndexCacheSaveDelayMillis = 5_000L

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

internal fun buildDependentModuleNamesBySourceModuleName(
    sourceModules: List<StandaloneSourceModuleSpec>,
): Map<ModuleName, Set<ModuleName>> {
    val reverseDependencies = linkedMapOf<ModuleName, MutableSet<ModuleName>>()
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
    val diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
    val dependentModuleNamesBySourceModuleName: Map<ModuleName, Set<ModuleName>> = emptyMap(),
)

internal data class StandaloneSourceModuleSpec(
    val name: ModuleName,
    val sourceRoots: List<Path>,
    val binaryRoots: List<Path>,
    val dependencyModuleNames: List<ModuleName>,
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
                    name = ModuleName(moduleName),
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
                name = ModuleName(moduleName),
                sourceRoots = discoverSourceRoots(normalizedWorkspaceRoot),
                binaryRoots = normalizeStandalonePaths(classpathRoots),
                dependencyModuleNames = emptyList(),
            ),
        ),
    )
}

internal fun discoverStandaloneWorkspaceLayoutPhased(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
): PhasedDiscoveryResult {
    val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
    if (sourceRoots.isNotEmpty() || !looksLikeGradleWorkspace(normalizedWorkspaceRoot)) {
        return PhasedDiscoveryResult(
            initialLayout = discoverStandaloneWorkspaceLayout(
                workspaceRoot = normalizedWorkspaceRoot,
                sourceRoots = sourceRoots,
                classpathRoots = classpathRoots,
                moduleName = moduleName,
            ),
            enrichmentFuture = null,
        )
    }

    return GradleWorkspaceDiscovery.discoverPhased(
        workspaceRoot = normalizedWorkspaceRoot,
        extraClasspathRoots = normalizeStandalonePaths(classpathRoots),
    )
}

internal fun normalizeStandalonePath(path: Path): Path = NormalizedPath.of(path).toJavaPath()

@Suppress("unused")
private fun normalizeStandaloneMissingPath(@Suppress("UNUSED_PARAMETER") path: Path): Path {
    error("Replaced by NormalizedPath.of()")
}

internal fun normalizeStandaloneModelPath(path: Path): Path = NormalizedPath.ofAbsolute(path).toJavaPath()

internal fun normalizeStandalonePaths(paths: Iterable<Path>): List<Path> = paths
    .map { NormalizedPath.of(it) }
    .distinct()
    .sorted()
    .map { it.toJavaPath() }

internal fun normalizeStandaloneSourceRoots(paths: Iterable<Path>): List<Path> = paths
    .map { NormalizedPath.of(it) }
    .distinct()
    .sorted()
    .map { it.toJavaPath() }

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
    val outgoingEdges = linkedMapOf<ModuleName, MutableSet<ModuleName>>()
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
            .sortedBy { it.value },
    )
    val orderedModules = mutableListOf<StandaloneSourceModuleSpec>()
    while (readyNames.isNotEmpty()) {
        val moduleName = readyNames.removeFirst()
        orderedModules += checkNotNull(sourceModulesByName[moduleName])
        for (dependentName in outgoingEdges[moduleName].orEmpty().sortedBy { it.value }) {
            val dependencies = incomingEdges.getValue(dependentName)
            dependencies.remove(moduleName)
            if (dependencies.isEmpty()) {
                readyNames.addLast(dependentName)
            }
        }
    }

    require(orderedModules.size == sourceModules.size) {
        val unresolvedModuleNames = incomingEdges
            .filterValues(Set<ModuleName>::isNotEmpty)
            .keys
            .sortedBy { it.value }
        "The standalone workspace layout contains cyclic source module dependencies: ${
            unresolvedModuleNames.joinToString(
                ", "
            )
        }"
    }
    return orderedModules
}
