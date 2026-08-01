package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.IndexingCriticalPaths
import io.github.amichne.kast.api.client.fields.IndexingIgnoredPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkspaceIndexingScopeTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `kastignore uses gitignore ordering and affects persisted indexing scope only`() {
        workspace.resolve(".kastignore").writeText(
            """
                generated/**
                !generated/keep.kt
                **/fixtures/**
            """.trimIndent(),
        )
        val candidates = candidates(
            "src/main/App.kt",
            "generated/Drop.kt",
            "generated/keep.kt",
            "module/fixtures/Fixture.kt",
        )

        val scope = WorkspaceIndexingScope.resolve(workspace, KastConfig.defaults().indexing, candidates)

        assertEquals(
            listOf("generated/keep.kt", "src/main/App.kt"),
            scope.includedPaths.map { workspace.relativize(it).toString() },
        )
        assertEquals(
            listOf("generated/Drop.kt", "module/fixtures/Fixture.kt"),
            scope.ignoredPaths.map { workspace.relativize(it).toString() },
        )
    }

    @Test
    fun `critical paths are shared and conflicting ignore rules fail typed`() {
        val candidates = candidates("src/main/App.kt", "src/test/AppTest.kt")
        val config = KastConfig.defaults().indexing.copy(
            criticalPaths = IndexingCriticalPaths(listOf("src/main/**", "missing/**")),
            ignoredPaths = IndexingIgnoredPaths(listOf("src/main/App.kt")),
        )

        val error = assertThrows(IndexingScopeConfigurationException::class.java) {
            WorkspaceIndexingScope.resolve(workspace, config, candidates)
        }

        assertEquals("INDEXING_SCOPE_CONFLICT", error.code)
    }

    @Test
    fun `critical matches and unmatched obligations remain explicit`() {
        val candidates = candidates("src/main/App.kt", "src/test/AppTest.kt")
        val config = KastConfig.defaults().indexing.copy(
            criticalPaths = IndexingCriticalPaths(listOf("src/main/**", "missing/**")),
        )

        val scope = WorkspaceIndexingScope.resolve(workspace, config, candidates)

        assertEquals(listOf("src/main/App.kt"), scope.criticalPaths.map { workspace.relativize(it).toString() })
        assertEquals(listOf("missing/**"), scope.unmatchedCriticalPatterns)
    }

    @Test
    fun `project ignores and hard output exclusions cannot enter persisted scope`() {
        val candidates = candidates(
            "src/main/App.kt",
            "src/test/AppTest.kt",
            "build/generated/Generated.kt",
            ".gradle/cache/Cache.kt",
            "out/classes/Output.kt",
            ".idea/metadata/Idea.kt",
        )
        val config = KastConfig.defaults().indexing.copy(
            ignoredPaths = IndexingIgnoredPaths(listOf("src/test/**")),
        )

        val scope = WorkspaceIndexingScope.resolve(workspace, config, candidates)

        assertEquals(listOf("src/main/App.kt"), scope.includedPaths.map(::relative))
        assertEquals(
            listOf(
                ".gradle/cache/Cache.kt",
                ".idea/metadata/Idea.kt",
                "build/generated/Generated.kt",
                "out/classes/Output.kt",
                "src/test/AppTest.kt",
            ),
            scope.ignoredPaths.map(::relative),
        )
    }

    @Test
    fun `invalid patterns fail typed`() {
        val config = KastConfig.defaults().indexing.copy(
            criticalPaths = IndexingCriticalPaths(listOf("[]")),
        )

        val error = assertThrows(IndexingScopeConfigurationException::class.java) {
            WorkspaceIndexingScope.resolve(workspace, config, emptyList())
        }

        assertEquals("INDEXING_SCOPE_INVALID", error.code)
    }

    @Test
    fun `invalid live ignore retains the last valid scope and reports typed`() {
        val candidates = candidates("src/main/App.kt", "generated/Drop.kt")
        workspace.resolve(".kastignore").writeText("generated/**")
        val failures = mutableListOf<IndexingScopeConfigurationException>()
        val cache = WorkspaceIndexingScopeCache(failures::add)
        val valid = cache.resolve(workspace, KastConfig.defaults().indexing, candidates)

        workspace.resolve(".kastignore").writeText("!")
        val fallback = cache.resolve(workspace, KastConfig.defaults().indexing, candidates)

        assertEquals(valid, fallback)
        assertEquals("INDEXING_SCOPE_INVALID", failures.single().code)
    }

    @Test
    fun `invalid live ignore reapplies the last valid policy to new candidates`() {
        workspace.resolve(".kastignore").writeText("generated/**")
        val cache = WorkspaceIndexingScopeCache()
        cache.resolve(
            workspace,
            KastConfig.defaults().indexing,
            candidates("src/main/App.kt", "generated/Drop.kt"),
        )

        workspace.resolve(".kastignore").writeText("!")
        val fallback = cache.resolve(
            workspace,
            KastConfig.defaults().indexing,
            candidates("src/main/Other.kt", "generated/New.kt"),
        )

        assertEquals(listOf("src/main/Other.kt"), fallback.includedPaths.map(::relative))
        assertEquals(listOf("generated/New.kt"), fallback.ignoredPaths.map(::relative))
    }

    private fun candidates(vararg paths: String): List<Path> = paths.map { relative ->
        workspace.resolve(relative).also { path ->
            path.parent.createDirectories()
            path.writeText("class Fixture\n")
        }
    }

    private fun relative(path: Path): String = workspace.relativize(path).toString()
}
