package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
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
    @TempDir lateinit var directory: Path

    @Test
    fun `session start prepares the settings owner once and emits only a host envelope`() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        val nested = Files.createDirectories(directory.resolve("module/src"))
        val roots = mutableListOf<Path>()
        val output =
            preparePublicHook(listOf("hook", "session-start", "--workspace", nested.toString()), input()) {
                roots.add(it.path)
                Refinement.Refined(Unit)
            }
        assertEquals(listOf(directory.toRealPath()), roots)
        assertEquals(PublicShellExit.COMPLETE, output.exit)
        assertEquals(emptySet<String>(), Json.parseToJsonElement(output.text).jsonObject.keys)
    }

    @Test
    fun `non projects skip preparation and invalid events reject before effects`() {
        val skipped =
            preparePublicHook(listOf("hook", "session-start", "--workspace", directory.toString()), input()) {
                error("Non-project preparation")
            }
        assertEquals(PublicShellExit.COMPLETE, skipped.exit)
        assertEquals(emptySet<String>(), Json.parseToJsonElement(skipped.text).jsonObject.keys)
        for (event in
            listOf(
                Json.encodeToString(MissingHookWorkspace()),
                Json.encodeToString(TestHookEvent("relative", "1")),
                Json.encodeToString(emptyList<String>()),
                "{} {}",
            )) {
            val rejected =
                preparePublicHook(listOf("hook", "session-start"), input(event)) { error("Invalid event effect") }
            assertEquals(PublicShellExit.INPUT, rejected.exit, event)
        }
    }

    @Test
    fun `host event admits cwd and keeps the exact preparation rejection`() {
        Files.writeString(directory.resolve("settings.gradle"), "")
        val event = Json.encodeToString(TestHookEvent(directory.toString(), "1"))
        val output =
            preparePublicHook(listOf("hook", "session-start"), input(event)) {
                Refinement.Rejected(
                    HookPreparationFailure.Native(DaemonOperationFailure.Root(CanonicalRootFailure.START_UNAVAILABLE))
                )
            }
        assertEquals(PublicShellExit.RUNTIME, output.exit)
        val cause =
            Json.parseToJsonElement(output.text).jsonObject.getValue("cause").jsonObject.getValue("failure").jsonObject
        assertEquals("root", cause.getValue("type").jsonPrimitive.content)
        assertEquals("START_UNAVAILABLE", cause.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `invalid settings marker rejects instead of skipping`() {
        Files.createDirectory(directory.resolve("settings.gradle.kts"))
        val output =
            preparePublicHook(listOf("hook", "session-start", "--workspace", directory.toString()), input()) {
                error("Invalid marker effect")
            }
        assertEquals(PublicShellExit.WORKSPACE, output.exit)
        assertEquals(
            CanonicalRootFailure.INVALID_ROOT_MARKER,
            (FilesystemCanonicalRootDiscovery.discover(directory)
                    as io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery.Rejected)
                .failure,
        )
    }

    private fun input(text: String = "") = ByteArrayInputStream(text.toByteArray())
}
