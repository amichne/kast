package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Path

internal fun runGitCommand(directory: Path, vararg arguments: String) {
    val process = startGitProcess(directory, *arguments)
    val output = process.inputStream.use { it.readAllBytes().toString(Charsets.UTF_8) }
    assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")} failed: $output")
}

internal fun readGitOutput(directory: Path, vararg arguments: String): String {
    val process = startGitProcess(directory, *arguments)
    val output = process.inputStream.use { it.readAllBytes().toString(Charsets.UTF_8) }
    assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")} failed: $output")
    return output.trim()
}

internal fun startGitProcess(directory: Path, vararg arguments: String): Process =
    ProcessBuilder("git", *arguments)
        .directory(directory.toFile())
        .redirectErrorStream(true)
        .start()
