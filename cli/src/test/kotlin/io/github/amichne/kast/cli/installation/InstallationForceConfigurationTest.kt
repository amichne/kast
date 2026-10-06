package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.BrokerPublicEndpointMode
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Configuration selection and staged admission require only a private filesystem, never service retirement. */
internal class InstallationForceConfigurationTest {
    @Test
    fun `force resets admitted customized settings to fresh defaults`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        val saved = fixture.customized(BrokerPublicEndpointMode.PRIVATE)
        val plan = fixture.plan(InstallationSwitch.ENABLED)
        val selected = fixture.select(plan)
        assertEquals(InstallationConfigurationSelection.FreshDefault, selected)
        fixture.assertFreshConfiguration(plan, selected, BrokerPublicEndpointMode.CODEX_CONTROL)
        assertEquals(saved, Files.readString(fixture.configuration))
    }

    @Test
    fun `ordinary replacement retains admitted customized settings`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.customized(BrokerPublicEndpointMode.PRIVATE)
        val plan = fixture.plan(InstallationSwitch.DISABLED)
        val selected = fixture.select(plan)
        assertInstanceOf(InstallationConfigurationSelection.Prior::class.java, selected)
        val variables = fixture.materializedVariables(plan, selected)
        assertEquals(fixture.customCodex.toString(), variables["CODEX_HOME"])
        assertEquals("1", variables["KAST_DEBUG"])
        assertEquals("private", variables["KAST_APP_SERVER_PUBLIC_ENDPOINT"])
    }

    @Test
    fun `malformed prior configuration cannot block force reset`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        val malformed = "invalid record\n"
        Files.writeString(fixture.configuration, malformed)
        val plan = fixture.plan(InstallationSwitch.ENABLED)
        val selected = fixture.select(plan)
        fixture.assertFreshConfiguration(plan, selected, BrokerPublicEndpointMode.CODEX_CONTROL)
        assertEquals(malformed, Files.readString(fixture.configuration))
    }

    @Test
    fun `missing prior configuration cannot block force reset`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        val plan = fixture.plan(InstallationSwitch.ENABLED)
        val selected = fixture.select(plan)
        fixture.assertFreshConfiguration(plan, selected, BrokerPublicEndpointMode.CODEX_CONTROL)
        assertFalse(Files.exists(fixture.configuration))
    }

    @Test
    fun `force honors explicit Codex control instead of the saved private endpoint`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.customized(BrokerPublicEndpointMode.PRIVATE)
        val plan = fixture.plan(InstallationSwitch.ENABLED, BrokerPublicEndpointMode.CODEX_CONTROL)
        val selected = fixture.select(plan)
        assertEquals(InstallationConfigurationSelection.FreshExplicit(BrokerPublicEndpointMode.CODEX_CONTROL), selected)
        fixture.assertFreshConfiguration(plan, selected, BrokerPublicEndpointMode.CODEX_CONTROL)
    }

    @Test
    fun `force honors explicit private instead of the saved Codex control endpoint`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.customized(BrokerPublicEndpointMode.CODEX_CONTROL)
        val plan = fixture.plan(InstallationSwitch.ENABLED, BrokerPublicEndpointMode.PRIVATE)
        val selected = fixture.select(plan)
        assertEquals(InstallationConfigurationSelection.FreshExplicit(BrokerPublicEndpointMode.PRIVATE), selected)
        fixture.assertFreshConfiguration(plan, selected, BrokerPublicEndpointMode.PRIVATE)
    }

    @Test
    fun `force honors an explicit endpoint even when prior endpoint admission fails`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        Files.writeString(fixture.configuration, "KAST_APP_SERVER_PUBLIC_ENDPOINT=unsupported\n")
        val plan = fixture.plan(InstallationSwitch.ENABLED, BrokerPublicEndpointMode.PRIVATE)
        val selected = fixture.select(plan)
        fixture.assertFreshConfiguration(plan, selected, BrokerPublicEndpointMode.PRIVATE)
    }

    @Test
    fun `force unspecified endpoint uses fresh occupied socket fallback`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.customized(BrokerPublicEndpointMode.CODEX_CONTROL)
        val socket =
            Files.writeString(
                Files.createDirectory(fixture.codex.resolve("app-server-control")).resolve("app-server-control.sock"),
                "incumbent",
            )
        val plan = fixture.plan(InstallationSwitch.ENABLED)
        val selected = fixture.select(plan)
        assertEquals(InstallationConfigurationSelection.FreshOccupiedCodexControl, selected)
        fixture.assertFreshConfiguration(plan, selected, BrokerPublicEndpointMode.PRIVATE)
        assertEquals("incumbent", Files.readString(socket))
    }

    private class Fixture(temporary: Path) {
        private val root = temporary.toRealPath()
        private val home = root.resolve("home")
        val codex: Path = Files.createDirectory(root.resolve("codex"))
        val customCodex: Path = root.resolve("custom-codex")
        private val prior = Files.createDirectory(root.resolve("prior"))
        val configuration: Path = Files.createDirectory(prior.resolve("config")).resolve("environment")

        fun customized(endpoint: BrokerPublicEndpointMode): String {
            val saved =
                "CODEX_HOME=$customCodex\nKAST_DEBUG=1\nKAST_APP_SERVER_PUBLIC_ENDPOINT=${endpoint.configurationValue}\n"
            Files.writeString(configuration, saved)
            return saved
        }

        fun plan(force: InstallationSwitch, endpoint: BrokerPublicEndpointMode? = null): VerifiedInstallationPlan {
            val environment =
                mapOf(
                    "KAST_INSTALL_CONTROL_ROOT" to root.resolve("control").toString(),
                    "KAST_INSTALL_CONTROL_ARCHIVE" to root.resolve("control.tar.gz").toString(),
                    "KAST_INSTALL_CONTROL_SHA256" to "a".repeat(64),
                    "KAST_INSTALL_VERSION" to "1.2.3",
                    "KAST_INSTALL_IDEA_HOME" to root.resolve("idea").toString(),
                    "KAST_INSTALL_JAVA_HOME" to root.resolve("idea/jbr/Contents/Home").toString(),
                    "KAST_INSTALL_ROOT" to home.resolve(".local/share/kast").toString(),
                    "KAST_BIN_DIR" to home.resolve(".local/bin").toString(),
                    "HOME" to home.toString(),
                    "CODEX_HOME" to codex.toString(),
                    "KAST_INSTALL_MODE" to "apply",
                    "KAST_INSTALL_FORCE" to if (force == InstallationSwitch.ENABLED) "1" else "0",
                ) + endpoint?.let { mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to it.configurationValue) }.orEmpty()
            val request = (InstallationRequest.parse(environment) as Refinement.Refined).value
            return VerifiedInstallationPlan(
                request,
                request.controlDigest,
                request.installRoot.value.resolve("installation"),
            )
        }

        fun select(plan: VerifiedInstallationPlan): InstallationConfigurationSelection {
            val outcome = observeInstallationConfigurationSelection(plan, prior)
            assertInstanceOf(Refinement.Refined::class.java, outcome)
            return (outcome as Refinement.Refined).value
        }

        fun assertFreshConfiguration(
            plan: VerifiedInstallationPlan,
            selection: InstallationConfigurationSelection,
            endpoint: BrokerPublicEndpointMode,
        ) {
            val variables = materializedVariables(plan, selection)
            assertFalse(variables.containsKey("CODEX_HOME"))
            assertEquals("0", variables["KAST_DEBUG"])
            assertEquals(endpoint.configurationValue, variables["KAST_APP_SERVER_PUBLIC_ENDPOINT"])
            assertEquals(plan.request.ideaHome.value.toString(), variables["KAST_INSTALL_IDEA_HOME"])
            assertEquals(plan.targetRoot.resolve("state/run").toString(), variables["KAST_RUNTIME_DIRECTORY"])
        }

        fun materializedVariables(
            plan: VerifiedInstallationPlan,
            selection: InstallationConfigurationSelection,
        ): Map<String, String> {
            val staged =
                Files.writeString(root.resolve("staged-environment"), installationConfigurationContent(plan, selection))
            val admitted = readInstallationConfiguration(staged)
            assertInstanceOf(Refinement.Refined::class.java, admitted)
            return (admitted as Refinement.Refined).value.configuration.launchEnvironment().variables
        }
    }
}
