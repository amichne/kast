package io.github.amichne.kast.cli.installation

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagedHostDeploymentTest {
    @Test
    fun `missing managed state rejects start before process discovery or launch`() {
        val result =
            executeManagedHost(
                ManagedHostRequest.Start("/unused/idea", "/unused/profile"),
                Path.of("/unused/home"),
                processes = { error("Missing managed state must not discover desktop processes") },
                start = { error("Missing managed state must not launch IDEA") },
            )
        assertEquals(ManagedHostResponse.Rejected(ManagedHostFailure.LAUNCH_REJECTED), result)
    }
}
