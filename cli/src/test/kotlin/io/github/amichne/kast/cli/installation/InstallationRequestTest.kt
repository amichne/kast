package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.RetiredConfigurationSetting
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallationRequestTest {
    @Test
    fun `home proof retains quoted JVM identity and rejects control characters`() {
        val home = "/fixture/home with \"quote\" and \\slash"
        val request =
            InstallationRequest.parse(
                validEnvironment() +
                    mapOf(
                        "HOME" to home,
                        "KAST_INSTALL_ROOT" to "$home/.local/share/kast",
                        "KAST_BIN_DIR" to "$home/.local/bin",
                    )
            ) as Refinement.Refined
        assertEquals(
            "-Duser.home=\"/fixture/home with \\\"quote\\\" and \\\\slash\"",
            request.value.jvmUserHomeOption.value,
        )
        for (character in listOf('\n', '\r', '\u0000')) {
            assertEquals(
                Refinement.Rejected(InstallationRequestFailure.InvalidPath(InstallationEnvironment.HOME)),
                InstallationRequest.parse(validEnvironment() + ("HOME" to ("/fixture/home" + character))),
            )
        }
    }

    @Test
    fun `installer target retains only an exact profile under the actual user home`() {
        val root = "/fixture/home/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins"
        val request =
            InstallationRequest.parse(validEnvironment() + (InstallationEnvironment.IDEA_PLUGIN_ROOT.key to root))
                as Refinement.Refined
        assertEquals(
            root,
            (request.value.ideaPluginTarget
                    as io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget.Recorded)
                .root,
        )
        assertEquals(
            io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget.Unrecorded,
            (InstallationRequest.parse(validEnvironment()) as Refinement.Refined).value.ideaPluginTarget,
        )
        for (invalid in
            listOf(
                "/another/home/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins",
                "/fixture/home/Library/Application Support/JetBrains/../plugins",
                "relative",
            )) {
            assertEquals(
                Refinement.Rejected(InstallationRequestFailure.InvalidPath(InstallationEnvironment.IDEA_PLUGIN_ROOT)),
                InstallationRequest.parse(
                    validEnvironment() + (InstallationEnvironment.IDEA_PLUGIN_ROOT.key to invalid)
                ),
            )
        }
    }

    @Test
    fun `control-only reset rejects at request boundary`() {
        assertEquals(
            Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.CONTROL_ONLY)),
            InstallationRequest.parse(
                validEnvironment() + mapOf("KAST_INSTALL_CONTROL_ONLY" to "1", "KAST_INSTALL_FORCE" to "1")
            ),
        )
    }

    @Test
    fun `control request requires no host artifact or digest`() {
        org.junit.jupiter.api.Assertions.assertTrue(InstallationRequest.parse(validEnvironment()) is Refinement.Refined)
    }

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
    fun `endpoint retains an unspecified request and rejects unknown explicit selections`() {
        val default = InstallationRequest.parse(validEnvironment()) as Refinement.Refined
        assertEquals(
            InstallationPublicEndpointSelection.Unspecified,
            default.value.publicEndpoint,
        )
        for (endpoint in io.github.amichne.kast.appserver.BrokerPublicEndpointMode.entries) {
            val request =
                InstallationRequest.parse(
                    validEnvironment() + (InstallationEnvironment.PUBLIC_ENDPOINT.key to endpoint.configurationValue)
                ) as Refinement.Refined
            assertEquals(InstallationPublicEndpointSelection.Explicit(endpoint), request.value.publicEndpoint)
        }
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
    fun `only the persistent user installation is admitted`() {
        assertEquals(
            InstallationProfile.PERSISTENT,
            (InstallationRequest.parse(validEnvironment()) as Refinement.Refined).value.profile,
        )
        for (profile in listOf("session", "partial")) {
            assertEquals(
                Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.PROFILE)),
                InstallationRequest.parse(validEnvironment() + ("KAST_INSTALL_PROFILE" to profile)),
            )
        }
        assertEquals(
            Refinement.Rejected(InstallationRequestFailure.InvalidPath(InstallationEnvironment.INSTALL_ROOT)),
            InstallationRequest.parse(validEnvironment() + ("KAST_INSTALL_ROOT" to "/fixture/other-install")),
        )
        assertEquals(
            Refinement.Rejected(InstallationRequestFailure.InvalidPath(InstallationEnvironment.BIN_DIRECTORY)),
            InstallationRequest.parse(validEnvironment() + ("KAST_BIN_DIR" to "/fixture/other-bin")),
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
        assertEquals(Path.of("/fixture/home/.local/share/kast"), request.installRoot.value)
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
            InstallationEnvironment.VERSION.key to "1.2.3",
            InstallationEnvironment.IDEA_HOME.key to "/fixture/idea",
            InstallationEnvironment.JAVA_HOME.key to "/fixture/idea/jbr/Contents/Home",
            InstallationEnvironment.INSTALL_ROOT.key to "/fixture/home/.local/share/kast",
            InstallationEnvironment.BIN_DIRECTORY.key to "/fixture/home/.local/bin",
            InstallationEnvironment.HOME.key to "/fixture/home",
            InstallationEnvironment.CODEX_HOME.key to "/fixture/codex",
            InstallationEnvironment.MODE.key to "apply",
        )
}
