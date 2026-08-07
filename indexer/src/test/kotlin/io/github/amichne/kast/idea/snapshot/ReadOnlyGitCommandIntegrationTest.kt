package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.api.client.ReadOnlyGitCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadOnlyGitCommandIntegrationTest {
    @Test
    fun `indexer retains optional-lock proof after repository environment sanitization`() {
        val builder = ReadOnlyGitCommand.workspaceTopLevel().processBuilder().also { builder ->
            builder.environment()["GIT_DIR"] = "poisoned-git-dir"
            builder.environment().remove("GIT_DIR")
        }

        assertEquals("0", builder.environment()["GIT_OPTIONAL_LOCKS"])
        assertEquals(null, builder.environment()["GIT_DIR"])
    }
}
