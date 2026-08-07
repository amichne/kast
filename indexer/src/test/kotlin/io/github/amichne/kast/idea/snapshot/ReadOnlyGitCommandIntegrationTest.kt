package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.api.client.ReadOnlyGitCommand
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReadOnlyGitCommandIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `indexer fake Git observes optional locks disabled after environment sanitization`() {
        val fakeGit = fakeGit("printf '%s|%s' \"\${GIT_OPTIONAL_LOCKS-unset}\" \"\${GIT_DIR-unset}\"")
        val process = ReadOnlyGitCommand.processBuilder(listOf(fakeGit.toString())).also { builder ->
            builder.environment()["GIT_DIR"] = temporaryDirectory.resolve("poisoned-git-dir").toString()
            builder.environment().remove("GIT_DIR")
        }.start()
        val observed = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.waitFor())
        assertEquals("0|unset", observed)
    }

    private fun fakeGit(body: String): Path = temporaryDirectory.resolve("git").also { executable ->
        Files.writeString(executable, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    }
}
