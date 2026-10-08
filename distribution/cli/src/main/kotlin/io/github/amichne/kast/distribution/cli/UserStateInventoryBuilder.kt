package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.evidence.sqlite.SqliteMutationStateInspection
import io.github.amichne.kast.evidence.sqlite.SqliteMutationStateInspectionFailure
import io.github.amichne.kast.evidence.sqlite.SqliteMutationStateInspectionResult
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

// This exception is private control flow at the filesystem effect boundary, never the domain failure protocol.
internal class UserStateInventoryRejected(val failure: UserStateCleanupFailure) : RuntimeException()

internal class UserStateInventoryBuilder(private val home: Path) : AutoCloseable {
    private val homeIdentity: UserStateHomeIdentity by lazy { UserStateHomeIdentity.capture(home) }
    private val artifacts = mutableListOf<UserStateArtifact>()
    private val locks = mutableListOf<Pair<FileChannel, FileLock>>()
    private val deadOwners = mutableListOf<Long>()
    private val settledMutations = mutableListOf<SqliteMutationStateInspectionResult.Settled>()
    private val owner: UserPrincipal by lazy { Files.getOwner(home, NOFOLLOW_LINKS) }

    private fun reject(failure: UserStateCleanupFailure = UserStateCleanupFailure.OWNERSHIP_UNPROVEN): Nothing =
        throw UserStateInventoryRejected(failure)

    fun collect() {
        if (!home.isAbsolute || home.toRealPath() != home) reject()
        if (!homeIdentity.unchanged()) reject()
        val root = home.resolve(".kast")
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        directory(root)
        children(root).forEach { family ->
            when (family.fileName.toString()) {
                "ide-hosted",
                "ide-lifecycle" -> endpoints(family)
                "state" -> durableState(family)
                "approval" -> legacyApproval(family)
                else -> reject()
            }
        }
    }

    private fun capture(path: Path): BasicFileAttributes {
        if (artifacts.size >= MAXIMUM_ARTIFACTS) reject(UserStateCleanupFailure.CAPACITY_EXCEEDED)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (attributes.isSymbolicLink || attributes.fileKey() == null || Files.getOwner(path, NOFOLLOW_LINKS) != owner)
            reject()
        artifacts +=
            UserStateArtifact.capture(
                path,
                attributes,
                owner,
                if (attributes.isDirectory) children(path).toSet() else emptySet(),
            )
        return attributes
    }

    private fun directory(path: Path) {
        if (!capture(path).isDirectory || path.toRealPath() != path) reject()
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        if (
            permissions.any {
                it == java.nio.file.attribute.PosixFilePermission.GROUP_WRITE ||
                    it == java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
            }
        )
            reject()
    }

    private fun regular(path: Path) {
        if (!capture(path).isRegularFile) reject()
    }

    private fun children(path: Path): List<Path> =
        Files.newDirectoryStream(path).use { entries ->
            val result = mutableListOf<Path>()
            for (entry in entries) {
                if (result.size >= MAXIMUM_ARTIFACTS) reject(UserStateCleanupFailure.CAPACITY_EXCEEDED)
                result.add(entry)
            }
            result.sortedBy { it.fileName.toString() }
        }

