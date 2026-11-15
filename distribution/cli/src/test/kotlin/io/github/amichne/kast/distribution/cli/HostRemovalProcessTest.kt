package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostRemovalProcessTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Host helper diagnostics on stderr never enter the decoded removal reply`() {
        val plugin = Path.of("/owned/idea/plugins/kast-ide-hosted")
        val reply = Json.encodeToString(RemovalReply("REMOVED", plugin.toString()))
        val child = temporary.resolve("host-helper")
        // A real child process: the deprecated helper warns on stderr before its stdout reply.
        Files.writeString(
            child,
            "#!/bin/sh\nprintf 'kast-host-installation: deprecated\\n' >&2\nprintf '%s' '$reply'\n",
        )
        child.toFile().setExecutable(true)

        val observed = executeHostRemoval(listOf(child.toString()))

        assertEquals(HostRemovalProcessObservation.Exited(0, reply), observed)
        assertEquals(Refinement.Refined(Unit), admitHostRemovalOutput(observed, plugin))
    }

    @Serializable private data class RemovalReply(val type: String, val plugin: String)
}
