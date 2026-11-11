package io.github.amichne.kast.distribution.contract

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedEndpointPathsTest {
    @Test
    fun `arbitrarily long homes fit one short socket namespace without aliasing owners or families`() {
        val home = Path.of("/Users/" + "long".repeat(100) + "/state/home")
        val semantic = HostedEndpointPaths.directory(home, HostedEndpointPaths.Family.SEMANTIC, "/workspace")
        assertTrue(semantic.resolve("host.sock").toString().toByteArray().size <= 100)
        assertTrue(semantic.startsWith(Path.of("/tmp").toRealPath()))
        assertEquals(semantic, HostedEndpointPaths.directory(home, HostedEndpointPaths.Family.SEMANTIC, "/workspace"))
        assertNotEquals(
            semantic,
            HostedEndpointPaths.directory(home, HostedEndpointPaths.Family.LIFECYCLE, "/workspace"),
        )
        assertNotEquals(
            semantic,
            HostedEndpointPaths.directory(home.resolve("other"), HostedEndpointPaths.Family.SEMANTIC, "/workspace"),
        )
    }
}
