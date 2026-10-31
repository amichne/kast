package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedUpgradeTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `shutdown recovery precedes pin admission and stops after the child`() {
        val fixture = fixture(pinned = true)
        Files.writeString(fixture.root.resolve(SHUTDOWN_FENCE), "pending")
        assertRecovery(fixture)
    }

    @Test
    fun `partial replacement storage precedes catalog reads`() {
        val fixture = fixture()
        Files.createDirectories(fixture.root.resolve("recovery/replacement"))
        assertRecovery(fixture)
    }

    @Test
    fun `recovery works before the public executable receipt agrees`() {
        val fixture = fixture()
        Files.writeString(fixture.root.resolve(SHUTDOWN_FENCE), "pending")
        Files.writeString(receiptPath(fixture.root), "uncommitted receipt")
        assertRecovery(fixture)
    }

    @Test
    fun `missing recovery bridge rejects without fresh discovery`() {
        val fixture = fixture()
        Files.writeString(fixture.root.resolve(SHUTDOWN_FENCE), "pending")
        Files.delete(fixture.installation.resolve("share/kast/install-candidate.sh"))
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(),
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { error("discovery during recovery") },
                    installer = { _, _, _ -> error("unowned recovery child") },
                ),
            )
        assertEquals(
            ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED),
            result,
        )
    }

    @Test
    fun `missing installation preserves the exact fence and skips release reads`() {
        val fixture = fixture(pinned = true)
        val fence = fixture.root.resolve(SHUTDOWN_FENCE)
        val bytes =
            managementJson.encodeToString(InstallationShutdownRequest(fixture.installation.toString())).toByteArray()
        Files.write(fence, bytes)
        val priorReceipt = Files.readString(receiptPath(fixture.root))
        Files.move(fixture.installation, fixture.root.resolve("retained-fixture-installation"))
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(json = true),
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { error("release read during missing-installation recovery") },
                    installer = { _, _, _ -> error("missing installation cannot supply an admitted bridge") },
                ),
            )
        assertEquals(
            ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED),
            result,
        )
        assertEquals(bytes.toList(), Files.readAllBytes(fence).toList())
        assertEquals(priorReceipt, Files.readString(receiptPath(fixture.root)))
    }

    @Test
    fun `missing installation publishes one finite recovery JSON result`() {
        val home = temporary.toRealPath().resolve("json-home")
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        val fence = root.resolve(SHUTDOWN_FENCE)
        val bytes =
            managementJson
                .encodeToString(InstallationShutdownRequest(root.resolve("installation").toString()))
                .toByteArray()
        Files.write(fence, bytes)
        val child =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "io.github.amichne.kast.distribution.cli.KastManagementMainKt",
                    "upgrade",
                    "--json",
                )
                .directory(home.toFile())
                .apply {
                    environment().clear()
                    environment()["HOME"] = home.toString()
                }
                .start()
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(1, child.exitValue())
            val stdout = child.inputReader().readText()
            assertEquals(1, stdout.lines().count { it.isNotEmpty() })
            val document = managementJson.decodeFromString<JsonObject>(stdout)
            assertEquals(setOf("type", "reason"), document.keys)
            assertEquals("RECOVERY_REQUIRED", document.getValue("type").jsonPrimitive.content)
            assertEquals("INSTALLER_CONTRACT_UNSUPPORTED", document.getValue("reason").jsonPrimitive.content)
            assertEquals(bytes.toList(), Files.readAllBytes(fence).toList())
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `unqualified pin rejects before release reads and child creation`() {
        val fixture = fixture(pinned = true)
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(),
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { error("release read for a pin") },
                    installer = { _, _, _ -> error("child for a pin") },
                ),
            )
        assertEquals(ManagedUpgradeOutcome.Rejected(BuildSelectionFailure.PINNED_BUILD), result)
    }

    @Test
    fun `explicit selection captures bytes once and preserves interrupt exit`() {
        val fixture = fixture()
        val request = BuildRequest.Version((StableVersion.parse("1.2.3") as SelectionAdmission.Admitted).value)
        var captured: Path? = null
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(request = request),
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { error("exact stable selection uses no discovery") },
                    installer = { argv, _, _ ->
                        val file = Path.of(argv[argv.indexOf("--resolved-selection") + 1])
                        captured = file
                        assertEquals(
                            ResolvedBuildSelection.Stable("1.2.3", "v1.2.3", StableSelectionPolicy.PinnedBuild),
                            managementJson.decodeFromString<ResolvedBuildSelection>(Files.readString(file)),
                        )
                        130
                    },
                ),
            )
        assertEquals(ManagedUpgradeOutcome.ChildExited(130), result)
        assertFalse(Files.exists(captured!!))
    }

    @Test
    fun `selection cleanup failure preserves the reaped child exit and reports one notice`() {
        val fixture = fixture()
        val request = BuildRequest.Version((StableVersion.parse("1.2.3") as SelectionAdmission.Admitted).value)
        val notices = mutableListOf<ManagedUpgradeNotice>()
        var captured: Path? = null
        try {
            val result =
                executeManagedUpgrade(
                    fixture.root,
                    fixture.home,
                    ManagementCommand.Upgrade(request = request),
                    emptyMap(),
                    ManagedUpgradeExecution(
                        readRelease = { error("exact stable selection uses no discovery") },
                        installer = { argv, _, _ ->
                            val path = Path.of(argv[argv.indexOf("--resolved-selection") + 1])
                            captured = path
                            Files.delete(path)
                            Files.createDirectory(path)
                            Files.writeString(path.resolve("kept"), "fixture-owned bytes")
                            130
                        },
                        notice = notices::add,
                    ),
                )
            assertEquals(ManagedUpgradeOutcome.ChildExited(130), result)
            assertEquals(listOf(ManagedUpgradeNotice.CAPTURED_SELECTION_CLEANUP_FAILED), notices)
            assertEquals("fixture-owned bytes", Files.readString(captured!!.resolve("kept")))
        } finally {
            captured?.let { path ->
                Files.deleteIfExists(path.resolve("kept"))
                Files.deleteIfExists(path)
            }
        }
    }

    private fun assertRecovery(fixture: LifecycleFixture) {
        var reads = 0
        var calls = 0
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(dryRun = true, json = true, restartIde = true, ideaHome = "ignored"),
                mapOf("EXAMPLE" to "kept"),
                ManagedUpgradeExecution(
                    readRelease = {
                        reads++
                        ReleaseDocumentRead.Unavailable
                    },
                    installer = { argv, directory, environment ->
                        calls++
                        assertEquals(
                            listOf(
                                "/bin/bash",
                                fixture.installation.resolve("share/kast/install-candidate.sh").toString(),
                                "--recover-only",
                                "--install-root",
                                fixture.root.toString(),
                                "--dry-run",
                                "--json",
                            ),
                            argv,
                        )
                        assertEquals(fixture.installation, directory)
                        assertEquals(mapOf("EXAMPLE" to "kept", "HOME" to fixture.home.toString()), environment)
                        0
                    },
                ),
            )
        assertEquals(0, reads)
        assertEquals(1, calls)
        assertEquals(ManagedUpgradeOutcome.ChildExited(0), result)
    }

    private fun fixture(pinned: Boolean = false): LifecycleFixture {
        val fixture = createLifecycleFixture(temporary)
        val manifest = (readBundledManifest(fixture.installation) as BundledManifestRead.Read).manifest
        val relative = "share/kast/install-candidate.sh"
        val bridge = fixture.installation.resolve(relative)
        Files.writeString(bridge, "#!/bin/bash\nexit 0\n")
        Files.writeString(
            fixture.installation.resolve("installation.json"),
            managementJson.encodeToString(
                FixtureLifecycleManifest(
                    3,
                    "1.2.3",
                    fixture.installation.toString(),
                    manifest.payloadFiles + BundledPayload(relative, "sha256:${sha256(bridge)}", 493),
                )
            ),
        )
        if (pinned) {
            val receipt = (readReceipt(fixture.root) as ReceiptRead.Read).receipt
            val identity = InstalledBuildIdentity("v1.2.3", "1.2.3", "a".repeat(40), "b".repeat(40))
            writeManagementReceipt(
                fixture.root,
                receipt.copy(updatePolicy = UpdatePolicy.PinnedBuild(identity), installedBuild = identity),
            )
        }
        return fixture
    }
}