    private fun acquire(path: Path) {
        val channel = FileChannel.open(path, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
        val lock =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
        if (lock == null) {
            channel.close()
            reject(UserStateCleanupFailure.ACTIVE_HOST)
        }
        locks += channel to lock
    }

    private fun endpoints(family: Path) {
        directory(family)
        children(family).forEach(::endpoint)
    }

    private fun endpoint(endpoint: Path) {
        if (!endpoint.fileName.toString().matches(Regex("[0-9a-f]{32}"))) reject()
        directory(endpoint)
        val files = children(endpoint)
        if (files.isEmpty()) return
        if (files.any { it.fileName.toString() !in ENDPOINT_FILES }) reject()
        val lock = endpoint.resolve("owner.lock")
        if (lock !in files) reject()
        regular(lock)
        if (Files.size(lock) != 0L) reject()
        acquire(lock)
        val descriptor = endpoint.resolve("endpoint.json")
        val socket = endpoint.resolve("host.sock")
        if (descriptor in files) retainEndpointOwner(descriptor, endpoint, socket) else if (socket in files) reject()
        if (socket in files && !capture(socket).isOther) reject()
    }

    private fun retainEndpointOwner(descriptor: Path, endpoint: Path, socket: Path) {
        regular(descriptor)
        if (Files.size(descriptor) !in 1..MAXIMUM_DESCRIPTOR_BYTES) reject()
        val value =
            try {
                managementJson.decodeFromString<RetiredEndpointDescriptor>(readDescriptor(descriptor))
            } catch (_: SerializationException) {
                reject()
            }
        if (
            !validEndpointProtocol(value) ||
                !validEndpointAddress(value, endpoint, socket) ||
                !validHostIdentity(value.host)
        )
            reject()
        if (ProcessHandle.of(value.hostPid).isPresent) reject(UserStateCleanupFailure.ACTIVE_HOST)
        deadOwners += value.hostPid
    }

    private fun validEndpointProtocol(value: RetiredEndpointDescriptor): Boolean =
        value.type == "KAST_IDE_ENDPOINT" && value.protocol in 1..LATEST_ENDPOINT_PROTOCOL && value.hostPid > 0

    private fun validEndpointAddress(value: RetiredEndpointDescriptor, endpoint: Path, socket: Path): Boolean =
        value.socket == socket.toString() &&
            canonicalRoot(value.root) &&
            rootHash(value.root).take(ENDPOINT_HASH_LENGTH) == endpoint.fileName.toString()

    private fun validHostIdentity(host: String): Boolean =
        try {
            UUID.fromString(host).toString() == host
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun durableState(state: Path) {
        directory(state)
        val roots = children(state)
        if (roots.any { it.fileName.toString() != "workspaces" }) reject()
        roots.forEach(::workspaceStates)
    }

    private fun workspaceStates(workspaces: Path) {
        directory(workspaces)
        children(workspaces).forEach(::workspaceState)
    }

    private fun workspaceState(workspace: Path) {
        if (!workspace.fileName.toString().matches(Regex("[0-9a-f]{64}"))) reject()
        directory(workspace)
        children(workspace).forEach(::databaseFile)
    }

    private fun databaseFile(file: Path) {
        val name = file.fileName.toString()
        if (name !in DATABASE_FILES) reject()
        regular(file)
        val base = name.removeSuffix("-wal").removeSuffix("-shm")
        if (!Files.isRegularFile(file.parent.resolve(base), NOFOLLOW_LINKS)) reject()
        if (name != base) return
        val header = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(SQLITE_HEADER.size) }
        if (!header.contentEquals(SQLITE_HEADER)) reject()
        if (name == "topology.sqlite") reject(UserStateCleanupFailure.SCHEMA_REJECTED)
        when (val inspection = SqliteMutationStateInspection.inspect(file, file.parent.fileName.toString())) {
            is SqliteMutationStateInspectionResult.Settled -> settledMutations.add(inspection)
            is SqliteMutationStateInspectionResult.Rejected -> reject(inspection.failure.userStateFailure())
        }
    }

    private fun legacyApproval(path: Path) {
        directory(path)
        if (Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != PRIVATE_DIRECTORY_MODE) reject()
        children(path).forEach(::legacyApprovalFile)
    }

    private fun legacyApprovalFile(file: Path) {
        if (file.fileName.toString() !in LEGACY_APPROVAL_FILES) reject()
        regular(file)
        if (Files.getPosixFilePermissions(file, NOFOLLOW_LINKS) != PRIVATE_FILE_MODE) reject()
        if (file.fileName.toString() == ".enroll.lock") {
            if (Files.size(file) != 0L) reject()
            acquire(file)
        } else validateLegacyKey(file)
    }

    private fun validateLegacyKey(file: Path) {
        val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_KEY_BYTES + 1) }
        if (bytes.isEmpty() || bytes.size > MAXIMUM_KEY_BYTES) reject()
        val key =
            try {
                val factory = java.security.KeyFactory.getInstance("Ed25519")
                when (file.fileName.toString()) {
                    "broker.pk8" -> factory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(bytes))
                    "broker.pub" -> factory.generatePublic(java.security.spec.X509EncodedKeySpec(bytes))
                    else -> reject()
                }
            } catch (_: java.security.GeneralSecurityException) {
                reject()
            }
        if (!bytes.contentEquals(key.encoded)) reject()
    }

    private fun readDescriptor(path: Path): String {
        val bytes =
            Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_DESCRIPTOR_BYTES.toInt() + 1) }
        if (bytes.size > MAXIMUM_DESCRIPTOR_BYTES) reject(UserStateCleanupFailure.CAPACITY_EXCEEDED)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }

    fun finish(): UserStateInventory =
        UserStateInventory(
            homeIdentity,
            artifacts.toList(),
            locks.toList(),
            deadOwners.toList(),
            settledMutations.toList(),
        )

    override fun close() {
        locks.asReversed().forEach { (channel, lock) ->
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    companion object {
        private const val MAXIMUM_ARTIFACTS = 4096
        private const val LATEST_ENDPOINT_PROTOCOL = 3
        private const val ENDPOINT_HASH_LENGTH = 32
        private const val MAXIMUM_KEY_BYTES = 128
        private val ENDPOINT_FILES = setOf("owner.lock", "endpoint.json", "host.sock")
        private val LEGACY_APPROVAL_FILES = setOf("broker.pk8", "broker.pub", ".enroll.lock")
        private val PRIVATE_DIRECTORY_MODE = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        private val PRIVATE_FILE_MODE = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        private const val MAXIMUM_DESCRIPTOR_BYTES = 65536L
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val DATABASE_FILES =
            setOf(
                "mutation.sqlite",
                "mutation.sqlite-wal",
                "mutation.sqlite-shm",
                "topology.sqlite",
                "topology.sqlite-wal",
                "topology.sqlite-shm",
            )

        private fun rootHash(root: String): String =
            java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(root.toByteArray(Charsets.UTF_8)))

        private fun canonicalRoot(root: String): Boolean =
            try {
                Path.of(root).let { it.isAbsolute && it.normalize().toString() == root }
            } catch (_: java.nio.file.InvalidPathException) {
                false
            }
    }
}

