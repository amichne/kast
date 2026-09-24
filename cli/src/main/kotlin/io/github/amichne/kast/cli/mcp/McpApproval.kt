package io.github.amichne.kast.cli.mcp

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable internal data class McpApprovedArguments(val arguments: JsonObject, val approval: String)

@Serializable
internal data class ApprovalChallenge(
    val version: Int,
    val operation: String,
    val root: String,
    val host: String,
    val planId: String,
    val challenge: String,
    val preview: ApprovalPreview,
)

@Serializable internal data class ApprovalPreview(val path: String, val diff: String)

@Serializable
private data class SignedApprovalPayload(
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

/** Signs the exact internal plan challenge for direct MCP's one-call change. */
internal object McpApprovalHelper {
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ComplexCondition", "MagicNumber")
    internal fun sign(home: Path, challenge: ApprovalChallenge): String? =
        try {
            val directory = home.resolve(".kast/approval")
            val privatePath = directory.resolve("broker.pk8")
            val publicPath = directory.resolve("broker.pub")
            val directoryMode = PosixFilePermissions.fromString("rwx------")
            val fileMode = PosixFilePermissions.fromString("rw-------")
            if (
                listOf(home, home.resolve(".kast"), directory).any(Files::isSymbolicLink) ||
                    Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) != directoryMode ||
                    listOf(privatePath, publicPath).any {
                        Files.isSymbolicLink(it) ||
                            !Files.isRegularFile(it, NOFOLLOW_LINKS) ||
                            Files.getPosixFilePermissions(it, NOFOLLOW_LINKS) != fileMode ||
                            Files.size(it) !in 1..4096
                    }
            )
                return null
            val factory = KeyFactory.getInstance("Ed25519")
            val privateKey =
                factory.generatePrivate(
                    PKCS8EncodedKeySpec(Files.newInputStream(privatePath, NOFOLLOW_LINKS).use { it.readNBytes(4097) })
                )
            val publicKey =
                factory.generatePublic(
                    X509EncodedKeySpec(Files.newInputStream(publicPath, NOFOLLOW_LINKS).use { it.readNBytes(4097) })
                )
            val payload =
                approvalJson
                    .encodeToString(
                        SignedApprovalPayload(
                            operation = challenge.operation,
                            root = challenge.root,
                            host = challenge.host,
                            planId = challenge.planId,
                            challenge = challenge.challenge,
                            threadId = UUID.randomUUID().toString(),
                            turnId = UUID.randomUUID().toString(),
                            callId = UUID.randomUUID().toString(),
                            keyId =
                                HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)),
                        )
                    )
                    .toByteArray(Charsets.UTF_8)
            val signature =
                Signature.getInstance("Ed25519").run {
                    initSign(privateKey)
                    update(payload)
                    sign()
                }
            val verified =
                Signature.getInstance("Ed25519").run {
                    initVerify(publicKey)
                    update(payload)
                    verify(signature)
                }
            if (!verified) null
            else
                Base64.getUrlEncoder().withoutPadding().let { encoder ->
                    "${encoder.encodeToString(payload)}.${encoder.encodeToString(signature)}"
                }
        } catch (_: Exception) {
            null
        }
}

private val approvalJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
