package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

internal fun runGitCommand(
    directory: Path,
    vararg arguments: String,
) {
    val process = startGitProcess(directory, *arguments)
    val output = process.inputStream.use { it.readAllBytes().toString(Charsets.UTF_8) }
    assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")} failed: $output")
}

internal fun readGitOutput(
    directory: Path,
    vararg arguments: String,
): String {
    val process = startGitProcess(directory, *arguments)
    val output = process.inputStream.use { it.readAllBytes().toString(Charsets.UTF_8) }
    assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")} failed: $output")
    return output.trim()
}

internal fun startGitProcess(
    directory: Path,
    vararg arguments: String,
): Process =
    ProcessBuilder("git", *arguments)
        .directory(directory.toFile())
        .redirectErrorStream(true)
        .start()

internal fun createCommittedTestRepository(tempDir: Path): Path {
    val repository = tempDir.resolve("repository").also(Files::createDirectories)
    runGitCommand(repository, "init")
    runGitCommand(repository, "config", "user.name", "Kast Test")
    runGitCommand(repository, "config", "user.email", "kast@example.invalid")
    Files.writeString(repository.resolve("README.md"), "initial")
    runGitCommand(repository, "add", "README.md")
    runGitCommand(repository, "commit", "-m", "initial")
    return repository
}

internal fun workspaceTransitionProjectStub(): Project = Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java),
) { _, method, _ ->
    when (method.name) {
        "getName" -> "stub"
        "isDisposed" -> false
        "hashCode" -> 0
        "equals" -> false
        "toString" -> "ProjectStub"
        else -> null
    }
} as Project
