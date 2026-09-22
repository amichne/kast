package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.RetiredConfigurationSetting
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallationRequestTest {
    @Test
    fun `force command reaches verified payload admission while unknown options reject`() {
        val forced =
            InstallationCliInspection.inspect(listOf("installation", "install", "--force"), validEnvironment())
                as InstallationHandling.Handled
        assertEquals(
            io.github.amichne.kast.cli.CliBoundaryExitStatus.BOOTSTRAP,
            (forced.exit as io.github.amichne.kast.cli.CliExit.BoundaryRejected).status,
        )
        val unknown =
            InstallationCliInspection.inspect(listOf("installation", "install", "--unknown"), validEnvironment())
                as InstallationHandling.Handled
        assertEquals(
            io.github.amichne.kast.cli.CliBoundaryExitStatus.USAGE,
            (unknown.exit as io.github.amichne.kast.cli.CliExit.BoundaryRejected).status,
        )
    }

    @Test
    fun `force defaults off and rejects unknown values`() {
        assertEquals(
            InstallationSwitch.DISABLED,
            (InstallationRequest.parse(validEnvironment()) as Refinement.Refined).value.force,
        )
        assertEquals(
            InstallationSwitch.ENABLED,
            (InstallationRequest.parse(validEnvironment() + ("KAST_INSTALL_FORCE" to "1")) as Refinement.Refined)
                .value
                .force,
        )
        assertEquals(
            Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.FORCE)),
            InstallationRequest.parse(validEnvironment() + ("KAST_INSTALL_FORCE" to "yes")),
        )
    }

    @Test
    fun `endpoint defaults to private and rejects unknown explicit selections`() {
        val default = InstallationRequest.parse(validEnvironment()) as Refinement.Refined
        assertEquals(io.github.amichne.kast.appserver.BrokerPublicEndpointMode.PRIVATE, default.value.publicEndpoint)
        for (invalid in listOf("", "unknown")) {
            assertEquals(
                Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.PUBLIC_ENDPOINT)),
                InstallationRequest.parse(
                    validEnvironment() + (InstallationEnvironment.PUBLIC_ENDPOINT.key to invalid)
                ),
            )
        }
    }

    @Test
    fun `removed overrides reject before installation`() {
        for (setting in RetiredConfigurationSetting.entries) {
            assertEquals(
                Refinement.Rejected(InstallationRequestFailure.RetiredSetting(setting)),
                InstallationRequest.parse(validEnvironment() + (setting.key to "1")),
            )
        }
    }

    @Test
    fun `installation profile selects persistent service or private session`() {
        assertEquals(
            InstallationProfile.PERSISTENT,
            (InstallationRequest.parse(validEnvironment()) as Refinement.Refined).value.profile,
        )
        assertEquals(
            InstallationProfile.SESSION,
            (InstallationRequest.parse(validEnvironment() + ("KAST_INSTALL_PROFILE" to "session"))
                    as Refinement.Refined)
                .value
                .profile,
        )
        assertEquals(
            Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.PROFILE)),
            InstallationRequest.parse(validEnvironment() + ("KAST_INSTALL_PROFILE" to "partial")),
        )
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
            InstallationEnvironment.MODE.key to "apply",
        )
}
