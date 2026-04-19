package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.RefreshResult
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `watcher uses content-only refresh for ENTRY_MODIFY`() {
        val changedFile = writeFile(
            relativePath = "app/src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun app(): String = "ready"
            """.trimIndent() + "\n",
        )
        val contentRefreshes = mutableListOf<Set<String>>()
        var fullRefreshCount = 0
        val sourceRoot = workspaceRoot.resolve("app/src/main/kotlin")

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(sourceRoot),
            classpathRoots = emptyList(),
            moduleName = "main",
        )
        session.use { standaloneSession ->
            WorkspaceRefreshWatcher(
                session = standaloneSession,
                contentRefresh = { paths ->
                    contentRefreshes += paths
                    RefreshResult(
                        refreshedFiles = paths.toList().sorted(),
                        removedFiles = emptyList(),
                        fullRefresh = false,
                    )
                },
                fullRefresh = {
                    fullRefreshCount += 1
                    RefreshResult(
                        refreshedFiles = emptyList(),
                        removedFiles = emptyList(),
                        fullRefresh = true,
                    )
                },
            ).use { watcher ->
                flushPendingChanges(
                    watcher = watcher,
                    changedPaths = setOf(changedFile.toString()),
                    forceFullRefresh = false,
                )

                assertEquals(listOf(setOf(changedFile.toString())), contentRefreshes)
                assertEquals(0, fullRefreshCount)
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

    private fun flushPendingChanges(
        watcher: WorkspaceRefreshWatcher,
        changedPaths: Set<String>,
        forceFullRefresh: Boolean,
    ) {
        val method = WorkspaceRefreshWatcher::class.java.getDeclaredMethod(
            "flushPendingChanges",
            Set::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(watcher, changedPaths, forceFullRefresh)
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
