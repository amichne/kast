package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class ColdInstallationTest {
    @Test
    fun `completed installation lifts its exact shutdown fence without a child effect`(@TempDir temporary: Path) {
        val fixture = createLifecycleFixture(temporary)
        val fence = fixture.root.resolve(SHUTDOWN_FENCE)
        Files.writeString(
            fence,
            managementJson.encodeToString(InstallationShutdownRequest(fixture.installation.toString())),
        )
        val effects = mutableListOf<String>()
        val admitted =
            assertInstanceOf(
                LifecycleAdmission.Admitted::class.java,
                InstallationLifecycle.admit(
                    fixture.root,
                    fixture.home,
                    emptyMap(),
                    LifecycleExecution(
                        child =
                            LifecycleChildExecutor { command, _, _ ->
                                effects += command.last()
                                LifecycleChildObservation.Exited(0)
                            },
                        processes = closedHostProcesses(),
                        observe = {},
                    ),
                ),
            )

        assertEquals(LifecycleEffect.Completed, admitted.lifecycle.resume())
        assertEquals(emptyList<String>(), effects)
        assertFalse(Files.exists(fence))
    }
}
