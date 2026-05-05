package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.GradleRunExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class GradleRunExecutorTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `returns structured failure result and writes raw log`() {
        val wrapper = workspaceRoot.resolve("gradlew")
        wrapper.writeText(
            """
            #!/usr/bin/env bash
            echo "> Task :broken FAILED"
            echo "FAILURE: Build failed with an exception."
            echo "BUILD FAILED in 1s"
            exit 7
            """.trimIndent(),
        )
        assertTrue(wrapper.toFile().setExecutable(true))

        val result = GradleRunExecutor().run(workspaceRoot, ":broken")

        assertFalse(result.ok)
        assertEquals(":broken", result.task)
        assertEquals(7, result.exitCode)
        assertEquals(1, result.tasksExecuted)
        assertTrue(result.failureSummary?.contains("FAILURE: Build failed") == true)
        assertTrue(Files.isRegularFile(Path.of(result.logFile)))
    }
}
