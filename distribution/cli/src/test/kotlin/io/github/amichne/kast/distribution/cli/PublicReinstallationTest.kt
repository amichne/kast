package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicReinstallationTest {
    @TempDir lateinit var temporary: Path

    @Test fun `stable force reinstall uses the public command`() = pairedReinstall(ReleaseChannel.STABLE)

    @Test fun `developer force reinstall preserves its channel`() = pairedReinstall(ReleaseChannel.DEVELOPER)

    private fun pairedReinstall(channel: ReleaseChannel) {
        val home = Files.createDirectories(temporary.toRealPath().resolve(channel.name))
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        val command = home.resolve(".local/bin/kast")
        writeManagementReceipt(
            root,
            ManagementReceipt(2, root.toString(), command.toString(), "0".repeat(64), channel, emptyList()),
        )
        val scope =
            (PublicResetScope.admit(root, mapOf("HOME" to home.toString())) as PublicResetAdmission.Admitted).scope
        var calls = 0
        val result =
            reinstallPublicInstallation(
                scope,
                mapOf("PATH" to "/fixture", "KAST_VERSION" to "injected"),
                executor =
                    LifecycleChildExecutor { arguments, directory, environment ->
                        calls++
                        assertEquals(home, directory)
                        assertEquals("/bin/bash", arguments[0])
                        assertEmbeddedBootstrap(Path.of(arguments[1]))
                        assertPublicOptions(arguments, environment, channel, command)
                        prepareFreshPayload(root, command)
                        writeActiveReport(Path.of(environment.getValue("KAST_MANAGEMENT_REPORT_PATH")))
                        LifecycleChildObservation.Exited(0)
                    },
                runtime =
                    PublicReadinessScript(
                        root.resolve("installation"),
                        ResetActivationObservation.Ready("1.2.3", "fresh-generation"),
                    ),
            )
        assertEquals(1, calls)
        assertEquals(
            ForceResetOutcome.Reinstalled(
                root.resolve("installation").toString(),
                "1.2.3",
                command.toString(),
                "fresh-generation",
            ),
            result,
        )
    }

    private fun writeActiveReport(path: Path) =
        Files.writeString(
            path,
            managementJson.encodeToString(
                FixtureInstallerReport(status = "installed", activation = FixtureActivation("ready"))
            ),
        )

    private fun assertEmbeddedBootstrap(script: Path) =
        assertTrue(Files.readString(script).contains("readonly REPOSITORY=\"amichne/kast\""))

    private fun assertPublicOptions(
        arguments: List<String>,
        environment: Map<String, String>,
        channel: ReleaseChannel,
        command: Path,
    ) {
        val options =
            listOf("--force", "--skip-codex-mcp") +
                when (channel) {
                    ReleaseChannel.STABLE -> emptyList()
                    ReleaseChannel.DEVELOPER -> listOf("--developer-latest")
                }
        assertEquals(options, arguments.drop(2))
        assertFalse(arguments.contains("--stage-only"))
        assertEquals(command.parent.toString(), environment["XDG_CONFIG_HOME"])
        assertFalse(environment.containsKey("KAST_VERSION"))
    }

    @Test
    fun `zero child exit without a qualified payload cannot report reinstall success`() {
        val home = Files.createDirectories(temporary.toRealPath().resolve("home"))
        val root = home.resolve(".local/share/kast")
        val scope =
            (PublicResetScope.admit(root, mapOf("HOME" to home.toString())) as PublicResetAdmission.Admitted).scope
        val result =
            reinstallPublicInstallation(
                scope,
                emptyMap(),
                executor = LifecycleChildExecutor { _, _, _ -> LifecycleChildObservation.Exited(0) },
                runtime = PublicReadinessScript(root.resolve("installation"), ResetActivationObservation.Unavailable),
            )
        assertEquals(
            ForceResetOutcome.Rejected(
                ForceResetOperation.REINSTALL,
                ForceResetStage.INSTALLATION,
                ForceResetFailure.INSTALLATION_UNVERIFIED,
            ),
            result,
        )
    }

    @Test
    fun `receipt recovery repairs the recorded Pi directory with the new adapter`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI, "old adapter")
        val selected = temporary.resolve("selected-pi")
        val directory =
            (ConnectionDirectory.admit(selected.toString()) as ConnectionDirectoryAdmission.Admitted).directory
        connectHarness(root, home, HarnessConnection.PI, directory = directory)
        val destination = registrationDestinationFor(root, HarnessConnection.PI, directory)
        val recovery = temporary.resolve("saved-connections.json")
        val environment =
            mapOf(
                "HOME" to home.toString(),
                "KAST_PUBLIC_RESET_RECOVERY" to recovery.toString(),
                "PI_CODING_AGENT_DIR" to temporary.resolve("unrelated-pi").toString(),
            )
        assertEquals(LifecycleEffect.Completed, savePublicResetConnections(root, environment))
        integrationFixture(temporary, HarnessConnection.PI, "new adapter")
        assertEquals(LifecycleEffect.Completed, restorePublicResetConnections(root, environment))
        assertTrue(Files.readString(destination).contains("new adapter"))
        val receipt = (readReceipt(root) as ReceiptRead.Read).receipt
        assertEquals(selected.toString(), receipt.registrations.single().directory)
        assertFalse(Files.exists(temporary.resolve("unrelated-pi")))
    }

    @Test
    fun `saved connections survive erasure and foreign recovery receipts cannot select connections`() {
        val home = Files.createDirectories(temporary.toRealPath().resolve("home"))
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        val command = home.resolve(".local/bin/kast")
        val registration =
            ManagedRegistration(HarnessConnection.PI, home.resolve(".pi/extensions/kast.ts").toString(), "1".repeat(64))
        val receipt =
            ManagementReceipt(
                2,
                root.toString(),
                command.toString(),
                "0".repeat(64),
                ReleaseChannel.DEVELOPER,
                listOf(registration),
            )
        writeManagementReceipt(root, receipt)
        val recovery = temporary.resolve("recovery.json")
        val environment = mapOf("KAST_PUBLIC_RESET_RECOVERY" to recovery.toString())
        savePublicResetConnections(root, environment)
        Files.delete(root.resolve("management.json"))
        assertEquals(listOf(registration), publicResetConnections(root, environment))
        Files.writeString(
            recovery,
            managementJson.encodeToString(receipt.copy(installationRoot = home.resolve("foreign").toString())),
        )
        org.junit.jupiter.api.assertThrows<ManagementRejected> { publicResetConnections(root, environment) }
    }
}

private class PublicReadinessScript(private val installation: Path, private val result: ResetActivationObservation) :
    ForceDaemonRuntime {
    override fun launch(command: ResetLaunchCommand, home: Path) = error("unexpected launch effect")

    override fun processes() = error("unexpected process observation")

    override fun retire(process: ResetScopedProcess, allowance: Duration) = error("unexpected retirement")

    override fun activate(installation: Path, home: Path, environment: Map<String, String>) =
        error("unexpected activation")

    override fun readiness(installation: Path): ResetActivationObservation {
        assertEquals(this.installation, installation)
        return result
    }
}
