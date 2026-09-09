package io.github.amichne.kast.distribution.managed.endpoint

import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import com.sun.security.auth.module.UnixSystem

enum class InstalledUpstreamDirectoryFailure {
    PHYSICAL_DIRECTORY_REJECTED,
    UPSTREAM_DIRECTORY_REJECTED,
    RECEIPT_REJECTED,
    FILESYSTEM_REJECTED,
}

@Serializable
@JvmInline
internal value class UpstreamDirectoryDocumentVersion private constructor(
    val value: Int,
) {
    companion object {
        val CURRENT = UpstreamDirectoryDocumentVersion(1)
    }
}

@Serializable
@JvmInline
internal value class RecordedInstalledUpstreamDirectory private constructor(
    val value: String,
) {
    companion object {
        fun from(directory: InstalledPrivateUpstreamDirectory) =
            RecordedInstalledUpstreamDirectory(directory.path.toString())
    }
}

@Serializable
@JvmInline
internal value class RecordedInstalledPhysicalRunDirectory private constructor(
    val value: String,
) {
    companion object {
        fun from(directory: InstalledPhysicalRunDirectory) =
            RecordedInstalledPhysicalRunDirectory(directory.path.toString())
    }
}

@Serializable
internal data class UpstreamDirectoryDocument(
    val schemaVersion: UpstreamDirectoryDocumentVersion,
    val directory: RecordedInstalledUpstreamDirectory,
    val physicalDirectory: RecordedInstalledPhysicalRunDirectory,
    val directoryIdentity: EndpointFileIdentity,
    val physicalDirectoryIdentity: EndpointFileIdentity,
)

/** An absolute, canonical, owner-private directory in Kast's deterministic `/tmp` namespace. */
class InstalledPrivateUpstreamDirectory private constructor(
    val path: Path,
    internal val identity: EndpointFileIdentity,
) {
    internal fun retained(): Boolean = InstalledUpstreamDirectories.directoryIdentity(path) == identity

    internal fun record(): RecordedInstalledUpstreamDirectory = RecordedInstalledUpstreamDirectory.from(this)

    companion object {
        internal fun capture(
            path: Path,
        ): Validation<InstalledPrivateUpstreamDirectory, InstalledUpstreamDirectoryFailure> =
            when (val identity = InstalledUpstreamDirectories.directoryIdentity(path)) {
                null -> Validation.rejected(InstalledUpstreamDirectoryFailure.UPSTREAM_DIRECTORY_REJECTED)
                else -> Validation.validated(InstalledPrivateUpstreamDirectory(path, identity))
            }
    }
}

/** An absolute, canonical, owner-private installed `state/run` directory. */
class InstalledPhysicalRunDirectory private constructor(
    val path: Path,
    internal val identity: EndpointFileIdentity,
) {
    internal fun retained(): Boolean =
        InstalledEndpointAliases.physicalDirectoryIdentity(path) == identity

    internal fun record(): RecordedInstalledPhysicalRunDirectory =
        RecordedInstalledPhysicalRunDirectory.from(this)

    companion object {
        internal fun capture(
            path: Path,
        ): Validation<InstalledPhysicalRunDirectory, InstalledUpstreamDirectoryFailure> =
            if (path.fileName?.toString() != "run" || path.parent?.fileName?.toString() != "state") {
                Validation.rejected(InstalledUpstreamDirectoryFailure.PHYSICAL_DIRECTORY_REJECTED)
            } else {
                when (val identity = InstalledEndpointAliases.physicalDirectoryIdentity(path)) {
                    null -> Validation.rejected(InstalledUpstreamDirectoryFailure.PHYSICAL_DIRECTORY_REJECTED)
                    else -> Validation.validated(InstalledPhysicalRunDirectory(path, identity))
                }
            }
    }
}

