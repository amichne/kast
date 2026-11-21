package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `missing bridge or changed private support rejects without effects`(missingBridge: Boolean) {
        val fixture = fixture()
        val fence = fixture.root.resolve(SHUTDOWN_FENCE)
        Files.writeString(fence, "pending")
        val receipt = Files.readAllBytes(receiptPath(fixture.root)).toList()
        if (missingBridge) Files.delete(fixture.installation.resolve("share/kast/install-candidate.sh"))
        else Files.writeString(fixture.installation.resolve("share/kast/bootstrap_entry.py"), "changed")
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(),
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { error("discovery during recovery") },
                    installer = { _, _, _ -> error("changed private support must not execute") },
                ),
            )
        assertEquals(
            ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED),
            result,
        )
        assertEquals("pending", Files.readString(fence))
        assertEquals(receipt, Files.readAllBytes(receiptPath(fixture.root)).toList())
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
                    environment()["KAST_OUTPUT_TOON"] = "0"
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
        val record = productEntry().record
        val raw = managementJson.encodeToString(record)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).toHexString()
        val reads = mutableListOf<String>()
        var captured: Path? = null
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(request = request),
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { url ->
                        reads += url
                        exactStableRead(url, raw, digest)
                    },
                    installer = { argv, _, _ ->
                        val file = Path.of(argv[argv.indexOf("--resolved-selection") + 1])
                        captured = file
                        assertEquals(
                            ResolvedBuildSelection.Product(
                                "v1.2.3",
                                record,
                                digest,
                                ProductSelectionPolicy.PinnedBuild,
                                "1.2.3",
                                null,
                            ),
                            managementJson.decodeFromString<ResolvedBuildSelection>(Files.readString(file)),
                        )
                        130
                    },
                ),
            )
        assertEquals(ManagedUpgradeOutcome.ChildExited(130), result)
        assertEquals(2, reads.size)
        assertEquals(2, reads.toSet().size)
        assertFalse(Files.exists(captured!!))
    }

    @Test
    fun `selection cleanup failure preserves the reaped child exit and reports one notice`() {
        val fixture = fixture()
        val request = BuildRequest.Version((StableVersion.parse("1.2.3") as SelectionAdmission.Admitted).value)
        val raw = managementJson.encodeToString(productEntry().record)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).toHexString()
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
                        readRelease = { url -> exactStableRead(url, raw, digest) },
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

    private fun exactStableRead(url: String, record: String, digest: String): ReleaseDocumentRead =
        when (url) {
            "https://github.com/amichne/kast/releases/download/v1.2.3/release-mapping.json" ->
                ReleaseDocumentRead.Read(managementJson.encodeToString(PublicReleaseMapping(1, "1.2.3", digest)))
            "https://github.com/amichne/kast/releases/download/v1.2.3/build-record.json" ->
                ReleaseDocumentRead.Read(record)
            else -> error("Exact stable selection must not discover or substitute another release: $url")
        }

    private fun assertRecovery(fixture: LifecycleFixture) {
        var reads = 0
        var calls = 0
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(dryRun = true, json = true),
                mapOf("EXAMPLE" to "kept"),
                ManagedUpgradeExecution(
                    readRelease = {
                        reads++
                        ReleaseDocumentRead.Unavailable(
                            ReleaseReadObservation.Unavailable(ReleaseReadStage.OPEN, ReleaseReadFailure.IO_FAILURE)
                        )
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
        Files.createDirectories(fixture.installation.resolve("bin"))
        val manifest = (readBundledManifest(fixture.installation) as BundledManifestRead.Read).manifest
        val relative = "share/kast/install-candidate.sh"
        val bridge = fixture.installation.resolve(relative)
        Files.writeString(bridge, "#!/bin/bash\nexit 0\n")
        val support = fixture.installation.resolve("share/kast/bootstrap_entry.py")
        Files.writeString(support, "private dispatch fixture")
        Files.writeString(
            fixture.installation.resolve("installation.json"),
            managementJson.encodeToString(
                FixtureLifecycleManifest(
                    3,
                    "1.2.3",
                    fixture.installation.toString(),
                    manifest.payloadFiles +
                        listOf(
                            BundledPayload(relative, "sha256:${sha256(bridge)}", 493),
                            BundledPayload("share/kast/bootstrap_entry.py", "sha256:${sha256(support)}", 420),
                        ),
                )
            ),
        )
        if (pinned) pinFixtureProduct(fixture)
        return fixture
    }
}

/** A pin is the product's selection policy, recorded in the installation and its receipt. */
private fun pinFixtureProduct(fixture: LifecycleFixture) {
    val receipt = (readReceipt(fixture.root) as ReceiptRead.Read).receipt
    val product = receipt.product.copy(policy = ProductSelectionPolicy.PinnedBuild)
    val selection = fixture.installation.resolve("share/kast/product-selection.json")
    Files.writeString(selection, containerJson.encodeToString(product))
    val manifestPath = fixture.installation.resolve("installation.json")
    val written = managementJson.decodeFromString<FixtureLifecycleManifest>(Files.readString(manifestPath))
    val refreshed =
        written.payloadFiles.map {
            if (it.path == "share/kast/product-selection.json") it.copy(sha256 = "sha256:${sha256(selection)}") else it
        }
    Files.writeString(manifestPath, managementJson.encodeToString(written.copy(payloadFiles = refreshed)))
    writeTestManagementReceipt(fixture.root, Path.of(receipt.executable), product)
}
