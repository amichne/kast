package io.github.amichne.kast.standalone

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.java.JavaParserDefinition
import com.intellij.lang.java.syntax.JavaElementTypeConverterExtension
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory
import com.intellij.platform.syntax.psi.ElementTypeConverters
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.impl.PsiManagerEx
import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.KotlinIdentifier
import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.contract.PackageName
import io.github.amichne.kast.api.contract.RefreshResult
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.targetFqNameAndPackage
import io.github.amichne.kast.standalone.cache.CacheManager
import io.github.amichne.kast.standalone.cache.SourceIndexCache
import io.github.amichne.kast.standalone.cache.SqliteSourceIndexStore
import io.github.amichne.kast.standalone.cache.SymbolReferenceRow
import io.github.amichne.kast.standalone.cache.scanTrackedKotlinFileTimestamps
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import io.github.amichne.kast.standalone.workspace.PhasedDiscoveryResult
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    private val clock: Clock = Clock.SYSTEM,
    private val analysisSessionLock: SessionLock = ReentrantSessionLock(),
    private val identifierIndexWaitMillis: Long = defaultIdentifierIndexWaitMillis,
    internal val telemetry: StandaloneTelemetry = StandaloneTelemetry.disabled(),
) : AutoCloseable {
    private val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
    private val disposable: Disposable = Disposer.newDisposable("kast-standalone")
    private val ktFilesByPath = ConcurrentHashMap<NormalizedPath, KtFile>()
    private val targetedKtFilesByPath = ConcurrentHashMap<NormalizedPath, KtFile>()
    private val ktFileLastModifiedMillisByPath = ConcurrentHashMap<NormalizedPath, Long>()
    private val targetedCandidatePathsByLookupKey = ConcurrentHashMap<CandidateLookupKey, List<String>>()
    private val sourceIdentifierIndex = AtomicReference<MutableSourceIdentifierIndex?>(null)
    private val sourceModuleNamesByPath = ConcurrentHashMap<NormalizedPath, ModuleName>()

    private val pendingSourceIndexRefreshPaths = ConcurrentHashMap.newKeySet<String>()
    private val sourceIndexGeneration = AtomicInteger(0)
    private val analysisStateGeneration = AtomicInteger(0)
    private lateinit var backgroundIndexer: BackgroundIndexer
    private val enrichmentReady = CompletableFuture<Unit>()
    private val fullKtFileMapLoadLock = Any()
    private val cacheManager = CacheManager(normalizedWorkspaceRoot, envReader = cacheEnvReader)
    private val sourceIndexCache = SourceIndexCache(
        workspaceRoot = normalizedWorkspaceRoot,
        enabled = cacheManager.isEnabled(),
    )

    @Volatile
    private var workspaceRefreshWatcher: WorkspaceRefreshWatcher? = null

    /**
     * Stable snapshot of known paths taken immediately after Phase 1 indexing completes and
     * advanced after each explicit full refresh (invalidateCaches=true). Used by
     * [refreshWorkspace] when invalidating caches so that interim watcher-driven partial
     * refreshes cannot corrupt the "before" picture used to compute [RefreshResult.removedFiles].
     *
     * Watcher-triggered refreshes (invalidateCaches=false) still use the live
     * [sourceIdentifierIndex] because they need up-to-date incremental state.
     */
    @Volatile
    private var checkpointKnownPaths: Set<String> = emptySet()

    @Volatile
    private var enrichmentComplete = false

    @Volatile
    private var closed = false

    @Volatile
    private var fullKtFileMapLoaded = false

    @Volatile
    private var sourceModuleSpecs: List<StandaloneSourceModuleSpec> = emptyList()

    @Volatile
    private var _dependentModuleNamesBySourceModuleName: Map<ModuleName, Set<ModuleName>> = emptyMap()

    internal val dependentModuleGraph: Map<ModuleName, Set<ModuleName>>
        get() = _dependentModuleNamesBySourceModuleName

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

    fun moduleSpecs(): List<StandaloneSourceModuleSpec> = sourceModuleSpecs

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
            val cachedEntriesByPath = refreshVirtualFilesAndInvalidateCachedPsi(normalizedPaths)

            normalizedPaths.forEach { normalizedPath ->
                val cachedEntries = cachedEntriesByPath.getValue(normalizedPath)
                val hadKtFileEntry = cachedEntries.ktFile != null
                val hadTargetedEntry = cachedEntries.targetedKtFile != null
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

        PsiManager.getInstance(session.project).dropResolveCaches()
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

        telemetry.inSpan(
            scope = StandaloneTelemetryScope.SESSION_LIFECYCLE,
            name = "kast.session.refreshFiles",
            attributes = mapOf("kast.session.refreshedFileCount" to normalizedPaths.size),
        ) {
            analysisSessionLock.write {
                refreshVirtualFilesAndInvalidateCachedPsi(normalizedPaths)
                normalizedPaths.forEach { normalizedPath ->
                    ktFilesByPath.remove(normalizedPath)
                    targetedKtFilesByPath.remove(normalizedPath)
                    ktFileLastModifiedMillisByPath.remove(normalizedPath)
                }
                normalizedPaths
                    .filter { normalizedPath -> Files.isRegularFile(normalizedPath.toJavaPath()) }
                    .forEach { normalizedPath -> loadKtFileByPath(normalizedPath, session) }
                PsiManager.getInstance(session.project).dropResolveCaches()

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
        }

        refreshSourceIdentifierIndex(normalizedPaths)
        targetedCandidatePathsByLookupKey.clear()
        return buildRefreshResult(normalizedPaths, fullRefresh = false)
    }

    fun refreshTargetedPaths(paths: Set<String>): RefreshResult {
        val normalizedPaths = normalizeTrackedKotlinPaths(paths)
        if (normalizedPaths.all(::isKnownRegularKotlinFile)) {
            return refreshFileContents(paths)
        }
        return refreshFiles(paths)
    }

    private data class CachedKtFileEntries(
        val ktFile: KtFile?,
        val targetedKtFile: KtFile?,
    )

    private fun refreshVirtualFilesAndInvalidateCachedPsi(
        normalizedPaths: Collection<NormalizedPath>,
    ): Map<NormalizedPath, CachedKtFileEntries> {
        val cachedEntriesByPath = normalizedPaths.associateWith { normalizedPath ->
            CachedKtFileEntries(
                ktFile = ktFilesByPath[normalizedPath],
                targetedKtFile = targetedKtFilesByPath[normalizedPath],
            )
        }
        val refreshedVirtualFiles = normalizedPaths.mapNotNull { normalizedPath ->
            refreshVirtualFile(normalizedPath)
        }

        val fileManager = PsiManagerEx.getInstanceEx(session.project).fileManager
        val cachedVirtualFilesToInvalidate = (
            cachedEntriesByPath.values.asSequence()
                .flatMap { cachedEntries ->
                    sequenceOf(cachedEntries.ktFile, cachedEntries.targetedKtFile)
                        .filterNotNull()
                        .mapNotNull { ktFile -> ktFile.virtualFile }
                } + refreshedVirtualFiles.asSequence()
            )
            .distinct()
            .toList()
        if (cachedVirtualFilesToInvalidate.isNotEmpty()) {
            ApplicationManager.getApplication().runWriteAction {
                cachedVirtualFilesToInvalidate.forEach { virtualFile ->
                    fileManager.setViewProvider(virtualFile, null)
                }
            }
        }

        return cachedEntriesByPath
    }

    private fun refreshVirtualFile(normalizedPath: NormalizedPath): com.intellij.openapi.vfs.VirtualFile? {
        val filePath = normalizedPath.toJavaPath()
        val virtualFileManager = VirtualFileManager.getInstance()
        val virtualFile = virtualFileManager.findFileByNioPath(filePath)
                          ?: virtualFileManager.refreshAndFindFileByNioPath(filePath)
                          ?: return null
        VfsUtil.markDirtyAndRefresh(
            false,
            false,
            false,
            virtualFile,
        )
        return virtualFile
    }

    private fun isKnownRegularKotlinFile(normalizedPath: NormalizedPath): Boolean {
        if (!Files.isRegularFile(normalizedPath.toJavaPath())) {
            return false
        }
        if (ktFilesByPath.containsKey(normalizedPath) || targetedKtFilesByPath.containsKey(normalizedPath)) {
            return true
        }
        return sourceIdentifierIndex.get()?.knownPaths()?.contains(normalizedPath.value) == true
    }

    /**
     * Rebuilds the K2 analysis session without a full workspace refresh.
     *
     * In K2 standalone mode, `KotlinStandaloneDeclarationProviderFactory` builds a
     * static declaration index at session construction time. Content changes that alter
     * declaration signatures (e.g., renames) require a session rebuild for cross-file
     * resolution to pick up the new names. The lightweight `refreshFileContents` path
     * updates PSI/VFS and our source identifier index, but cannot incrementally update
     * K2's declaration index because standalone mode's `MockComponentManager` does not
     * support the message-bus-based invalidation that IDE mode relies on.
     *
     * Call this after `refreshFileContents` when the edits include declaration-level
     * changes and cross-file resolution correctness is required.
     */
    fun rebuildAnalysisSession() {
        telemetry.inSpan(
            scope = StandaloneTelemetryScope.SESSION_LIFECYCLE,
            name = "kast.session.rebuildAnalysisSession",
        ) {
            analysisSessionLock.write {
                val previousDisposable = sessionStateDisposable
                buildAnalysisStateAndCache()
                fullKtFileMapLoaded = false
                Disposer.dispose(previousDisposable)
            }
        }
    }

    fun refreshWorkspace(invalidateCaches: Boolean = false): RefreshResult {
        if (invalidateCaches) {
            backgroundIndexer.close()
            cacheManager.invalidateAll()
        }
        val currentPaths = allTrackedKotlinSourcePaths()
        val knownPaths = buildSet {
            addAll(ktFilesByPath.keys.map { it.value })
            addAll(targetedKtFilesByPath.keys.map { it.value })
            // When invalidating caches we use the stable checkpoint instead of the live index.
            // The WorkspaceRefreshWatcher may have already mutated sourceIdentifierIndex via
            // incremental refreshes (e.g. removing deleted files) before this explicit full
            // refresh runs.  On slow runners (Linux CI) the watcher's 200 ms debounce fires
            // before the CLI process finishes starting, wiping the "before" picture from the
            // live index.  checkpointKnownPaths is only advanced by explicit full refreshes
            // and by the Phase 1 onIndexBuilt callback, so it is always the last clean baseline.
            if (invalidateCaches) {
                addAll(checkpointKnownPaths)
            } else {
                addAll(sourceIdentifierIndex.get()?.knownPaths().orEmpty())
            }
            addAll(pendingSourceIndexRefreshPaths)
        }
        val removedPaths = (knownPaths - currentPaths).sorted()
        refreshFiles(currentPaths + removedPaths)
        if (invalidateCaches) {
            // Advance the stable baseline so the next explicit refresh has an accurate picture.
            checkpointKnownPaths = currentPaths.toSet()
        }
        return RefreshResult(
            refreshedFiles = currentPaths.sorted(),
            removedFiles = removedPaths,
            fullRefresh = true,
        )
    }

    internal fun awaitInitialSourceIndex() {
        backgroundIndexer.identifierIndexReady.join()
    }

    /** True when Phase 2 (symbol reference) indexing has completed successfully (not cancelled). */
    internal fun isReferenceIndexReady(): Boolean =
        backgroundIndexer.referenceIndexReady.isDone &&
            !backgroundIndexer.referenceIndexReady.isCompletedExceptionally

    /** The underlying SQLite store for cached symbol reference lookups. */
    internal val sqliteStore: SqliteSourceIndexStore get() = sourceIndexCache.store

    internal fun currentAnalysisStateGeneration(): Int = analysisStateGeneration.get()

    fun isEnrichmentComplete(): Boolean = enrichmentComplete

    fun awaitEnrichment() {
        enrichmentReady.join()
    }

    internal fun attachWorkspaceRefreshWatcher(watcher: WorkspaceRefreshWatcher) {
        workspaceRefreshWatcher = watcher
        watcher.refreshSourceRoots(resolvedSourceRoots)
    }

    internal inline fun <T> withReadAccess(crossinline action: () -> T): T = analysisSessionLock.read { action() }

    internal fun candidateKotlinFilePaths(identifier: String): List<String> {
        return candidateKotlinFilePaths(identifier = identifier, anchorFilePath = null)
    }

    internal fun candidateKotlinFilePaths(
        identifier: String,
        anchorFilePath: String?,
    ): List<String> = telemetry.inSpan(
        scope = StandaloneTelemetryScope.INDEXING,
        name = "kast.session.candidateKotlinFilePaths",
        attributes = mapOf("kast.candidates.identifier" to identifier),
    ) { span ->
        if (!identifier.isIndexableIdentifier()) {
            span.setAttribute("kast.candidates.source", "empty")
            span.setAttribute("kast.candidates.resultCount", 0)
            return@inSpan emptyList()
        }

        val anchorSourceModuleName = anchorFilePath?.let { filePath -> sourceModuleNameForFile(NormalizedPath.of(Path.of(filePath))) }

        val readyIndex = readySourceIdentifierIndex()
        if (readyIndex != null) {
            span.setAttribute("kast.candidates.source", "memory-index")
            span.setAttribute("kast.candidates.indexReady", true)
            val allowedSourceModuleNames = anchorSourceModuleName
                ?.let(_dependentModuleNamesBySourceModuleName::get)
                .takeUnless { it.isNullOrEmpty() }
            val result = if (allowedSourceModuleNames == null) {
                readyIndex.candidatePathsFor(identifier)
            } else {
                readyIndex.candidatePathsForModule(
                    identifier = identifier,
                    allowedModuleNames = allowedSourceModuleNames,
                )
            }
            span.setAttribute("kast.candidates.resultCount", result.size)
            return@inSpan result
        }

        span.setAttribute("kast.candidates.indexReady", false)

        // Second-tier fallback: try loading from SQLite cache (fast, no filesystem walk).
        val sqliteIndex = runCatching { sourceIndexCache.store.loadFullIndex() }.getOrNull()
        if (sqliteIndex != null && sqliteIndex.knownPaths().isNotEmpty()) {
            span.setAttribute("kast.candidates.source", "sqlite-fallback")
            val result = sqliteIndex.candidatePathsFor(identifier)
            span.setAttribute("kast.candidates.resultCount", result.size)
            return@inSpan result
        }

        // Last resort: walk-based fallback
        span.setAttribute("kast.candidates.source", "filesystem-walk")
        val lookupKey = CandidateLookupKey(
            identifier = identifier,
            anchorSourceModuleName = anchorSourceModuleName,
        )
        val result = targetedCandidatePathsByLookupKey.computeIfAbsent(lookupKey) { key ->
            buildTargetedCandidatePaths(
                identifier = key.identifier,
                anchorSourceModuleName = key.anchorSourceModuleName,
            )
        }
        span.setAttribute("kast.candidates.resultCount", result.size)
        result
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

        val readyIndex = readySourceIdentifierIndex()
        if (readyIndex != null) {
            val allowedSourceModuleNames = anchorFilePath
                ?.let { filePath -> sourceModuleNameForFile(NormalizedPath.of(Path.of(filePath))) }
                ?.let(_dependentModuleNamesBySourceModuleName::get)
                .takeUnless { it.isNullOrEmpty() }
            val enrichedPaths = readyIndex.candidatePathsForFqName(
                identifier = identifier,
                targetPackage = targetPackage.value,
                targetFqName = targetFqName.value,
                allowedModuleNames = allowedSourceModuleNames,
            )
            if (enrichedPaths.isNotEmpty()) {
                return enrichedPaths
            }
        }

        return candidateKotlinFilePaths(identifier = identifier, anchorFilePath = anchorFilePath)
    }

    internal fun isInitialSourceIndexReady(): Boolean = backgroundIndexer.identifierIndexReady.isDone

    internal fun isFullKtFileMapLoaded(): Boolean = fullKtFileMapLoaded

    override fun close() {
        closed = true
        backgroundIndexer.close()
        sourceIdentifierIndex.get()?.let { index ->
            runCatching {
                sourceIndexCache.save(index = index, sourceRoots = resolvedSourceRoots)
            }
        }
        sourceIndexCache.close()
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
        _dependentModuleNamesBySourceModuleName = workspaceLayout.dependentModuleNamesBySourceModuleName
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
        telemetry.inSpan(
            scope = StandaloneTelemetryScope.SESSION_LIFECYCLE,
            name = "kast.session.rebuildWorkspaceLayout",
            attributes = mapOf("kast.session.sourceModuleCount" to workspaceLayout.sourceModules.size),
        ) {
            // Build the expensive K2 analysis state OUTSIDE the write lock.
            val newAnalysisState = telemetry.inSpan(
                scope = StandaloneTelemetryScope.SESSION_LIFECYCLE,
                name = "kast.session.buildAnalysisState",
                attributes = mapOf("kast.session.sourceModuleCount" to workspaceLayout.sourceModules.size),
            ) {
                buildAnalysisStateForSpecs(workspaceLayout.sourceModules)
            }

            // Short write lock: swap state atomically.
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.SESSION_LOCK,
                name = "kast.lock.acquire",
                attributes = mapOf("kast.lock.type" to "WRITE", "kast.lock.caller" to "rebuildWorkspaceLayout"),
            ) {
                analysisSessionLock.write {
                    val previousSessionDisposable = sessionStateDisposable
                    val previousIndexer = backgroundIndexer
                    applyWorkspaceLayout(workspaceLayout)
                    applyAnalysisState(newAnalysisState)
                    sourceIdentifierIndex.set(null)
                    // Clear the stable baseline so that any refreshWorkspace(invalidateCaches=true)
                    // that races during Phase 1 rebuild cannot report stale files from the old layout.
                    checkpointKnownPaths = emptySet()
                    fullKtFileMapLoaded = false
                    startInitialSourceIndex()
                    previousIndexer.close()
                    Disposer.dispose(previousSessionDisposable)
                }
            }
        }
    }

    /**
     * Applies a pre-built [AnalysisState] to the session's mutable fields.
     * Must be called under the write lock.
     */
    private fun applyAnalysisState(analysisState: AnalysisState) {
        session = analysisState.session
        analysisStateGeneration.incrementAndGet()
        sourceModules = analysisState.sourceModules
        sessionStateDisposable = analysisState.disposable
        sourceModuleNamesByPath.clear()
        targetedKtFilesByPath.clear()
        ktFilesByPath.clear()
        ktFileLastModifiedMillisByPath.clear()
        targetedCandidatePathsByLookupKey.clear()
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
        val currentPathsByLastModifiedMillis = scanTrackedKotlinFileTimestamps(resolvedSourceRoots)
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

    private fun buildAnalysisState(): AnalysisState = buildAnalysisStateForSpecs(sourceModuleSpecs)

    /**
     * Builds a new K2 analysis state for the given module specs without reading or
     * mutating any shared mutable state. This allows the expensive session construction
     * to happen outside the write lock.
     */
    private fun buildAnalysisStateForSpecs(specs: List<StandaloneSourceModuleSpec>): AnalysisState {
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
                    libraryName = "JDK for ${specs.first().name.value}"
                }

                for (moduleSpec in topologicallySortSourceModules(specs)) {
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
        backgroundIndexer = BackgroundIndexer(
            sourceRoots = resolvedSourceRoots,
            sourceIndexFileReader = sourceIndexFileReader,
            sourceModuleNameResolver = ::sourceModuleNameForFile,
            sourceIndexCache = sourceIndexCache,
            store = sourceIndexCache.store,
            initialSourceIndexBuilder = initialSourceIndexBuilder,
        )
        val generation = sourceIndexGeneration.incrementAndGet()
        // Publish the index synchronously in the Phase 1 thread before identifierIndexReady
        // completes, so that any caller waiting on identifierIndexReady sees a non-null
        // sourceIdentifierIndex immediately (no async thenRun race).
        backgroundIndexer.startPhase1 { builtIndex ->
            if (closed || sourceIndexGeneration.get() != generation) return@startPhase1
            applyPendingSourceIndexRefreshes(builtIndex)
            sourceIdentifierIndex.set(builtIndex)
            // Advance the stable baseline so refreshWorkspace(invalidateCaches=true) has a
            // clean starting point that watcher-driven partial refreshes cannot corrupt.
            checkpointKnownPaths = builtIndex.knownPaths()
        }
        backgroundIndexer.identifierIndexReady.thenRun {
            if (closed || sourceIndexGeneration.get() != generation) return@thenRun
            backgroundIndexer.startPhase2(::scanFileReferences)
        }
    }

    /**
     * Phase 2 reference scanner: walks a single file's PSI tree, resolves references
     * via the K2 analysis session, and extracts [SymbolReferenceRow]s mapping each
     * reference site to its target's fully-qualified name, path, and offset.
     *
     * Called per-file on the Phase 2 background thread. Errors for individual elements
     * are silently skipped so one bad reference doesn't abort the entire file scan.
     */
    private fun scanFileReferences(filePath: String): List<SymbolReferenceRow> {
        val rows = mutableListOf<SymbolReferenceRow>()
        withReadAccess {
            val ktFile = runCatching { findKtFile(filePath) }.getOrNull() ?: return@withReadAccess
            val sourceFilePath = runCatching { ktFile.resolvedFilePath().value }.getOrElse { filePath }

            ktFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: com.intellij.psi.PsiElement) {
                        element.references.forEach { reference ->
                            runCatching {
                                val resolved = reference.resolve() ?: return@forEach
                                val fqNameAndPkg = resolved.targetFqNameAndPackage() ?: return@forEach
                                val (fqName, _) = fqNameAndPkg
                                val targetPath = runCatching { resolved.resolvedFilePath().value }.getOrNull()
                                val targetOffset = resolved.textRange?.startOffset
                                val sourceOffset = reference.element.textRange.startOffset +
                                    reference.rangeInElement.startOffset
                                rows += SymbolReferenceRow(
                                    sourcePath = sourceFilePath,
                                    sourceOffset = sourceOffset,
                                    targetFqName = fqName.value,
                                    targetPath = targetPath,
                                    targetOffset = targetOffset,
                                )
                            }
                        }
                        super.visitElement(element)
                    }
                },
            )
        }
        return rows
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
        persistIncrementally: Boolean = true,
    ) {
        val filePath = normalizedPath.toJavaPath()
        if (!Files.isRegularFile(filePath)) {
            sourceModuleNamesByPath.remove(normalizedPath)
            index.removeFile(normalizedPath.value)
            if (persistIncrementally) {
                sourceIndexCache.saveRemovedFile(normalizedPath.value)
            }
            return
        }

        index.updateFile(
            normalizedPath = normalizedPath.value,
            newContent = sourceIndexFileReader(filePath),
            moduleName = sourceModuleNameForFile(normalizedPath),
        )
        if (persistIncrementally) {
            sourceIndexCache.saveFileIndex(index, normalizedPath)
        }
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
        scanTrackedKotlinFileTimestamps(resolvedSourceRoots).keys

    private fun buildTargetedCandidatePaths(
        identifier: String,
        anchorSourceModuleName: ModuleName?,
    ): List<String> = buildList {
        val allowedSourceModuleNames = anchorSourceModuleName
            ?.let(_dependentModuleNamesBySourceModuleName::get)
            .takeUnless { it.isNullOrEmpty() }

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

        val allowedSourceModuleNames = _dependentModuleNamesBySourceModuleName[anchorSourceModuleName]
            .takeUnless { it.isNullOrEmpty() }
            ?: return candidatePaths
        return candidatePaths.filter { candidatePath ->
            sourceModuleNameForFile(candidatePath) in allowedSourceModuleNames
        }
    }

    internal fun sourceModuleNameForFile(normalizedPath: String): ModuleName? =
        sourceModuleNameForFile(NormalizedPath.ofNormalized(normalizedPath))

    private fun sourceModuleNameForFile(normalizedPath: NormalizedPath): ModuleName? =
        sourceModuleNamesByPath[normalizedPath]
            ?: sourceModuleSpecs.firstOrNull { moduleSpec ->
                moduleSpec.sourceRoots.any(normalizedPath.toJavaPath()::startsWith)
            }?.name?.also { moduleName ->
                sourceModuleNamesByPath[normalizedPath] = moduleName
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
        applyAnalysisState(rebuiltAnalysisState)
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

    private fun readySourceIdentifierIndex(): MutableSourceIdentifierIndex? {
        return telemetry.inSpan(
            scope = StandaloneTelemetryScope.INDEXING,
            name = "kast.session.readySourceIdentifierIndex",
            attributes = mapOf("kast.indexWait.configuredTimeoutMillis" to identifierIndexWaitMillis),
        ) { span ->
            sourceIdentifierIndex.get()?.let { index ->
                span.setAttribute("kast.indexWait.immediateHit", true)
                span.setAttribute("kast.indexWait.indexAvailable", true)
                return@inSpan index
            }
            span.setAttribute("kast.indexWait.immediateHit", false)
            if (identifierIndexWaitMillis > 0) {
                runCatching {
                    backgroundIndexer.identifierIndexReady.get(identifierIndexWaitMillis, TimeUnit.MILLISECONDS)
                }.getOrNull()
            }
            val result = sourceIdentifierIndex.get()
            span.setAttribute("kast.indexWait.indexAvailable", result != null)
            result
        }
    }
}

private data class AnalysisState(
    val disposable: Disposable,
    val session: StandaloneAnalysisAPISession,
    val sourceModules: List<KaSourceModule>,
)

private data class CandidateLookupKey(
    val identifier: String,
    val anchorSourceModuleName: ModuleName?,
)

private const val defaultSourceIndexCacheSaveDelayMillis = 5_000L

private const val defaultIdentifierIndexWaitMillis = 10_000L
