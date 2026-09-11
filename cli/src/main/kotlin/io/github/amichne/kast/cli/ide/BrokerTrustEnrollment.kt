package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.*

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

/** Explicit local enrollment is the only key creation boundary. Apply never calls it. */
internal class FilesystemBrokerTrustRegistrar(private val home: Path) : BrokerTrustRegistrar {
    override fun enroll(): BrokerTrustResult {
        val rejected = { failure: BrokerTrustFailure -> BrokerTrustResult.Rejected(failure) }
        try {
            if (!home.isAbsolute || home.toRealPath() != home) return rejected(BrokerTrustFailure.UNSAFE_PATH)
            val owner = Files.getOwner(home)
            val directoryMode = PosixFilePermissions.fromString("rwx------")
            val fileMode = PosixFilePermissions.fromString("rw-------")
            val directories = listOf(home.resolve(".kast"), home.resolve(".kast/approval"))
            for (directory in directories) {
                if (!Files.exists(directory, NOFOLLOW_LINKS))
                    Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(directoryMode))
                if (
                    !Files.isDirectory(directory, NOFOLLOW_LINKS) ||
                        directory.toRealPath() != directory ||
                        Files.getOwner(directory) != owner ||
                        Files.getPosixFilePermissions(directory) != directoryMode
                )
                    return rejected(BrokerTrustFailure.UNSAFE_PATH)
            }
            val directory = directories.last()
            val lockPath = directory.resolve(".enroll.lock")
            if (!Files.exists(lockPath, NOFOLLOW_LINKS)) {
                try {
                    Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(fileMode))
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    /* Concurrent enrollment must take the same lock. */
                }
            }
            if (
                !Files.isRegularFile(lockPath, NOFOLLOW_LINKS) ||
                    Files.getOwner(lockPath) != owner ||
                    Files.getPosixFilePermissions(lockPath) != fileMode
            )
                return rejected(BrokerTrustFailure.UNSAFE_PATH)
            FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: java.nio.channels.OverlappingFileLockException) {
                        null
                    }
                if (lock == null) return rejected(BrokerTrustFailure.BUSY)
                lock.use {
                    val privatePath = directory.resolve("broker.pk8")
                    val publicPath = directory.resolve("broker.pub")
                    val existing = listOf(privatePath, publicPath).map { Files.exists(it, NOFOLLOW_LINKS) }
                    if (existing.any { it }) {
                        if (!existing.all { it }) return rejected(BrokerTrustFailure.INCOMPLETE_KEYS)
                        for (path in listOf(privatePath, publicPath)) {
                            if (
                                !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                                    Files.getOwner(path) != owner ||
                                    Files.getPosixFilePermissions(path) != fileMode ||
                                    Files.size(path) !in 1..128
                            )
                                return rejected(BrokerTrustFailure.UNSAFE_PATH)
                        }
                        val factory = KeyFactory.getInstance("Ed25519")
                        val privateBytes = Files.newInputStream(privatePath, NOFOLLOW_LINKS).use { it.readNBytes(129) }
                        val publicBytes = Files.newInputStream(publicPath, NOFOLLOW_LINKS).use { it.readNBytes(129) }
                        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                        val publicKey = factory.generatePublic(X509EncodedKeySpec(publicBytes))
                        if (
                            !privateKey.encoded.contentEquals(privateBytes) ||
                                !publicKey.encoded.contentEquals(publicBytes)
                        )
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
                    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                    for ((path, bytes) in
                        listOf(privatePath to pair.private.encoded, publicPath to pair.public.encoded)) {
                        FileChannel.open(
                                path,
                                setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                                PosixFilePermissions.asFileAttribute(fileMode),
                            )
                            .use {
                                val buffer = java.nio.ByteBuffer.wrap(bytes)
                                while (buffer.hasRemaining()) it.write(buffer)
                                it.force(true)
                            }
                    }
                    return BrokerTrustResult.Complete(BrokerTrustStatus.ENROLLED)
                }
            }
        } catch (_: java.security.GeneralSecurityException) {
            return rejected(BrokerTrustFailure.INVALID_KEYS)
        } catch (_: java.io.IOException) {
            return rejected(BrokerTrustFailure.IO_FAILED)
        } catch (_: SecurityException) {
            return rejected(BrokerTrustFailure.UNSAFE_PATH)
        } catch (_: UnsupportedOperationException) {
            return rejected(BrokerTrustFailure.UNAVAILABLE)
        }
    }
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
