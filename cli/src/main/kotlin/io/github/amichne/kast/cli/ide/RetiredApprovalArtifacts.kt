package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class RetiredApprovalFailure {
    UNSAFE_PATH,
    INVALID_KEY,
    BUSY,
    IDENTITY_CHANGED,
    IO_FAILED,
}

@Serializable
internal sealed interface RetiredApprovalOutcome {
    @Serializable @SerialName("ABSENT") data object Absent : RetiredApprovalOutcome

    @Serializable @SerialName("REMOVED") data object Removed : RetiredApprovalOutcome

    @Serializable
    @SerialName("RETAINED")
    data class Retained(val failure: RetiredApprovalFailure) : RetiredApprovalOutcome
}

private val privateDirectoryMode = PosixFilePermissions.fromString("rwx------")
private val privateFileMode = PosixFilePermissions.fromString("rw-------")
private const val MAXIMUM_KEY_BYTES = 128L

/** Retirement only. This owner never creates directories, locks, keys, or authorization. */
internal class RetiredApprovalArtifacts(private val home: Path) {
    /** The opaque file key stays at this filesystem boundary; ownership retains its typed snapshot. */
    private data class FileIdentity(
        val providerKey: Any,
        val size: Long,
        val modified: java.nio.file.attribute.FileTime,
    )

    private enum class LegacyArtifact(val fileName: String) {
        PRIVATE_KEY("broker.pk8"),
        PUBLIC_KEY("broker.pub"),
        ENROLLMENT_LOCK(".enroll.lock"),
    }

    private data class OwnedDirectory(val path: Path, val providerKey: Any, val private: Boolean)

    private data class OwnedFile(val path: Path, val artifact: LegacyArtifact, val identity: FileIdentity)

    fun remove(): RetiredApprovalOutcome =
        try {
            removeAdmitted()
        } catch (_: java.security.GeneralSecurityException) {
            RetiredApprovalOutcome.Retained(RetiredApprovalFailure.INVALID_KEY)
        } catch (_: java.io.IOException) {
            RetiredApprovalOutcome.Retained(RetiredApprovalFailure.IO_FAILED)
        } catch (_: SecurityException) {
            RetiredApprovalOutcome.Retained(RetiredApprovalFailure.UNSAFE_PATH)
        }

    private fun removeAdmitted(): RetiredApprovalOutcome {
        val directory = home.resolve(".kast/approval")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return RetiredApprovalOutcome.Absent
        if (
            !safeDirectory(home, private = false) ||
                !safeDirectory(home.resolve(".kast"), private = false) ||
                !safeDirectory(directory, private = true)
        )
            return RetiredApprovalOutcome.Retained(RetiredApprovalFailure.UNSAFE_PATH)
        val directories =
            listOf(home to false, home.resolve(".kast") to false, directory to true).map { (path, private) ->
                val key =
                    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                        ?: return RetiredApprovalOutcome.Retained(RetiredApprovalFailure.UNSAFE_PATH)
                OwnedDirectory(path, key, private)
            }
        val admitted = mutableListOf<OwnedFile>()
        for (artifact in LegacyArtifact.entries) {
            val path = directory.resolve(artifact.fileName)
            if (!Files.exists(path, NOFOLLOW_LINKS)) continue
            when (val file = admitFile(path, artifact)) {
                is Refinement.Refined -> admitted += file.value
                is Refinement.Rejected -> return RetiredApprovalOutcome.Retained(file.failure)
            }
        }
        return removeWithLegacyLock(directory, directories, admitted)
    }

    private fun removeWithLegacyLock(
        directory: Path,
        directories: List<OwnedDirectory>,
        admitted: List<OwnedFile>,
    ): RetiredApprovalOutcome {
        val lock =
            admitted.singleOrNull { it.artifact == LegacyArtifact.ENROLLMENT_LOCK }
                ?: return erase(directory, directories, admitted)
        return FileChannel.open(lock.path, WRITE, NOFOLLOW_LINKS).use { channel ->
            val held =
                try {
                    channel.tryLock()
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    null
                }
            if (held == null) RetiredApprovalOutcome.Retained(RetiredApprovalFailure.BUSY)
            else held.use { erase(directory, directories, admitted) }
        }
    }

