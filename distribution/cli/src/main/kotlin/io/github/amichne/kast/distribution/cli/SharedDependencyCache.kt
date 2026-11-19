package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.ManagedHeadlessGradleHome
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** Publish a snapshot once. A concurrent creator can use the writable cache while the copy proceeds. */
internal fun prepareSharedDependencyCache(home: Path, staging: Path, localSeed: Path?) {
    val destination = home.resolve(ManagedHeadlessGradleHome.READ_ONLY_DIRECTORY)
    if (Files.exists(destination, NOFOLLOW_LINKS)) return
    val sources = listOfNotNull(home.resolve("caches/modules-2"), localSeed)
    FileChannel.open(home.resolve("kast-dependency-snapshot.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
        val lock = trySharedCacheLock(channel, shared = false) ?: return
        lock.use {
            if (Files.exists(destination, NOFOLLOW_LINKS)) return
            sources.firstOrNull { source ->
                tryCacheSnapshot(source, destination, staging.resolve("dependency-snapshot"))
                Files.exists(destination, NOFOLLOW_LINKS)
            }
        }
    }
}

private fun tryCacheSnapshot(source: Path, destination: Path, copy: Path) {
    try {
        copyDependencyCache(source, destination, copy)
    } catch (failure: java.nio.channels.ClosedByInterruptException) {
        throw failure
    } catch (failure: IOException) {
        recordContainerFilesystemObservation(containerFilesystemObservation(failure))
    } catch (failure: UncheckedIOException) {
        recordContainerFilesystemObservation(containerFilesystemObservation(failure.cause ?: throw failure))
    } finally {
        if (Files.exists(copy, NOFOLLOW_LINKS)) {
            Files.walk(copy).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

private fun trySharedCacheLock(channel: FileChannel, shared: Boolean) =
    try {
        channel.tryLock(0, Long.MAX_VALUE, shared)
    } catch (_: OverlappingFileLockException) {
        null
    }

private fun copyDependencyCache(source: Path, destination: Path, copy: Path) {
    val lock = source.resolve("modules-2.lock")
    if (!Files.isRegularFile(lock, NOFOLLOW_LINKS) || lock.toRealPath() != lock) return
    FileChannel.open(lock, READ, NOFOLLOW_LINKS).use { channel ->
        trySharedCacheLock(channel, shared = true)?.use {
            if (!Files.isDirectory(source.resolve("files-2.1"), NOFOLLOW_LINKS)) return
            val modules = Files.createDirectories(copy.resolve("modules-2"))
            if (copyModuleFiles(source, modules)) Files.move(copy, destination, ATOMIC_MOVE)
        }
    }
}

private fun copyModuleFiles(source: Path, modules: Path): Boolean {
    var copiedArtifact = false
    Files.walk(source).use { paths ->
        var count = 0
        for (path in paths.iterator()) {
            if (++count > MAXIMUM_PROFILE_FILES) return false
            val relative = source.relativize(path)
            when (copyCacheEntry(path, modules.resolve(relative))) {
                CacheEntry.IGNORED -> Unit
                CacheEntry.FILE -> copiedArtifact = copiedArtifact || relative.startsWith("files-2.1")
                CacheEntry.UNSUPPORTED -> return false
            }
        }
    }
    return copiedArtifact
}

private enum class CacheEntry {
    FILE,
    IGNORED,
    UNSUPPORTED,
}

private fun copyCacheEntry(path: Path, target: Path): CacheEntry =
    when {
        excludedModuleEntry(path) -> CacheEntry.IGNORED
        Files.isDirectory(path, NOFOLLOW_LINKS) -> {
            Files.createDirectories(target)
            CacheEntry.IGNORED
        }
        Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
            Files.copy(path, target, NOFOLLOW_LINKS, COPY_ATTRIBUTES)
            CacheEntry.FILE
        }
        else -> CacheEntry.UNSUPPORTED
    }

private fun excludedModuleEntry(path: Path): Boolean {
    val name = path.fileName.toString()
    return name.endsWith(".lock") || name == "gc.properties"
}
