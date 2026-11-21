package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationLifecycleTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `rejected uninstall preparation does not strand the product behind a fence`() {
        val fixture = productFixture(temporary)
        val root = fixture.installation.parent
        val home = Files.createDirectory(temporary.resolve("user-home")).toRealPath()
        val executable = Files.writeString(temporary.resolve("public-control"), "fixture Control")
        writeProductRemovalFixture(fixture)
        writeProductManagement(fixture.installation, executable, fixture.reference)
        val store = ContainerStore(fixture.runtime.root.parent.parent, fixture.runtime.manifest.platform)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        assertTrue(
            store.create(ContainerCommand.Create("owned", null, null, false), workspace, fixture.runtime)
                is Refinement.Refined
        )
        org.junit.jupiter.api.assertThrows<ManagementRejected> {
            uninstallInstallation(
                root,
                home,
                executeInstaller = { _, _ -> error("uninstall preparation did not complete") },
                observe = {},
            )
        }
        assertFalse(Files.exists(root.resolve(SHUTDOWN_FENCE)))
        assertFalse(Files.exists(uninstallJournalPath(root)))
        assertTrue(Files.exists(fixture.installation))
        assertTrue(Files.exists(workspace))
    }

    @Test
    fun `profile rejection before the uninstall journal releases only this attempts exact fence`() {
        val fixture = productFixture(temporary)
        writeProductRemovalFixture(fixture)
        val root = fixture.installation.parent
        val lifecycle =
            (InstallationLifecycle.admitProduct(
                    root,
                    temporary.toRealPath(),
                    emptyMap(),
                    fixture.runtime.manifest.platform,
                    LifecycleExecution(),
                ) as Refinement.Refined)
                .value
        val fence = root.resolve(SHUTDOWN_FENCE)
        org.junit.jupiter.api.assertThrows<ManagementRejected> {
            lifecycle.duringUninstallPreparation {
                assertEquals(Refinement.Refined(Unit), lifecycle.fenceManagedProduct())
                throw ManagementRejected("uninstall-user-state", "unsettled mutation")
            }
        }
        assertFalse(Files.exists(fence))
        org.junit.jupiter.api.assertThrows<ManagementRejected> {
            lifecycle.duringUninstallPreparation {
                assertEquals(Refinement.Refined(Unit), lifecycle.fenceManagedProduct())
                Files.writeString(fence, "foreign replacement")
                throw ManagementRejected("uninstall-user-state", "unsettled mutation")
            }
        }
        assertEquals("foreign replacement", Files.readString(fence))
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
