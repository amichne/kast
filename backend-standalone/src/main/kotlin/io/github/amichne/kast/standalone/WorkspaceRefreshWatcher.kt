package io.github.amichne.kast.standalone

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.extension

internal class WorkspaceRefreshWatcher(
    private val session: StandaloneAnalysisSession,
    private val debounceMillis: Long = 200,
) : AutoCloseable {
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val directoriesByWatchKey = ConcurrentHashMap<WatchKey, Path>()
    private val watchKeysByDirectory = ConcurrentHashMap<Path, WatchKey>()
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
        session.resolvedSourceRoots.forEach(::registerDirectoryRecursively)
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
                        System.nanoTime() - lastEventAtNanos >= debounceMillis * 1_000_000
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
                lastEventAtNanos = System.nanoTime()
            }
        } catch (_: ClosedWatchServiceException) {
            return
        }
    }

    private fun flushPendingChanges(
        changedPaths: Set<String>,
        forceFullRefresh: Boolean,
    ) {
        runCatching {
            if (forceFullRefresh) {
                session.refreshWorkspace()
            } else if (changedPaths.isNotEmpty()) {
                session.refreshFiles(changedPaths)
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

        Files.walk(rootDirectory).use { paths ->
            paths
                .filter(Files::isDirectory)
                .forEach(::registerDirectory)
        }
    }

    private fun registerDirectory(directory: Path) {
        val normalizedDirectory = directory.toAbsolutePath().normalize()
        if (watchKeysByDirectory.containsKey(normalizedDirectory)) {
            return
        }

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
    }

    private fun unregister(watchKey: WatchKey) {
        val directory = directoriesByWatchKey.remove(watchKey) ?: return
        watchKeysByDirectory.remove(directory, watchKey)
    }
}
