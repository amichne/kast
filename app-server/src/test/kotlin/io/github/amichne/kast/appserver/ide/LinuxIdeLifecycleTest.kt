package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunch
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxIdeLifecycleTest {
    @Test
    fun `managed cold launch requires trust and early exit grants no readiness`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        val fixture = ManagedDeploymentFixture(root)
        var launches = 0
        val platform =
            object : IdeLaunchPlatform {
                override fun startManaged(
                    specification: ManagedHeadlessLaunch
                ): Refinement<Process, IdeLifecycleFailure> {
                    launches++
                    assertEquals(
                        listOf(fixture.idea.resolve("bin/idea").toString(), "kast-headless"),
                        specification.arguments,
                    )
                    assertEquals(fixture.home.toString(), specification.environment["HOME"])
                    return Refinement.Refined(ProcessBuilder("/usr/bin/false").start().also { it.waitFor() })
                }
            }
        val client =
            IdeLifecycleClient(
                fixture.home,
                fixture.idea,
                LifecycleExchangeTimer.Native,
                System::nanoTime,
                platform,
                fixture.deployment,
            )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.TRUST_REQUIRED),
            client.open(root.toString(), "request", "test"),
        )
        assertEquals(0, launches)
        assertEquals(IdeLifecycleResult.Blocked(IdeLifecycleFailure.LAUNCH_FAILED), client.startInstallation())
        assertEquals(1, launches)
    }

    @Test
    fun `explicit managed selection rejects missing ownership before launch`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        val fixture = ManagedDeploymentFixture(root)
        val platform =
            object : IdeLaunchPlatform {
                override fun startManaged(
                    specification: ManagedHeadlessLaunch
                ): Refinement<Process, IdeLifecycleFailure> = error("Unowned state must not launch")
            }
        val client =
            IdeLifecycleClient(
                fixture.home,
                fixture.idea,
                LifecycleExchangeTimer.Native,
                System::nanoTime,
                platform,
                IdeLifecycleDeployment.Headless(root.resolve("missing")),
            )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE),
            client.open(root.toString(), "request", "test"),
        )
        assertEquals(IdeLifecycleResult.Blocked(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE), client.startInstallation())
    }
}
