package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.runtime.ControllerApprovedPlan
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalGrant
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads explicitly enrolled keys. Request handling never creates or replaces signing authority. */
internal class EnrolledPlanApprovalSigner(private val userHome: Path) {
    private class Keys(val privateKey: PrivateKey, val publicKey: PublicKey)

    fun availability(): Refinement<Unit, HostedPlanApprovalFailure> =
        when (val keys = load()) {
            is Refinement.Rejected -> keys
            is Refinement.Refined -> Refinement.Refined(Unit)
        }

    fun sign(approval: ControllerApprovedPlan): Refinement<HostedPlanApprovalGrant, HostedPlanApprovalFailure> {
        val keys =
            when (val loaded = load()) {
                is Refinement.Rejected -> return loaded
                is Refinement.Refined -> loaded.value
            }
        val payload =
            json
                .encodeToString(
                    SignedPlanApprovalPayload.serializer(),
                    SignedPlanApprovalPayload(
                        operation = approval.subject.operation.canonical.name,
                        root = approval.subject.root.path.toString(),
                        host = approval.subject.host.value.toString(),
                        planId = approval.subject.planIdentity,
                        challenge = approval.subject.hostedChallenge,
                        threadId = approval.invocation.threadId.value,
                        turnId = approval.invocation.turnId.value,
                        callId = approval.invocation.callId.value,
                        keyId =
                            HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(keys.publicKey.encoded)),
                    ),
                )
                .toByteArray(Charsets.UTF_8)
        return try {
            val signed =
                Signature.getInstance("Ed25519").run {
                    initSign(keys.privateKey)
                    update(payload)
                    sign()
                }
            val coherent =
                Signature.getInstance("Ed25519").run {
                    initVerify(keys.publicKey)
                    update(payload)
                    verify(signed)
                }
            if (!coherent) Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
            else {
                val encoder = Base64.getUrlEncoder().withoutPadding()
                HostedPlanApprovalGrant.fromSignedControllerApproval(
                    approval,
                    "${encoder.encodeToString(payload)}.${encoder.encodeToString(signed)}",
                )
            }
        } catch (_: GeneralSecurityException) {
            Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
        }
    }

    private fun load(): Refinement<Keys, HostedPlanApprovalFailure> =
        try {
            val kast = userHome.resolve(".kast")
            val directory = kast.resolve("approval")
            val privatePath = directory.resolve("broker.pk8")
            val publicPath = directory.resolve("broker.pub")
            when {
                listOf(userHome, kast, directory, privatePath, publicPath).any(Files::isSymbolicLink) ->
                    Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
                !Files.exists(privatePath, NOFOLLOW_LINKS) || !Files.exists(publicPath, NOFOLLOW_LINKS) ->
                    Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_UNAVAILABLE)
                !privateDirectory(directory) || !privateKeyFile(privatePath) || !privateKeyFile(publicPath) ->
                    Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
                else -> decode(privatePath, publicPath)
            }
        } catch (_: IOException) {
            Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
        } catch (_: GeneralSecurityException) {
            Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
        } catch (_: SecurityException) {
            Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
        } catch (_: UnsupportedOperationException) {
            Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
        }

    private fun privateDirectory(path: Path): Boolean =
        Files.isDirectory(path, NOFOLLOW_LINKS) &&
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------")

    private fun privateKeyFile(path: Path): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            Files.size(path) in 1..MAXIMUM_KEY_BYTES &&
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------")

    private fun decode(privatePath: Path, publicPath: Path): Refinement<Keys, HostedPlanApprovalFailure> {
        val privateBytes =
            Files.newInputStream(privatePath, NOFOLLOW_LINKS).use { input ->
                input.readNBytes(MAXIMUM_KEY_BYTES + 1)
            }
        val publicBytes =
            Files.newInputStream(publicPath, NOFOLLOW_LINKS).use { input ->
                input.readNBytes(MAXIMUM_KEY_BYTES + 1)
            }
        if (privateBytes.size > MAXIMUM_KEY_BYTES || publicBytes.size > MAXIMUM_KEY_BYTES)
            return Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED)
        val factory = KeyFactory.getInstance("Ed25519")
        return Refinement.Refined(
            Keys(
                factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
                factory.generatePublic(X509EncodedKeySpec(publicBytes)),
            )
        )
    }

    private companion object {
        const val MAXIMUM_KEY_BYTES = 4096
        val json = Json { encodeDefaults = true }
    }
}

@Serializable
private data class SignedPlanApprovalPayload(
    val version: Int = 1,
    val operation: String,
    val root: String,
    val host: String,
    val planId: String,
    val challenge: String,
    val threadId: String,
    val turnId: String,
    val callId: String,
    val keyId: String,
)
