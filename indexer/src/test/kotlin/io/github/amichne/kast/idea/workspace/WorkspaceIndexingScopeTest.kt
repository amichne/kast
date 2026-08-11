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
            scope.includedPaths.map { it.relative.value },
        )
    }

    @Test
    fun `kastignore negation can target names that begin with directive characters`() {
        workspace.resolve(".kastignore").writeText(
            """
                *
                !!important.kt
                !#important.kt
            """.trimIndent(),
        )

        val scope = WorkspaceIndexingScope.resolve(
            workspace,
            KastConfig.defaults().indexing,
            candidates("ordinary.kt", "!important.kt", "#important.kt"),
        )

        assertEquals(
            listOf("!important.kt", "#important.kt"),
            scope.includedPaths.map { it.relative.value },
        )
    }

    @Test
    fun `critical paths are shared and conflicting ignore rules fail typed`() {
        val candidates = candidates("src/main/App.kt", "src/test/AppTest.kt")
        val config = KastConfig.defaults().indexing.copy(
            criticalPaths = IndexingCriticalPaths.parse(listOf("src/main/**", "missing/**")),
            ignoredPaths = IndexingIgnoredPaths.parse(listOf("src/main/App.kt")),
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
            criticalPaths = IndexingCriticalPaths.parse(listOf("src/main/**", "missing/**")),
        )

        val scope = WorkspaceIndexingScope.resolve(workspace, config, candidates)

        assertEquals(listOf("src/main/App.kt"), scope.criticalPaths.map { it.relative.value })
        assertEquals(listOf("missing/**"), scope.unmatchedCriticalPatterns.map { it.toString() })
    }

    @Test
    fun `project ignores and hard output exclusions cannot enter persisted scope`() {
        val candidates = candidates(
            "src/main/App.kt",
            "src/test/AppTest.kt",
            "build/generated/Generated.kt",
            "cli-rs/target/debug/generated/Generated.kt",
            ".gradle/cache/Cache.kt",
            "out/classes/Output.kt",
            ".idea/metadata/Idea.kt",
        )
        val config = KastConfig.defaults().indexing.copy(
            ignoredPaths = IndexingIgnoredPaths.parse(listOf("src/test/**")),
        )

        val scope = WorkspaceIndexingScope.resolve(workspace, config, candidates)

        assertEquals(listOf("src/main/App.kt"), scope.includedPaths.map { it.relative.value })
    }

    @Test
    fun `large excluded output scope does not remain retained after resolution`() {
        val outputCandidates = List(20_000) { index ->
            workspace.resolve("cli-rs/target/debug/incremental/$index/Generated.kt")
        }
        val sourceCandidates = List(64) { index ->
            workspace.resolve("module-$index/src/main/kotlin/Source$index.kt")
        }

        val scope = WorkspaceIndexingScope.resolve(
            workspace,
            KastConfig.defaults().indexing,
            outputCandidates + sourceCandidates,
        )

        assertEquals(64, scope.includedPaths.size)
        assertEquals(
            false,
            WorkspaceIndexingScope::class.java.declaredFields.any { field -> field.name == "ignoredPaths" },
            "Excluded build outputs must not be retained for the reconciliation lifetime",
        )
    }

    @Test
    fun `leading slash anchors a workspace pattern at the root`() {
        workspace.resolve(".kastignore").writeText("/App.kt")

        val scope = WorkspaceIndexingScope.resolve(
            workspace,
            KastConfig.defaults().indexing,
            candidates("App.kt", "nested/App.kt"),
        )

        assertEquals(listOf("nested/App.kt"), scope.includedPaths.map { it.relative.value })
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

        assertEquals(listOf("src/main/Other.kt"), fallback.includedPaths.map { it.relative.value })
    }

    private fun candidates(vararg paths: String): List<Path> = paths.map { relative ->
        workspace.resolve(relative).also { path ->
            path.parent.createDirectories()
            path.writeText("class Fixture\n")
        }
    }
}
