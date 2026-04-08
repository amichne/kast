package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.ServerLimits
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import io.github.amichne.kast.standalone.cache.CacheManager
import io.github.amichne.kast.standalone.cache.SourceIndexCache
import io.github.amichne.kast.standalone.cache.WorkspaceDiscoveryCache
import io.github.amichne.kast.standalone.cache.kastCacheDirectory
import io.github.amichne.kast.standalone.cache.writeCacheFileAtomically
import io.github.amichne.kast.standalone.workspace.GradleModuleModel
import io.github.amichne.kast.standalone.workspace.GradleWorkspaceDiscoveryResult

class CacheManagerTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `cache manager debounces writes`() {
        val cacheManager = CacheManager(workspaceRoot)
        val cacheFile = kastCacheDirectory(normalizeStandalonePath(workspaceRoot)).resolve("debounce.txt")
        val writeCount = AtomicInteger(0)

        repeat(10) {
            cacheManager.schedule(key = "debounce", delayMillis = 25) {
                val nextCount = writeCount.incrementAndGet()
                writeCacheFileAtomically(cacheFile, nextCount.toString())
            }
        }

        waitUntil { writeCount.get() == 1 && Files.isRegularFile(cacheFile) }
        assertEquals("1", Files.readString(cacheFile))
        cacheManager.close()
    }

    @Test
    fun `cache manager writes atomically`() {
        val cacheManager = CacheManager(workspaceRoot)
        val cacheFile = kastCacheDirectory(normalizeStandalonePath(workspaceRoot)).resolve("atomic.txt")
        val oldPayload = "old"
        val newPayload = "new".repeat(8_192)
        val observedPayloads = linkedSetOf<String>()
        writeCacheFileAtomically(cacheFile, oldPayload)

        val writer = thread(start = true) {
            cacheManager.runNow {
                writeCacheFileAtomically(cacheFile, newPayload)
            }
        }

        while (writer.isAlive) {
            observedPayloads += Files.readString(cacheFile)
        }
        writer.join()
        observedPayloads += Files.readString(cacheFile)

        assertTrue(observedPayloads.all { payload -> payload == oldPayload || payload == newPayload })
        cacheManager.close()
    }

    @Test
    fun `KAST_CACHE_DISABLED prevents cache reads and writes`() {
        createGradleWorkspace()
        val disabledCacheManager = CacheManager(
            workspaceRoot = workspaceRoot,
            envReader = { name -> if (name == "KAST_CACHE_DISABLED") "true" else null },
        )
        assertFalse(disabledCacheManager.isEnabled())

        val sourceFile = writeSourceFile("sample/App.kt", "package sample\n\nfun welcome(): String = \"hi\"\n")
        val sourceIndexCache = SourceIndexCache(
            workspaceRoot = normalizeStandalonePath(workspaceRoot),
            enabled = disabledCacheManager.isEnabled(),
        )
        sourceIndexCache.save(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                mapOf("welcome" to listOf(normalizeStandalonePath(sourceFile).toString())),
            ),
            sourceRoots = sourceRoots(),
        )
        WorkspaceDiscoveryCache(enabled = disabledCacheManager.isEnabled()).write(
            workspaceRoot = workspaceRoot,
            result = workspaceDiscoveryResult(),
        )

        assertFalse(Files.exists(kastCacheDirectory(normalizeStandalonePath(workspaceRoot))))
        assertNull(sourceIndexCache.load(sourceRoots()))
        assertNull(WorkspaceDiscoveryCache(enabled = disabledCacheManager.isEnabled()).read(workspaceRoot))
        disabledCacheManager.close()
    }

    @Test
    fun `workspace refresh invalidates all caches`() = runBlocking {
        createGradleWorkspace()
        writeSourceFile("sample/App.kt", "package sample\n\nfun welcome(): String = \"hi\"\n")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
        )
        session.use { session ->
            session.awaitInitialSourceIndex()
            WorkspaceDiscoveryCache().write(workspaceRoot, workspaceDiscoveryResult())
            val cacheDirectory = kastCacheDirectory(normalizeStandalonePath(workspaceRoot))
            assertTrue(Files.isRegularFile(cacheDirectory.resolve("source-index.db")))
            assertTrue(Files.isRegularFile(cacheDirectory.resolve("gradle-workspace.json")))

            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            backend.refresh(RefreshQuery(filePaths = emptyList()))

            assertFalse(Files.exists(cacheDirectory.resolve("source-index.db")))
            assertFalse(Files.exists(cacheDirectory.resolve("gradle-workspace.json")))
            assertFalse(Files.exists(cacheDirectory.resolve("file-manifest.json")))
        }
    }

    private fun createGradleWorkspace() {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeFile(relativePath = "app/build.gradle.kts", content = "")
    }

    private fun workspaceDiscoveryResult(): GradleWorkspaceDiscoveryResult = GradleWorkspaceDiscoveryResult(
        modules = listOf(
            GradleModuleModel(
                gradlePath = ":app",
                ideaModuleName = ":app",
                mainSourceRoots = listOf(normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin"))),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = emptyList(),
            ),
        ),
    )

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

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val file = workspaceRoot.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        return file
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
