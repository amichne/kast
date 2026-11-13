package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.direct.ClientInstallationFailure
import io.github.amichne.kast.distribution.managed.CanonicalRootFailure
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class TestHookEvent(val cwd: String, @SerialName("session_id") val sessionId: String)

@Serializable private class MissingHookWorkspace

class PublicHookTest {
    @Test
    fun `preparation expiry differs from a closed editor and preserves native cause`() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        for ((reason, expected) in
            listOf(
                io.github.amichne.kast.protocol.contract.IdeLifecycleFailure.DEADLINE_EXCEEDED to
                    PublicShellExit.ELAPSED,
                io.github.amichne.kast.protocol.contract.IdeLifecycleFailure.HOST_UNAVAILABLE to
                    PublicShellExit.RUNTIME,
            )) {
            val output =
                preparePublicHook(listOf("workspace", "wait", "--workspace", directory.toString()), input()) { _, _ ->
                    Refinement.Rejected(
                        HookPreparationFailure.Lifecycle(
                            io.github.amichne.kast.protocol.contract.IdeLifecycleResult.Blocked(reason)
                        )
                    )
                }
            assertEquals(expected, output.exit)
            val cause =
                Json.parseToJsonElement(output.text)
                    .jsonObject
                    .getValue("cause")
                    .jsonObject
                    .getValue("result")
                    .jsonObject
            assertEquals(reason.name, cause.getValue("reason").jsonPrimitive.content)
        }
    }

    @TempDir lateinit var directory: Path

    @Test
    fun `session start leaves a settings-owned workspace passive`() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        val nested = Files.createDirectories(directory.resolve("module/src"))
        val output =
            preparePublicHook(listOf("hook", "session-start", "--workspace", nested.toString()), input()) { _, _ ->
                error("Passive hook must not prepare or grant trust")
            }
        assertEquals(PublicShellExit.COMPLETE, output.exit)
        assertEquals(setOf("additionalContext"), Json.parseToJsonElement(output.text).jsonObject.keys)
    }

    @Test
    fun `non projects skip preparation and invalid events reject before effects`() {
        val skipped =
            preparePublicHook(listOf("hook", "session-start", "--workspace", directory.toString()), input()) { _, _ ->
                error("Non-project preparation")
            }
        assertEquals(PublicShellExit.COMPLETE, skipped.exit)
        assertEquals(setOf("additionalContext"), Json.parseToJsonElement(skipped.text).jsonObject.keys)
        for (event in
            listOf(
                Json.encodeToString(MissingHookWorkspace()),
                Json.encodeToString(TestHookEvent("relative", "1")),
                Json.encodeToString(emptyList<String>()),
                "{} {}",
            )) {
            val rejected =
                preparePublicHook(listOf("hook", "session-start"), input(event)) { _, _ ->
                    error("Invalid event effect")
                }
            assertEquals(PublicShellExit.INPUT, rejected.exit, event)
        }
    }

    @Test
    fun `host event admits cwd without invoking workspace preparation`() {
        Files.writeString(directory.resolve("settings.gradle"), "")
        val event = Json.encodeToString(TestHookEvent(directory.toString(), "1"))
        val output =
            preparePublicHook(listOf("hook", "session-start"), input(event)) { _, _ ->
                error("Passive event must not prepare")
            }
        assertEquals(PublicShellExit.COMPLETE, output.exit)
        assertEquals(setOf("additionalContext"), Json.parseToJsonElement(output.text).jsonObject.keys)
    }

    @Test
    fun `invalid settings marker rejects instead of skipping`() {
        Files.createDirectory(directory.resolve("settings.gradle.kts"))
        val output =
            preparePublicHook(listOf("hook", "session-start", "--workspace", directory.toString()), input()) { _, _ ->
                error("Invalid marker effect")
            }
        assertEquals(PublicShellExit.WORKSPACE, output.exit)
        assertEquals(
            CanonicalRootFailure.INVALID_ROOT_MARKER,
            (FilesystemCanonicalRootDiscovery.discover(directory)
                    as io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery.Rejected)
                .failure,
        )
    }

    @Test
    fun `every preparation rejection keeps its explicit tag and finite cause`() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        val cases =
            listOf(
                HookPreparationFailure.Stopped to "stopped",
                HookPreparationFailure.Unproven to "unproven",
            ) + ClientInstallationFailure.entries.map { HookPreparationFailure.Installation(it) to "installation" }
        for ((failure, expected) in cases) {
            val output =
                preparePublicHook(listOf("workspace", "wait", "--workspace", directory.toString()), input()) { _, _ ->
                    Refinement.Rejected(failure)
                }
            assertEquals(PublicShellExit.RUNTIME, output.exit)
            val reply = Json.parseToJsonElement(output.text).jsonObject
            assertEquals(setOf("type", "failure", "cause"), reply.keys)
            assertEquals("rejected", reply.getValue("type").jsonPrimitive.content)
            assertEquals("PREPARATION_REJECTED", reply.getValue("failure").jsonPrimitive.content)
            val cause = reply.getValue("cause").jsonObject
            assertEquals(expected, cause.getValue("type").jsonPrimitive.content)
            if (failure is HookPreparationFailure.Installation)
                assertEquals(failure.failure.name, cause.getValue("failure").jsonPrimitive.content)
        }
    }

    private fun input(text: String = "") = ByteArrayInputStream(text.toByteArray())

    @Test
    fun `managed admission preserves finite failures and separates preparation expiry`() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        for ((failure, exit) in
            listOf(
                io.github.amichne.kast.distribution.cli.ContainerFailure.FOREIGN_STATE to PublicShellExit.RUNTIME,
                io.github.amichne.kast.distribution.cli.ContainerFailure.FENCE_REJECTED to PublicShellExit.RUNTIME,
                io.github.amichne.kast.distribution.cli.ContainerFailure.PREPARATION_WAIT_EXPIRED to
                    PublicShellExit.ELAPSED,
            )) {
            val output =
                preparePublicHook(listOf("workspace", "wait", "--workspace", directory.toString()), input()) { _, _ ->
                    Refinement.Rejected(HookPreparationFailure.Managed(failure))
                }
            assertEquals(exit, output.exit)
            val cause = Json.parseToJsonElement(output.text).jsonObject.getValue("cause").jsonObject
            assertEquals("managed", cause.getValue("type").jsonPrimitive.content)
            assertEquals(failure.name, cause.getValue("failure").jsonPrimitive.content)
        }
    }

    @Test
    fun `both model hooks remain passive regardless of legacy wait selection`() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        for ((host, waitSetting) in listOf("codex" to "none", "copilot" to "indefinite", "codex" to "1250")) {
            val output =
                preparePublicHook(
                    listOf(
                        "hook",
                        "session-start",
                        "--workspace",
                        directory.toString(),
                        "--wait",
                        waitSetting,
                        "--host",
                        host,
                    ),
                    input(),
                ) { _, _ ->
                    error("Model hook must not start a Host")
                }
            val document = Json.parseToJsonElement(output.text).jsonObject
            val context = if (host == "codex") document.getValue("hookSpecificOutput").jsonObject else document
            val text = context.getValue("additionalContext").jsonPrimitive.content
            org.junit.jupiter.api.Assertions.assertTrue(text.contains("first semantic query"))
            assertEquals(PublicShellExit.COMPLETE, output.exit)
        }
    }

    @Test
    fun `wait selection is visible before preparation starts and rejects invalid settings before effects`() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        for (setting in listOf("1250", "indefinite")) {
            val progress = mutableListOf<String>()
            val output =
                preparePublicHook(
                    listOf("workspace", "wait", "--workspace", directory.toString(), "--wait", setting),
                    input(),
                    diagnostic = progress::add,
                ) { _, wait ->
                    assertEquals(1, progress.size)
                    val selected = (wait as HookWait.Selected).allowance
                    assertEquals(if (setting == "indefinite") null else 1250, selected.protocolMillis())
                    Refinement.Refined(
                        HookPreparation.Ready(
                            HookWaitDocument(
                                if (setting == "indefinite") HookWaitMode.INDEFINITE else HookWaitMode.BOUNDED,
                                selected.protocolMillis(),
                            )
                        )
                    )
                }
            assertEquals(PublicShellExit.COMPLETE, output.exit)
        }
        for (setting in listOf("0", "-1", "forever", "none")) {
            val rejected =
                preparePublicHook(
                    listOf("workspace", "wait", "--workspace", directory.toString(), "--wait", setting),
                    input(),
                ) { _, _ ->
                    error("Invalid wait started preparation")
                }
            assertEquals(PublicShellExit.INPUT, rejected.exit)
        }
    }
}
