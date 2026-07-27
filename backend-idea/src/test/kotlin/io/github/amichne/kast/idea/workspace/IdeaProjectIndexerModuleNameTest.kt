package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class IdeaProjectIndexerModuleNameTest {
    private val workspaceRoot = Path.of("/workspace/kast")

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `filesystem fallback refuses to scan filesystem root`() {
        val filesystemRoot = tempDir.root

        val paths = discoverWorkspaceKotlinFilePaths(filesystemRoot) { false }

        assertEquals(emptyList<String>(), paths)
    }

    @Test
    fun `filesystem fallback discovers eligible workspace Kotlin files and skips generated directories`() {
        val workspace = tempDir.resolve("workspace")
        val sourceFile = workspace.resolve("analysis-api/src/main/kotlin/demo/Fallback.kt")
        val testFile = workspace.resolve("analysis-api/src/test/kotlin/demo/FallbackTest.kt")
        val generatedFile = workspace.resolve("analysis-api/build/generated/demo/Generated.kt")
        val hiddenFile = workspace.resolve(".gradle/caches/demo/Cached.kt")
        Files.createDirectories(sourceFile.parent)
        Files.createDirectories(testFile.parent)
        Files.createDirectories(generatedFile.parent)
        Files.createDirectories(hiddenFile.parent)
        Files.writeString(sourceFile, "package demo\nclass Fallback\n")
        Files.writeString(testFile, "package demo\nclass FallbackTest\n")
        Files.writeString(generatedFile, "package demo\nclass Generated\n")
        Files.writeString(hiddenFile, "package demo\nclass Cached\n")

        val paths = discoverWorkspaceKotlinFilePaths(workspace) { false }

        assertEquals(
            listOf(sourceFile, testFile).map { path -> path.toAbsolutePath().normalize().toString() },
            paths,
        )
    }

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

    @Test
    fun `module priority order merges duplicate indexed module specs before sorting`() {
        val specs = listOf(
            IdeaModuleSpec(":analysis-api", listOf(":build-logic")),
            IdeaModuleSpec(":analysis-api", listOf(":index-store")),
            IdeaModuleSpec(":build-logic", emptyList()),
            IdeaModuleSpec(":index-store", emptyList()),
        )

        val order = computeModulePriorityOrder(
            activeModule = null,
            moduleSpecs = specs,
            dependentModuleGraph = emptyMap(),
            depth = 2,
        )

        assertEquals(
            listOf(":build-logic", ":index-store", ":analysis-api"),
            order,
        )
    }

    @Test
    fun `module priority order ignores self dependencies introduced by duplicate source set modules`() {
        val specs = listOf(
            IdeaModuleSpec(":analysis-api", listOf(":analysis-api", ":index-store")),
            IdeaModuleSpec(":analysis-api", listOf(":build-logic")),
            IdeaModuleSpec(":build-logic", emptyList()),
            IdeaModuleSpec(":index-store", emptyList()),
        )

        val order = computeModulePriorityOrder(
            activeModule = null,
            moduleSpecs = specs,
            dependentModuleGraph = emptyMap(),
            depth = 2,
        )

        assertEquals(
            listOf(":build-logic", ":index-store", ":analysis-api"),
            order,
        )
    }
}
