package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.protocol.ConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@TestApplication
class KastDiagnosticsCompletenessTest {
    companion object {
        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private const val validSource = """
            package diagnostics

            fun valid(): Int = 42
        """

        private const val brokenSource = """
            package diagnostics

            fun broken(): Int = "not an integer"
        """
    }

    private val projectFixture: TestFixture<Project> = projectFixture()
    private val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val sourceRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture()
    private val validFileFixture: TestFixture<PsiFile> =
        sourceRootFixture.psiFileFixture("Valid.kt", validSource)
    private val brokenFileFixture: TestFixture<PsiFile> =
        sourceRootFixture.psiFileFixture("Broken.kt", brokenSource)
    private val nonKotlinFileFixture: TestFixture<PsiFile> =
        sourceRootFixture.psiFileFixture("Notes.txt", "Semantic analysis requires Kotlin source.")

    private val project: Project
        get() = projectFixture.get()

    private val sourceRoot: Path
        get() = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()

    private val workspaceRoot: Path
        get() = sourceRoot.parent

    private fun backend(
        psiGeneration: () -> Long = { 1L },
        readEpochObserver: IdeaReadEpochObserver = IdeaReadEpochObserver.Disabled,
    ): KastPluginBackend = KastPluginBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        psiGeneration = psiGeneration,
        readEpochObserver = readEpochObserver,
    )

    private fun ensureProjectReady() {
        moduleFixture.get()
        validFileFixture.get()
        brokenFileFixture.get()
        nonKotlinFileFixture.get()
        waitUntilIndexesAreReady(project)
    }

    @Test
    fun `missing file is explicit incomplete evidence`() = runBlocking {
        ensureProjectReady()
        val missingFile = sourceRoot.resolve("Missing.kt")

        val result = backend().diagnostics(
            DiagnosticsQuery(filePaths = listOf(missingFile.toString())),
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.MISSING_ON_DISK, result.fileStatuses.single().state)
        assertEquals(0, result.analyzedFileCount)
        assertEquals(1, result.skippedFileCount)
        assertEquals("ANALYSIS_FAILURE", result.diagnostics.single().code)
    }

    @Test
    fun `missing file takes precedence over workspace classification`() = runBlocking {
        ensureProjectReady()
        val missingOutsideWorkspace = workspaceRoot.parent.resolve("MissingOutsideWorkspace.kt")
        Files.deleteIfExists(missingOutsideWorkspace)

        val result = backend().diagnostics(
            DiagnosticsQuery(filePaths = listOf(missingOutsideWorkspace.toString())),
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.MISSING_ON_DISK, result.fileStatuses.single().state)
        assertEquals("ANALYSIS_FAILURE", result.diagnostics.single().code)
    }

    @Test
    fun `ordinary compiler diagnostics retain complete semantic evidence`() = runBlocking {
        ensureProjectReady()
        val brokenFile = Path.of(brokenFileFixture.get().virtualFile.path)

        val result = backend().diagnostics(
            DiagnosticsQuery(filePaths = listOf(brokenFile.toString())),
        )

        assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.ANALYZED, result.fileStatuses.single().state)
        assertEquals(1, result.analyzedFileCount)
        assertEquals(0, result.skippedFileCount)
        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics.none { it.code == "ANALYSIS_FAILURE" })
    }

    @Test
    fun `diagnostic continuation rejects unknown mismatched and consumed tokens`() = runBlocking {
        ensureProjectReady()
        val backend = backend()
        val missingA = sourceRoot.resolve("MissingA.kt").toString()
        val missingB = sourceRoot.resolve("MissingB.kt").toString()
        val first = backend.diagnostics(
            DiagnosticsQuery(filePaths = listOf(missingA, missingB), maxResults = 1),
        )
        val token = requireNotNull(first.page?.nextPageToken)

        val mismatch = runCatching {
            backend.diagnostics(
                DiagnosticsQuery(
                    filePaths = listOf(missingB, missingA),
                    maxResults = 1,
                    pageToken = token,
                ),
            )
        }.exceptionOrNull()
        assertTrue(mismatch is ConflictException)

        val consumed = runCatching {
            backend.diagnostics(
                DiagnosticsQuery(
                    filePaths = listOf(missingA, missingB),
                    maxResults = 1,
                    pageToken = token,
                ),
            )
        }.exceptionOrNull()
        assertTrue(consumed is ConflictException)

        val unknown = runCatching {
            backend.diagnostics(
                DiagnosticsQuery(
                    filePaths = listOf(missingA, missingB),
                    maxResults = 1,
                    pageToken = "00000000-0000-0000-0000-000000000338",
                ),
            )
        }.exceptionOrNull()
        assertTrue(unknown is ConflictException)
    }

    @Test
    fun `diagnostic continuation rejects a changed PSI generation`() = runBlocking {
        ensureProjectReady()
        val generation = AtomicLong(1)
        val backend = backend(generation::get)
        val missingA = sourceRoot.resolve("MissingA.kt").toString()
        val missingB = sourceRoot.resolve("MissingB.kt").toString()
        val first = backend.diagnostics(
            DiagnosticsQuery(filePaths = listOf(missingA, missingB), maxResults = 1),
        )
        val token = requireNotNull(first.page?.nextPageToken)
        generation.set(2)

        val failure = runCatching {
            backend.diagnostics(
                DiagnosticsQuery(
                    filePaths = listOf(missingA, missingB),
                    maxResults = 1,
                    pageToken = token,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ConflictException, "expected generation conflict, got $failure")
        assertTrue(failure?.message.orEmpty().contains("PSI changed"))
    }

    @Test
    fun `diagnostic snapshot and generation share one read epoch against a concurrent write`() = runBlocking {
        ensureProjectReady()
        val generation = AtomicLong(1)
        val enteredReadEpoch = CountDownLatch(1)
        val releaseReadEpoch = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)
        val observer = IdeaReadEpochObserver { kind ->
            if (kind == IdeaReadEpochKind.DIAGNOSTICS && blockedOnce.compareAndSet(false, true)) {
                enteredReadEpoch.countDown()
                assertTrue(releaseReadEpoch.await(10, TimeUnit.SECONDS))
            }
        }
        val backend = backend(
            psiGeneration = generation::get,
            readEpochObserver = observer,
        )
        val missingA = sourceRoot.resolve("ConcurrentMissingA.kt").toString()
        val missingB = sourceRoot.resolve("ConcurrentMissingB.kt").toString()
        val firstDeferred = async(Dispatchers.Default) {
            backend.diagnostics(DiagnosticsQuery(filePaths = listOf(missingA, missingB), maxResults = 1))
        }
        assertTrue(enteredReadEpoch.await(10, TimeUnit.SECONDS))

        val writeStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        val application = ApplicationManager.getApplication()
        application.invokeLater {
            writeStarted.countDown()
            application.runWriteAction {
                generation.set(2)
            }
            writeCompleted.countDown()
        }
        assertTrue(writeStarted.await(10, TimeUnit.SECONDS))
        assertTrue(!writeCompleted.await(100, TimeUnit.MILLISECONDS))

        releaseReadEpoch.countDown()
        val first = firstDeferred.await()
        assertTrue(writeCompleted.await(10, TimeUnit.SECONDS))

        val failure = runCatching {
            backend.diagnostics(
                DiagnosticsQuery(
                    filePaths = listOf(missingA, missingB),
                    maxResults = 1,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is ConflictException)
        assertTrue(failure?.message.orEmpty().contains("PSI changed"))
    }

    @Test
    fun `indexing produces pending semantic evidence independently of runtime health`() {
        ensureProjectReady()
        val validFile = Path.of(validFileFixture.get().virtualFile.path)

        val (runtime, diagnostics) = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
            runBlocking {
                backend().runtimeStatus() to backend().diagnostics(
                    DiagnosticsQuery(filePaths = listOf(validFile.toString())),
                )
            }
        }

        assertEquals(RuntimeState.INDEXING, runtime.state)
        assertTrue(runtime.healthy)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, diagnostics.semanticOutcome)
        assertEquals(FileAnalysisState.PENDING_INDEX, diagnostics.fileStatuses.single().state)
        assertEquals(0, diagnostics.analyzedFileCount)
        assertEquals(1, diagnostics.skippedFileCount)
        assertEquals("ANALYSIS_FAILURE", diagnostics.diagnostics.single().code)
    }

    @Test
    fun `Kotlin file outside source modules is explicit incomplete evidence`() = runBlocking {
        ensureProjectReady()
        val outsideSourceFile = workspaceRoot.resolve("OutsideSource.kt")
        Files.writeString(outsideSourceFile, validSource)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outsideSourceFile)

        try {
            val result = backend().diagnostics(
                DiagnosticsQuery(filePaths = listOf(outsideSourceFile.toString())),
            )

            assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
            assertEquals(FileAnalysisState.OUTSIDE_SOURCE_MODULES, result.fileStatuses.single().state)
            assertEquals(0, result.analyzedFileCount)
            assertEquals(1, result.skippedFileCount)
            assertEquals("ANALYSIS_FAILURE", result.diagnostics.single().code)
        } finally {
            Files.deleteIfExists(outsideSourceFile)
        }
    }

    @Test
    fun `outside source modules takes precedence over indexing`() {
        ensureProjectReady()
        val outsideSourceFile = workspaceRoot.resolve("OutsideSourceDuringIndexing.kt")
        Files.writeString(outsideSourceFile, validSource)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outsideSourceFile)

        try {
            val result = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
                runBlocking {
                    backend().diagnostics(
                        DiagnosticsQuery(filePaths = listOf(outsideSourceFile.toString())),
                    )
                }
            }

            assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
            assertEquals(FileAnalysisState.OUTSIDE_SOURCE_MODULES, result.fileStatuses.single().state)
            assertEquals("ANALYSIS_FAILURE", result.diagnostics.single().code)
        } finally {
            Files.deleteIfExists(outsideSourceFile)
        }
    }

    @Test
    fun `non Kotlin source file is explicit backend failure evidence`() = runBlocking {
        ensureProjectReady()
        val nonKotlinFile = Path.of(nonKotlinFileFixture.get().virtualFile.path)

        val result = backend().diagnostics(
            DiagnosticsQuery(filePaths = listOf(nonKotlinFile.toString())),
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.BACKEND_FAILURE, result.fileStatuses.single().state)
        assertEquals(0, result.analyzedFileCount)
        assertEquals(1, result.skippedFileCount)
        assertEquals("ANALYSIS_FAILURE", result.diagnostics.single().code)
    }
}
