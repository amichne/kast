package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.PayloadFile
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_FENCE
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicHookManagedPreparationTest {
    @Test
    fun `explicit workspace wait rejects an unqualified installed product before Host effects`(
        @TempDir temporary: Path
    ) {
        val fixture = HookInstallation(temporary.toRealPath())
        val context = fixture.context()
        val root =
            (FilesystemCanonicalRootDiscovery.discover(fixture.workspace) as CanonicalRootDiscovery.Discovered).root
        val output =
            preparePublicHook(
                listOf("workspace", "wait", "--workspace", fixture.workspace.toString()),
                ByteArrayInputStream(byteArrayOf()),
            ) { selected, wait ->
                assertEquals(root, selected)
                prepareInstalledHook(selected, wait, {}, context)
            }
        assertEquals(PublicShellExit.RUNTIME, output.exit)
        val cause = Json.parseToJsonElement(output.text).jsonObject.getValue("cause").jsonObject
        assertEquals("managed", cause.getValue("type").jsonPrimitive.content)
        assertEquals("INVALID_RECORD", cause.getValue("failure").jsonPrimitive.content)
        assertFalse(Files.exists(fixture.store))
        assertFalse(Files.exists(fixture.installation.resolve("state")))
    }

    @Test
    fun `explicit wait rejects inherited managed state under qualified product admission`(@TempDir temporary: Path) {
        val fixture = HookInstallation(temporary.toRealPath())
        NativeManagedWorkspaceFixture(fixture.installation, fixture.workspace)
        val foreign = Files.createDirectory(temporary.resolve("foreign-state"))
        Files.writeString(foreign.resolve("preserve"), "foreign bytes")
        val before = snapshot(temporary)
        val root =
            (FilesystemCanonicalRootDiscovery.discover(fixture.workspace) as CanonicalRootDiscovery.Discovered).root
        val result =
            prepareInstalledHook(
                root,
                HookWait.Configured,
                { error("Rejected admission must not report readiness") },
                fixture.context(mapOf("KAST_MANAGED_HEADLESS_STATE" to foreign.toString())),
            )
        assertEquals(Refinement.Rejected(HookPreparationFailure.Managed(ContainerFailure.FOREIGN_STATE)), result)
        assertEquals(before, snapshot(temporary))
    }

    @Test
    fun `fenced Control rejects workspace wait before any preparation effect`(@TempDir temporary: Path) {
        val fixture = HookInstallation(temporary.toRealPath())
        val context = fixture.context()
        Files.writeString(fixture.installation.parent.resolve(INSTALLATION_SHUTDOWN_FENCE), "stopped")
        val before = snapshot(temporary)
        val root =
            (FilesystemCanonicalRootDiscovery.discover(fixture.workspace) as CanonicalRootDiscovery.Discovered).root
        assertEquals(
            Refinement.Rejected(HookPreparationFailure.Stopped),
            prepareInstalledHook(root, HookWait.Configured, { error("Fenced readiness") }, context),
        )
        assertEquals(before, snapshot(temporary))
    }

    private fun snapshot(root: Path): Map<String, String> =
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .toList()
                .associate { root.relativize(it).toString() to nativeDigest(it) }
        }
}

private class HookInstallation(val home: Path) {
    val installation = Files.createDirectories(home.resolve(".local/share/kast/installation"))
    val workspace = Files.createDirectory(home.resolve("workspace"))
    val store = installation.parent.resolveSibling("kast-workspaces")
    private val payload = installation.resolve("bin/kast-query")

    init {
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        Files.createDirectories(payload.parent)
        Files.writeString(payload, "inert Control payload")
        Files.createDirectories(installation.resolve("config"))
        Files.writeString(installation.resolve("config/environment"), "")
        val digest = "sha256:${nativeDigest(payload)}"
        Files.writeString(
            installation.resolve("installation.json"),
            Json { encodeDefaults = true }
                .encodeToString(
                    InstallationManifest(
                        semanticVersion = "0.0.1",
                        installationRoot = installation.toString(),
                        payloadIdentity = digest,
                        controlSha256 = digest,
                        codexHome = home.resolve(".codex").toString(),
                        configuration = installation.resolve("config/environment").toString(),
                        workspaceRegistry = installation.resolve("config/workspaces.json").toString(),
                        stateRoot = installation.resolve("state").toString(),
                        externalAnchors = emptyList(),
                        payloadFiles = listOf(PayloadFile("bin/kast-query", digest, 493)),
                    )
                ),
        )
    }

    fun context(extra: Map<String, String> = emptyMap()): InstalledClientContext =
        when (
            val admitted =
                InstalledClientContext.admit(
                    payload,
                    mapOf(
                        "HOME" to home.toString(),
                        "KAST_INSTALL_IDEA_HOME" to home.resolve("absent-vendor").toString(),
                    ) + extra,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Fixture context rejected: ${admitted.failure}")
        }
}
