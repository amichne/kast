package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.BrokerPublicEndpointMode
import io.github.amichne.kast.appserver.InstalledSavedConfigurationIngress
import io.github.amichne.kast.appserver.SavedConfigurationIngress
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Private filesystem publication proof; owned fixture children do not establish native service behavior. */
internal class InstallationPublicEndpointPreservationTest {
    @Test
    fun `paired upgrade preserves the saved private endpoint when the request leaves it unspecified`(
        @TempDir temporary: Path
    ) {
        val fixture = Fixture(temporary)
        fixture.install("1.2.3", BrokerPublicEndpointMode.PRIVATE)
        fixture.preparePrior()
        fixture.install("1.2.4", expectedUpgrades = 1)
        assertEquals(BrokerPublicEndpointMode.PRIVATE, savedEndpoint(fixture.configuration))
        assertEquals(listOf("1.2.3 --version", "1.2.4 --version", "1.2.3 app-server disable"), fixture.effects())
    }

    @Test
    fun `fresh unspecified installation uses private when the Codex control endpoint is occupied`(
        @TempDir temporary: Path
    ) {
        val fixture = Fixture(temporary)
        val socket =
            Files.writeString(
                Files.createDirectory(fixture.codex.resolve("app-server-control")).resolve("app-server-control.sock"),
                "incumbent",
            )
        val identity = observeInstallationFilesystemIdentity(socket.parent)
        fixture.install("1.2.3")
        assertEquals(BrokerPublicEndpointMode.PRIVATE, savedEndpoint(fixture.configuration))
        assertEquals(identity, observeInstallationFilesystemIdentity(socket.parent))
    }

    @Test
    fun `fresh unspecified installation keeps the Codex control default when the endpoint is absent`(
        @TempDir temporary: Path
    ) {
        val fixture = Fixture(temporary)
        fixture.install("1.2.3")
        assertEquals(BrokerPublicEndpointMode.CODEX_CONTROL, savedEndpoint(fixture.configuration))
    }

