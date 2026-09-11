package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.json.*

internal enum class InstallationStateFailure {
    PATH_REJECTED,
    PAYLOAD_REJECTED,
    EPOCH_ABSENT,
    EPOCH_REJECTED,
    WRITE_REJECTED,
}

/** Creates one durable epoch only at coordinator startup. Inspection and frontend attachment do not reset it. */
internal object BrokerInstallationState {
    fun admit(root: Path): Refinement<ThreadBindingOwner.Installation, InstallationStateFailure> {
        val identity =
            when (val payload = identity(root)) {
                is Refinement.Refined -> payload.value
                is Refinement.Rejected -> return payload
            }
        return try {
            val state = root.resolve("state")
            Files.createDirectories(state)
            if (state.toRealPath() != state || !Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) {
                return Refinement.Rejected(InstallationStateFailure.PATH_REJECTED)
            }
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"))
            val epoch = state.resolve("epoch.json")
            if (!Files.exists(epoch, LinkOption.NOFOLLOW_LINKS)) {
                val temporary = Files.createTempFile(state, ".epoch-", ".json")
                try {
                    Files.writeString(
                        temporary,
                        buildJsonObject {
                            put("schemaVersion", 1)
                            put("installation", identity)
                            put("epoch", UUID.randomUUID().toString())
                        }
                            .toString(),
                    )
                    Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
                    java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use {
                        it.force(true)
                    }
                    try {
                        Files.createLink(epoch, temporary)
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        /* Read and validate the winning owner. */
                    }
                    java.nio.channels.FileChannel.open(state, java.nio.file.StandardOpenOption.READ).use {
                        it.force(true)
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            readEpoch(state, identity)
        } catch (_: Exception) {
            Refinement.Rejected(InstallationStateFailure.WRITE_REJECTED)
        }
    }

    /** Passive proof against the physical immutable payload; it never creates state or epochs. */
    fun observe(root: Path): Refinement<ThreadBindingOwner.Installation, InstallationStateFailure> =
        when (val payload = identity(root)) {
            is Refinement.Rejected -> payload
            is Refinement.Refined -> readEpoch(root.resolve("state"), payload.value)
        }

    private fun readEpoch(
        state: Path,
        identity: String,
    ): Refinement<ThreadBindingOwner.Installation, InstallationStateFailure> {
        return try {
            if (
                !Files.readAttributes(
                        state,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    .isDirectory || state.toRealPath() != state
            )
                return Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED)
            val epoch = state.resolve("epoch.json")
            val attributes =
                Files.readAttributes(
                    epoch,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            if (!attributes.isRegularFile || attributes.size() > BrokerOperationalLimits.maximumEpochBytes) {
                return Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED)
            }
            val document =
                Files.newInputStream(epoch, LinkOption.NOFOLLOW_LINKS).use {
                    val bytes = it.readNBytes(BrokerOperationalLimits.maximumEpochBytes + 1)
                    if (bytes.size > BrokerOperationalLimits.maximumEpochBytes)
                        return Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED)
                    Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                }
            if (
                document.keys != setOf("schemaVersion", "installation", "epoch") ||
                    document["schemaVersion"] != JsonPrimitive(1) ||
                    document["installation"] != JsonPrimitive(identity)
            ) {
                return Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED)
            }
            val raw =
                (document["epoch"] as? JsonPrimitive)?.contentOrNull
                    ?: return Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED)
            when (val owner = ThreadBindingOwner.admit(identity, raw)) {
                is Refinement.Refined -> owner
                is Refinement.Rejected -> Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED)
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            Refinement.Rejected(InstallationStateFailure.EPOCH_ABSENT)
        } catch (_: Exception) {
            Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED)
        }
    }

    /** Immutable control payload plus physical installation root; mutable configuration/state are excluded. */
    private fun identity(root: Path): Refinement<String, InstallationStateFailure> {
        return try {
            if (root.toRealPath() != root) return Refinement.Rejected(InstallationStateFailure.PATH_REJECTED)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(root.toString().toByteArray(Charsets.UTF_8))
            var totalBytes = 0L
            var count = 0
            for (directory in listOf("bin", "lib", "share")) {
                val payload = root.resolve(directory)
                if (!Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS)) {
                    return Refinement.Rejected(InstallationStateFailure.PAYLOAD_REJECTED)
                }
                Files.walk(payload).use { paths ->
                    val files =
                        paths.limit(BrokerOperationalLimits.maximumInventoryEntries.toLong() + 1).sorted().toList()
                    if (files.size > BrokerOperationalLimits.maximumInventoryEntries)
                        return Refinement.Rejected(InstallationStateFailure.PAYLOAD_REJECTED)
                    for (file in files) {
                        if (Files.isSymbolicLink(file))
                            return Refinement.Rejected(InstallationStateFailure.PAYLOAD_REJECTED)
                        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue
                        if (
                            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                                ++count > BrokerOperationalLimits.maximumInventoryEntries
                        ) {
                            return Refinement.Rejected(InstallationStateFailure.PAYLOAD_REJECTED)
                        }
                        digest.update(0)
                        digest.update(root.relativize(file).toString().toByteArray(Charsets.UTF_8))
                        digest.update(0)
                        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                            val buffer = ByteArray(64 * 1_024)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                totalBytes += size
                                if (totalBytes > BrokerOperationalLimits.maximumInventoryBytes)
                                    return Refinement.Rejected(InstallationStateFailure.PAYLOAD_REJECTED)
                                digest.update(buffer, 0, size)
                            }
                        }
                    }
                }
            }
            Refinement.Refined("sha256:" + HexFormat.of().formatHex(digest.digest()))
        } catch (_: Exception) {
            Refinement.Rejected(InstallationStateFailure.PAYLOAD_REJECTED)
        }
    }
}
