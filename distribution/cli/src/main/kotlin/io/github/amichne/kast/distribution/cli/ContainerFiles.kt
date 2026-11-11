package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val containerJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
internal val containerDirectoryMode = PosixFilePermissions.fromString("rwx------")
internal const val CONTAINER_RECORD_BYTES = 65_536

/** Expected adapter failures cross this boundary once; defects and interruption are not converted to success. */
internal inline fun <T> containerFiles(
    observe: (ContainerFilesystemObservation) -> Unit = ::recordContainerFilesystemObservation,
    action: () -> Refinement<T, ContainerFailure>,
): Refinement<T, ContainerFailure> =
    try {
        action()
    } catch (_: java.util.zip.ZipException) {
        Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
    } catch (failure: IOException) {
        observe(containerFilesystemObservation(failure))
        Refinement.Rejected(ContainerFailure.FILESYSTEM_UNAVAILABLE)
    } catch (_: SecurityException) {
        Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    } catch (_: SerializationException) {
        Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    } catch (_: IllegalArgumentException) {
        Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }

internal fun privateContainerDirectory(path: Path): Refinement<Path, ContainerFailure> {
    Files.createDirectories(path, PosixFilePermissions.asFileAttribute(containerDirectoryMode))
    return if (
        path.toRealPath() != path ||
            Files.getOwner(path) != Files.getOwner(Path.of(System.getProperty("user.home"))) ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != containerDirectoryMode
    )
        Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    else Refinement.Refined(path)
}

internal inline fun <reified T> readContainerRecord(
    path: Path,
    limit: Int = CONTAINER_RECORD_BYTES,
): Refinement<T, ContainerFailure> {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path)
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
    return if (bytes.size > limit) Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    else Refinement.Refined(containerJson.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true)))
}

internal inline fun <reified T> writeContainerRecord(path: Path, record: T) {
    Files.writeString(
        path,
        containerJson.encodeToString(record) + "\n",
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
}

internal fun <T> withContainerLock(
    root: Path,
    action: () -> Refinement<T, ContainerFailure>,
): Refinement<T, ContainerFailure> = containerFiles {
    Files.createDirectories(root, PosixFilePermissions.asFileAttribute(containerDirectoryMode))
    val mode = Files.getPosixFilePermissions(root, NOFOLLOW_LINKS)
    val sharedWrite =
        setOf(
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
        )
    if (
        root.toRealPath() != root ||
            Files.getOwner(root) != Files.getOwner(Path.of(System.getProperty("user.home"))) ||
            mode.any(sharedWrite::contains)
    ) {
        return@containerFiles Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    }
    val lock = root.resolve("container.lock")
    FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
        val lease =
            try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
        if (lease == null) Refinement.Rejected(ContainerFailure.BUSY) else lease.use { action() }
    }
}
