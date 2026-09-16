package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.ProjectCloseApprovalPayload
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal sealed interface ProjectCloseAuthority {
    data object ManagedCleanup : ProjectCloseAuthority

    class UserApproved private constructor(private val payload: ProjectCloseApprovalPayload) : ProjectCloseAuthority {
        fun matches(command: IdeLifecycleCommand.Close): Boolean =
            payload.target == command.target &&
                payload.requestId == command.requestId &&
                payload.client == command.client

        companion object {
            fun verify(
                home: Path,
                command: IdeLifecycleCommand.AuthorizedClose,
            ): Refinement<UserApproved, IdeLifecycleFailure> {
                val key =
                    when (val loaded = loadKey(home)) {
                        is Refinement.Rejected -> return loaded
                        is Refinement.Refined -> loaded.value
                    }
                val payload =
                    when (val verified = verifyPayload(key, command.assertion)) {
                        is Refinement.Rejected -> return verified
                        is Refinement.Refined -> verified.value
                    }
                val authority = UserApproved(payload)
                return if (
                    authority.matches(IdeLifecycleCommand.Close(command.requestId, command.client, command.target))
                )
                    Refinement.Refined(authority)
                else rejected()
            }
        }
    }
}

private const val MAX_ASSERTION_CHARS = 16_384
private const val MAX_KEY_BYTES = 128

private fun rejected() = Refinement.Rejected(IdeLifecycleFailure.USER_AUTHORIZATION_REQUIRED)

private fun verifyPayload(
    key: PublicKey,
    assertion: String,
): Refinement<ProjectCloseApprovalPayload, IdeLifecycleFailure> {
    if (assertion.length !in 1..MAX_ASSERTION_CHARS) return rejected()
    val parts = assertion.split('.')
    if (parts.size != 2) return rejected()
    return try {
        val decoder = Base64.getUrlDecoder()
        val bytes = decoder.decode(parts[0])
        val signature = decoder.decode(parts[1])
        if (
            !Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(bytes)
                verify(signature)
            }
        )
            return rejected()
        val payload = Json.decodeFromString<ProjectCloseApprovalPayload>(bytes.toString(Charsets.UTF_8))
        if (!validPurpose(payload)) rejected() else Refinement.Refined(payload)
    } catch (_: java.security.GeneralSecurityException) {
        rejected()
    } catch (_: IllegalArgumentException) {
        rejected()
    } catch (_: SerializationException) {
        rejected()
    }
}

private fun validPurpose(payload: ProjectCloseApprovalPayload): Boolean =
    payload.purpose == "kast-project-close-v1" &&
        payload.client == payload.threadId &&
        listOf(payload.threadId, payload.turnId, payload.callId).none(String::isBlank)

private fun loadKey(home: Path): Refinement<PublicKey, IdeLifecycleFailure> =
    try {
        val directory = home.resolve(".kast/approval")
        val file = directory.resolve("broker.pub")
        if (listOf(home, home.resolve(".kast"), directory, file).any(Files::isSymbolicLink)) rejected()
        else if (!privateKeyLocation(directory, file)) rejected() else decodeKey(file)
    } catch (_: java.io.IOException) {
        rejected()
    } catch (_: java.security.GeneralSecurityException) {
        rejected()
    } catch (_: SecurityException) {
        rejected()
    } catch (_: UnsupportedOperationException) {
        rejected()
    }

private fun privateKeyLocation(directory: Path, file: Path): Boolean {
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !Files.isRegularFile(file, NOFOLLOW_LINKS)) return false
    return Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------") &&
        Files.getPosixFilePermissions(file, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------")
}

private fun decodeKey(file: Path): Refinement<PublicKey, IdeLifecycleFailure> {
    val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(MAX_KEY_BYTES + 1) }
    if (bytes.size !in 1..MAX_KEY_BYTES) return rejected()
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes))
    return if (key.encoded.contentEquals(bytes)) Refinement.Refined(key) else rejected()
}