/** Exact ownership proof for the real short directory required by Codex's Unix listener. */
class InstalledUpstreamDirectoryReceipt private constructor(
    val directory: InstalledPrivateUpstreamDirectory,
    val physicalDirectory: InstalledPhysicalRunDirectory,
) {
    fun validate(): Validation<InstalledUpstreamDirectoryReceipt, InstalledUpstreamDirectoryFailure> = try {
        if (
            !InstalledUpstreamDirectories.matches(directory, physicalDirectory) ||
            !physicalDirectory.retained() ||
            !directory.retained() ||
            directory.identity.owner != physicalDirectory.identity.owner
        ) {
            Validation.rejected(InstalledUpstreamDirectoryFailure.UPSTREAM_DIRECTORY_REJECTED)
        } else {
            Validation.validated(this)
        }
    } catch (_: IOException) {
        Validation.rejected(InstalledUpstreamDirectoryFailure.FILESYSTEM_REJECTED)
    } catch (_: SecurityException) {
        Validation.rejected(InstalledUpstreamDirectoryFailure.FILESYSTEM_REJECTED)
    }

    internal fun document(): String = Json.encodeToString(
        UpstreamDirectoryDocument(
            schemaVersion = UpstreamDirectoryDocumentVersion.CURRENT,
            directory = directory.record(),
            physicalDirectory = physicalDirectory.record(),
            directoryIdentity = directory.identity,
            physicalDirectoryIdentity = physicalDirectory.identity,
        ),
    )

    companion object {
        internal fun capture(
            directory: InstalledPrivateUpstreamDirectory,
            physicalDirectory: InstalledPhysicalRunDirectory,
        ): Validation<InstalledUpstreamDirectoryReceipt, InstalledUpstreamDirectoryFailure> {
            return InstalledUpstreamDirectoryReceipt(
                directory,
                physicalDirectory,
            ).validate()
        }
    }
}

/** A deterministic, real, owner-only `/tmp` directory used only for Codex's private socket. */
object InstalledUpstreamDirectories {
    private val root = Path.of("/tmp").toRealPath()
    private const val PREFIX = "kast-codex-"
    private val directoryName = Regex("${PREFIX}[a-f0-9]{32}")
    private const val RECEIPT = "upstream-directory.json"
    private val privateDirectory = PosixFilePermissions.fromString("rwx------")
    private val privateFile = PosixFilePermissions.fromString("rw-------")

    fun transportPath(physicalSocket: Path): Path =
        if (physicalSocket.toString().toByteArray(StandardCharsets.UTF_8).size < UNIX_PATH_BYTES) {
            physicalSocket
        } else {
            directoryFor(physicalSocket.parent).resolve(physicalSocket.fileName)
        }

