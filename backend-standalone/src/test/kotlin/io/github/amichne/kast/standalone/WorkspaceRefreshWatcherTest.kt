package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.result.RefreshResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Validates java.nio.file.WatchService integration for filesystem change monitoring.
 * Requires real filesystem: java.nio.file.WatchService does not support Jimfs or other in-memory filesystems.
 */
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

    @Test
    fun `watcher continues registering accessible directories when some are inaccessible`() {
        // Create a simple valid source root first
        val initialRoot = writeFile(
            relativePath = "initial/src/main/kotlin/sample/Init.kt",
            content = """
                package sample

                fun init(): String = "ok"
            """.trimIndent() + "\n",
        ).parent.parent.parent

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(initialRoot),
            classpathRoots = emptyList(),
            moduleName = "main",
        )

        session.use { standaloneSession ->
            WorkspaceRefreshWatcher(standaloneSession).use { watcher ->
                // Now create additional directories, including one with problems
                val goodDir = writeFile(
                    relativePath = "good/src/main/kotlin/sample/Good.kt",
                    content = """
                        package sample

                        fun good(): String = "ok"
                    """.trimIndent() + "\n",
                ).parent.parent.parent

                // Create a directory with a broken symlink inside it
                val problematicRoot = workspaceRoot.resolve("problematic/src/main/kotlin")
                problematicRoot.createDirectories()
                val brokenSymlink = problematicRoot.resolve("broken")
                java.nio.file.Files.createSymbolicLink(brokenSymlink, workspaceRoot.resolve("nonexistent"))

                // Manually refresh with new source roots including the problematic one
                // This directly tests the error handling in refreshSourceRoots
                callRefreshSourceRoots(watcher, listOf(initialRoot, goodDir, problematicRoot))

                val watchedDirs = watchedDirectories(watcher)

                // Verify that accessible directories were successfully registered
                assertTrue(
                    watchedDirs.any { it.startsWith(goodDir.toAbsolutePath().normalize()) },
                    "Accessible directory should be registered despite sibling with broken symlink"
                )

                // Verify that the problematic root itself is registered
                assertTrue(
                    watchedDirs.contains(problematicRoot.toAbsolutePath().normalize()),
                    "Problematic root should be registered even if it contains broken symlink"
                )
            }
        }
    }

    private fun callRefreshSourceRoots(watcher: WorkspaceRefreshWatcher, sourceRoots: List<Path>) {
        val method = WorkspaceRefreshWatcher::class.java.getDeclaredMethod(
            "refreshSourceRoots",
            List::class.java,
        )
        method.isAccessible = true
        method.invoke(watcher, sourceRoots)
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
