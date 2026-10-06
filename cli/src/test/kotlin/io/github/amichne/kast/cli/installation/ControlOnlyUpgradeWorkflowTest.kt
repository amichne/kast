package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.BrokerPublicEndpointMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Filesystem transaction proof with explicit child observations; this does not establish live IDEA behavior. */
class ControlOnlyUpgradeWorkflowTest {
    @Test
    fun `preflight rejection leaves previous control and plugin untouched`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.prior()
        val identity = observeInstallationFilesystemIdentity(fixture.selected)
        val request = fixture.candidate(preflight = 1)
        val outcome =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
            )
        assertEquals(InstallationFailure.HOST_ADMISSION_REJECTED, outcome.failure)
        assertEquals(identity, observeInstallationFilesystemIdentity(fixture.selected))
        assertEquals(listOf("C2 host-admission"), fixture.effects())
        fixture.assertHostUnchanged()
    }

    @Test
    fun `compatible control upgrade changes only control payload`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.prior()
        Files.createDirectories(fixture.selected.resolve("state"))
        Files.writeString(fixture.selected.resolve("state/prior-session"), "old control ownership")
        val configuration = fixture.selected.resolve("config/environment")
        Files.writeString(configuration, Files.readString(configuration).replace("KAST_DEBUG=0", "KAST_DEBUG=1"))
        val priorConfiguration = Files.readString(configuration)
        val request = fixture.candidate()
        val outcome =
            assertInstanceOf(
                InstallationOutcome.Complete::class.java,
                executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
            )
        assertEquals(InstallationActivation.Ready, outcome.report.activation)
        assertEquals("1.2.4", outcome.report.semanticVersion)
        assertEquals(listOf("C2 host-admission", "C1 disable", "C2 enable", "C2 host-admission"), fixture.effects())
        assertEquals(priorConfiguration, Files.readString(configuration))
        assertFalse(Files.exists(fixture.selected.resolve("state/prior-session")))
        assertFalse(Files.exists(fixture.selected.resolve("share/kast/plugins")))
        assertFalse(Files.exists(fixture.selected.resolve(".kast-plugin-sha256")))
        fixture.assertHostUnchanged()
    }

    @Test
    fun `explicit control endpoint overrides prior choice while preserving other saved settings`(
        @TempDir temporary: Path
    ) {
        val fixture = Fixture(temporary)
        fixture.prior()
        val configuration = fixture.selected.resolve("config/environment")
        Files.writeString(configuration, "KAST_DEBUG=1\nKAST_APP_SERVER_PUBLIC_ENDPOINT=private\n")
        val request = fixture.candidate(endpoint = BrokerPublicEndpointMode.CODEX_CONTROL)
        val outcome =
            assertInstanceOf(
                InstallationOutcome.Complete::class.java,
                executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
            )
        assertEquals(InstallationActivation.Ready, outcome.report.activation)
        val admitted =
            (readInstallationConfiguration(configuration) as io.github.amichne.kast.kernel.Refinement.Refined).value
        assertEquals("1", admitted.configuration.launchEnvironment().variables["KAST_DEBUG"])
        assertEquals(
            "codex-control",
            admitted.configuration.launchEnvironment().variables["KAST_APP_SERVER_PUBLIC_ENDPOINT"],
        )
        assertEquals(listOf("C2 host-admission", "C1 disable", "C2 enable", "C2 host-admission"), fixture.effects())
        fixture.assertHostUnchanged()
    }

    @Test
    fun `failed candidate reconnect restores and verifies previous control`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.prior()
        val identity = observeInstallationFilesystemIdentity(fixture.selected)
        val request = fixture.candidate(reconnect = 1)
        val outcome =
            assertInstanceOf(
                InstallationOutcome.RolledBack::class.java,
                executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
            )
        assertEquals(InstallationFailure.HOST_RECONNECT_REJECTED, outcome.failure)
        assertEquals(identity, observeInstallationFilesystemIdentity(fixture.selected))
        assertEquals(
            listOf(
                "C2 host-admission",
                "C1 disable",
                "C2 enable",
                "C2 host-admission",
                "C2 disable",
                "C1 enable",
                "C1 host-admission",
            ),
            fixture.effects(),
        )
        assertFalse(Files.exists(fixture.install.resolve("recovery/replacement")))
        fixture.assertHostUnchanged()
    }

    @Test
    fun `unverified restoration preserves original and recovery failures`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.prior(recovery = 1)
        val request = fixture.candidate(reconnect = 1)
        val outcome =
            assertInstanceOf(
                InstallationOutcome.RecoveryRequired::class.java,
                executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
            )
        assertEquals(InstallationFailure.HOST_RECONNECT_REJECTED, outcome.failure)
        assertEquals(InstallationFailure.CONTROL_RECOVERY_REJECTED, outcome.recoveryFailure)
        assertTrue(Files.exists(fixture.install.resolve("recovery/replacement/rejected-control")))
        assertEquals(
            listOf("C2 host-admission", "C1 disable", "C2 enable", "C2 host-admission", "C2 disable", "C1 enable"),
            fixture.effects(),
        )
        fixture.assertHostUnchanged()
    }

    @Test
    fun `late finalization recovery restores public control before reporting rollback`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.prior()
        val request = fixture.candidate()
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
        )
        val outcome =
            assertInstanceOf(
                InstallationOutcome.RolledBack::class.java,
                InstallationWorkflow.recoverControl(request, InstallationFailure.CONTROL_FINALIZATION_REJECTED),
            )
        assertEquals(InstallationFailure.CONTROL_FINALIZATION_REJECTED, outcome.failure)
        assertEquals(
            listOf(
                "C2 host-admission",
                "C1 disable",
                "C2 enable",
                "C2 host-admission",
                "C2 disable",
                "C1 enable",
                "C1 host-admission",
                "C1 public-control",
            ),
            fixture.effects(),
        )
        assertFalse(Files.exists(fixture.install.resolve("recovery/replacement")))
        fixture.assertHostUnchanged()
    }

    @Test
    fun `failed public control restoration preserves finalization failure`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.prior(publicRecovery = 1)
        val request = fixture.candidate()
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
        )
        val outcome =
            assertInstanceOf(
                InstallationOutcome.RecoveryRequired::class.java,
                InstallationWorkflow.recoverControl(request, InstallationFailure.CONTROL_FINALIZATION_REJECTED),
            )
        assertEquals(InstallationFailure.CONTROL_FINALIZATION_REJECTED, outcome.failure)
        assertEquals(InstallationFailure.CONTROL_PUBLICATION_RECOVERY_REJECTED, outcome.recoveryFailure)
        assertTrue(Files.exists(fixture.install.resolve("recovery/replacement/rejected-control")))
        fixture.assertHostUnchanged()
    }

    @Test
    fun `postreplacement filesystem failure uses owned transaction to recover`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.prior()
        val request = fixture.candidate()
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
        )
        val plan = VerifiedInstallationPlan(request, request.controlDigest, fixture.selected)
        val outcome =
            assertInstanceOf(
                InstallationOutcome.RolledBack::class.java,
                ControlInstallationRecovery.afterFailure(plan, InstallationFailure.FILESYSTEM_REJECTED),
            )
        assertEquals(InstallationFailure.FILESYSTEM_REJECTED, outcome.failure)
        assertEquals(
            listOf(
                "C2 host-admission",
                "C1 disable",
                "C2 enable",
                "C2 host-admission",
                "C2 disable",
                "C1 enable",
                "C1 host-admission",
            ),
            fixture.effects(),
        )
        assertFalse(Files.exists(fixture.install.resolve("recovery/replacement")))
        fixture.assertHostUnchanged()
    }

    @Test
    fun `prereplacement filesystem failure leaves previous controller untouched`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        fixture.prior()
        val identity = observeInstallationFilesystemIdentity(fixture.selected)
        val request = fixture.candidate()
        val plan = VerifiedInstallationPlan(request, request.controlDigest, fixture.selected)
        val outcome =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                ControlInstallationRecovery.afterFailure(plan, InstallationFailure.FILESYSTEM_REJECTED),
            )
        assertEquals(InstallationFailure.FILESYSTEM_REJECTED, outcome.failure)
        assertEquals(identity, observeInstallationFilesystemIdentity(fixture.selected))
        assertFalse(Files.exists(fixture.log))
        fixture.assertHostUnchanged()
    }

    private class Fixture(temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        val codex = Files.createDirectory(root.resolve("codex"))
        val install = home.resolve(".local/share/kast")
        val selected = install.resolve("installation")
        val commands = root.resolve("home/.local/bin")
        val plugin = Files.createDirectories(home.resolve("idea/plugins/kast-ide-hosted"))
        val hostFile = Files.writeString(plugin.resolve("host.jar"), "P1")
        val hostIdentity = observeInstallationFilesystemIdentity(plugin)
        val log = home.resolve("effects")

        fun prior(recovery: Int = 0, publicRecovery: Int = 0) {
            val request = releaseRequest(root, install, commands, home, codex, "1.2.3")
            service(request, "C1", 0, 0, recovery)
            val management = request.controlRoot.value.resolve("share/kast/libexec/kast-management")
            Files.writeString(
                management,
                """
                #!/bin/sh
                [ "${'$'}*" = '--internal-install commit' ] || exit 95
                [ "${'$'}KAST_INSTALL_ROOT" = '$install' ] || exit 94
                printf '%s\n' 'C1 public-control' >> "${'$'}HOME/effects"
                exit $publicRecovery
            """
                    .trimIndent() + "\n",
            )
            Files.setPosixFilePermissions(management, PosixFilePermissions.fromString("rwxr-xr-x"))
            assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(request))
            discardFixtureReplacementAfterSetup(install)
            Files.writeString(
                selected.resolve("config/workspaces.json"),
                Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString()))),
            )
        }

        fun candidate(
            preflight: Int = 0,
            reconnect: Int = 0,
            endpoint: BrokerPublicEndpointMode? = null,
        ): InstallationRequest {
            val request =
                releaseRequest(
                    root,
                    install,
                    commands,
                    home,
                    codex,
                    "1.2.4",
                    environmentOverrides =
                        mapOf("KAST_INSTALL_PROFILE" to "persistent", "KAST_INSTALL_CONTROL_ONLY" to "1") +
                            endpoint
                                ?.let {
                                    mapOf(InstallationEnvironment.PUBLIC_ENDPOINT.key to it.configurationValue)
                                }
                                .orEmpty(),
                )
            service(request, "C2", preflight, reconnect, 0)
            return request
        }

        private fun service(
            request: InstallationRequest,
            version: String,
            preflight: Int,
            reconnect: Int,
            recovery: Int,
        ) {
            val path =
                Files.createDirectories(request.controlRoot.value.resolve("share/kast/libexec")).resolve("kast-service")
            Files.writeString(
                path,
                """
                #!/bin/sh
                [ "${'$'}KAST_OPTS" = '-Duser.home="$home"' ] || exit 97
                printf '%s\n' '$version '"${'$'}1" >> "${'$'}HOME/effects"
                case "${'$'}1" in
                  host-admission)
                    case "${'$'}0" in */.install-*) exit $preflight ;; *) exit $reconnect ;; esac ;;
                  enable) exit $recovery ;;
                  disable) exit 0 ;;
                  *) exit 96 ;;
                esac
            """
                    .trimIndent() + "\n",
            )
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        fun effects(): List<String> = Files.readAllLines(log)

        fun assertHostUnchanged() {
            assertEquals(hostIdentity, observeInstallationFilesystemIdentity(plugin))
            assertEquals("P1", Files.readString(hostFile))
        }
    }
}
