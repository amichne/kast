package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ModuleName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import io.github.amichne.kast.standalone.cache.SourceIndexCache
import io.github.amichne.kast.standalone.cache.kastCacheDirectory

class SourceIndexCacheTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `source index cache lives under gradle cache directory`() {
        assertEquals(
            normalizeStandalonePath(workspaceRoot.resolve(".gradle/kast/cache")),
            kastCacheDirectory(normalizeStandalonePath(workspaceRoot)),
        )
    }

    @Test
    fun `source index cache round-trips correctly`() {
        val appFile = writeSourceFile(
            relativePath = "sample/App.kt",
            content = """
                package sample

                fun welcome(): String = helper()
            """.trimIndent() + "\n",
        )
        val helperFile = writeSourceFile(
            relativePath = "sample/Helper.kt",
            content = """
                package sample

                fun helper(): String = "hi"
            """.trimIndent() + "\n",
        )
        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))

        cache.save(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                mapOf(
                    "welcome" to listOf(normalizeStandalonePath(appFile).toString()),
                    "helper" to listOf(
                        normalizeStandalonePath(appFile).toString(),
                        normalizeStandalonePath(helperFile).toString(),
                    ),
                ),
            ),
            sourceRoots = sourceRoots(),
        )

        val loaded = requireNotNull(cache.load(sourceRoots()))
        assertEquals(listOf(normalizeStandalonePath(appFile).toString()), loaded.index.candidatePathsFor("welcome"))
        assertEquals(
            listOf(
                normalizeStandalonePath(appFile).toString(),
                normalizeStandalonePath(helperFile).toString(),
            ),
            loaded.index.candidatePathsFor("helper"),
        )
        assertTrue(loaded.newPaths.isEmpty())
        assertTrue(loaded.modifiedPaths.isEmpty())
        assertTrue(loaded.deletedPaths.isEmpty())
    }

    @Test
    fun `source index cache round-trips import-aware metadata`() {
        val callerFile = writeSourceFile(
            relativePath = "consumer/Caller.kt",
            content = """
                package consumer

                import lib.Foo

                fun use() = Foo()
            """.trimIndent() + "\n",
        )
        val bystanderFile = writeSourceFile(
            relativePath = "other/Bystander.kt",
            content = """
                package other

                fun Foo() = "shadow"
            """.trimIndent() + "\n",
        )
        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))
        val index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(emptyMap())
        index.updateFile(
            normalizeStandalonePath(callerFile).toString(),
            Files.readString(callerFile),
            moduleName = ModuleName(":consumer[main]"),
        )
        index.updateFile(
            normalizeStandalonePath(bystanderFile).toString(),
            Files.readString(bystanderFile),
            moduleName = ModuleName(":other[main]"),
        )

        cache.save(
            index = index,
            sourceRoots = sourceRoots(),
        )

        val loaded = requireNotNull(cache.load(sourceRoots()))
        assertEquals(
            listOf(normalizeStandalonePath(callerFile).toString()),
            loaded.index.candidatePathsForFqName(
                identifier = "Foo",
                targetPackage = "lib",
                targetFqName = "lib.Foo",
            ),
        )
        assertEquals(
            listOf(normalizeStandalonePath(callerFile).toString()),
            loaded.index.candidatePathsForModule(
                identifier = "Foo",
                allowedModuleNames = setOf(ModuleName(":consumer[main]")),
            ),
        )
    }

    @Test
    fun `source index cache detects modified files`() {
        val file = writeSourceFile(
            relativePath = "sample/App.kt",
            content = """
                package sample

                fun welcome(): String = "hi"
            """.trimIndent() + "\n",
        )
        val cache = saveSimpleCache(file)

        file.writeText(
            """
                package sample

                fun welcome(): String = "hello"
            """.trimIndent() + "\n",
        )
        bumpLastModified(file)

        val loaded = requireNotNull(cache.load(sourceRoots()))
        assertEquals(listOf(normalizeStandalonePath(file).toString()), loaded.modifiedPaths)
    }

    @Test
    fun `source index cache detects new files`() {
        val file = writeSourceFile(
            relativePath = "sample/App.kt",
            content = """
                package sample

                fun welcome(): String = "hi"
            """.trimIndent() + "\n",
        )
        val cache = saveSimpleCache(file)
        val newFile = writeSourceFile(
            relativePath = "sample/NewFile.kt",
            content = """
                package sample

                fun salute(): String = "hello"
            """.trimIndent() + "\n",
        )
        bumpLastModified(newFile)

        val loaded = requireNotNull(cache.load(sourceRoots()))
        assertEquals(listOf(normalizeStandalonePath(newFile).toString()), loaded.newPaths)
    }

    @Test
    fun `source index cache detects deleted files`() {
        val file = writeSourceFile(
            relativePath = "sample/App.kt",
            content = """
                package sample

                fun welcome(): String = "hi"
            """.trimIndent() + "\n",
        )
        val cache = saveSimpleCache(file)

        Files.delete(file)

        val loaded = requireNotNull(cache.load(sourceRoots()))
        assertEquals(listOf(normalizeStandalonePath(file).toString()), loaded.deletedPaths)
    }

    @Test
    fun `incremental index startup only reads changed files`() {
        repeat(10) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    fun value$index(): Int = $index
                """.trimIndent() + "\n",
            )
        }

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
        ).use { session ->
            session.awaitInitialSourceIndex()
        }

        val changedFile = workspaceRoot.resolve("src/main/kotlin/sample/File4.kt")
        changedFile.writeText(
            """
                package sample

                fun renamedValue4(): Int = 4
            """.trimIndent() + "\n",
        )
        bumpLastModified(changedFile)

        var readCount = 0
        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
            sourceIndexFileReader = { path ->
                readCount += 1
                Files.readString(path)
            },
        ).use { session ->
            session.awaitInitialSourceIndex()
        }

        assertEquals(1, readCount)
    }

    @Test
    fun `source index cache is updated after refresh`() {
        val file = writeSourceFile(
            relativePath = "sample/App.kt",
            content = """
                package sample

                fun welcome(): String = "hi"
            """.trimIndent() + "\n",
        )
        val normalizedFile = normalizeStandalonePath(file).toString()
        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
            sourceIndexCacheSaveDelayMillis = 25,
        ).use { session ->
            session.awaitInitialSourceIndex()

            file.writeText(
                """
                    package sample

                    fun salute(): String = welcome()
                """.trimIndent() + "\n",
            )
            bumpLastModified(file)

            session.refreshFileContents(setOf(file.toString()))

            waitUntil {
                val loaded = cache.load(sourceRoots()) ?: return@waitUntil false
                loaded.newPaths.isEmpty() &&
                    loaded.modifiedPaths.isEmpty() &&
                    loaded.deletedPaths.isEmpty() &&
                    loaded.index.candidatePathsFor("salute") == listOf(normalizedFile)
            }
        }
    }

    private fun saveSimpleCache(file: Path): SourceIndexCache {
        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))
        cache.save(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                mapOf("welcome" to listOf(normalizeStandalonePath(file).toString())),
            ),
            sourceRoots = sourceRoots(),
        )
        return cache
    }

    private fun sourceRoots(): List<Path> = listOf(normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin")))

    private fun writeSourceFile(
        relativePath: String,
        content: String,
    ): Path {
        val file = workspaceRoot.resolve("src/main/kotlin").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        return file
    }

    private fun bumpLastModified(file: Path) {
        Files.setLastModifiedTime(
            file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1_000),
        )
    }

    private fun waitUntil(
        timeoutMillis: Long = 5_000,
        pollMillis: Long = 25,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(pollMillis)
        }
        error("Condition was not met within ${timeoutMillis}ms")
    }
}
