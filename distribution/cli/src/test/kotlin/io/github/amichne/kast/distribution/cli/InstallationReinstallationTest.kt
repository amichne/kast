package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationReinstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `reinstall pins release and removes fence only for verified activation`() {
        val fixture = createLifecycleFixture(temporary)
        val calls = mutableListOf<String>()
        val events = mutableListOf<LifecycleObservation>()
        val before = Files.readString(fixture.installation.resolve("config/environment"))
        val child = LifecycleChildExecutor { command, _, _ ->
            calls += command.last()
            assertEquals(command.last() == "disable", Files.exists(fixture.root.resolve(SHUTDOWN_FENCE)))
            LifecycleChildObservation.Exited(0)
        }
        val installer = InstallerExecutor { script, arguments, report ->
            assertEquals(fixture.installation.resolve("share/kast/install.sh"), script)
            assertEquals(listOf("--version", "1.2.3", "--force", "--skip-codex-mcp", "--stage-only"), arguments)
            assertTrue(Files.isRegularFile(fixture.root.resolve(SHUTDOWN_FENCE)))
            calls += "installer"
            writeStagedReport(report)
            0
        }
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.REINSTALL,
                LifecycleExecution(
                    child = child,
                    processes = closedHostProcesses(),
                    installer = installer,
                    observe = events::add,
                ),
            )
        assertEquals(
            LifecycleOutcome.Reinstalled(LifecycleOperation.REINSTALL, fixture.installation.toString(), "1.2.3"),
            result,
        )
        assertEquals(listOf("disable", "installer", "bootstrap"), calls)
        assertFalse(Files.exists(fixture.root.resolve(SHUTDOWN_FENCE)))
        assertEquals(before, Files.readString(fixture.installation.resolve("config/environment")))
        assertEquals(LifecycleStage.REGISTRATIONS, events.last().stage)
    }

    @Test
    fun `successful installer exit without a report never reopens tool admission`() {
        val fixture = createLifecycleFixture(temporary)
        val calls = mutableListOf<String>()
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.REINSTALL,
                LifecycleExecution(
                    child =
                        LifecycleChildExecutor { command, _, _ ->
                            calls += command.last()
                            LifecycleChildObservation.Exited(0)
                        },
                    processes = closedHostProcesses(),
                    installer = InstallerExecutor { _, _, _ -> 0 },
                    observe = {},
                ),
            )
        assertEquals(
            LifecycleOutcome.Rejected(
                LifecycleOperation.REINSTALL,
                LifecycleStage.INSTALLATION,
                LifecycleFailure.INSTALLATION_REJECTED,
            ),
            result,
        )
        assertEquals(listOf("disable"), calls)
        assertTrue(Files.isRegularFile(fixture.root.resolve(SHUTDOWN_FENCE)))
    }

    @Test
    fun `exact reinstall rejects activation claimed by its cold staging installer`() {
        val fixture = createLifecycleFixture(temporary)
        val calls = mutableListOf<String>()
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.REINSTALL,
                LifecycleExecution(
                    child =
                        LifecycleChildExecutor { command, _, _ ->
                            assertEquals("disable", command.last())
                            calls += command.last()
                            LifecycleChildObservation.Exited(0)
                        },
                    processes = closedHostProcesses(),
                    installer =
                        InstallerExecutor { _, _, report ->
                            calls += "installer"
                            Files.writeString(
                                report,
                                managementJson.encodeToString(
                                    FixtureInstallerReport(
                                        status = "installed",
                                        activation = FixtureActivation("ready"),
                                    )
                                ),
                            )
                            0
                        },
                    observe = {},
                ),
            )
        assertEquals(
            LifecycleOutcome.Rejected(
                LifecycleOperation.REINSTALL,
                LifecycleStage.INSTALLATION,
                LifecycleFailure.INSTALLATION_REJECTED,
            ),
            result,
        )
        assertEquals(listOf("disable", "installer"), calls)
        assertTrue(Files.isRegularFile(fixture.root.resolve(SHUTDOWN_FENCE)))
    }

    @Test
    fun `restaged payload without shutdown capability remains fenced and cannot activate`() {
        val fixture = createLifecycleFixture(temporary)
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.REINSTALL,
                LifecycleExecution(
                    child =
                        LifecycleChildExecutor { command, _, _ ->
                            assertEquals("disable", command.last())
                            LifecycleChildObservation.Exited(0)
                        },
                    processes = closedHostProcesses(),
                    installer =
                        InstallerExecutor { _, _, report ->
                            Files.delete(fixture.installation.resolve("share/kast/lifecycle-fence-v1"))
                            writeStagedReport(report)
                            0
                        },
                    observe = {},
                ),
            )
        assertEquals(
            LifecycleOutcome.Pending(
                LifecycleOperation.REINSTALL,
                "1.2.3",
                LifecycleStage.ACTIVATION,
                LifecycleFailure.OWNERSHIP_UNPROVEN,
            ),
            result,
        )
        assertTrue(Files.isRegularFile(fixture.root.resolve(SHUTDOWN_FENCE)))
    }

    @Test
    fun `failed reactivation restores fence and reports committed installation pending`() {
        val fixture = createLifecycleFixture(temporary)
        val calls = mutableListOf<String>()
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.REINSTALL,
                LifecycleExecution(
                    child =
                        LifecycleChildExecutor { command, _, _ ->
                            calls += command.last()
                            LifecycleChildObservation.Exited(if (command.last() == "bootstrap") 7 else 0)
                        },
                    processes = closedHostProcesses(),
                    installer =
                        InstallerExecutor { _, _, report ->
                            writeStagedReport(report)
                            0
                        },
                    observe = {},
                ),
            )
        assertEquals(
            LifecycleOutcome.Pending(
                LifecycleOperation.REINSTALL,
                "1.2.3",
                LifecycleStage.ACTIVATION,
                LifecycleFailure.CHILD_REJECTED,
            ),
            result,
        )
        assertEquals(listOf("disable", "bootstrap", "disable"), calls)
        assertTrue(Files.isRegularFile(fixture.root.resolve(SHUTDOWN_FENCE)))
    }
}
