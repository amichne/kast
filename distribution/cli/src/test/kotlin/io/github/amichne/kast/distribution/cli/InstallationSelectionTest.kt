package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class InstallationSelectionTest {
    private val root = Path.of("/selected/kast")

    @Test
    fun `stable control upgrade selects only control without force`() {
        assertEquals(
            listOf("--skip-codex-mcp", "--control-only", "--install-root", "/selected/kast"),
            installerArguments(root, InstallationSelection.ControlOnly, ReleaseChannel.STABLE),
        )
    }

    @Test
    fun `developer control request remains explicit for unsupported installer rejection`() {
        assertEquals(
            listOf("--developer-latest", "--skip-codex-mcp", "--control-only", "--install-root", "/selected/kast"),
            installerArguments(root, InstallationSelection.ControlOnly, ReleaseChannel.DEVELOPER),
        )
    }

    @Test
    fun `exact reinstall remains distinct from control upgrade`() {
        val version =
            assertInstanceOf(
                    ReinstallationVersionAdmission.Admitted::class.java,
                    ReinstallationVersion.admit("0.50.0"),
                )
                .version
        assertEquals(
            listOf("--version", "0.50.0", "--force", "--skip-codex-mcp", "--stage-only", "--install-root") +
                "/selected/kast",
            installerArguments(root, InstallationSelection.Exact(version), ReleaseChannel.STABLE),
        )
    }

    @Test
    fun `upgrade parser retains explicit control ownership`() {
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Upgrade(true)),
            parseManagementCommand(listOf("upgrade", "--control-only")),
        )
    }

    @Test
    fun `private installer removes foreign selectors and retains exact report authority`() {
        val inherited =
            mapOf(
                "HOME" to "/selected/home",
                "PATH" to "/selected/bin",
                "KAST_INSTALL_ROOT" to "/foreign/root",
                "KAST_INSTALL_STAGE_ONLY" to "1",
                "KAST_VERSION" to "9.9.9",
                "KAST_HOST_VERSION" to "8.8.8",
                "KAST_RELEASE_BASE_URL" to "https://foreign.invalid",
                "KAST_MANAGEMENT_CHANNEL" to "developer",
                "KAST_MANAGEMENT_REPORT_PATH" to "/foreign/report",
            )
        assertEquals(
            mapOf(
                "HOME" to "/selected/home",
                "PATH" to "/selected/bin",
                "KAST_MANAGEMENT_REPORT_PATH" to "/selected/kast/report.json",
            ),
            privateInstallerEnvironment(inherited, root.resolve("report.json")),
        )
        assertEquals(
            mapOf("HOME" to "/selected/home", "PATH" to "/selected/bin"),
            privateInstallerEnvironment(inherited, null),
        )
    }
}
