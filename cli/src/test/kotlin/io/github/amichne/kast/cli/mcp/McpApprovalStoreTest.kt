package io.github.amichne.kast.cli.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpApprovalStoreTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `approved plan is consumed once for the same root and operation`() {
        val home = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectory(
            home.resolve(".kast"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        val root = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val plan = "a".repeat(64)
        val store = McpApprovalStore(home)
        val challenge =
            ApprovalChallenge(
                1,
                "CHANGE_APPLY",
                root.toString(),
                "host",
                plan,
                "b".repeat(64),
                ApprovalPreview("File.kt", "@@ -1 +1 @@"),
            )
        assertTrue(store.put("apply", root, challenge, "signed-assertion"))
        val arguments = Json.encodeToJsonElement(TestPlanIdentity("plan:$plan")).jsonObject
        assertNull(store.take("change_recover", arguments, root))
        assertEquals("signed-assertion", store.take("change_apply", arguments, root))
        assertNull(store.take("change_apply", arguments, root))
    }

    @Test
    @Suppress("LongMethod")
    fun `helper signs the exact hosted challenge with the enrolled key`() {
        val home = Files.createDirectory(temporary.resolve("home"))
        val kast =
            Files.createDirectory(
                home.resolve(".kast"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        val directory =
            Files.createDirectory(
                kast.resolve("approval"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val privatePath =
            Files.createFile(
                directory.resolve("broker.pk8"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        val publicPath =
            Files.createFile(
                directory.resolve("broker.pub"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        Files.write(privatePath, keys.private.encoded)
        Files.write(publicPath, keys.public.encoded)
        val challenge =
            ApprovalChallenge(
                1,
                "CHANGE_APPLY",
                "/project",
                "host",
                "a".repeat(64),
                "b".repeat(64),
                ApprovalPreview("File.kt", "diff"),
            )
        val assertion = McpApprovalHelper.sign(home, challenge)
        assertNotNull(assertion)
        val parts = assertion!!.split('.')
        val payload = Base64.getUrlDecoder().decode(parts[0])
        val signature = Base64.getUrlDecoder().decode(parts[1])
        val verified =
            Signature.getInstance("Ed25519").run {
                initVerify(keys.public)
                update(payload)
                verify(signature)
            }
        assertTrue(verified)
        val document = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        assertEquals("CHANGE_APPLY", document.getValue("operation").jsonPrimitive.content)
        assertEquals(challenge.planId, document.getValue("planId").jsonPrimitive.content)
        assertEquals(challenge.challenge, document.getValue("challenge").jsonPrimitive.content)
        assertEquals("host", document.getValue("host").jsonPrimitive.content)
        Files.delete(privatePath)
        assertNull(McpApprovalHelper.sign(home, challenge))
    }
}

@Serializable private data class TestPlanIdentity(val planIdentity: String)
