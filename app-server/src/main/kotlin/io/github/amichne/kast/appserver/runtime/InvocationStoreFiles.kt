package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** All paths descend from one admitted private store. File and directory checks precede each effect. */
internal class InvocationStoreFiles private constructor(val root: Path, private val owner: UserPrincipal) {
    fun createStage(): Path =
        Files.createTempDirectory(root, ".migration-", PosixFilePermissions.asFileAttribute(directoryMode))

    fun publishStage(stage: Path, target: Path) {
        requireDirectory(stage)
        Files.move(stage, target, ATOMIC_MOVE)
        force(root)
    }

    fun directory(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(directoryMode))
            force(path.parent)
        }
        requireDirectory(path)
    }

    fun requireDirectory(path: Path) {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path)
            throw IOException("Directory path rejected")
        if (
            Files.getOwner(path, NOFOLLOW_LINKS) != owner ||
                Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != directoryMode
        )
            throw IOException("Private directory rejected")
    }

    fun <T> read(path: Path, serializer: KSerializer<T>, maximumBytes: Int): T {
        requireDirectory(path.parent)
        requireFile(path)
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(maximumBytes + 1) }
        if (bytes.size > maximumBytes) throw IOException("Record bound exceeded")
        val text = bytes.toString(Charsets.UTF_8)
        val decoded = json.decodeFromString(serializer, text)
        // Owner-written records have one canonical shape. This also rejects duplicate keys and lossy UTF-8.
        if (
            !(json.encodeToString(serializer, decoded) + "\n").toByteArray(Charsets.UTF_8).contentEquals(bytes) &&
                !json.encodeToString(serializer, decoded).toByteArray(Charsets.UTF_8).contentEquals(bytes)
        )
            throw IOException("Noncanonical record rejected")
        return decoded
    }

    fun <T> write(path: Path, serializer: KSerializer<T>, document: T) {
        requireDirectory(path.parent)
        if (Files.exists(path, NOFOLLOW_LINKS)) requireFile(path)
        val bytes = (json.encodeToString(serializer, document) + "\n").toByteArray(Charsets.UTF_8)
        val temporary =
            Files.createTempFile(path.parent, ".record-", ".tmp", PosixFilePermissions.asFileAttribute(fileMode))
        try {
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            force(path.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun <T> locked(block: () -> Refinement<T, InvocationFenceFailure>): Refinement<T, InvocationFenceFailure> =
        guarded {
            requireDirectory(root)
            val lockPath = root.resolve(".lock")
            if (!Files.exists(lockPath, NOFOLLOW_LINKS)) {
                try {
                    Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(fileMode))
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    /* Another opener established the same lock. */
                }
            }
            requireFile(lockPath)
            FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) Refinement.Rejected(InvocationFenceFailure.STORE_BUSY) else lock.use { block() }
            }
        }

    private fun requireFile(path: Path) {
        if (
            !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                Files.getOwner(path, NOFOLLOW_LINKS) != owner ||
                Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != fileMode
        )
            throw IOException("Private file rejected")
    }

    companion object {
        private val directoryMode = PosixFilePermissions.fromString("rwx------")
        private val fileMode = PosixFilePermissions.fromString("rw-------")
        private val json = Json { encodeDefaults = true }

        fun open(root: Path): Refinement<InvocationStoreFiles, InvocationFenceFailure> = guarded {
            if (!root.isAbsolute || root.normalize() != root || root.parent.toRealPath() != root.parent)
                return@guarded Refinement.Rejected(InvocationFenceFailure.STORE_REJECTED)
            val files = InvocationStoreFiles(root, Files.getOwner(root.parent, NOFOLLOW_LINKS))
            files.requireDirectory(root.parent)
            files.directory(root)
            Refinement.Refined(files)
        }

        fun force(directory: Path) {
            FileChannel.open(directory, READ, NOFOLLOW_LINKS).use { it.force(true) }
        }

        internal fun <T> guarded(
            block: () -> Refinement<T, InvocationFenceFailure>
        ): Refinement<T, InvocationFenceFailure> =
            try {
                block()
            } catch (_: IOException) {
                Refinement.Rejected(InvocationFenceFailure.STORE_REJECTED)
            } catch (_: SerializationException) {
                Refinement.Rejected(InvocationFenceFailure.STORE_REJECTED)
            } catch (_: SecurityException) {
                Refinement.Rejected(InvocationFenceFailure.STORE_REJECTED)
            } catch (_: UnsupportedOperationException) {
                Refinement.Rejected(InvocationFenceFailure.STORE_REJECTED)
            }
    }
}
