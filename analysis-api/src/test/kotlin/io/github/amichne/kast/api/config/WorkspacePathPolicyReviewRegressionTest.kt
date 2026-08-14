package io.github.amichne.kast.api.client

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class WorkspacePathPolicyReviewRegressionTest {
    @Test
    fun `Unix backslash filename retains identity distinct from nested path`() {
        assumeTrue(Path.of("a\\b.kt").nameCount == 1)

        val literalBackslash = WorkspaceRelativePath.parse(Path.of("a\\b.kt"))
        val nestedPath = WorkspaceRelativePath.parse(Path.of("a/b.kt"))

        assertEquals("a\\b.kt", literalBackslash.value)
        assertNotEquals(nestedPath, literalBackslash)
    }
}
