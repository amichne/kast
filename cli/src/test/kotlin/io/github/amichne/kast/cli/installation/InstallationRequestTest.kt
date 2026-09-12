package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallationRequestTest {
    @Test
    fun `absent bootstrap tool selection uses the current canonical catalog`() {
        val result = InstallationRequest.parse(validEnvironment() - InstallationEnvironment.APP_SERVER_TOOLS.key)
        val request =
            when (result) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> error("unexpected rejection: ${result.failure}")
            }
        assertEquals(
            CanonicalAgentToolDefinitions.defaultAppServerTools.joinToString(",") { it.name.value },
            request.appServerTools.value,
        )
    }

    @Test
    fun `retired unknown empty and duplicate tool selections reject before installation`() {
        for (selection in listOf("query", "diagnostic_check", "unknown_tool", "", "source_read,source_read")) {
            assertEquals(
                Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.APP_SERVER_TOOLS)),
                InstallationRequest.parse(
                    validEnvironment() + (InstallationEnvironment.APP_SERVER_TOOLS.key to selection)
                ),
                selection,
            )
        }
    }

    @Test
    fun `complete bootstrap environment refines to one install request`() {
        val result = InstallationRequest.parse(validEnvironment())

        val request =
            when (result) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> error("unexpected rejection: ${result.failure}")
            }
        assertEquals(SemanticVersion(1, 2, 3), request.version)
        assertEquals(Path.of("/fixture/install"), request.installRoot.value)
        assertEquals(InstallationMode.APPLY, request.mode)
    }

    @Test
    fun `relative bootstrap path is a closed rejection`() {
        val result =
            InstallationRequest.parse(
                validEnvironment() + (InstallationEnvironment.CONTROL_ROOT.key to "relative/control")
            )

        assertEquals(
            Refinement.Rejected(InstallationRequestFailure.InvalidPath(InstallationEnvironment.CONTROL_ROOT)),
            result,
        )
    }

    @Test
    fun `dry run is represented by the installation mode`() {
        val result = InstallationRequest.parse(validEnvironment() + (InstallationEnvironment.MODE.key to "plan"))

        val request =
            when (result) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> error("unexpected rejection: ${result.failure}")
            }
        assertEquals(InstallationMode.PLAN, request.mode)
    }

    private fun validEnvironment(): Map<String, String> =
        mapOf(
            InstallationEnvironment.CONTROL_ROOT.key to "/fixture/control",
            InstallationEnvironment.CONTROL_ARCHIVE.key to "/fixture/control.tar.gz",
            InstallationEnvironment.CONTROL_SHA256.key to "a".repeat(64),
            InstallationEnvironment.HOSTED_PLUGIN_ARCHIVE.key to "/fixture/runtime.zip",
            InstallationEnvironment.HOSTED_PLUGIN_SHA256.key to "b".repeat(64),
            InstallationEnvironment.VERSION.key to "1.2.3",
            InstallationEnvironment.IDEA_HOME.key to "/fixture/idea",
            InstallationEnvironment.JAVA_HOME.key to "/fixture/idea/jbr/Contents/Home",
            InstallationEnvironment.INSTALL_ROOT.key to "/fixture/install",
            InstallationEnvironment.BIN_DIRECTORY.key to "/fixture/bin",
            InstallationEnvironment.HOME.key to "/fixture/home",
            InstallationEnvironment.CODEX_HOME.key to "/fixture/codex",
            InstallationEnvironment.ENABLE_LAUNCHD.key to "0",
            InstallationEnvironment.ENABLE_APP_SERVER.key to "1",
            InstallationEnvironment.APP_SERVER_TOOLS.key to "query_symbols,source_read",
            InstallationEnvironment.REFRESH_APP_SERVER.key to "0",
            InstallationEnvironment.MODE.key to "apply",
        )
}
