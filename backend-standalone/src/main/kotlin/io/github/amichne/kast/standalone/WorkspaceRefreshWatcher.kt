package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.contract.result.RefreshResult
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.extension

internal class WorkspaceRefreshWatcher(
    private val session: StandaloneAnalysisSession,
    private val debounceMillis: Long = KastConfig.load(session.workspaceRoot).watcher.debounceMillis,
    private val contentRefresh: (Set<String>) -> RefreshResult = session::refreshFileContents,
    private val fullRefresh: () -> RefreshResult = session::refreshWorkspace,
    private val clock: Clock = Clock.SYSTEM,
) : AutoCloseable {
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val directoriesByWatchKey = ConcurrentHashMap<WatchKey, Path>()
    private val watchKeysByDirectory = ConcurrentHashMap<Path, WatchKey>()
    private val sourceRoots = ConcurrentHashMap.newKeySet<Path>()
    @Volatile
    private var closed = false
    private val worker = thread(
        start = true,
        isDaemon = true,
        name = "kast-workspace-refresh-watcher",
    ) {
        processEvents()
    }

    init {
        refreshSourceRoots(session.resolvedSourceRoots)
    }

    override fun close() {
        closed = true
        watchService.close()
        runCatching {
            worker.join(TimeUnit.SECONDS.toMillis(5))
        }.onFailure { Thread.currentThread().interrupt() }
    }

    private fun processEvents() {
        var pendingPaths = linkedSetOf<String>()
        var forceFullRefresh = false
        var lastEventAtNanos = 0L

        try {
            while (!closed) {
                val nextKey = watchService.poll(100, TimeUnit.MILLISECONDS)
                if (nextKey == null) {
                    if ((pendingPaths.isNotEmpty() || forceFullRefresh) &&
                        lastEventAtNanos != 0L &&
                        clock.nanoTime() - lastEventAtNanos >= debounceMillis * 1_000_000
                    ) {
                        flushPendingChanges(
                            changedPaths = pendingPaths,
                            forceFullRefresh = forceFullRefresh,
                        )
                        pendingPaths = linkedSetOf()
                        forceFullRefresh = false
                        lastEventAtNanos = 0L
                    }
                    continue
                }

                var key: WatchKey? = nextKey
                while (key != null) {
                    val directory = directoriesByWatchKey[key]
                    if (directory != null) {
                        key.pollEvents().forEach { event ->
                            when (event.kind()) {
                                StandardWatchEventKinds.OVERFLOW -> {
                                    forceFullRefresh = true
                                }

                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                -> {
                                    val relativePath = event.context() as? Path ?: return@forEach
                                    val absolutePath = directory.resolve(relativePath).toAbsolutePath().normalize()
                                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(absolutePath)) {
                                        registerDirectoryRecursively(absolutePath)
                                        forceFullRefresh = true
                                        return@forEach
                                    }
                                    if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE && absolutePath.extension != "kt") {
                                        forceFullRefresh = true
                                        return@forEach
                                    }
                                    if (absolutePath.extension == "kt") {
                                        pendingPaths += absolutePath.toString()
                                        // .kt file creation and deletion are structural changes
                                        // that require a full K2 session rebuild so that resolve
                                        // caches for other files are properly invalidated.
                                        // Content-only refresh only invalidates caches for files
                                        // that were already loaded into the PSI map, which is not
                                        // the case for newly created files.
                                        if (event.kind() != StandardWatchEventKinds.ENTRY_MODIFY) {
                                            forceFullRefresh = true
                                        }
                                    }
                                }
                            }
                        }
                        if (!key.reset()) {
                            unregister(key)
                        }
                    }
                    key = watchService.poll()
                }
                lastEventAtNanos = clock.nanoTime()
            }
        } catch (_: ClosedWatchServiceException) {
            return
        }
    }

    fun refreshSourceRoots(newSourceRoots: List<Path>) {
        val normalizedSourceRoots = newSourceRoots
            .map { sourceRoot -> sourceRoot.toAbsolutePath().normalize() }
            .toSet()
        val previousSourceRoots = sourceRoots.toSet()
        val removedSourceRoots = previousSourceRoots - normalizedSourceRoots

        sourceRoots.clear()
        sourceRoots.addAll(normalizedSourceRoots)

        (normalizedSourceRoots - previousSourceRoots)
            .sorted()
            .forEach(::registerDirectoryRecursively)
        if (removedSourceRoots.isEmpty()) {
            return
        }

        watchKeysByDirectory.entries
            .filter { (directory, _) ->
                removedSourceRoots.any(directory::startsWith) &&
                    normalizedSourceRoots.none(directory::startsWith)
            }
            .map { entry -> entry.value }
            .forEach(::unregister)
    }

    private fun flushPendingChanges(
        changedPaths: Set<String>,
        forceFullRefresh: Boolean,
    ) {
        runCatching {
            if (forceFullRefresh) {
                fullRefresh()
            } else if (changedPaths.isNotEmpty()) {
                contentRefresh(changedPaths)
            }
        }.onFailure { error ->
            System.err.println(
                "kast workspace watcher refresh failed: ${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    private fun registerDirectoryRecursively(rootDirectory: Path) {
        if (!Files.isDirectory(rootDirectory)) {
            return
        }

        runCatching {
            Files.walkFileTree(
                rootDirectory,
                object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        registerDirectory(dir)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult {
                        System.err.println(
                            "kast workspace watcher skipping inaccessible path $file: ${exc.message ?: exc::class.java.simpleName}",
                        )
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        }.onFailure { error ->
            System.err.println(
                "kast workspace watcher registration failed for $rootDirectory: ${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    private fun registerDirectory(directory: Path) {
        val normalizedDirectory = directory.toAbsolutePath().normalize()
        if (watchKeysByDirectory.containsKey(normalizedDirectory)) {
            return
        }

        runCatching {
            val watchKey = normalizedDirectory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            val existingKey = watchKeysByDirectory.putIfAbsent(normalizedDirectory, watchKey)
            if (existingKey == null) {
                directoriesByWatchKey[watchKey] = normalizedDirectory
            } else {
                watchKey.cancel()
            }
        }.onFailure { error ->
            System.err.println(
                "kast workspace watcher registration failed for $normalizedDirectory: ${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    private fun unregister(watchKey: WatchKey) {
        watchKey.cancel()
        val directory = directoriesByWatchKey.remove(watchKey) ?: return
        watchKeysByDirectory.remove(directory, watchKey)
    }
}
