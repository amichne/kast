package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class IdeaProjectIndexerModuleNameTest {
    private val workspaceRoot = Path.of("/workspace/kast")

    @Test
    fun `index module name uses Gradle project path and testFixtures source set`() {
        val filePath = "/workspace/kast/analysis-api/src/testFixtures/kotlin/io/github/FakeBackend.kt"

        val moduleName = indexedModuleNameForFilePath(
            ideaModuleName = "analysis-api.main",
            filePath = filePath,
            workspaceRoot = workspaceRoot,
            sourceSet = "testFixtures",
        )

        assertEquals(":analysis-api[testFixtures]", moduleName)
    }

    @Test
    fun `index module name supports nested Gradle projects`() {
        val filePath = "/workspace/kast/features/payments/src/main/kotlin/Payment.kt"

        val moduleName = indexedModuleNameForFilePath(
            ideaModuleName = "payments.main",
            filePath = filePath,
            workspaceRoot = workspaceRoot,
            sourceSet = "main",
        )

        assertEquals(":features:payments[main]", moduleName)
    }

    @Test
    fun `index module name falls back to IDEA module name outside Gradle layout`() {
        val filePath = "/workspace/kast/generated/Foo.kt"

        val moduleName = indexedModuleNameForFilePath(
            ideaModuleName = "scratch",
            filePath = filePath,
            workspaceRoot = workspaceRoot,
            sourceSet = null,
        )

        assertEquals("scratch", moduleName)
    }
}
