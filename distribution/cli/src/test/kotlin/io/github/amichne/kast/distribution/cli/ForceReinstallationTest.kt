package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ForceReinstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `fresh installer uses embedded bytes and stages without daemon activation`() {
        val home = Files.createDirectories(temporary.resolve("home"))
        val root = temporary.toRealPath().resolve("kast")
        var calls = 0
        ErasedResetFixture.create(root, home).use { fixture ->
            val executor = LifecycleChildExecutor { command, directory, environment ->
                calls++
                assertEquals(home, directory)
                val script = Path.of(command[1])
                assertTrue(Files.readString(script).contains("readonly REPOSITORY=\"amichne/kast\""))
                assertEquals(
                    listOf(
                        "/bin/bash",
                        script.toString(),
                        "--install-root",
                        root.toString(),
                        "--force",
                        "--skip-codex-mcp",
                        "--stage-only",
                    ),
                    command,
                )
                assertEquals(setOf("HOME", "PATH", "XDG_CONFIG_HOME", "KAST_MANAGEMENT_REPORT_PATH"), environment.keys)
                assertEquals(root.resolve("bin").toString(), environment["XDG_CONFIG_HOME"])
                assertEquals(home.toString(), environment["HOME"])
                assertFalse(Files.exists(root))
                prepareFreshPayload(root)
                writeStagedReport(Path.of(environment.getValue("KAST_MANAGEMENT_REPORT_PATH")))
                LifecycleChildObservation.Exited(0)
            }
            val result =
                installFresh(
                    fixture.erased,
                    home,
                    mapOf("PATH" to "/fixture", "KAST_VERSION" to "injected", "KAST_INSTALL_ROOT" to "/wrong"),
                    executor,
                )
            assertEquals(1, calls)
            val prepared = (result as FreshResetResult.Prepared).installation
            assertEquals("1.2.3", prepared.version.value)
            assertEquals(root.resolve("bin/kast"), prepared.command)
        }
    }

    @Test
    fun `child failures and unverified exit zero cannot become a staged payload`() {
        val home = Files.createDirectories(temporary.toRealPath().resolve("home"))
        val root = temporary.toRealPath().resolve("kast")
        ErasedResetFixture.create(root, home).use { fixture ->
            listOf(
                    LifecycleChildObservation.Exited(5) to ForceResetFailure.INSTALLER_REJECTED,
                    LifecycleChildObservation.DeadlineExceeded to ForceResetFailure.INSTALLER_DEADLINE_EXCEEDED,
                    LifecycleChildObservation.Unavailable to ForceResetFailure.INSTALLER_UNAVAILABLE,
                    LifecycleChildObservation.Exited(0) to ForceResetFailure.INSTALLATION_UNVERIFIED,
                )
                .forEach { (child, failure) ->
                    var calls = 0
                    val result =
                        installFresh(
                            fixture.erased,
                            home,
                            emptyMap(),
                            LifecycleChildExecutor { _, _, _ ->
                                calls++
                                child
                            },
                        )
                    assertEquals(1, calls)
                    assertEquals(FreshResetResult.Rejected(failure), result)
                }
        }
    }

    @Test
    fun `staging rejects missing reports and claimed activation during a fenced stage`() {
        val root = temporary.toRealPath().resolve("kast")
        prepareFreshPayload(root)
        val report = temporary.resolve("report.json")
        assertEquals(
            FreshResetResult.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED),
            FreshResetInstallation.admit(root, report),
        )
        Files.writeString(
            report,
            managementJson.encodeToString(
                FixtureInstallerReport(status = "installed", activation = FixtureActivation("ready"))
            ),
        )
        assertEquals(
            FreshResetResult.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED),
            FreshResetInstallation.admit(root, report),
        )
        writeStagedReport(report)
        assertTrue(FreshResetInstallation.admit(root, report) is FreshResetResult.Prepared)
    }
}

internal fun writeStagedReport(report: Path) =
    Files.writeString(
        report,
        managementJson.encodeToString(
            FixtureInstallerReport(status = "installed", activation = FixtureActivation("not-requested"))
        ),
    )

internal fun prepareFreshPayload(root: Path) {
    val installation = root.resolve("installation")
    val paths =
        listOf("share/kast/libexec/kast-management", "share/kast/libexec/kast-service", "share/kast/reset-fence-v1")
    val payloads = paths.map { relative ->
        val path = installation.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, if (relative.endsWith("-v1")) "1\n" else "fresh-payload")
        path.toFile().setExecutable(true)
        BundledPayload(relative, "sha256:${sha256(path)}", 493)
    }
    Files.writeString(
        installation.resolve("installation.json"),
        managementJson.encodeToString(FixtureLifecycleManifest(3, "1.2.3", installation.toString(), payloads)),
    )
    val command = root.resolve("bin/kast")
    Files.createDirectories(command.parent)
    Files.copy(installation.resolve(paths.first()), command)
    command.toFile().setExecutable(true)
    writeManagementReceipt(
        root,
        ManagementReceipt(2, root.toString(), command.toString(), sha256(command), ReleaseChannel.STABLE, emptyList()),
    )
}