@Serializable
private data class RetiredEndpointDescriptor(
    val type: String,
    val protocol: Int,
    val root: String,
    val socket: String,
    val hostPid: Long,
    val host: String,
    val querySchema: String,
    val operations: List<String>,
)

private fun SqliteMutationStateInspectionFailure.userStateFailure(): UserStateCleanupFailure =
    when (this) {
        SqliteMutationStateInspectionFailure.PATH_REJECTED -> UserStateCleanupFailure.PATH_REJECTED
        SqliteMutationStateInspectionFailure.SCHEMA_REJECTED -> UserStateCleanupFailure.SCHEMA_REJECTED
        SqliteMutationStateInspectionFailure.CAPACITY_EXCEEDED -> UserStateCleanupFailure.CAPACITY_EXCEEDED
        SqliteMutationStateInspectionFailure.RECORD_REJECTED -> UserStateCleanupFailure.RECORD_REJECTED
        SqliteMutationStateInspectionFailure.WORKSPACE_MISMATCH -> UserStateCleanupFailure.WORKSPACE_MISMATCH
        SqliteMutationStateInspectionFailure.UNSETTLED_MUTATION -> UserStateCleanupFailure.UNSETTLED_MUTATION
        SqliteMutationStateInspectionFailure.CHECKPOINT_REQUIRED -> UserStateCleanupFailure.CHECKPOINT_REQUIRED
        SqliteMutationStateInspectionFailure.STORAGE_UNAVAILABLE -> UserStateCleanupFailure.STORAGE_UNAVAILABLE
    }
