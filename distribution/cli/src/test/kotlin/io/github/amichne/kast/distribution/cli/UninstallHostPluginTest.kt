package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class UninstallHostPluginTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `owned Host removal response admits only its exact configured plugin`() {
        val plugin = Path.of("/owned/idea/plugins/kast-ide-hosted")
        val output = Json.encodeToString(RemovalFixture("REMOVED", plugin.toString()))
        assertEquals(
            Refinement.Refined(Unit),
            admitHostRemovalOutput(HostRemovalProcessObservation.Exited(0, output), plugin),
        )
        assertEquals(
            Refinement.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED),
            admitHostRemovalOutput(HostRemovalProcessObservation.Exited(0, output), Path.of("/another/plugin")),
        )
    }

    @Test
    fun `owner rejection retains its finite code and exit binding`() {
        val plugin = Path.of("/owned/idea/plugins/kast-ide-hosted")
        for (failure in
            listOf(
                HostPluginCleanupFailure.PAYLOAD_REJECTED,
                HostPluginCleanupFailure.RELEASE_REJECTED,
                HostPluginCleanupFailure.COMPATIBILITY_REJECTED,
                HostPluginCleanupFailure.OWNERSHIP_UNPROVEN,
                HostPluginCleanupFailure.FILESYSTEM_REJECTED,
                HostPluginCleanupFailure.RECOVERY_REQUIRED,
            )) {
            val output = Json.encodeToString(RejectionFixture("REJECTED", failure.name))
            assertEquals(
                Refinement.Rejected(failure),
                admitHostRemovalOutput(HostRemovalProcessObservation.Exited(1, output), plugin),
            )
            assertEquals(
                Refinement.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED),
                admitHostRemovalOutput(HostRemovalProcessObservation.Exited(0, output), plugin),
            )
        }
        val unknown = Json.encodeToString(RejectionFixture("REJECTED", "UNKNOWN"))
        assertEquals(
            Refinement.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED),
            admitHostRemovalOutput(HostRemovalProcessObservation.Exited(1, unknown), plugin),
        )
        assertEquals(
            Refinement.Rejected(HostPluginCleanupFailure.DEADLINE_EXCEEDED),
            admitHostRemovalOutput(
                HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.DEADLINE_EXCEEDED),
                plugin,
            ),
        )
    }

    @Test
    fun `configured Host uses the admitted helper and recorded installation target`() {
        val fixture = fixture()
        val observed = mutableListOf<UninstallCleanupObservation>()
        var calls = 0
        removeConfiguredUninstallHost(fixture.payload, fixture.home, observed::add) { arguments ->
            calls++
            assertEquals(
                listOf(
                    "python3",
                    "-I",
                    fixture.helper.toString(),
                    "--remove",
                    "--plugin-root",
                    fixture.plugin.parent.toString(),
                ),
                arguments,
            )
            HostRemovalProcessObservation.Exited(
                0,
                Json.encodeToString(RemovalFixture("REMOVED", fixture.plugin.toString())),
            )
        }
        assertEquals(1, calls)
        assertEquals(
            listOf(
                UninstallCleanupObservation.Started(UninstallArtifact.HOST_PLUGIN),
                UninstallCleanupObservation.Completed(UninstallArtifact.HOST_PLUGIN),
            ),
            observed,
        )
        assertTrue(Files.exists(fixture.helper))
        assertFalse(Files.exists(fixture.plugin))
    }

    @Test
    fun `child rejection is observable and preserves the Control helper`() {
        val fixture = fixture()
        val observed = mutableListOf<UninstallCleanupObservation>()
        val rejection =
            assertThrows<ManagementRejected> {
                removeConfiguredUninstallHost(fixture.payload, fixture.home, observed::add) {
                    HostRemovalProcessObservation.Exited(
                        1,
                        Json.encodeToString(RejectionFixture("REJECTED", "RECOVERY_REQUIRED")),
                    )
                }
            }
        assertEquals("uninstall-host-plugin", rejection.stage)
        assertEquals(
            UninstallCleanupObservation.HostRejected(HostPluginCleanupFailure.RECOVERY_REQUIRED),
            observed.last(),
        )
        assertTrue(Files.exists(fixture.helper))
    }

    @Test
    fun `success-shaped reply cannot authorize a still-present plugin`() {
        val fixture = fixture()
        val sentinel = Files.createDirectories(fixture.plugin).resolve("protected")
        Files.writeString(sentinel, "plugin remains")
        val observed = mutableListOf<UninstallCleanupObservation>()
        assertThrows<ManagementRejected> {
            removeConfiguredUninstallHost(fixture.payload, fixture.home, observed::add) {
                HostRemovalProcessObservation.Exited(
                    0,
                    Json.encodeToString(RemovalFixture("REMOVED", fixture.plugin.toString())),
                )
            }
        }
        assertEquals(
            UninstallCleanupObservation.HostRejected(HostPluginCleanupFailure.OUTPUT_REJECTED),
            observed.last(),
        )
        assertEquals("plugin remains", Files.readString(sentinel))
        assertTrue(Files.exists(fixture.helper))
    }

    @Test
    fun `altered helper and absent saved selection reject before a child can run`() {
        val fixture = fixture()
        val original = Files.readString(fixture.helper)
        Files.writeString(fixture.helper, "changed helper")
        val observed = mutableListOf<UninstallCleanupObservation>()
        assertThrows<ManagementRejected> {
            removeConfiguredUninstallHost(fixture.payload, fixture.home, observed::add) {
                throw AssertionError("unexpected child")
            }
        }
        assertEquals(
            UninstallCleanupObservation.HostRejected(HostPluginCleanupFailure.OWNERSHIP_UNPROVEN),
            observed.last(),
        )
        Files.writeString(fixture.helper, original)
        Files.delete(fixture.payload.resolve("config/selected-ide.json"))
        assertThrows<ManagementRejected> {
            removeConfiguredUninstallHost(fixture.payload, fixture.home, observed::add) {
                throw AssertionError("unexpected child")
            }
        }
        assertEquals(
            UninstallCleanupObservation.HostRejected(HostPluginCleanupFailure.CONFIGURATION_UNPROVEN),
            observed.last(),
        )
        assertTrue(Files.exists(fixture.helper))
    }

    @Test
    fun `vendor profile changes cannot redirect the recorded Host removal target`() {
        val fixture = fixture()
        Files.writeString(
            fixture.metadata,
            Json { encodeDefaults = true }
                .encodeToString(ProductFixture("262.1234.1", "IntelliJIdeaChanged", listOf(LaunchFixture()))),
        )
        val observed = mutableListOf<UninstallCleanupObservation>()
        var calls = 0
        removeConfiguredUninstallHost(fixture.payload, fixture.home, observed::add) { arguments ->
            calls++
            assertEquals(fixture.plugin.parent.toString(), arguments.last())
            HostRemovalProcessObservation.Exited(
                0,
                Json.encodeToString(RemovalFixture("REMOVED", fixture.plugin.toString())),
            )
        }
        assertEquals(1, calls)
        assertEquals(UninstallCleanupObservation.Completed(UninstallArtifact.HOST_PLUGIN), observed.last())
        assertFalse(Files.exists(fixture.home.resolve("Library/Application Support/JetBrains/IntelliJIdeaChanged")))
    }

    @Test
    fun `legacy launch selection cannot manufacture an unrecorded Host target`() {
        val fixture = fixture()
        val selected =
            managementJson.decodeFromString<SelectedIdeLaunch>(Files.readString(fixture.saved))
                as SelectedIdeLaunch.Resolved
        Files.writeString(
            fixture.saved,
            Json.encodeToString(
                LegacySelectionFixture("resolved", selected.home, selected.bundle, selected.executable)
            ),
        )
        val observed = mutableListOf<UninstallCleanupObservation>()
        assertThrows<ManagementRejected> {
            removeConfiguredUninstallHost(fixture.payload, fixture.home, observed::add) {
                throw AssertionError("unexpected child")
            }
        }
        assertEquals(
            UninstallCleanupObservation.HostRejected(HostPluginCleanupFailure.TARGET_UNRECORDED),
            observed.last(),
        )
        assertTrue(Files.exists(fixture.helper))
    }

    @Test
    fun `captured mutable selection retains exact contents and physical identity`() {
        val fixture = fixture()
        val captured =
            org.junit.jupiter.api
                .assertInstanceOf<Refinement.Refined<CapturedUninstallIdeSelection>>(
                    CapturedUninstallIdeSelection.admit(fixture.saved)
                )
                .value
        assertTrue(captured.unchanged())
        val original = Files.readString(fixture.saved)
        Files.writeString(fixture.saved, original + " ")
        assertFalse(captured.unchanged())
        Files.writeString(fixture.saved, original)
        assertTrue(captured.unchanged())
        val replacement = fixture.saved.resolveSibling("replacement.json")
        Files.writeString(replacement, original)
        Files.move(replacement, fixture.saved, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertFalse(captured.unchanged())
    }

    @Test
    fun `escaping recorded target rejects before child execution`() {
        val fixture = fixture()
        val selected =
            managementJson.decodeFromString<SelectedIdeLaunch>(Files.readString(fixture.saved))
                as SelectedIdeLaunch.Resolved
        Files.writeString(
            fixture.saved,
            Json.encodeToString(
                SelectionFixture(
                    "resolved",
                    selected.home,
                    selected.bundle,
                    selected.executable,
                    TargetFixture("RECORDED", temporary.resolve("foreign/plugins").toString()),
                )
            ),
        )
        val observed = mutableListOf<UninstallCleanupObservation>()
        assertThrows<ManagementRejected> {
            removeConfiguredUninstallHost(fixture.payload, fixture.home, observed::add) {
                throw AssertionError("unexpected child")
            }
        }
        assertEquals(
            UninstallCleanupObservation.HostRejected(HostPluginCleanupFailure.CONFIGURATION_UNPROVEN),
            observed.last(),
        )
        assertTrue(Files.exists(fixture.helper))
    }

    private fun fixture(): Fixture {
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val payload = Files.createDirectories(home.resolve(".local/share/kast/installation"))
        val helper = Files.createDirectories(payload.resolve("share/kast")).resolve("host-installation.py")
        Files.writeString(helper, "manifest-owned helper fixture")
        val ide = Files.createDirectories(temporary.resolve("Selected.app/Contents")).toRealPath()
        val metadata = Files.createDirectories(ide.resolve("Resources")).resolve("product-info.json")
        Files.writeString(
            metadata,
            Json { encodeDefaults = true }
                .encodeToString(ProductFixture("262.1234.1", "IntelliJIdea2026.2", listOf(LaunchFixture()))),
        )
        val launcher = Files.createDirectories(ide.resolve("MacOS")).resolve("idea")
        Files.writeString(launcher, "launcher fixture")
        assertTrue(launcher.toFile().setExecutable(true))
        val plugin = home.resolve("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted")
        val target =
            org.junit.jupiter.api.assertInstanceOf<Refinement.Refined<InstalledHostPluginTarget>>(
                SelectedIdeInstallation.admitPluginTarget(plugin.parent.toString(), home)
            )
        val selected = SelectedIdeInstallation.resolve(ide) as SelectedIdeLaunch.Resolved
        val recorded = target.value as InstalledHostPluginTarget.Recorded
        val saved = Files.createDirectories(payload.resolve("config")).resolve("selected-ide.json")
        Files.writeString(
            saved,
            Json.encodeToString(
                SelectionFixture(
                    "resolved",
                    selected.home,
                    selected.bundle,
                    selected.executable,
                    TargetFixture("RECORDED", recorded.root),
                )
            ),
        )
        val owned =
            listOf(helper).map {
                BundledPayload(payload.relativize(it).toString(), "sha256:${sha256(it)}", 420)
            }
        Files.writeString(
            payload.resolve("installation.json"),
            managementJson.encodeToString(BundledManifest(3, payload.toString(), owned)),
        )
        return Fixture(home, payload, helper, metadata, plugin, saved)
    }

    private data class Fixture(
        val home: Path,
        val payload: Path,
        val helper: Path,
        val metadata: Path,
        val plugin: Path,
        val saved: Path,
    )
}

@Serializable private data class RemovalFixture(val type: String, val plugin: String)

@Serializable private data class RejectionFixture(val type: String, val failure: String)

@Serializable
private data class ProductFixture(
    val buildNumber: String,
    val dataDirectoryName: String,
    val launch: List<LaunchFixture>,
)

@Serializable
private data class LaunchFixture(
    val os: String = "macOS",
    val arch: String = "aarch64",
    val launcherPath: String = "../MacOS/idea",
)

@Serializable
private data class LegacySelectionFixture(
    val type: String,
    val home: String,
    val bundle: String,
    val executable: String,
)

@Serializable
private data class SelectionFixture(
    val type: String,
    val home: String,
    val bundle: String,
    val executable: String,
    val hostPluginTarget: TargetFixture,
)

@Serializable private data class TargetFixture(val type: String, val root: String)