    private fun safeDirectory(path: Path, private: Boolean): Boolean =
        Files.isDirectory(path, NOFOLLOW_LINKS) &&
            path.toRealPath() == path &&
            (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong() ==
                com.sun.security.auth.module.UnixSystem().uid &&
            if (private) Files.getPosixFilePermissions(path) == privateDirectoryMode
            else
                Files.getPosixFilePermissions(path).none {
                    it == java.nio.file.attribute.PosixFilePermission.GROUP_WRITE ||
                        it == java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
                }

    private fun admitFile(path: Path, artifact: LegacyArtifact): Refinement<OwnedFile, RetiredApprovalFailure> {
        if (
            !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong() !=
                    com.sun.security.auth.module.UnixSystem().uid ||
                Files.getPosixFilePermissions(path) != privateFileMode
        )
            return Refinement.Rejected(RetiredApprovalFailure.UNSAFE_PATH)
        val observed = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val providerKey = observed.fileKey() ?: return Refinement.Rejected(RetiredApprovalFailure.UNSAFE_PATH)
        val identity = FileIdentity(providerKey, observed.size(), observed.lastModifiedTime())
        if (artifact == LegacyArtifact.ENROLLMENT_LOCK) {
            if (observed.size() != 0L) return Refinement.Rejected(RetiredApprovalFailure.UNSAFE_PATH)
        } else {
            if (observed.size() !in 1..MAXIMUM_KEY_BYTES) return Refinement.Rejected(RetiredApprovalFailure.INVALID_KEY)
            val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_KEY_BYTES.toInt() + 1) }
            val factory = KeyFactory.getInstance("Ed25519")
            val canonical =
                when (artifact) {
                    LegacyArtifact.PRIVATE_KEY -> factory.generatePrivate(PKCS8EncodedKeySpec(bytes)).encoded
                    LegacyArtifact.PUBLIC_KEY -> factory.generatePublic(X509EncodedKeySpec(bytes)).encoded
                    LegacyArtifact.ENROLLMENT_LOCK -> return Refinement.Rejected(RetiredApprovalFailure.UNSAFE_PATH)
                }
            if (!canonical.contentEquals(bytes)) return Refinement.Rejected(RetiredApprovalFailure.INVALID_KEY)
        }
        return Refinement.Refined(OwnedFile(path, artifact, identity))
    }

    private fun retainedDirectory(directory: OwnedDirectory): Boolean =
        safeDirectory(directory.path, directory.private) &&
            Files.readAttributes(directory.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() ==
                directory.providerKey

    private fun erase(
        directory: Path,
        directories: List<OwnedDirectory>,
        files: List<OwnedFile>,
    ): RetiredApprovalOutcome {
        if (!directories.all(::retainedDirectory))
            return RetiredApprovalOutcome.Retained(RetiredApprovalFailure.IDENTITY_CHANGED)
        for (file in files) {
            when (val current = admitFile(file.path, file.artifact)) {
                is Refinement.Refined ->
                    if (current.value != file)
                        return RetiredApprovalOutcome.Retained(RetiredApprovalFailure.IDENTITY_CHANGED)
                is Refinement.Rejected -> return RetiredApprovalOutcome.Retained(current.failure)
            }
        }
        for (file in files) {
            if (!directories.all(::retainedDirectory))
                return RetiredApprovalOutcome.Retained(RetiredApprovalFailure.IDENTITY_CHANGED)
            Files.delete(file.path)
        }
        Files.newDirectoryStream(directory).use { entries ->
            if (!entries.iterator().hasNext()) Files.delete(directory)
        }
        return RetiredApprovalOutcome.Removed
    }
}
