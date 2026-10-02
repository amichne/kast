package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationStageOnlyTest {
    @Test
    fun `aliased user directory rejects before creating an alternate installation`(@TempDir temporary: Path) {
        val request = persistentRequest(temporary)
        val alternate = Files.createDirectory(temporary.toRealPath().resolve("alternate"))
        Files.createSymbolicLink(request.home.value.resolve(".local"), alternate)
        val rejected =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                InstallationWorkflow.execute(request, unexpectedPriorUpgrade, InstallationActivationPolicy.STAGE_ONLY),
            )
        assertEquals(InstallationFailure.INSTALLATION_ROOT_REJECTED, rejected.failure)
        assertTrue(Files.notExists(alternate.resolve("share")))
    }

    @Test
    fun `persistent cold staging commits payload without starting service`(@TempDir temporary: Path) {
        val request = persistentRequest(temporary)
        val complete =
            assertInstanceOf(
                InstallationOutcome.Complete::class.java,
                InstallationWorkflow.execute(
                    request,
                    unexpectedPriorUpgrade,
                    InstallationActivationPolicy.STAGE_ONLY,
                ),
            )
        val payload = request.installRoot.value.resolve("installation")
        assertEquals(InstallationActivation.NotRequested, complete.report.activation)
        assertEquals(InstallationReportStatus.INSTALLED, complete.report.status)
        assertEquals(payload.toString(), complete.report.installation)
        assertTrue(Files.isRegularFile(payload.resolve("installation.json")))
        assertTrue(Files.isExecutable(payload.resolve("share/kast/libexec/kast-service")))
        assertTrue(Files.notExists(payload.resolve("share/kast/libexec/kast-service.called")))
    }

    @Test
    fun `persistent default activation executes service and retains typed failure`(@TempDir temporary: Path) {
        val request = persistentRequest(temporary)
        val complete =
            assertInstanceOf(
                InstallationOutcome.Complete::class.java,
                InstallationWorkflow.execute(request, unexpectedPriorUpgrade),
            )
        assertEquals(
            InstallationActivation.Pending(InstallationActivationFailure.EXIT_REJECTED),
            complete.report.activation,
        )
        assertEquals(InstallationReportStatus.INSTALLED_ACTIVATION_PENDING, complete.report.status)
        assertEquals(
            listOf("enable"),
            Files.readAllLines(
                request.installRoot.value.resolve("installation/share/kast/libexec/kast-service.called")
            ),
        )
    }

    @Test
    fun `stage option reaches payload admission without treating cold stage as usage error`() {
        for (options in listOf(listOf("--stage-only"), listOf("--force", "--stage-only"))) {
            val handled =
                assertInstanceOf(
                    InstallationHandling.Handled::class.java,
                    InstallationCliInspection.inspect(
                        listOf("installation", "install") + options,
                        requestEnvironment(),
                    ),
                )
            assertEquals(
                CliBoundaryExitStatus.BOOTSTRAP,
                assertInstanceOf(CliExit.BoundaryRejected::class.java, handled.exit).status,
            )
        }
    }

    @Test
    fun `duplicate and unknown stage options reject before payload admission`() {
        for (options in listOf(listOf("--stage-only", "--stage-only"), listOf("--stage-only", "--unknown"))) {
            val handled =
                assertInstanceOf(
                    InstallationHandling.Handled::class.java,
                    InstallationCliInspection.inspect(
                        listOf("installation", "install") + options,
                        requestEnvironment(),
                    ),
                )
            assertEquals(
                CliBoundaryExitStatus.USAGE,
                assertInstanceOf(CliExit.BoundaryRejected::class.java, handled.exit).status,
            )
        }
    }

    @Test
    fun `control-only stage option rejects before payload admission`() {
        val handled =
            assertInstanceOf(
                InstallationHandling.Handled::class.java,
                InstallationCliInspection.inspect(
                    listOf("installation", "install", "--stage-only"),
                    requestEnvironment() + (InstallationEnvironment.CONTROL_ONLY.key to "1"),
                ),
            )
        assertEquals(
            CliBoundaryExitStatus.USAGE,
            assertInstanceOf(CliExit.BoundaryRejected::class.java, handled.exit).status,
        )
    }

    @Test
    fun `direct control-only staging rejects without creating installation`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val request =
            releaseRequest(
                root,
                root.resolve("home/.local/share/kast"),
                root.resolve("home/.local/bin"),
                Files.createDirectory(root.resolve("home")),
                Files.createDirectory(root.resolve("codex")),
                "1.2.3",
                environmentOverrides =
                    mapOf(
                        InstallationEnvironment.PROFILE.key to "persistent",
                        InstallationEnvironment.CONTROL_ONLY.key to "1",
                    ),
            )
        val rejected =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                InstallationWorkflow.execute(request, unexpectedPriorUpgrade, InstallationActivationPolicy.STAGE_ONLY),
            )
        assertEquals(InstallationFailure.REQUEST_REJECTED, rejected.failure)
        assertTrue(Files.notExists(request.installRoot.value))
    }

    private fun persistentRequest(temporary: Path): InstallationRequest {
        val root = temporary.toRealPath()
        val request =
            releaseRequest(
                root,
                root.resolve("home/.local/share/kast"),
                root.resolve("home/.local/bin"),
                Files.createDirectory(root.resolve("home")),
                Files.createDirectory(root.resolve("codex")),
                "1.2.3",
                environmentOverrides = mapOf(InstallationEnvironment.PROFILE.key to "persistent"),
            )
        val libexec = Files.createDirectories(request.controlRoot.value.resolve("share/kast/libexec"))
        val service =
            Files.writeString(
                libexec.resolve("kast-service"),
                "#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"\$0.called\"\nexit 17\n",
            )
        Files.setPosixFilePermissions(service, PosixFilePermissions.fromString("rwxr-xr-x"))
        return request
    }

    private val unexpectedPriorUpgrade = PriorDaemonUpgradeGateway { _, _, _ ->
        error("fresh cold-stage fixture must not retire a prior installation")
    }

    private fun requestEnvironment(): Map<String, String> =
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
