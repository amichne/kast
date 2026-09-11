package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.boundaryExit
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class BrokerTrustFailure {
    UNAVAILABLE,
    UNSAFE_PATH,
    INCOMPLETE_KEYS,
    INVALID_KEYS,
    BUSY,
    IO_FAILED,
}

internal enum class BrokerTrustStatus {
    ENROLLED,
    PRESERVED,
}

internal sealed interface BrokerTrustResult {
    data class Complete(val status: BrokerTrustStatus) : BrokerTrustResult

    data class Rejected(val failure: BrokerTrustFailure) : BrokerTrustResult
}

internal fun interface BrokerTrustRegistrar {
    fun enroll(): BrokerTrustResult

    data object Unavailable : BrokerTrustRegistrar {
        override fun enroll() = BrokerTrustResult.Rejected(BrokerTrustFailure.UNAVAILABLE)
    }
}

private const val MAXIMUM_KEY_BYTES = 128
private val privateDirectoryMode = PosixFilePermissions.fromString("rwx------")
private val privateFileMode = PosixFilePermissions.fromString("rw-------")

/** Explicit local enrollment is the only key creation boundary. Apply never calls it. */
internal class FilesystemBrokerTrustRegistrar(private val home: Path) : BrokerTrustRegistrar {
    override fun enroll(): BrokerTrustResult =
        try {
            when (val admitted = admitTrustDirectory()) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> enrollWithLock(admitted.value)
                is io.github.amichne.kast.kernel.Refinement.Rejected -> rejected(admitted.failure)
            }
        } catch (_: java.security.GeneralSecurityException) {
            rejected(BrokerTrustFailure.INVALID_KEYS)
        } catch (_: java.io.IOException) {
            rejected(BrokerTrustFailure.IO_FAILED)
        } catch (_: SecurityException) {
            rejected(BrokerTrustFailure.UNSAFE_PATH)
        } catch (_: UnsupportedOperationException) {
            rejected(BrokerTrustFailure.UNAVAILABLE)
        }

    private class PrivateDirectory(val path: Path, val owner: java.nio.file.attribute.UserPrincipal)

    private fun admitTrustDirectory(): io.github.amichne.kast.kernel.Refinement<PrivateDirectory, BrokerTrustFailure> {
        val unsafe = io.github.amichne.kast.kernel.Refinement.Rejected(BrokerTrustFailure.UNSAFE_PATH)
        if (!home.isAbsolute || home.toRealPath() != home) return unsafe
        val owner = Files.getOwner(home)
        val parent = home.resolve(".kast")
        when (val admitted = prepareParent(parent, owner)) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> Unit
            is io.github.amichne.kast.kernel.Refinement.Rejected -> return admitted
        }
        val directory = parent.resolve("approval")
        if (!Files.exists(directory, NOFOLLOW_LINKS))
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(privateDirectoryMode))
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || directory.toRealPath() != directory) return unsafe
        if (Files.getOwner(directory) != owner || Files.getPosixFilePermissions(directory) != privateDirectoryMode)
            return unsafe
        return io.github.amichne.kast.kernel.Refinement.Refined(PrivateDirectory(directory, owner))
    }

    private fun prepareParent(
        parent: Path,
        owner: java.nio.file.attribute.UserPrincipal,
    ): io.github.amichne.kast.kernel.Refinement<Unit, BrokerTrustFailure> {
        val unsafe = io.github.amichne.kast.kernel.Refinement.Rejected(BrokerTrustFailure.UNSAFE_PATH)
        if (!Files.exists(parent, NOFOLLOW_LINKS))
            Files.createDirectory(parent, PosixFilePermissions.asFileAttribute(privateDirectoryMode))
        if (
            !Files.isDirectory(parent, NOFOLLOW_LINKS) ||
                parent.toRealPath() != parent ||
                Files.getOwner(parent) != owner
        )
            return unsafe
        val permissions = Files.getPosixFilePermissions(parent)
        if (
            permissions.any { permission ->
                permission in
                    setOf(
                        java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
                    )
            }
        )
            return unsafe
        return io.github.amichne.kast.kernel.Refinement.Refined(Unit)
    }

    private fun enrollWithLock(directory: PrivateDirectory): BrokerTrustResult {
        val lockPath = directory.path.resolve(".enroll.lock")
        if (!Files.exists(lockPath, NOFOLLOW_LINKS)) {
            try {
                Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(privateFileMode))
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                /* Concurrent enrollment uses the same lock. */
            }
        }
        if (!privateFile(lockPath, directory.owner)) return rejected(BrokerTrustFailure.UNSAFE_PATH)
        return FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock =
                try {
                    channel.tryLock()
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    null
                }
            if (lock == null) return rejected(BrokerTrustFailure.BUSY)
            lock.use { enrollLocked(directory) }
        }
    }

    private fun enrollLocked(directory: PrivateDirectory): BrokerTrustResult {
        val privatePath = directory.path.resolve("broker.pk8")
        val publicPath = directory.path.resolve("broker.pub")
        val existing = listOf(privatePath, publicPath).count { Files.exists(it, NOFOLLOW_LINKS) }
        return when (existing) {
            0 -> createKeys(privatePath, publicPath)
            2 -> preserveKeys(privatePath, publicPath, directory.owner)
            else -> rejected(BrokerTrustFailure.INCOMPLETE_KEYS)
        }
    }

    private fun preserveKeys(
        privatePath: Path,
        publicPath: Path,
        owner: java.nio.file.attribute.UserPrincipal,
    ): BrokerTrustResult {
        for (path in listOf(privatePath, publicPath)) {
            if (!privateFile(path, owner) || Files.size(path) !in 1..MAXIMUM_KEY_BYTES)
                return rejected(BrokerTrustFailure.UNSAFE_PATH)
        }
        val factory = KeyFactory.getInstance("Ed25519")
        val privateBytes =
            Files.newInputStream(privatePath, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_KEY_BYTES + 1) }
        val publicBytes = Files.newInputStream(publicPath, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_KEY_BYTES + 1) }
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val publicKey = factory.generatePublic(X509EncodedKeySpec(publicBytes))
        if (!privateKey.encoded.contentEquals(privateBytes) || !publicKey.encoded.contentEquals(publicBytes))
            return rejected(BrokerTrustFailure.INVALID_KEYS)
        val challenge = "kast-explicit-broker-enrollment-v1".toByteArray(Charsets.UTF_8)
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(privateKey)
                update(challenge)
                sign()
            }
        val valid =
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(challenge)
                verify(signature)
            }
        return if (valid) BrokerTrustResult.Complete(BrokerTrustStatus.PRESERVED)
        else rejected(BrokerTrustFailure.INVALID_KEYS)
    }

    private fun createKeys(privatePath: Path, publicPath: Path): BrokerTrustResult {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        for ((path, bytes) in listOf(privatePath to pair.private.encoded, publicPath to pair.public.encoded)) {
            FileChannel.open(
                    path,
                    setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(privateFileMode),
                )
                .use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
        }
        return BrokerTrustResult.Complete(BrokerTrustStatus.ENROLLED)
    }

    private fun privateFile(path: Path, owner: java.nio.file.attribute.UserPrincipal): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            Files.getOwner(path) == owner &&
            Files.getPosixFilePermissions(path) == privateFileMode

    private fun rejected(failure: BrokerTrustFailure) = BrokerTrustResult.Rejected(failure)
}

internal fun executeBrokerTrustEnrollment(registrar: BrokerTrustRegistrar): CliExit =
    when (val result = registrar.enroll()) {
        is BrokerTrustResult.Complete ->
            CliExit.Complete(
                CliJsonDocument.generated(JsonObject.serializer())
                    .create(
                        buildJsonObject {
                            put("outcome", "complete")
                            put("operation", "ide-trust-broker")
                            put("status", result.status.name)
                        }
                    )
            )
        is BrokerTrustResult.Rejected ->
            boundaryExit(
                CliBoundaryExitStatus.RUNTIME,
                "ide-trust-${result.failure.name.lowercase().replace('_', '-')}",
            )
    }
