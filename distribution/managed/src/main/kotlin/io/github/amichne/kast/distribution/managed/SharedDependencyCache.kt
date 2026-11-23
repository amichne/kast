package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
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
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DependencyCacheSource {
    SHARED_GRADLE,
    USER_GRADLE,
}

@Serializable
enum class DependencyCacheRejection {
    SOURCE_UNAVAILABLE,
    UNSUPPORTED_WRITE_STATE,
    WRITE_IN_PROGRESS,
    CHANGED_DURING_COPY,
    CAPACITY_EXCEEDED,
    UNSUPPORTED_ENTRY,
    NO_ARTIFACTS,
    FILESYSTEM_REJECTED,
}

@Serializable
data class RejectedDependencyCache(val source: DependencyCacheSource, val reason: DependencyCacheRejection)

@Serializable
sealed interface SharedDependencyCacheOutcome {
    @Serializable @SerialName("ALREADY_PUBLISHED") data object AlreadyPublished : SharedDependencyCacheOutcome

    @Serializable @SerialName("CREATOR_BUSY") data object CreatorBusy : SharedDependencyCacheOutcome

    @Serializable
    @SerialName("PUBLISHED")
    data class Published(val source: DependencyCacheSource) : SharedDependencyCacheOutcome

    @Serializable
    @SerialName("SKIPPED")
    data class Skipped(val rejected: List<RejectedDependencyCache>) : SharedDependencyCacheOutcome
}

/** Publish a snapshot once. A concurrent creator can use the writable cache while the copy proceeds. */
fun prepareSharedDependencyCache(
    home: Path,
    staging: Path,
    localSeed: Path?,
    maximumEntries: Long,
    report: (IOException) -> Unit,
): SharedDependencyCacheOutcome =
    prepareSharedDependencyCache(home, staging, localSeed, report) { source, target ->
        copyModuleFiles(source, target, maximumEntries)
    }

/** The copy effect between two observations of the source generation. */
internal fun interface DependencyCacheCopy {
    fun copy(source: Path, target: Path): Refinement<Unit, DependencyCacheRejection>
}

internal fun prepareSharedDependencyCache(
    home: Path,
    staging: Path,
    localSeed: Path?,
    report: (IOException) -> Unit,
    copyFiles: DependencyCacheCopy,
): SharedDependencyCacheOutcome {
    val destination = home.resolve(ManagedHeadlessGradleHome.READ_ONLY_DIRECTORY)
    if (Files.exists(destination, NOFOLLOW_LINKS)) return SharedDependencyCacheOutcome.AlreadyPublished
    val sources = buildList {
        add(DependencyCacheSource.SHARED_GRADLE to home.resolve("caches/modules-2"))
        if (localSeed != null) add(DependencyCacheSource.USER_GRADLE to localSeed)
    }
    FileChannel.open(home.resolve("kast-dependency-snapshot.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
        val lock = tryCreatorLock(channel) ?: return SharedDependencyCacheOutcome.CreatorBusy
        lock.use {
            return if (Files.exists(destination, NOFOLLOW_LINKS)) SharedDependencyCacheOutcome.AlreadyPublished
            else
                publishFirst(sources) { source ->
                    trySnapshot(source, destination, staging.resolve("dependency-snapshot"), report, copyFiles)
                }
        }
    }
}

private fun publishFirst(
    sources: List<Pair<DependencyCacheSource, Path>>,
    publish: (Path) -> Refinement<Unit, DependencyCacheRejection>,
): SharedDependencyCacheOutcome {
    val rejected = mutableListOf<RejectedDependencyCache>()
    for ((identity, source) in sources) {
        when (val published = publish(source)) {
            is Refinement.Refined -> return SharedDependencyCacheOutcome.Published(identity)
            is Refinement.Rejected -> rejected += RejectedDependencyCache(identity, published.failure)
        }
    }
    return SharedDependencyCacheOutcome.Skipped(rejected)
}

private fun tryCreatorLock(channel: FileChannel) =
    try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }

private fun trySnapshot(
    source: Path,
    destination: Path,
    copy: Path,
    report: (IOException) -> Unit,
    copyFiles: DependencyCacheCopy,
): Refinement<Unit, DependencyCacheRejection> =
    try {
        publishSnapshot(source, destination, copy, copyFiles)
    } catch (failure: java.nio.channels.ClosedByInterruptException) {
        throw failure
    } catch (failure: IOException) {
        report(failure)
        Refinement.Rejected(DependencyCacheRejection.FILESYSTEM_REJECTED)
    } catch (failure: UncheckedIOException) {
        report(failure.cause ?: throw failure)
        Refinement.Rejected(DependencyCacheRejection.FILESYSTEM_REJECTED)
    } finally {
        if (Files.exists(copy, NOFOLLOW_LINKS)) {
            Files.walk(copy).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

/** Only an unchanged generation around the whole copy admits the rename that makes the snapshot visible. */
private fun publishSnapshot(
    source: Path,
    destination: Path,
    copy: Path,
    copyFiles: DependencyCacheCopy,
): Refinement<Unit, DependencyCacheRejection> {
    val before =
        when (val observed = GradleCacheGeneration.read(source)) {
            is Refinement.Rejected -> return observed
            is Refinement.Refined -> observed.value
        }
    if (!Files.isDirectory(source.resolve(ARTIFACTS), NOFOLLOW_LINKS))
        return Refinement.Rejected(DependencyCacheRejection.NO_ARTIFACTS)
    when (val copied = copyFiles.copy(source, Files.createDirectories(copy.resolve("modules-2")))) {
        is Refinement.Rejected -> return copied
        is Refinement.Refined -> Unit
    }
    val after =
        when (val observed = GradleCacheGeneration.read(source)) {
            is Refinement.Rejected -> return observed
            is Refinement.Refined -> observed.value
        }
    if (after != before) return Refinement.Rejected(DependencyCacheRejection.CHANGED_DURING_COPY)
    Files.move(copy, destination, ATOMIC_MOVE)
    return Refinement.Refined(Unit)
}

internal fun copyModuleFiles(
    source: Path,
    modules: Path,
    maximumEntries: Long,
): Refinement<Unit, DependencyCacheRejection> {
    var copiedArtifact = false
    Files.walk(source).use { paths ->
        var count = 0
        for (path in paths.iterator()) {
            if (++count > maximumEntries) return Refinement.Rejected(DependencyCacheRejection.CAPACITY_EXCEEDED)
            val relative = source.relativize(path)
            when (copyCacheEntry(path, modules.resolve(relative))) {
                CacheEntry.IGNORED -> Unit
                CacheEntry.FILE -> copiedArtifact = copiedArtifact || relative.startsWith(ARTIFACTS)
                CacheEntry.UNSUPPORTED -> return Refinement.Rejected(DependencyCacheRejection.UNSUPPORTED_ENTRY)
            }
        }
    }
    return if (copiedArtifact) Refinement.Refined(Unit) else Refinement.Rejected(DependencyCacheRejection.NO_ARTIFACTS)
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

private const val ARTIFACTS = "files-2.1"
