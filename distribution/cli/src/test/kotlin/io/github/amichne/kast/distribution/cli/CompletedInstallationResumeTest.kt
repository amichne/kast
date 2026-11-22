package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompletedInstallationResumeTest {
    @Test
    fun `replacement of an admitted fence prevents deletion and bootstrap`(@TempDir temporary: Path) {
        val fixture = createLifecycleFixture(temporary)
        val remaining = ArrayDeque<String>()
        val execution =
            LifecycleExecution(
                child =
                    LifecycleChildExecutor { command, _, _ ->
                        assertEquals(remaining.removeFirstOrNull(), command.last(), "Unexpected child execution")
                        LifecycleChildObservation.Exited(0)
                    },
                processes = closedHostProcesses(),
                observe = {},
            )
        val lifecycle = fixture.admitLifecycle(execution)
        assertEquals(Refinement.Refined(Unit), lifecycle.fenceManagedProduct())
        val fence = fixture.root.resolve(SHUTDOWN_FENCE)
        val bytes = Files.readString(fence)
        Files.move(fence, fixture.root.resolve("previous-fence"))
        Files.writeString(fence, bytes)

        assertEquals(LifecycleEffect.Rejected(LifecycleFailure.FENCE_REJECTED), lifecycle.resume())
        assertTrue(remaining.isEmpty(), "Unconsumed child executions")
        assertEquals(bytes, Files.readString(fence))
    }

    @Test
    fun `new lifecycle owner resumes an exact persisted shutdown after installation`(@TempDir temporary: Path) {
        val fixture = createLifecycleFixture(temporary)
        val fence = fixture.root.resolve(SHUTDOWN_FENCE)
        Files.writeString(
            fence,
            managementJson.encodeToString(InstallationShutdownRequest(fixture.installation.toString())),
        )
        val remaining = ArrayDeque<String>()
        val execution =
            LifecycleExecution(
                child =
                    LifecycleChildExecutor { command, directory, environment ->
                        assertEquals(remaining.removeFirstOrNull(), command.last(), "Unexpected child execution")
                        assertEquals(fixture.installation, directory)
                        assertEquals(fixture.home.toString(), environment["HOME"])
                        assertFalse(Files.exists(fence))
                        LifecycleChildObservation.Exited(0)
                    },
                processes = closedHostProcesses(),
                observe = {},
            )
        val lifecycle = fixture.admitLifecycle(execution)

        assertEquals(LifecycleEffect.Completed, lifecycle.resume())
        assertTrue(remaining.isEmpty(), "Unconsumed child executions")
        assertFalse(Files.exists(fence))
    }
}