    fun prepare(
        physicalDirectory: Path,
    ): Validation<InstalledUpstreamDirectoryReceipt, InstalledUpstreamDirectoryFailure> = try {
        val physical = when (val captured = InstalledPhysicalRunDirectory.capture(physicalDirectory)) {
            is Validation.Validated -> captured.value
            is Validation.Rejected -> return captured
        }
        val directory = directoryFor(physical.path)
        val receipt = physical.path.resolve(RECEIPT)
        if (Files.exists(directory, NOFOLLOW_LINKS)) return observe(physical)
        if (Files.exists(receipt, NOFOLLOW_LINKS)) {
            return Validation.rejected(InstalledUpstreamDirectoryFailure.RECEIPT_REJECTED)
        }
        val createdIdentity: EndpointFileIdentity
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(privateDirectory))
            Files.setPosixFilePermissions(directory, privateDirectory)
            createdIdentity = InstalledEndpointAliases.identity(directory)
                ?: return Validation.rejected(InstalledUpstreamDirectoryFailure.UPSTREAM_DIRECTORY_REJECTED)
        } catch (_: FileAlreadyExistsException) {
            return observe(physical)
        }
        val upstream = when (val captured = InstalledPrivateUpstreamDirectory.capture(directory)) {
            is Validation.Validated -> captured.value
            is Validation.Rejected -> {
                retireCreatedDirectory(directory, createdIdentity)
                return captured
            }
        }
        val admitted = when (val captured = InstalledUpstreamDirectoryReceipt.capture(upstream, physical)) {
            is Validation.Validated -> captured.value
            is Validation.Rejected -> {
                retireCreatedDirectory(directory, createdIdentity)
                return captured
            }
        }
        try {
            Files.writeString(receipt, admitted.document(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(receipt, privateFile)
        } catch (_: Exception) {
            retireCreatedDirectory(directory, createdIdentity)
            return Validation.rejected(InstalledUpstreamDirectoryFailure.RECEIPT_REJECTED)
        }
        Validation.validated(admitted)
    } catch (_: IOException) {
        Validation.rejected(InstalledUpstreamDirectoryFailure.FILESYSTEM_REJECTED)
    } catch (_: SecurityException) {
        Validation.rejected(InstalledUpstreamDirectoryFailure.FILESYSTEM_REJECTED)
    }

    internal fun matches(
        directory: InstalledPrivateUpstreamDirectory,
        physicalDirectory: InstalledPhysicalRunDirectory,
    ): Boolean = directory.path == directoryFor(physicalDirectory.path)

    internal fun directoryIdentity(path: Path): EndpointFileIdentity? =
        if (
            !path.isAbsolute || path.normalize() != path || path.toRealPath() != path ||
            path.parent != root || !directoryName.matches(path.fileName.toString()) ||
            !Files.isDirectory(path, NOFOLLOW_LINKS) ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != privateDirectory
        ) {
            null
        } else {
            InstalledEndpointAliases.identity(path)?.takeIf { it.owner == UnixSystem().uid }
        }

    private fun observe(
        physicalDirectory: InstalledPhysicalRunDirectory,
    ): Validation<InstalledUpstreamDirectoryReceipt, InstalledUpstreamDirectoryFailure> = try {
        val directoryPath = directoryFor(physicalDirectory.path)
        val directory = when (val captured = InstalledPrivateUpstreamDirectory.capture(directoryPath)) {
            is Validation.Validated -> captured.value
            is Validation.Rejected -> return captured
        }
        val receipt = physicalDirectory.path.resolve(RECEIPT)
        if (
            directory.identity.owner != physicalDirectory.identity.owner ||
            !Files.isRegularFile(receipt, NOFOLLOW_LINKS) ||
            Files.size(receipt) > MAXIMUM_RECEIPT_BYTES ||
            Files.getPosixFilePermissions(receipt, NOFOLLOW_LINKS) != privateFile ||
            InstalledEndpointAliases.identity(receipt)?.owner != physicalDirectory.identity.owner
        ) {
            return Validation.rejected(InstalledUpstreamDirectoryFailure.RECEIPT_REJECTED)
        }
        val bytes = Files.newInputStream(receipt, NOFOLLOW_LINKS).use {
            it.readNBytes(MAXIMUM_RECEIPT_BYTES + 1)
        }
        if (bytes.size > MAXIMUM_RECEIPT_BYTES) {
            return Validation.rejected(InstalledUpstreamDirectoryFailure.RECEIPT_REJECTED)
        }
        val document = Json.decodeFromString<UpstreamDirectoryDocument>(
            bytes.toString(StandardCharsets.UTF_8),
        )
        if (
            document.schemaVersion != UpstreamDirectoryDocumentVersion.CURRENT ||
            document.directory != directory.record() ||
            document.physicalDirectory != physicalDirectory.record() ||
            document.directoryIdentity != directory.identity ||
            document.physicalDirectoryIdentity != physicalDirectory.identity
        ) {
            return Validation.rejected(InstalledUpstreamDirectoryFailure.RECEIPT_REJECTED)
        }
        InstalledUpstreamDirectoryReceipt.capture(directory, physicalDirectory)
    } catch (_: Exception) {
        Validation.rejected(InstalledUpstreamDirectoryFailure.RECEIPT_REJECTED)
    }

    private fun directoryFor(physicalDirectory: Path): Path = root.resolve(
        PREFIX + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(
                physicalDirectory.toString().toByteArray(StandardCharsets.UTF_8),
            ),
        ).take(32),
    )

    private fun retireCreatedDirectory(directory: Path, identity: EndpointFileIdentity) {
        try {
            if (InstalledEndpointAliases.identity(directory) == identity &&
                Files.isDirectory(directory, NOFOLLOW_LINKS) &&
                Files.newDirectoryStream(directory).use { entries -> !entries.iterator().hasNext() }
            ) {
                Files.delete(directory)
            }
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }
    }

    private const val UNIX_PATH_BYTES = 104
    private const val MAXIMUM_RECEIPT_BYTES = 4096
}
