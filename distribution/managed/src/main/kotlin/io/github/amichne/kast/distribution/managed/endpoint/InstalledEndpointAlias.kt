package io.github.amichne.kast.distribution.managed.endpoint

import com.sun.security.auth.module.UnixSystem
import io.github.amichne.kast.kernel.Validation
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class InstalledEndpointAliasFailure {
    PHYSICAL_DIRECTORY_REJECTED,
    ALIAS_REJECTED,
    RECEIPT_REJECTED,
    FILESYSTEM_REJECTED,
}

@Serializable internal data class EndpointFileIdentity(val device: Long, val inode: Long, val owner: Long)

@Serializable
internal data class EndpointAliasDocument(
    val schemaVersion: Int,
    val alias: String,
    val target: String,
    val aliasIdentity: EndpointFileIdentity,
    val targetIdentity: EndpointFileIdentity,
)

/** An exact kernel identity receipt; callers retain it rather than trusting a symlink again. */
class InstalledEndpointAliasReceipt
private constructor(
    val alias: Path,
    val physicalDirectory: Path,
    private val aliasIdentity: EndpointFileIdentity,
    private val targetIdentity: EndpointFileIdentity,
) {
    fun validate(): Validation<InstalledEndpointAliasReceipt, InstalledEndpointAliasFailure> =
        try {
            if (
                !InstalledEndpointAliases.matches(alias, physicalDirectory) ||
                    physicalDirectory.fileName.toString() != "run" ||
                    physicalDirectory.parent.fileName.toString() != "state" ||
                    InstalledEndpointAliases.physicalDirectoryIdentity(physicalDirectory) != targetIdentity ||
                    !Files.isSymbolicLink(alias) ||
                    Files.readSymbolicLink(alias) != physicalDirectory ||
                    InstalledEndpointAliases.identity(alias) != aliasIdentity ||
                    aliasIdentity.owner != targetIdentity.owner
            )
                Validation.rejected(InstalledEndpointAliasFailure.ALIAS_REJECTED)
            else Validation.validated(this)
        } catch (_: IOException) {
            Validation.rejected(InstalledEndpointAliasFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            Validation.rejected(InstalledEndpointAliasFailure.FILESYSTEM_REJECTED)
        }

    companion object {
        internal fun capture(
            alias: Path,
            physicalDirectory: Path,
        ): Validation<InstalledEndpointAliasReceipt, InstalledEndpointAliasFailure> =
            try {
                val physical =
                    InstalledEndpointAliases.physicalDirectoryIdentity(physicalDirectory)
                        ?: return Validation.rejected(InstalledEndpointAliasFailure.PHYSICAL_DIRECTORY_REJECTED)
                val link =
                    InstalledEndpointAliases.identity(alias)
                        ?: return Validation.rejected(InstalledEndpointAliasFailure.ALIAS_REJECTED)
                InstalledEndpointAliasReceipt(alias, physicalDirectory, link, physical).validate()
            } catch (_: IOException) {
                Validation.rejected(InstalledEndpointAliasFailure.FILESYSTEM_REJECTED)
            } catch (_: SecurityException) {
                Validation.rejected(InstalledEndpointAliasFailure.FILESYSTEM_REJECTED)
            }

        internal fun read(
            document: EndpointAliasDocument
        ): Validation<InstalledEndpointAliasReceipt, InstalledEndpointAliasFailure> =
            InstalledEndpointAliasReceipt(
                    Path.of(document.alias),
                    Path.of(document.target),
                    document.aliasIdentity,
                    document.targetIdentity,
                )
                .validate()
    }

    internal fun document(): String =
        Json.encodeToString(
            EndpointAliasDocument(
                1,
                alias.toString(),
                physicalDirectory.toString(),
                aliasIdentity,
                targetIdentity,
            )
        )
}

/** Only this reserved, deterministic alias namespace admits noncanonical socket parents. */
object InstalledEndpointAliases {
    private val root = Path.of("/tmp")
    private const val PREFIX = "kast-uds-"
    private const val RECEIPT = "endpoint-alias.json"
    private val privateDirectory = PosixFilePermissions.fromString("rwx------")
    private val privateFile = PosixFilePermissions.fromString("rw-------")

    fun transportPath(physicalSocket: Path): Path =
        if (physicalSocket.toString().toByteArray(StandardCharsets.UTF_8).size < 104) physicalSocket
        else aliasFor(physicalSocket.parent).resolve(physicalSocket.fileName)

    private fun aliasFor(directory: Path): Path =
        root.resolve(
            PREFIX +
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(directory.toString().toByteArray(StandardCharsets.UTF_8))
                    )
                    .take(32)
        )

    internal fun matches(alias: Path, directory: Path): Boolean = alias == aliasFor(directory)

    fun isReservedSocket(path: Path): Boolean =
        path.parent?.parent == root &&
            path.parent.fileName.toString().matches(Regex("kast-uds-[a-f0-9]{32}")) &&
            (path.fileName.toString() in setOf("c.sock", "u.sock") ||
                path.fileName.toString().matches(Regex("kast-(?:[a-f0-9]{24}|[A-Za-z0-9_-]{43})\\.sock")))

    fun prepare(directory: Path): Validation<InstalledEndpointAliasReceipt, InstalledEndpointAliasFailure> =
        try {
            if (
                physicalDirectoryIdentity(directory) == null ||
                    directory.fileName.toString() != "run" ||
                    directory.parent.fileName.toString() != "state"
            )
                return Validation.rejected(InstalledEndpointAliasFailure.PHYSICAL_DIRECTORY_REJECTED)
            val alias = aliasFor(directory)
            val receipt = directory.resolve(RECEIPT)
            if (Files.exists(alias, NOFOLLOW_LINKS)) return observe(alias.resolve("c.sock"))
            // An old receipt without its alias is an incomplete ownership state, not permission to replace it.
            if (Files.exists(receipt, NOFOLLOW_LINKS))
                return Validation.rejected(InstalledEndpointAliasFailure.RECEIPT_REJECTED)
            try {
                Files.createSymbolicLink(alias, directory)
            } catch (_: FileAlreadyExistsException) {
                return observe(alias.resolve("c.sock"))
            }
            val admitted =
                when (val observed = InstalledEndpointAliasReceipt.capture(alias, directory)) {
                    is Validation.Validated -> observed.value
                    is Validation.Rejected -> return observed
                }
            try {
                Files.writeString(receipt, admitted.document(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                Files.setPosixFilePermissions(receipt, privateFile)
            } catch (_: Exception) {
                // Delete only the exact alias created here, never a replacement or its target.
                if (admitted.validate() is Validation.Validated) Files.delete(alias)
                return Validation.rejected(InstalledEndpointAliasFailure.RECEIPT_REJECTED)
            }
            Validation.validated(admitted)
        } catch (_: IOException) {
            Validation.rejected(InstalledEndpointAliasFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            Validation.rejected(InstalledEndpointAliasFailure.FILESYSTEM_REJECTED)
        }

    fun observe(socket: Path): Validation<InstalledEndpointAliasReceipt, InstalledEndpointAliasFailure> =
        try {
            if (!isReservedSocket(socket)) return Validation.rejected(InstalledEndpointAliasFailure.ALIAS_REJECTED)
            val alias = socket.parent
            if (!Files.isSymbolicLink(alias)) return Validation.rejected(InstalledEndpointAliasFailure.ALIAS_REJECTED)
            val target = Files.readSymbolicLink(alias)
            if (
                !target.isAbsolute ||
                    target.normalize() != target ||
                    aliasFor(target) != alias ||
                    target.fileName.toString() != "run" ||
                    target.parent.fileName.toString() != "state"
            )
                return Validation.rejected(InstalledEndpointAliasFailure.ALIAS_REJECTED)
            val physicalIdentity =
                physicalDirectoryIdentity(target)
                    ?: return Validation.rejected(InstalledEndpointAliasFailure.PHYSICAL_DIRECTORY_REJECTED)
            val receipt = target.resolve(RECEIPT)
            if (
                !Files.isRegularFile(receipt, NOFOLLOW_LINKS) ||
                    Files.size(receipt) > 4096 ||
                    Files.getPosixFilePermissions(receipt, NOFOLLOW_LINKS) != privateFile ||
                    identity(receipt)?.owner != physicalIdentity.owner
            )
                return Validation.rejected(InstalledEndpointAliasFailure.RECEIPT_REJECTED)
            val bytes = Files.newInputStream(receipt, NOFOLLOW_LINKS).use { it.readNBytes(4097) }
            if (bytes.size > 4096) return Validation.rejected(InstalledEndpointAliasFailure.RECEIPT_REJECTED)
            val document = Json.decodeFromString<EndpointAliasDocument>(bytes.toString(StandardCharsets.UTF_8))
            if (
                document.schemaVersion != 1 ||
                    document.alias != alias.toString() ||
                    document.target != target.toString()
            )
                return Validation.rejected(InstalledEndpointAliasFailure.RECEIPT_REJECTED)
            InstalledEndpointAliasReceipt.read(document)
        } catch (_: Exception) {
            Validation.rejected(InstalledEndpointAliasFailure.RECEIPT_REJECTED)
        }

    internal fun identity(path: Path): EndpointFileIdentity? {
        val values = Files.readAttributes(path, "unix:dev,ino,uid", NOFOLLOW_LINKS)
        val device = (values["dev"] as? Number)?.toLong() ?: return null
        val inode = (values["ino"] as? Number)?.toLong() ?: return null
        val owner = (values["uid"] as? Number)?.toLong() ?: return null
        return EndpointFileIdentity(device, inode, owner)
    }

    internal fun physicalDirectoryIdentity(path: Path): EndpointFileIdentity? =
        if (
            !path.isAbsolute ||
                path.normalize() != path ||
                path.toRealPath() != path ||
                !Files.isDirectory(path, NOFOLLOW_LINKS) ||
                Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != privateDirectory
        )
            null
        else identity(path)?.takeIf { it.owner == UnixSystem().uid }
}
