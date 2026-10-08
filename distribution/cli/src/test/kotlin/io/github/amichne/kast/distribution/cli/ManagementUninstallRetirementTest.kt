package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ManagementUninstallRetirementTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `changed configuration on requested removal rejects before either child`() {
        val prepared = changedConfigurationFixture()
        val fixture = prepared.installation
        val events = mutableListOf<UninstallCleanupObservation>()
        assertThrows<ManagementRejected> {
            uninstallInstallation(
                fixture.root,
                fixture.home,
                executeInstaller = { _, _ -> throw AssertionError("unexpected Control child") },
                observe = events::add,
                executeHost = { throw AssertionError("unexpected Host child") },
            )
        }
        assertEquals(
            UninstallCleanupObservation.HomeConfigurationRejected(HomeConfigurationCleanupFailure.CONTENT_CHANGED),
            events.last(),
        )
        assertEquals(prepared.changed, Files.readString(prepared.configuration))
        assertTrue(Files.exists(prepared.script))
        assertTrue(Files.exists(fixture.executable))
        assertEquals(UninstallRetirementType.REMOVAL_REQUESTED, readUninstallRetirement(fixture.root).type)
    }

    @Test
    fun `Control child exit nine is structured and keeps requested ownership proof`() {
        val prepared = controlledExitFixture()
        val fixture = prepared.installation
        val events = mutableListOf<UninstallCleanupObservation>()
        var controlCalls = 0
        var hostCalls = 0
        assertThrows<ManagementRejected> {
            uninstallInstallation(
                fixture.root,
                fixture.home,
                executeInstaller = { script, args ->
                    controlCalls++
                    controlledExitNine(prepared, script, args)
                },
                observe = events::add,
                executeHost = { args ->
                    hostCalls++
                    successfulAbsentHost(prepared, args)
                },
            )
        }
        assertEquals(1, controlCalls)
        assertEquals(1, hostCalls)
        assertTrue(events.contains(UninstallCleanupObservation.ControlChildRejected(9)))
        assertEquals(UninstallRetirementType.REMOVAL_REQUESTED, readUninstallRetirement(fixture.root).type)
        assertTrue(Files.exists(prepared.installer))
        assertTrue(Files.exists(fixture.executable))
        assertTrue(Files.exists(receiptPath(fixture.root)))
    }

    private fun changedConfigurationFixture(): ChangedConfigurationFixture {
        val fixture = retiredUninstallFixture(temporary)
        val payload = Files.createDirectory(fixture.root.resolve("installation"))
        val script = Files.createDirectories(payload.resolve("share/kast")).resolve("install.sh")
        Files.writeString(script, "manifest-owned private installer")
        val manifest =
            BundledManifest(
                3,
                payload.toString(),
                listOf(BundledPayload("share/kast/install.sh", "sha256:${sha256(script)}", 420)),
            )
        Files.writeString(payload.resolve("installation.json"), managementJson.encodeToString(manifest))
        val configuration = connectionConfigurationPath(fixture.home)
        Files.createDirectories(configuration.parent)
        writeConnectionConfiguration(configuration, ConnectionConfiguration.empty())
        val originalProof = captureUninstallHomeConfiguration(fixture.home, {})
        val record =
            managementJson.decodeFromString<FixtureRetirementRecord>(
                Files.readString(uninstallJournalPath(fixture.root))
            )
        Files.writeString(
            uninstallJournalPath(fixture.root),
            managementJson.encodeToString(
                record.copy(
                    type = "REMOVAL_REQUESTED",
                    controlIdentity = uninstallFilesystemIdentity(payload),
                    homeConfiguration = originalProof,
                )
            ),
        )
        val changed =
            managementJson.encodeToString(
                ConnectionConfigurationDocument(
                    1,
                    listOf(SavedConnectionDirectory(HarnessConnection.PI, fixture.home.resolve("chosen").toString())),
                )
            )
        Files.writeString(configuration, changed)
        return ChangedConfigurationFixture(fixture, script, configuration, changed)
    }

    private fun controlledExitFixture(): ControlChildFixture {
        val fixture = retiredUninstallFixture(temporary)
        Files.delete(uninstallJournalPath(fixture.root))
        val payload = Files.createDirectory(fixture.root.resolve("installation"))
        val share = Files.createDirectories(payload.resolve("share/kast"))
        val installer = Files.writeString(share.resolve("install.sh"), "manifest-owned installer")
        val helper = Files.writeString(share.resolve("host-installation.py"), "manifest-owned Host helper")
        val pluginRoot = fixture.home.resolve("Library/Application Support/JetBrains/OwnedProfile/plugins")
        val target =
            (io.github.amichne.kast.distribution.managed.SelectedIdeInstallation.admitPluginTarget(
                    pluginRoot.toString(),
                    fixture.home,
                ) as io.github.amichne.kast.kernel.Refinement.Refined)
                .value
        val selection =
            SavedSelectionFixture(
                "resolved",
                "/opt/Selected.app/Contents",
                "/opt/Selected.app",
                "/opt/Selected.app/Contents/MacOS/idea",
                target,
            )
        val selected = Files.createDirectories(payload.resolve("config")).resolve("selected-ide.json")
        Files.writeString(selected, managementJson.encodeToString(selection))
        val entries =
            listOf(installer, helper).map {
                BundledPayload(payload.relativize(it).toString(), "sha256:${sha256(it)}", 420)
            }
        Files.writeString(
            payload.resolve("installation.json"),
            managementJson.encodeToString(BundledManifest(3, payload.toString(), entries)),
        )
        return ControlChildFixture(fixture, installer, helper, pluginRoot)
    }

    private fun controlledExitNine(prepared: ControlChildFixture, script: Path, args: List<String>): Int {
        val fixture = prepared.installation
        assertEquals(prepared.installer, script)
        assertEquals(
            listOf(
                "uninstall",
                "--managed-registrations",
                "--install-root",
                fixture.root.toString(),
                "--uninstall-journal",
                uninstallJournalPath(fixture.root).toString(),
            ),
            args,
        )
        assertEquals(UninstallRetirementType.REMOVAL_REQUESTED, readUninstallRetirement(fixture.root).type)
        return 9
    }

    private fun successfulAbsentHost(prepared: ControlChildFixture, args: List<String>): HostRemovalProcessObservation {
        assertEquals(
            listOf(
                "python3",
                "-I",
                prepared.helper.toString(),
                "--remove",
                "--plugin-root",
                prepared.pluginRoot.toString(),
            ),
            args,
        )
        return HostRemovalProcessObservation.Exited(
            0,
            managementJson.encodeToString(
                HostRemovalFixture("REMOVED", prepared.pluginRoot.resolve("kast-ide-hosted").toString())
            ),
        )
    }

    private data class ChangedConfigurationFixture(
        val installation: RetiredFixture,
        val script: Path,
        val configuration: Path,
        val changed: String,
    )

    private data class ControlChildFixture(
        val installation: RetiredFixture,
        val installer: Path,
        val helper: Path,
        val pluginRoot: Path,
    )

    @Serializable
    private data class SavedSelectionFixture(
        val type: String,
        val home: String,
        val bundle: String,
        val executable: String,
        val hostPluginTarget: io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget,
    )

    @Serializable private data class HostRemovalFixture(val type: String, val plugin: String)
}
