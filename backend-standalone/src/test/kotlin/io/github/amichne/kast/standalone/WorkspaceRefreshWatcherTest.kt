package io.github.amichne.kast.standalone

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkspaceRefreshWatcherTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `watcher registers new directories after source root refresh`() {
        writeFile(
            relativePath = "app/src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun app(): String = "ready"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "generated/src/main/kotlin/sample/Generated.kt",
            content = """
                package sample

                fun generated(): String = "later"
            """.trimIndent() + "\n",
        )
        val initialRoot = workspaceRoot.resolve("app/src/main/kotlin")
        val refreshedRoot = workspaceRoot.resolve("generated/src/main/kotlin")

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(initialRoot),
            classpathRoots = emptyList(),
            moduleName = "main",
        )
        session.use { standaloneSession ->
            WorkspaceRefreshWatcher(standaloneSession).use { watcher ->
                watcher.refreshSourceRoots(listOf(initialRoot, refreshedRoot))

                assertTrue(
                    watchedDirectories(watcher).any { directory ->
                        directory.startsWith(refreshedRoot.toAbsolutePath().normalize())
                    },
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun watchedDirectories(watcher: WorkspaceRefreshWatcher): Set<Path> {
        val field = WorkspaceRefreshWatcher::class.java.getDeclaredField("watchKeysByDirectory")
        field.isAccessible = true
        val watchKeys = field.get(watcher) as ConcurrentHashMap<Path, WatchKey>
        return watchKeys.keys.toSet()
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeText(content)
        return path
    }
}
