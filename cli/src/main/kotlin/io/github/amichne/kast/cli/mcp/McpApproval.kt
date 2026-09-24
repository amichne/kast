package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.mcpWorkspaceOperationClient
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.configuredExistingIdeClient
import io.github.amichne.kast.cli.ide.executeExistingIdeCli
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE
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
import kotlinx.serialization.json.JsonPrimitive

@Serializable internal data class McpApprovedArguments(val arguments: JsonObject, val approval: String)

@Serializable private data class PlanIdentityRequest(val planIdentity: String)

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

@Serializable
private data class StoredApproval(
    val operation: String,
    val root: String,
    val planId: String,
    val assertion: String,
)

/** The signer is shared by direct MCP's one-call change and the legacy interactive approval command. */
internal object McpApprovalHelper {
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ComplexCondition", "MagicNumber")
    fun run(args: List<String>): Int {
        if (args.size != 2 || args[0] !in setOf("apply", "recover") || !args[1].matches(Regex("plan:[0-9a-f]{64}"))) {
            System.err.println("usage: kast-mcp approve <apply|recover> <plan:identity>")
            return 64
        }
        val console =
            System.console()
                ?: run {
                    System.err.println("kast-mcp: approval requires an interactive terminal")
                    return 64
                }
        val root =
            when (val found = FilesystemCanonicalRootDiscovery.discover(Path.of(""))) {
                is CanonicalRootDiscovery.Discovered -> found.root.path
                is CanonicalRootDiscovery.Rejected -> return 65
            }
        val home = Path.of(System.getProperty("user.home"))
        val challengeExit =
            executeExistingIdeCli(
                argv = listOf("change", args[0], "--hosted-approval-prepare"),
                start = root,
                capabilities =
                    ExistingIdeCliCapabilities(
                        FilesystemCanonicalRootDiscovery,
                        configuredExistingIdeClient(home, System.getenv()),
                        mcpWorkspaceOperationClient(home, System.getenv()),
                    ),
                requestInput =
                    CliRequestDocumentInput.Provided(approvalJson.encodeToString(PlanIdentityRequest(args[1]))),
            )
        if (challengeExit !is CliExit.Complete) {
            System.err.println("kast-mcp: stored plan cannot be prepared")
            return 65
        }
        val challenge =
            try {
                approvalJson.decodeFromString<ApprovalChallenge>(challengeExit.document.value)
            } catch (_: Exception) {
                return 65
            }
        if (
            challenge.version != 1 ||
                challenge.operation != "CHANGE_${args[0].uppercase()}" ||
                challenge.root != root.toString() ||
                challenge.planId != args[1].removePrefix("plan:") ||
                !challenge.challenge.matches(Regex("[0-9a-f]{64}")) ||
                challenge.preview.path.isBlank() ||
                challenge.preview.diff.isBlank()
        )
            return 65
        console.writer().apply {
            println("Kast ${args[0]}: ${challenge.preview.path}")
            println(challenge.preview.diff)
            flush()
        }
        if (console.readLine("Type approve to authorize this exact preview: ") != "approve") return 1
        val assertion = sign(home, challenge) ?: return 65
        return if (McpApprovalStore(home).put(args[0], root, challenge, assertion)) {
            console.writer().println("Approved once. Repeat the Kast change tool call in Codex.")
            0
        } else 65
    }

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

/** Moving the grant before dispatch makes a lost MCP response uncertain, never a second write. */
internal class McpApprovalStore(private val home: Path) {
    private val directory = home.resolve(".kast/mcp-approvals")

    @Suppress("ComplexCondition")
    fun put(operation: String, root: Path, challenge: ApprovalChallenge, assertion: String): Boolean =
        try {
            if (
                !ready(create = true) ||
                    challenge.operation != "CHANGE_${operation.uppercase()}" ||
                    challenge.root != root.toRealPath().toString() ||
                    !challenge.planId.matches(Regex("[0-9a-f]{64}"))
            )
                return false
            val target = directory.resolve("${challenge.planId}.$operation.json")
            Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(
                target,
                approvalJson.encodeToString(StoredApproval(operation, root.toString(), challenge.planId, assertion)),
                WRITE,
            )
            true
        } catch (_: Exception) {
            false
        }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "MagicNumber")
    fun take(tool: String, arguments: JsonObject, root: Path): String? =
        try {
            val operation =
                when (tool) {
                    "change_apply" -> "apply"
                    "change_recover" -> "recover"
                    else -> return null
                }
            val identity = arguments["planIdentity"] as? JsonPrimitive ?: return null
            if (
                arguments.keys != setOf("planIdentity") ||
                    !identity.isString ||
                    !identity.content.matches(Regex("plan:[0-9a-f]{64}"))
            )
                return null
            if (!ready(create = false)) return null
            val source = directory.resolve("${identity.content.removePrefix("plan:")}.$operation.json")
            if (
                !Files.isRegularFile(source, NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(source) ||
                    Files.size(source) > 16_384 ||
                    Files.getPosixFilePermissions(source, NOFOLLOW_LINKS) !=
                        PosixFilePermissions.fromString("rw-------")
            )
                return null
            val consumed = directory.resolve(".consumed-${UUID.randomUUID()}")
            Files.move(source, consumed, ATOMIC_MOVE)
            val record =
                try {
                    approvalJson.decodeFromString<StoredApproval>(
                        Files.newInputStream(consumed, NOFOLLOW_LINKS).bufferedReader().use { it.readText() }
                    )
                } finally {
                    Files.deleteIfExists(consumed)
                }
            if (
                record.operation != operation ||
                    record.root != root.toRealPath().toString() ||
                    record.planId != identity.content.removePrefix("plan:")
            )
                null
            else record.assertion
        } catch (_: Exception) {
            null
        }

    @Suppress("MagicNumber")
    private fun ready(create: Boolean): Boolean {
        val parent = home.resolve(".kast")
        if (
            listOf(home, parent).any(Files::isSymbolicLink) ||
                !Files.isDirectory(parent, NOFOLLOW_LINKS) ||
                Files.getPosixFilePermissions(parent, NOFOLLOW_LINKS) != PosixFilePermissions.fromString("rwx------")
        )
            return false
        if (create && !Files.exists(directory, NOFOLLOW_LINKS)) {
            Files.createDirectory(
                directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        }
        return Files.isDirectory(directory, NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(directory) &&
            Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------")
    }
}

private val approvalJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
