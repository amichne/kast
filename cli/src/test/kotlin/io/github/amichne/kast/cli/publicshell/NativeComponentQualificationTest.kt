package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.installation.ManagedHeadlessShutdownFailure
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.cli.NativeComponentIdentity
import java.io.ByteArrayInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeComponentQualificationTest {
    @Test
    fun `exec retains public arguments exactly`() {
        val public = listOf("query", "--name", "invokeCallback", "--in", "a directory", "--json")
        val command = parseNativeComponentCommand(prefix() + listOf("exec", "--") + public)
        assertTrue(command is NativeComponentCommand.Execute)
        assertEquals(public, (command as NativeComponentCommand.Execute).arguments)
    }

    @Test
    fun `duplicate selectors and missing separator reject`() {
        assertEquals(
            NativeComponentCommand.Invalid,
            parseNativeComponentCommand(prefix() + listOf("--store", "/other", "inspect")),
        )
        assertEquals(NativeComponentCommand.Invalid, parseNativeComponentCommand(prefix() + listOf("exec", "query")))
        assertEquals(NativeComponentCommand.Invalid, parseNativeComponentCommand(prefix() + listOf("stop", "foreign")))
    }

    @Test
    fun `describe and plan do not admit nonexistent component paths`() {
        for (arguments in listOf(listOf("describe"), listOf("query", "plan", "--name", "OrderService"))) {
            val command = parseNativeComponentCommand(prefix() + listOf("exec", "--") + arguments)
            assertTrue(command is NativeComponentCommand.Execute)
            val reply =
                prepareNativeComponentQuery(
                    command as NativeComponentCommand.Execute,
                    ByteArrayInputStream(byteArrayOf()),
                )
            assertEquals(PublicShellExit.COMPLETE, reply.exit, reply.text)
        }
    }

    @Test
    fun `inspection serializes exact paths and identities without qualification claims`() {
        val identity =
            NativeComponentIdentity(
                sourceRevision = "c".repeat(40),
                version = "1.2.3",
                platform = "macos-aarch64",
                runtimeSha256 = "a".repeat(64),
                runtimeManifestSha256 = "d".repeat(64),
                controlSha256 = "b".repeat(64),
            )
        val reply: NativeComponentReply =
            NativeComponentReply.Inspected(
                identity = identity,
                workspace = "/workspace",
                workspaceId = "e".repeat(64),
                workspaceState = "/private/state",
                runtimeHome = "/runtime/host",
                home = "/private/state/home",
                javaHome = "/runtime/jbr",
                profile = "/private",
            )
        val encoded = Json.parseToJsonElement(Json.encodeToString(reply)).jsonObject
        assertEquals(
            setOf(
                "type",
                "identity",
                "workspace",
                "workspaceId",
                "workspaceState",
                "runtimeHome",
                "home",
                "javaHome",
                "profile",
            ),
            encoded.keys,
        )
        assertEquals("INSPECTED", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("/private/state", encoded.getValue("workspaceState").jsonPrimitive.content)
        assertEquals("/runtime/jbr", encoded.getValue("javaHome").jsonPrimitive.content)
        assertEquals(
            "a".repeat(64),
            encoded.getValue("identity").jsonObject.getValue("runtimeSha256").jsonPrimitive.content,
        )
        assertEquals(
            "b".repeat(64),
            encoded.getValue("identity").jsonObject.getValue("controlSha256").jsonPrimitive.content,
        )
    }

    @Test
    fun `shutdown and archive failures retain their finite codes`() {
        for ((failure, code) in
            listOf(
                NativeComponentFailure.Workspace(ContainerFailure.DIGEST_MISMATCH) to "DIGEST_MISMATCH",
                NativeComponentFailure.Shutdown(ManagedHeadlessShutdownFailure.HOST_DID_NOT_EXIT) to
                    "HOST_DID_NOT_EXIT",
            )) {
            val reply: NativeComponentReply = NativeComponentReply.Rejected(failure)
            val encoded = Json.parseToJsonElement(Json.encodeToString(reply)).jsonObject
            assertEquals("REJECTED", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(code, encoded.getValue("failure").jsonObject.getValue("failure").jsonPrimitive.content)
        }
    }

    private fun prefix() =
        listOf(
            "--store",
            "/nonexistent-component-test/store",
            "--workspace",
            "/nonexistent-component-test/workspace",
            "--runtime-archive",
            "/nonexistent-component-test/runtime.zip",
            "--runtime-sha256",
            "a".repeat(64),
            "--control",
            "/nonexistent-component-test/control",
            "--control-sha256",
            "b".repeat(64),
            "--version",
            "1.2.3",
            "--source-revision",
            "c".repeat(40),
        )
}
