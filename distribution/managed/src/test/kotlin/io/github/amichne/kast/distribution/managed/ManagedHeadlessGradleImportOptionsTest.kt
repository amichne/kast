package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.configuration.HostHeapPolicy
import io.github.amichne.kast.distribution.contract.configuration.HostIdleShutdown
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class ManagedHeadlessGradleImportOptionsTest {
    @Test
    fun `private launch publishes one complete Gradle model without download progress events`(
        @TempDir directory: Path
    ) {
        val fixture = managedHeadlessFixture(directory)
        val installation =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessInstallation>>(
                    ManagedHeadlessInstallation.resolve(fixture.launch, fixture.state, fixture.state.resolve("home"))
                )
                .value
        val launch =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessLaunch>>(
                    ManagedHeadlessLaunch.prepare(
                        installation,
                        "/usr/bin:/bin",
                        HostIdleShutdown.Disabled,
                        HostHeapPolicy.Inherit,
                    )
                )
                .value
        assertEquals(
            listOf("-Dgradle.phased.sync.enabled=false", "-Dgradle.output.sync.progress.events=false"),
            launch.options.lines().filter { it.startsWith("-Dgradle.") },
        )
    }
}
