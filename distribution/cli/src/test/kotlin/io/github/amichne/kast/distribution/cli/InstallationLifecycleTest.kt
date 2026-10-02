package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationLifecycleTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `shutdown fences first and retains payload config and registrations`() {
        val fixture = fixture()
        val events = mutableListOf<LifecycleObservation>()
        var calls = 0
        val child = LifecycleChildExecutor { command, directory, environment ->
            calls++
            assertEquals(
                listOf(fixture.installation.resolve("share/kast/libexec/kast-service").toString(), "disable"),
                command,
            )
            assertEquals(fixture.installation, directory)
            assertEquals(fixture.home.toString(), environment["HOME"])
            assertTrue(Files.isRegularFile(fixture.root.resolve(SHUTDOWN_FENCE)))
            LifecycleChildObservation.Exited(0)
        }
        val before = Files.readString(receiptPath(fixture.root))
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.STOP,
                LifecycleExecution(child = child, processes = processes(), observe = events::add),
            )
        assertEquals(LifecycleOutcome.Stopped(LifecycleOperation.STOP, fixture.installation.toString()), result)
        assertEquals(1, calls)
        assertEquals(before, Files.readString(receiptPath(fixture.root)))
        assertTrue(Files.isRegularFile(fixture.installation.resolve("config/environment")))
        assertEquals(
            listOf(LifecycleStage.FENCE, LifecycleStage.COORDINATOR, LifecycleStage.REQUESTS, LifecycleStage.HOST),
            events.filter { it.outcome == LifecycleObservationOutcome.COMPLETED }.map { it.stage },
        )
    }

    @Test
    fun `coordinator rejection preserves fence and prevents later effects`() {
        val fixture = fixture()
        val events = mutableListOf<LifecycleObservation>()
        val process =
            object : LifecycleProcesses {
                override fun observe(pid: Long): LifecycleProcessObservation = error("unexpected process inspection")

                override fun retire(
                    process: OwnedLifecycleProcess,
                    operation: LifecycleOperation,
                    allowance: Duration,
                ): LifecycleEffect = error("unexpected signal")

                override fun selectedHost(executable: Path): LifecycleEffect = error("unexpected host inspection")
            }
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.REINSTALL,
                LifecycleExecution(
                    child = LifecycleChildExecutor { _, _, _ -> LifecycleChildObservation.Exited(7) },
                    processes = process,
                    observe = events::add,
                ),
            )
        assertEquals(
            LifecycleOutcome.Rejected(
                LifecycleOperation.REINSTALL,
                LifecycleStage.COORDINATOR,
                LifecycleFailure.CHILD_REJECTED,
            ),
            result,
        )
        assertTrue(Files.isRegularFile(fixture.root.resolve(SHUTDOWN_FENCE)))
        assertEquals(LifecycleObservationOutcome.REJECTED, events.last().outcome)
    }

    @Test
    fun `running user IDE prevents reinstall before payload effects`() {
        val fixture = fixture()
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.REINSTALL,
                LifecycleExecution(
                    child = LifecycleChildExecutor { _, _, _ -> LifecycleChildObservation.Exited(0) },
                    processes = processes(host = LifecycleEffect.Rejected(LifecycleFailure.HOST_RESTART_REQUIRED)),
                    observe = {},
                ),
            )
        assertEquals(
            LifecycleOutcome.Rejected(
                LifecycleOperation.REINSTALL,
                LifecycleStage.HOST,
                LifecycleFailure.HOST_RESTART_REQUIRED,
            ),
            result,
        )
        assertTrue(Files.isRegularFile(fixture.installation.resolve("share/kast/libexec/kast-service")))
        assertTrue(Files.isRegularFile(fixture.root.resolve(SHUTDOWN_FENCE)))
    }

    @Test
    fun `kill only signals recorded installed process incarnations and verifies retirement`() {
        val fixture = fixture()
        val record = InstalledToolInvocation(1, 42, 100)
        val directory = fixture.installation.resolve("state/run/one-shot")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("owned.json"), managementJson.encodeToString(record))
        val snapshot =
            LifecycleProcessSnapshot(
                42,
                100,
                "/fixture/bin/java",
                listOf(
                    "-classpath",
                    fixture.installation.resolve("lib/kast.jar").toString(),
                    "io.github.amichne.kast.cli.rpc.KastToolRpcMain",
                    "call",
                    "query_symbols",
                ),
            )
        var observations = 0
        var signals = 0
        val process =
            object : LifecycleProcesses {
                override fun observe(pid: Long): LifecycleProcessObservation {
                    assertEquals(42, pid)
                    observations++
                    return if (signals == 0) LifecycleProcessObservation.Present(snapshot)
                    else LifecycleProcessObservation.Absent
                }

                override fun retire(
                    process: OwnedLifecycleProcess,
                    operation: LifecycleOperation,
                    allowance: Duration,
                ): LifecycleEffect {
                    assertEquals(snapshot, process.snapshot)
                    assertEquals(LifecycleOperation.STOP_FORCE, operation)
                    signals++
                    return LifecycleEffect.Completed
                }

                override fun selectedHost(executable: Path) = LifecycleEffect.Completed
            }
        val outcome =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.STOP_FORCE,
                LifecycleExecution(
                    child = LifecycleChildExecutor { _, _, _ -> LifecycleChildObservation.Exited(0) },
                    processes = process,
                    observe = {},
                ),
            )
        assertTrue(outcome is LifecycleOutcome.Stopped)
        assertEquals(1, signals)
        assertEquals(2, observations)
    }

    @Test
    fun `PID reuse and foreign command cannot transfer signal authority`() {
        val installation = temporary.resolve("installation")
        val record = InstalledToolInvocation(1, 42, 100)
        val foreign = LifecycleProcessSnapshot(42, 100, "/usr/bin/sleep", listOf("60"))
        assertEquals(
            ShutdownProcessAdmission.Rejected,
            OwnedLifecycleProcess.admit(record, LifecycleProcessObservation.Present(foreign), installation),
        )
        assertEquals(
            ShutdownProcessAdmission.Finished,
            OwnedLifecycleProcess.admit(
                record,
                LifecycleProcessObservation.Present(foreign.copy(startEpochMillis = 101)),
                installation,
            ),
        )
        assertEquals(
            ShutdownProcessAdmission.Rejected,
            OwnedLifecycleProcess.admit(
                record.copy(schemaVersion = 99),
                LifecycleProcessObservation.Absent,
                installation,
            ),
        )
    }

    @Test
    fun `every lifecycle result has a required closed discriminator`() {
        val results =
            listOf(
                LifecycleOutcome.Stopped(LifecycleOperation.STOP, "/installation") to "STOPPED",
                LifecycleOutcome.Reinstalled(LifecycleOperation.REINSTALL, "/installation", "1.2.3") to "REINSTALLED",
                LifecycleOutcome.Rejected(
                    LifecycleOperation.STOP_FORCE,
                    LifecycleStage.HOST,
                    LifecycleFailure.HOST_RESTART_REQUIRED,
                ) to "REJECTED",
                LifecycleOutcome.Pending(
                    LifecycleOperation.REINSTALL,
                    "1.2.3",
                    LifecycleStage.ACTIVATION,
                    LifecycleFailure.CHILD_REJECTED,
                ) to "REINSTALLATION_PENDING",
            )
        results.forEach { (result, type) -> assertTrue(result.asJson().startsWith("{\"type\":\"$type\"")) }
    }

    @Test
    fun `foreign fence rejects before service execution and preserves its exact bytes`() {
        val fixture = fixture()
        Files.writeString(fixture.root.resolve(SHUTDOWN_FENCE), "foreign-record")
        val result =
            executeInstallationLifecycle(
                fixture.root,
                fixture.home,
                emptyMap(),
                LifecycleOperation.STOP_FORCE,
                LifecycleExecution(
                    child = LifecycleChildExecutor { _, _, _ -> error("unexpected child") },
                    processes = processes(),
                    observe = {},
                ),
            )
        assertEquals(
            LifecycleOutcome.Rejected(
                LifecycleOperation.STOP_FORCE,
                LifecycleStage.FENCE,
                LifecycleFailure.FENCE_REJECTED,
            ),
            result,
        )
        assertEquals("foreign-record", Files.readString(fixture.root.resolve(SHUTDOWN_FENCE)))
    }

    private fun processes(host: LifecycleEffect = LifecycleEffect.Completed) =
        object : LifecycleProcesses {
            override fun observe(pid: Long): LifecycleProcessObservation = error("unexpected process inspection")

            override fun retire(
                process: OwnedLifecycleProcess,
                operation: LifecycleOperation,
                allowance: Duration,
            ): LifecycleEffect = error("unexpected signal")

            override fun selectedHost(executable: Path) = host
        }

    private fun fixture(): LifecycleFixture = createLifecycleFixture(temporary)
}
