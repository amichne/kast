package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.client.workspaceCacheDirectory
import io.github.amichne.kast.indexstore.kastCacheDirectory
import io.github.amichne.kast.standalone.cache.GitDeltaCandidateDetector
import io.github.amichne.kast.standalone.cache.GitDeltaCandidates
import io.github.amichne.kast.standalone.cache.SourceIndexCache
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

class SourceIndexCacheTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `source index cache lives under workspace cache directory`() {
        assertEquals(
            workspaceCacheDirectory(normalizeStandalonePath(workspaceRoot)),
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
    fun `git delta cache load stats only candidate and new files`() {
        val appFile = writeSourceFile(
            relativePath = "sample/App.kt",
            content = "package sample\n\nfun welcome(): String = \"hi\"\n",
        )
        val helperFile = writeSourceFile(
            relativePath = "sample/Helper.kt",
            content = "package sample\n\nfun helper(): String = \"ok\"\n",
        )
        val normalizedRoot = normalizeStandalonePath(workspaceRoot)
        val normalizedApp = normalizeStandalonePath(appFile).toString()
        val normalizedHelper = normalizeStandalonePath(helperFile).toString()
        val detector = FakeGitDeltaCandidateDetector(headCommit = "head-1")
        detector.trackedPaths = setOf(normalizedApp, normalizedHelper)
        val cache = SourceIndexCache(normalizedRoot, gitDeltaChangeDetector = detector)
        cache.save(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                mapOf("welcome" to listOf(normalizedApp), "helper" to listOf(normalizedHelper)),
            ),
            sourceRoots = sourceRoots(),
        )

        appFile.writeText("package sample\n\nfun welcome(): String = \"hello\"\n")
        bumpLastModified(appFile)
        val newFile = writeSourceFile(
            relativePath = "sample/NewFile.kt",
            content = "package sample\n\nfun newcomer(): String = \"new\"\n",
        )
        bumpLastModified(newFile)
        val stattedPaths = mutableListOf<String>()
        detector.candidates = setOf(normalizedApp)
        val loadingCache = SourceIndexCache(
            workspaceRoot = normalizedRoot,
            gitDeltaChangeDetector = detector,
            lastModifiedMillis = { path ->
                stattedPaths += normalizeStandalonePath(path).toString()
                Files.getLastModifiedTime(path).toMillis()
            },
        )

        val loaded = requireNotNull(loadingCache.load(sourceRoots()))

        assertEquals(listOf(normalizedApp), loaded.modifiedPaths)
        assertEquals(listOf(normalizeStandalonePath(newFile).toString()), loaded.newPaths)
        assertEquals(
            setOf(normalizedApp, normalizeStandalonePath(newFile).toString()),
            stattedPaths.toSet(),
        )
        assertFalse(stattedPaths.contains(normalizedHelper))
    }

    @Test
    fun `source index cache save records current git head`() {
        val file = writeSourceFile(
            relativePath = "sample/App.kt",
            content = "package sample\n\nfun welcome(): String = \"hi\"\n",
        )
        val normalizedFile = normalizeStandalonePath(file).toString()
        val cache = SourceIndexCache(
            workspaceRoot = normalizeStandalonePath(workspaceRoot),
            gitDeltaChangeDetector = FakeGitDeltaCandidateDetector(headCommit = "head-2"),
        )

        cache.save(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                mapOf("welcome" to listOf(normalizedFile)),
            ),
            sourceRoots = sourceRoots(),
        )

        assertEquals("head-2", cache.store.readHeadCommit())
    }

    @Test
    fun `partial file saves do not advance workspace git head baseline`() {
        val file = writeSourceFile(
            relativePath = "sample/App.kt",
            content = "package sample\n\nfun welcome(): String = \"hi\"\n",
        )
        val normalizedFile = normalizeStandalonePath(file).toString()
        val detector = FakeGitDeltaCandidateDetector(headCommit = "head-1")
        val cache = SourceIndexCache(
            workspaceRoot = normalizeStandalonePath(workspaceRoot),
            gitDeltaChangeDetector = detector,
        )
        val index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
            mapOf("welcome" to listOf(normalizedFile)),
        )
        cache.save(index = index, sourceRoots = sourceRoots())

        detector.headCommit = "head-2"
        index.updateFile(normalizedFile, "package sample\n\nfun renamed(): String = \"hi\"\n")
        cache.saveFileIndex(index, NormalizedPath.ofNormalized(normalizedFile))

        assertEquals("head-1", cache.store.readHeadCommit())
    }

    @Test
    fun `git delta cache load stats cached untracked files`() {
        val trackedFile = writeSourceFile(
            relativePath = "sample/Tracked.kt",
            content = "package sample\n\nfun tracked(): String = \"hi\"\n",
        )
        val untrackedFile = writeSourceFile(
            relativePath = "sample/Generated.kt",
            content = "package sample\n\nfun generated(): String = \"old\"\n",
        )
        val normalizedRoot = normalizeStandalonePath(workspaceRoot)
        val normalizedTracked = normalizeStandalonePath(trackedFile).toString()
        val normalizedUntracked = normalizeStandalonePath(untrackedFile).toString()
        val detector = FakeGitDeltaCandidateDetector(headCommit = "head-1")
        detector.trackedPaths = setOf(normalizedTracked)
        val cache = SourceIndexCache(normalizedRoot, gitDeltaChangeDetector = detector)
        cache.save(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                mapOf("tracked" to listOf(normalizedTracked), "generated" to listOf(normalizedUntracked)),
            ),
            sourceRoots = sourceRoots(),
        )

        untrackedFile.writeText("package sample\n\nfun generated(): String = \"new\"\n")
        bumpLastModified(untrackedFile)
        val stattedPaths = mutableListOf<String>()
        val loadingCache = SourceIndexCache(
            workspaceRoot = normalizedRoot,
            gitDeltaChangeDetector = detector,
            lastModifiedMillis = { path ->
                stattedPaths += normalizeStandalonePath(path).toString()
                Files.getLastModifiedTime(path).toMillis()
            },
        )

        val loaded = requireNotNull(loadingCache.load(sourceRoots()))

        assertEquals(listOf(normalizedUntracked), loaded.modifiedPaths)
        assertEquals(setOf(normalizedUntracked), stattedPaths.toSet())
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
    fun `source index cache returns null when only legacy JSON exists without SQLite DB`() {
        val appFile = writeSourceFile(
            relativePath = "sample/App.kt",
            content = "package sample\n\nfun welcome(): String = \"hi\"\n",
        )
        val cacheDir = kastCacheDirectory(normalizeStandalonePath(workspaceRoot))
        Files.createDirectories(cacheDir)
        Files.writeString(
            cacheDir.resolve("source-identifier-index.json"),
            """{"schemaVersion":3,"candidatePathsByIdentifier":{"welcome":["${normalizeStandalonePath(appFile)}"]}}""",
        )

        val cache = SourceIndexCache(normalizeStandalonePath(workspaceRoot))
        assertNull(cache.load(sourceRoots()))
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

    private class FakeGitDeltaCandidateDetector(
        var headCommit: String,
    ) : GitDeltaCandidateDetector {
        var candidates: Set<String>? = null
        var trackedPaths: Set<String> = emptySet()

        override fun detectCandidatePaths(
            storedHeadCommit: String?,
            sourceRoots: List<Path>,
        ): GitDeltaCandidates? =
            storedHeadCommit?.let {
                GitDeltaCandidates(
                    headCommit = headCommit,
                    paths = candidates.orEmpty(),
                    trackedPaths = trackedPaths,
                )
            }

        override fun currentHeadCommit(): String = headCommit
    }
}
