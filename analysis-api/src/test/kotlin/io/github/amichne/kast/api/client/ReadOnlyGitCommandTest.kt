package io.github.amichne.kast.api.client

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReadOnlyGitCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `fake Git observes optional locks disabled`() {
        val fakeGit = fakeGit("printf '%s' \"\${GIT_OPTIONAL_LOCKS-unset}\"")

        val process = ReadOnlyGitCommand.processBuilder(listOf(fakeGit.toString())).start()
        val observed = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.waitFor())
        assertEquals("0", observed)
    }

    private fun fakeGit(body: String): Path = temporaryDirectory.resolve("git").also { executable ->
        Files.writeString(executable, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    }
}
