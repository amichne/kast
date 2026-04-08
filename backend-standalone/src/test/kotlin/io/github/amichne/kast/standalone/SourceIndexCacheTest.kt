package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ModuleName
import io.github.amichne.kast.api.NormalizedPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
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

    @Test
    fun `source index cache creates SQLite database not legacy JSON`() {
        val appFile = writeSourceFile(
            relativePath = "sample/App.kt",
            content = "package sample\n\nfun welcome(): String = \"hi\"\n",
        )
        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))
        cache.save(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                mapOf("welcome" to listOf(normalizeStandalonePath(appFile).toString())),
            ),
            sourceRoots = sourceRoots(),
        )

        val cacheDir = kastCacheDirectory(normalizeStandalonePath(workspaceRoot))
        assertTrue(Files.isRegularFile(cacheDir.resolve("source-index.db")))
        assertFalse(Files.exists(cacheDir.resolve("source-identifier-index.json")))
        assertFalse(Files.exists(cacheDir.resolve("file-manifest.json")))
    }

    @Test
    fun `saveFileIndex incrementally updates only the target file`() {
        val appFile = writeSourceFile(
            relativePath = "sample/App.kt",
            content = "package sample\n\nfun welcome(): String = \"hi\"\n",
        )
        val helperFile = writeSourceFile(
            relativePath = "sample/Helper.kt",
            content = "package sample\n\nfun helper(): String = \"ok\"\n",
        )
        val normalizedApp = normalizeStandalonePath(appFile).toString()
        val normalizedHelper = normalizeStandalonePath(helperFile).toString()

        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))
        val index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
            mapOf(
                "welcome" to listOf(normalizedApp),
                "helper" to listOf(normalizedHelper),
            ),
        )
        cache.save(index = index, sourceRoots = sourceRoots())

        // Simulate an in-place edit of App.kt — the identifier changes
        index.updateFile(normalizedApp, "package sample\n\nfun renamed(): String = \"hi\"\n")
        cache.saveFileIndex(index, NormalizedPath.ofNormalized(normalizedApp))

        val loaded = requireNotNull(cache.load(sourceRoots()))
        assertEquals(listOf(normalizedApp), loaded.index.candidatePathsFor("renamed"))
        assertTrue(loaded.index.candidatePathsFor("welcome").isEmpty())
        // Helper must be untouched
        assertEquals(listOf(normalizedHelper), loaded.index.candidatePathsFor("helper"))
    }

    @Test
    fun `source index cache migrates from legacy JSON to SQLite on first load`() {
        val appFile = writeSourceFile(
            relativePath = "sample/App.kt",
            content = "package sample\n\nfun welcome(): String = \"hi\"\n",
        )
        val normalizedFile = normalizeStandalonePath(appFile).toString()
        val lastModified = Files.getLastModifiedTime(appFile).toMillis()

        val cacheDir = kastCacheDirectory(normalizeStandalonePath(workspaceRoot))
        Files.createDirectories(cacheDir)
        // Write legacy JSON files in the old format
        Files.writeString(
            cacheDir.resolve("source-identifier-index.json"),
            """{"schemaVersion":3,"candidatePathsByIdentifier":{"welcome":["$normalizedFile"]}}""",
        )
        Files.writeString(
            cacheDir.resolve("file-manifest.json"),
            """{"schemaVersion":1,"fileLastModifiedMillisByPath":{"$normalizedFile":$lastModified}}""",
        )

        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))
        val result = requireNotNull(cache.load(sourceRoots()))

        assertEquals(listOf(normalizedFile), result.index.candidatePathsFor("welcome"))
        // SQLite DB must have been created
        assertTrue(Files.isRegularFile(cacheDir.resolve("source-index.db")))
        // Old JSON files must have been deleted
        assertFalse(Files.exists(cacheDir.resolve("source-identifier-index.json")))
        assertFalse(Files.exists(cacheDir.resolve("file-manifest.json")))
    }

    @Test
    fun `source index cache returns null on SQLite schema version mismatch`() {
        val cacheDir = kastCacheDirectory(normalizeStandalonePath(workspaceRoot))
        Files.createDirectories(cacheDir)
        val dbPath = cacheDir.resolve("source-index.db")
        // Manually write a DB with an unknown schema version
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
                stmt.execute("INSERT INTO schema_version (version) VALUES (999)")
            }
        }

        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))
        assertNull(cache.load(sourceRoots()))
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
