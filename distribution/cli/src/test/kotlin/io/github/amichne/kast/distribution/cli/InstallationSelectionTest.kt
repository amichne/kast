package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class InstallationSelectionTest {
    @Test
    fun `stable control upgrade selects only control without force`() {
        assertEquals(
            listOf("--skip-codex-mcp", "--control-only"),
            installerArguments(InstallationSelection.ControlOnly, ReleaseChannel.STABLE),
        )
    }

    @Test
    fun `developer control request remains explicit for unsupported installer rejection`() {
        assertEquals(
            listOf("--developer-latest", "--skip-codex-mcp", "--control-only"),
            installerArguments(InstallationSelection.ControlOnly, ReleaseChannel.DEVELOPER),
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
            listOf("--version", "0.50.0", "--force", "--skip-codex-mcp", "--stage-only"),
            installerArguments(InstallationSelection.Exact(version), ReleaseChannel.STABLE),
        )
    }

    @Test
    fun `upgrade parser retains explicit control ownership`() {
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Upgrade(true)),
            parseManagementCommand(listOf("upgrade", "--control-only")),
        )
    }
}
