package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompletedInstallationResumeTest {
    @Test
    fun `replacement of an admitted fence prevents deletion and bootstrap`(@TempDir temporary: Path) {
        val fixture = createLifecycleFixture(temporary)
        val remaining = ArrayDeque(listOf("disable"))
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
        val admitted =
            assertInstanceOf(
                LifecycleAdmission.Admitted::class.java,
                InstallationLifecycle.admit(fixture.root, fixture.home, emptyMap(), execution),
            )
        assertInstanceOf(LifecycleOutcome.Stopped::class.java, admitted.lifecycle.shutdown(LifecycleOperation.STOP))
        val fence = fixture.root.resolve(SHUTDOWN_FENCE)
        val bytes = Files.readString(fence)
        Files.move(fence, fixture.root.resolve("previous-fence"))
        Files.writeString(fence, bytes)

        assertEquals(LifecycleEffect.Rejected(LifecycleFailure.FENCE_REJECTED), admitted.lifecycle.resume())
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
        val remaining = ArrayDeque(listOf("bootstrap"))
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
        val admitted =
            assertInstanceOf(
                LifecycleAdmission.Admitted::class.java,
                InstallationLifecycle.admit(fixture.root, fixture.home, emptyMap(), execution),
            )

        assertEquals(LifecycleEffect.Completed, admitted.lifecycle.resume())
        assertTrue(remaining.isEmpty(), "Unconsumed child executions")
        assertFalse(Files.exists(fence))
    }
}
