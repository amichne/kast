package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReplacementInstallationWorkflowTest {
    @Test
    fun `upgrade retains the admitted workspace registry`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("home/.local/share/kast")
        val commands = root.resolve("home/.local/bin")
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(home.resolve(".codex"))
        val firstWorkspace = Files.createDirectory(root.resolve("first-workspace"))
        val secondWorkspace = Files.createDirectory(root.resolve("second-workspace"))

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        val registry =
            Json.encodeToString(RegistryFixture(2, 2, listOf(firstWorkspace.toString(), secondWorkspace.toString())))
        Files.writeString(prior.resolve("config/workspaces.json"), registry)

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.4")),
        )

        val selected = installation.resolve("installation")
        assertEquals(registry, Files.readString(selected.resolve("config/workspaces.json")))
    }

    @Test
    fun `upgrade rejects a corrupt prior registry and preserves selection`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("home/.local/share/kast")
        val commands = root.resolve("home/.local/bin")
        val home = Files.createDirectory(root.resolve("home"))
        val codex = Files.createDirectory(root.resolve("codex"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codex, "1.2.3")),
        )
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        Files.writeString(prior.resolve("config/workspaces.json"), "broken registry")
        val rejected =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                executeFixtureInstallation(releaseRequest(root, installation, commands, home, codex, "1.2.4")),
            )
        assertEquals(InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED, rejected.failure)
        assertEquals("broken registry", Files.readString(prior.resolve("config/workspaces.json")))
        val selected = installation.resolve("installation")
        assertEquals(prior, selected)
    }

    @Test
    fun `missing payload cannot overwrite retained recovery or management evidence`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("home/.local/share/kast")
        val request =
            releaseRequest(
                root,
                installation,
                root.resolve("home/.local/bin"),
                Files.createDirectory(root.resolve("home")),
                Files.createDirectory(root.resolve("codex")),
                "1.2.3",
            )
        assertFixtureComplete(executeFixtureInstallation(request))
        discardFixtureReplacementAfterSetup(installation)
        val bundle = installation.resolve("recovery/installation")
        val receipt = bundle.resolve("receipt.json")
        val receiptBytes = Files.readAllBytes(receipt)
        val receiptIdentity = observeInstallationFilesystemIdentity(receipt)
        val script = bundle.resolve("installation-recovery.py")
        val scriptBytes = Files.readAllBytes(script)
        val scriptIdentity = observeInstallationFilesystemIdentity(script)
        val management =
            Files.writeString(installation.resolve("management.receipt.json"), "foreign management evidence")
        val managementIdentity = observeInstallationFilesystemIdentity(management)
        Files.move(installation.resolve("installation"), root.resolve("unselected-payload"))
        val rejected = assertInstanceOf(InstallationOutcome.Rejected::class.java, executeFixtureInstallation(request))
        assertEquals(InstallationFailure.RECOVERY_REQUIRED, rejected.failure)
        org.junit.jupiter.api.Assertions.assertArrayEquals(receiptBytes, Files.readAllBytes(receipt))
        assertEquals(receiptIdentity, observeInstallationFilesystemIdentity(receipt))
        org.junit.jupiter.api.Assertions.assertArrayEquals(scriptBytes, Files.readAllBytes(script))
        assertEquals(scriptIdentity, observeInstallationFilesystemIdentity(script))
        assertEquals("foreign management evidence", Files.readString(management))
        assertEquals(managementIdentity, observeInstallationFilesystemIdentity(management))
        assertTrue(Files.notExists(installation.resolve("installation")))
        assertTrue(Files.notExists(installation.resolve("recovery/replacement")))
    }

    @Test
    fun `upgrade preserves an absent workspace registry`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("home/.local/share/kast")
        val commands = root.resolve("home/.local/bin")
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(root.resolve("codex"))
        assertFixtureComplete(
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3"))
        )
        discardFixtureReplacementAfterSetup(installation)
        val next = releaseRequest(root, installation, commands, home, codexHome, "1.2.4")
        Files.copy(
            Path.of("../packaging/installation-lifecycle.py"),
            next.controlRoot.value.resolve("share/kast/installation-lifecycle.py"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        assertFixtureComplete(executeFixtureInstallation(next))
        assertTrue(Files.notExists(installation.resolve("installation/config/workspaces.json")))
    }

    @Test
    fun `force repairs a corrupt manifest only after independent recovery and candidate qualification`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val installation = root.resolve("home/.local/share/kast")
        val commands = root.resolve("home/.local/bin")
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(root.resolve("codex"))
        assertFixtureComplete(
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3"))
        )
        discardFixtureReplacementAfterSetup(installation)
        val selected = installation.resolve("installation")
        val identity = observeInstallationFilesystemIdentity(selected)
        val manifest = selected.resolve("installation.json")
        Files.writeString(manifest, "corrupt original manifest")
        val receipt = installation.resolve("recovery/installation/receipt.json")
        val recovery = Files.readString(receipt)
        val forced =
            releaseRequest(
                root,
                installation,
                commands,
                home,
                codexHome,
                "1.2.4",
                environmentOverrides = mapOf("KAST_INSTALL_FORCE" to "1"),
            )
        val candidate = forced.controlRoot.value.resolve("bin/kast")
        Files.writeString(candidate, "#!/bin/sh\nexit 1\n")
        val unqualified = assertInstanceOf(InstallationOutcome.Rejected::class.java, executeFixtureInstallation(forced))
        assertEquals(InstallationFailure.CANDIDATE_QUALIFICATION_REJECTED, unqualified.failure)
        assertEquals("corrupt original manifest", Files.readString(manifest))
        assertEquals(identity, observeInstallationFilesystemIdentity(selected))
        assertTrue(Files.notExists(selected.resolve(".recovery-detached")))
        Files.writeString(candidate, "#!/bin/sh\nexit 0\n")
        Files.writeString(receipt, "unproven recovery")
        val unproven = assertInstanceOf(InstallationOutcome.Rejected::class.java, executeFixtureInstallation(forced))
        assertEquals(InstallationFailure.RECOVERY_REQUIRED, unproven.failure)
        assertEquals("corrupt original manifest", Files.readString(manifest))
        assertEquals(identity, observeInstallationFilesystemIdentity(selected))
        assertTrue(Files.notExists(selected.resolve(".recovery-detached")))
        Files.writeString(receipt, recovery)
        assertFixtureComplete(executeFixtureInstallation(forced))
        assertRepairedInstallation(installation, manifest)
    }

    private fun assertRepairedInstallation(installation: Path, manifest: Path) {
        val replacement =
            assertInstanceOf(
                PendingInstallationReplacement.Committed::class.java,
                observePendingInstallationReplacement(installation),
            )
        val previous = assertInstanceOf(PreviousInstallationPayload.Physical::class.java, replacement.receipt.previous)
        assertEquals(
            "corrupt original manifest",
            Files.readString(Path.of(previous.payload).resolve("installation.json")),
        )
        assertEquals(
            "3",
            Json.parseToJsonElement(Files.readString(manifest))
                .jsonObject
                .getValue("schemaVersion")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `verified legacy selection migrates into one physical payload and keeps a pending baseline`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val installation = root.resolve("home/.local/share/kast")
        val home = Files.createDirectory(root.resolve("home"))
        val commands = Files.createDirectories(home.resolve(".local/bin"))
        val codexHome = Files.createDirectory(root.resolve("codex"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        discardFixtureReplacementAfterSetup(installation)
        val direct = installation.resolve("installation")
        val legacy = prepareLegacySelection(root, installation, direct)
        val selector = installation.resolve("current")
        val legacyIdentity = observeInstallationFilesystemIdentity(legacy)

        assertFixtureComplete(
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.4"))
        )
        val pending =
            assertInstanceOf(
                PendingInstallationReplacement.Committed::class.java,
                observePendingInstallationReplacement(installation),
            )
        val previous = assertInstanceOf(PreviousInstallationPayload.Legacy::class.java, pending.receipt.previous)
        assertEquals(legacy.toString(), previous.installation)
        assertEquals(legacyIdentity, previous.identity)
        assertEquals(legacyIdentity, observeInstallationFilesystemIdentity(legacy))
        assertEquals(Path.of("versions/${legacy.fileName}"), Files.readSymbolicLink(selector))
        assertTrue(Files.isDirectory(direct))
        assertEquals(
            Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString()))),
            Files.readString(direct.resolve("config/workspaces.json")),
        )
        assertTrue(Files.isRegularFile(Path.of(previous.recovery).resolve("receipt.json")))
    }

    private fun prepareLegacySelection(root: Path, installation: Path, direct: Path): Path {
        val legacy = Files.createDirectory(installation.resolve("versions")).resolve("1.2.3-admitted")
        Files.move(direct, legacy)
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
        val manifest =
            json
                .decodeFromString(
                    LegacyManifestFixture.serializer(),
                    Files.readString(legacy.resolve("installation.json")),
                )
                .copy(
                    schemaVersion = 2,
                    installationRoot = legacy.toString(),
                    configuration = legacy.resolve("config/environment").toString(),
                    workspaceRegistry = legacy.resolve("config/workspaces.json").toString(),
                    stateRoot = legacy.resolve("state").toString(),
                    externalAnchors = emptyList(),
                )
        Files.writeString(legacy.resolve("installation.json"), json.encodeToString(manifest))
        Files.writeString(legacy.resolve("config/environment"), "")
        Files.writeString(
            legacy.resolve("config/workspaces.json"),
            Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString()))),
        )
        val selector = installation.resolve("current")
        Files.createSymbolicLink(selector, Path.of("versions/${legacy.fileName}"))
        val recovery = installation.resolve("recovery").resolve(legacy.fileName)
        Files.move(installation.resolve("recovery/installation"), recovery)
        Files.writeString(
            recovery.resolve("receipt.json"),
            Json { encodeDefaults = true }
                .encodeToString(
                    LegacyRecoveryFixture(
                        installation = legacy.toString(),
                        installationIdentity = observeInstallationFilesystemIdentity(legacy),
                        links = listOf(LegacyLinkFixture(selector.toString(), "versions/${legacy.fileName}", null)),
                    )
                ),
        )
        return legacy
    }

    @Test
    fun `upgrade keeps one physical rollback baseline until outer activation seals`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("home/.local/share/kast")
        val home = Files.createDirectory(root.resolve("home"))
        val commands = Files.createDirectories(home.resolve(".local/bin"))
        val codexHome = Files.createDirectory(home.resolve(".codex"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        val priorIdentity = observeInstallationFilesystemIdentity(prior)
        Files.writeString(
            prior.resolve("config/workspaces.json"),
            Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString()))),
        )
        val preserved = Files.createDirectories(prior.resolve("state/work"))
        Files.writeString(preserved.resolve("retained"), "prior state")

        assertFixtureComplete(
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.4"))
        )
        val replacement =
            assertInstanceOf(
                PendingInstallationReplacement.Committed::class.java,
                observePendingInstallationReplacement(installation),
            )
        val previous =
            assertInstanceOf(
                io.github.amichne.kast.distribution.contract.PreviousInstallationPayload.Physical::class.java,
                replacement.receipt.previous,
            )
        assertEquals(priorIdentity, previous.identity)
        assertEquals("prior state", Files.readString(prior.resolve("state/work/retained")))
        assertEquals("prior state", Files.readString(Path.of(previous.payload).resolve("state/work/retained")))
        assertTrue(Files.isRegularFile(Path.of(previous.recovery).resolve("receipt.json")))
        assertTrue(Files.notExists(installation.resolve("current")))
        assertTrue(Files.notExists(installation.resolve("versions")))
        Files.walk(installation).use { paths -> assertTrue(paths.noneMatch(Files::isSymbolicLink)) }
    }
}

