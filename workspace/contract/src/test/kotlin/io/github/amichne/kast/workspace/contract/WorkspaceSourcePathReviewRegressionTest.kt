package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkspaceSourcePathReviewRegressionTest {
    @Test
    fun `Unix backslash filename retains identity distinct from nested path`() {
        assumeTrue(Path.of("a\\b.kt").nameCount == 1)

        val literalBackslash = WorkspaceSourcePath.parse("a\\b.kt").refined()
        val nestedPath = WorkspaceSourcePath.parse("a/b.kt").refined()

        assertEquals("a\\b.kt", literalBackslash.value)
        assertNotEquals(nestedPath, literalBackslash)
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Unexpected path rejection: $failure")
    }
}