    @Test
    fun `explicit Codex control overrides occupied fresh endpoint fallback`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        val socket =
            Files.writeString(
                Files.createDirectory(fixture.codex.resolve("app-server-control")).resolve("app-server-control.sock"),
                "incumbent",
            )
        fixture.install("1.2.3", BrokerPublicEndpointMode.CODEX_CONTROL)
        assertEquals(BrokerPublicEndpointMode.CODEX_CONTROL, savedEndpoint(fixture.configuration))
        assertEquals("incumbent", Files.readString(socket))
    }

    @Test
    fun `explicit paired endpoint overrides an admitted saved private endpoint`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.install("1.2.3", BrokerPublicEndpointMode.PRIVATE)
        fixture.preparePrior()
        fixture.install("1.2.4", BrokerPublicEndpointMode.CODEX_CONTROL, expectedUpgrades = 1)
        assertEquals(BrokerPublicEndpointMode.CODEX_CONTROL, savedEndpoint(fixture.configuration))
    }

    @Test
    fun `paired upgrade preserves an admitted saved Codex control endpoint`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.install("1.2.3", BrokerPublicEndpointMode.CODEX_CONTROL)
        fixture.preparePrior()
        fixture.install("1.2.4", expectedUpgrades = 1)
        assertEquals(BrokerPublicEndpointMode.CODEX_CONTROL, savedEndpoint(fixture.configuration))
    }

    @Test
    fun `paired upgrade retains admitted custom connection and host settings`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.install("1.2.3", BrokerPublicEndpointMode.PRIVATE)
        fixture.preparePrior()
        val customCodex = Files.createDirectory(fixture.root.resolve("custom-codex"))
        Files.writeString(
            fixture.configuration,
            "CODEX_HOME=$customCodex\nKAST_DEBUG=1\nKAST_APP_SERVER_PUBLIC_ENDPOINT=private\n",
        )
        fixture.install("1.2.4", expectedUpgrades = 1)
        val configuration =
            (readInstallationConfiguration(fixture.configuration) as Refinement.Refined).value.configuration
        assertEquals(customCodex.toString(), configuration.launchEnvironment().variables["CODEX_HOME"])
        assertEquals("1", configuration.launchEnvironment().variables["KAST_DEBUG"])
        assertEquals(BrokerPublicEndpointMode.PRIVATE, savedEndpoint(fixture.configuration))
        assertEquals(listOf("1.2.3 --version", "1.2.4 --version", "1.2.3 app-server disable"), fixture.effects())
    }

    @Test
    fun `explicit same payload reconfiguration overrides saved endpoint and retains other settings`(
        @TempDir temporary: Path
    ) {
        val fixture = Fixture(temporary)
        val prior = fixture.install("1.2.3", BrokerPublicEndpointMode.PRIVATE)
        fixture.preparePrior()
        Files.writeString(
            fixture.configuration,
            Files.readString(fixture.configuration).replace("KAST_DEBUG=0", "KAST_DEBUG=1"),
        )
        val boundary = ScriptedUpgrades(fixture.selected, 1)
        val request = fixture.repeat(prior, BrokerPublicEndpointMode.CODEX_CONTROL)
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            InstallationWorkflow.execute(request, boundary.gateway, InstallationActivationPolicy.STAGE_ONLY),
        )
        assertEquals(BrokerPublicEndpointMode.CODEX_CONTROL, savedEndpoint(fixture.configuration))
        val configuration =
            (readInstallationConfiguration(fixture.configuration) as Refinement.Refined).value.configuration
        assertEquals("1", configuration.launchEnvironment().variables["KAST_DEBUG"])
        boundary.assertConsumed()
        assertEquals(listOf("1.2.3 --version", "1.2.3 --version", "1.2.3 app-server disable"), fixture.effects())
    }

    @Test
    fun `conflicting explicit endpoint preserves the committed transaction and requires recovery`(
        @TempDir temporary: Path
    ) {
        val fixture = Fixture(temporary)
        val prior = fixture.install("1.2.3", BrokerPublicEndpointMode.PRIVATE)
        val identity = observeInstallationFilesystemIdentity(fixture.selected)
        val transaction = fixture.installation.resolve("recovery/replacement")
        val transactionIdentity = observeInstallationFilesystemIdentity(transaction)
        val boundary = ScriptedUpgrades(fixture.selected, 0)
        val outcome =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                InstallationWorkflow.execute(
                    fixture.repeat(prior, BrokerPublicEndpointMode.CODEX_CONTROL),
                    boundary.gateway,
                    InstallationActivationPolicy.STAGE_ONLY,
                ),
            )
        boundary.assertConsumed()
        assertEquals(InstallationFailure.RECOVERY_REQUIRED, outcome.failure)
        assertEquals(identity, observeInstallationFilesystemIdentity(fixture.selected))
        assertEquals(transactionIdentity, observeInstallationFilesystemIdentity(transaction))
        assertEquals(listOf("1.2.3 --version"), fixture.effects())
    }

    @Test
    fun `invalid saved endpoint rejects before qualification or prior retirement`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.install("1.2.3", BrokerPublicEndpointMode.PRIVATE)
        fixture.preparePrior()
        Files.writeString(fixture.configuration, "KAST_APP_SERVER_PUBLIC_ENDPOINT=unsupported\n")
        val identity = observeInstallationFilesystemIdentity(fixture.selected)
        val effects = fixture.effects()
        val request = fixture.request("1.2.4", BrokerPublicEndpointMode.CODEX_CONTROL)
        val boundary = ScriptedUpgrades(fixture.selected, 0)
        val outcome =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                InstallationWorkflow.execute(request, boundary.gateway, InstallationActivationPolicy.STAGE_ONLY),
            )
        boundary.assertConsumed()
        assertEquals(InstallationFailure.CONFIGURATION_REJECTED, outcome.failure)
        assertEquals(identity, observeInstallationFilesystemIdentity(fixture.selected))
        assertEquals(effects, fixture.effects())
    }

    private fun savedEndpoint(path: Path): BrokerPublicEndpointMode {
        val source =
            InstalledSavedConfigurationIngress.read(path.toString(), emptyMap()) as SavedConfigurationIngress.Loaded
        val configuration = (ResolvedKastConfiguration.resolve(source.sources) as Refinement.Refined).value
        return (BrokerPublicEndpointMode.admit(
                configuration.ownerInputs(ConfigurationOwner.APP_SERVER)["KAST_APP_SERVER_PUBLIC_ENDPOINT"]
            ) as Refinement.Refined)
            .value
    }

    private class Fixture(temporary: Path) {
        val root: Path = temporary.toRealPath()
        val home: Path = Files.createDirectory(root.resolve("home"))
        val installation: Path = home.resolve(".local/share/kast")
        val codex: Path = Files.createDirectory(root.resolve("codex"))
        val selected: Path = installation.resolve("installation")
        val configuration: Path = selected.resolve("config/environment")
        private val log = home.resolve("effects")

        fun request(version: String, endpoint: BrokerPublicEndpointMode? = null): InstallationRequest {
            val request =
                releaseRequest(
                    fixture = root,
                    installation = installation,
                    commands = home.resolve(".local/bin"),
                    home = home,
                    codexHome = codex,
                    version = version,
                    environmentOverrides =
                        endpoint
                            ?.let { mapOf(InstallationEnvironment.PUBLIC_ENDPOINT.key to it.configurationValue) }
                            .orEmpty(),
                )
            Files.writeString(
                request.controlRoot.value.resolve("bin/kast"),
                """
                #!/bin/sh
                case "${'$'}*" in
                  --version|'app-server disable') ;;
                  *) exit 97 ;;
                esac
                printf '%s\n' '$version '"${'$'}*" >> "${'$'}HOME/effects"
                exit 0
                """
                    .trimIndent() + "\n",
            )
            return request
        }

        fun install(
            version: String,
            endpoint: BrokerPublicEndpointMode? = null,
            expectedUpgrades: Int = 0,
        ): InstallationRequest {
            val request = request(version, endpoint)
            val boundary = ScriptedUpgrades(selected, expectedUpgrades)
            assertInstanceOf(
                InstallationOutcome.Complete::class.java,
                InstallationWorkflow.execute(
                    request,
                    boundary.gateway,
                    InstallationActivationPolicy.STAGE_ONLY,
                ),
            )
            boundary.assertConsumed()
            return request
        }

        fun repeat(request: InstallationRequest, endpoint: BrokerPublicEndpointMode): InstallationRequest =
            (InstallationRequest.parse(
                    mapOf(
                        "KAST_INSTALL_CONTROL_ROOT" to request.controlRoot.value.toString(),
                        "KAST_INSTALL_CONTROL_ARCHIVE" to request.controlArchive.value.toString(),
                        "KAST_INSTALL_CONTROL_SHA256" to request.controlDigest.value,
                        "KAST_INSTALL_VERSION" to request.version.toString(),
                        "KAST_INSTALL_IDEA_HOME" to request.ideaHome.value.toString(),
                        "KAST_INSTALL_JAVA_HOME" to request.javaHome.value.toString(),
                        "KAST_INSTALL_ROOT" to request.installRoot.value.toString(),
                        "KAST_BIN_DIR" to request.binDirectory.value.toString(),
                        "HOME" to request.home.value.toString(),
                        "CODEX_HOME" to request.codexHome.value.toString(),
                        "KAST_INSTALL_MODE" to "apply",
                        "KAST_APP_SERVER_PUBLIC_ENDPOINT" to endpoint.configurationValue,
                    )
                ) as Refinement.Refined)
                .value

        fun preparePrior() {
            discardFixtureReplacementAfterSetup(installation)
            Files.writeString(
                selected.resolve("config/workspaces.json"),
                Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString()))),
            )
        }

        fun effects(): List<String> = Files.readAllLines(log)
    }

    private class ScriptedUpgrades(selected: Path, private val expected: Int) {
        private var calls = 0
        val gateway = PriorDaemonUpgradeGateway { retirement, request, digest ->
            assertEquals(true, calls < expected, "Unexpected prior upgrade preparation")
            calls++
            assertEquals(selected, retirement.executable.parent.parent)
            assertEquals(request.controlDigest, digest)
            io.github.amichne.kast.appserver.InstalledUpgradePreparation.NoDaemon
        }

        fun assertConsumed() = assertEquals(expected, calls, "Unconsumed prior upgrade preparation")
    }
}
