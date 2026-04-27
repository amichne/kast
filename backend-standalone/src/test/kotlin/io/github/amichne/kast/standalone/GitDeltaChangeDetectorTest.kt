package io.github.amichne.kast.standalone

import io.github.amichne.kast.standalone.cache.GitDeltaChangeDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class GitDeltaChangeDetectorTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `git delta candidates include committed and working tree Kotlin changes`() {
        git("init")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        writeFile("src/main/kotlin/sample/A.kt", "package sample\nfun a() = 1\n")
        writeFile("src/main/kotlin/sample/B.kt", "package sample\nfun b() = 1\n")
        writeFile("README.md", "initial\n")
        git("add", ".")
        git("commit", "-m", "initial")
        val storedHead = head()

        writeFile("src/main/kotlin/sample/A.kt", "package sample\nfun a() = 2\n")
        writeFile("README.md", "changed\n")
        git("add", ".")
        git("commit", "-m", "change a")
        writeFile("src/main/kotlin/sample/B.kt", "package sample\nfun b() = 2\n")

        val candidates = GitDeltaChangeDetector(workspaceRoot).detectCandidatePaths(
            storedHeadCommit = storedHead,
            sourceRoots = listOf(workspaceRoot.resolve("src/main/kotlin")),
        )

        assertEquals(
            setOf(
                normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin/sample/A.kt")).toString(),
                normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin/sample/B.kt")).toString(),
            ),
            requireNotNull(candidates).paths,
        )
        assertEquals(
            setOf(
                normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin/sample/A.kt")).toString(),
                normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin/sample/B.kt")).toString(),
            ),
            candidates.trackedPaths,
        )
        assertEquals(head(), candidates.headCommit)
    }

    @Test
    fun `git delta detection is unavailable without stored head`() {
        git("init")

        assertNull(
            GitDeltaChangeDetector(workspaceRoot).detectCandidatePaths(
                storedHeadCommit = null,
                sourceRoots = listOf(workspaceRoot.resolve("src/main/kotlin")),
            ),
        )
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ) {
        val file = workspaceRoot.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun head(): String = git("rev-parse", "HEAD").single()

    private fun git(vararg args: String): List<String> {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(workspaceRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readLines()
        check(process.waitFor() == 0) { output.joinToString("\n") }
        return output.filter(String::isNotBlank)
    }
}