@Serializable
private data class LegacyManifestFixture(
    val schemaVersion: Int,
    val semanticVersion: String,
    val installationRoot: String,
    val payloadIdentity: String,
    val controlSha256: String,
    val hostedPluginSha256: String? = null,
    val codexHome: String,
    val configuration: String,
    val workspaceRegistry: String,
    val stateRoot: String,
    val externalAnchors: List<LegacyAnchorFixture>,
    val payloadFiles: List<LegacyPayloadFileFixture>,
    val retention: LegacyRetentionFixture,
)

@Serializable
private data class LegacyAnchorFixture(
    val kind: String,
    val path: String,
    val expectedLinkTarget: String? = null,
    val requiresCurrentTarget: String? = null,
    val expectedExecutable: String? = null,
    val expectedLabel: String? = null,
    val identityReceipt: String? = null,
    val expectedPhysicalDirectory: String? = null,
    val ownership: String,
)

@Serializable private data class LegacyPayloadFileFixture(val path: String, val sha256: String, val mode: Int)

@Serializable
private data class LegacyRetentionFixture(
    val payload: String,
    val config: String,
    val state: String,
    val externalAnchors: String,
)

@Serializable
private data class LegacyRecoveryFixture(
    val schemaVersion: Int = 2,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val links: List<LegacyLinkFixture>,
    val priorInstallation: String? = null,
    val plugin: LegacyRecoveryPluginFixture? = null,
    val pluginRoot: String? = null,
    val stage: String = "Prepared",
)

@Serializable private data class LegacyLinkFixture(val path: String, val target: String, val priorTarget: String?)

@Serializable
private data class LegacyRecoveryPluginFixture(
    val destination: String,
    val candidate: String,
    val candidateIdentity: InstallationFilesystemIdentity,
    val backup: String,
    val priorIdentity: InstallationFilesystemIdentity?,
    val quarantine: String,
)

private fun assertFixtureComplete(outcome: InstallationOutcome): InstallationOutcome.Complete =
    assertInstanceOf(InstallationOutcome.Complete::class.java, outcome, outcome.toString())
