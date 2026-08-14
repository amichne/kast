package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@TestApplication
internal class KastDiagnosticsCompletenessTest : KastDiagnosticsCompletenessFixture() {

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
        assertTrue(result.fileHashes.isEmpty())
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
        assertEquals(brokenFile.toString(), result.fileHashes.single().filePath)
        assertEquals(FileHashing.sha256(brokenFileFixture.get().text), result.fileHashes.single().hash)
    }

    @Test
    fun `saved diagnostic hash preserves raw BOM and CRLF bytes`() = runBlocking {
        ensureProjectReady()
        val filePath = sourceRoot.resolve("RawBytes.kt")
        val sourceBytes = "\uFEFFpackage diagnostics\r\n\r\nfun rawBytes(): Int = 42\r\n".toByteArray()
        Files.write(filePath, sourceBytes)
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath))
        waitUntilIndexesAreReady(project)

        val result = backend().diagnostics(
            DiagnosticsQuery(filePaths = listOf(filePath.toString())),
        )

        assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
        assertEquals(FileHashing.sha256(sourceBytes), result.fileHashes.single().hash)
    }

    @Test
    fun `diagnostics reject cached PSI that is behind saved disk content`() = runBlocking {
        ensureProjectReady()
        val psiFile = validFileFixture.get()
        val filePath = Path.of(psiFile.virtualFile.path)
        readAction { psiFile.text }
        Files.writeString(filePath, "package diagnostics\n\nfun valid(): String = Missing.value\n")

        val result = backend().diagnostics(
            DiagnosticsQuery(filePaths = listOf(filePath.toString())),
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.PENDING_INDEX, result.fileStatuses.single().state)
        assertTrue(result.fileHashes.isEmpty())
    }

    @Test
    fun `diagnostic hash reflects unsaved committed PSI text from the analysis epoch`() = runBlocking {
        ensureProjectReady()
        val psiFile = validFileFixture.get()
        val ktFile = psiFile as KtFile
        val filePath = Path.of(psiFile.virtualFile.path)
        val diskText = Files.readString(filePath)
        val document = readAction {
            requireNotNull(FileDocumentManager.getInstance().getDocument(psiFile.virtualFile))
        }

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                ktFile.declarations.single().replace(
                    KtPsiFactory(project).createFunction("fun valid(): String = \"unsaved\""),
                )
            }
        }
        val unsavedPsiText = readAction { ktFile.text }

        val result = backend().diagnostics(
            DiagnosticsQuery(filePaths = listOf(filePath.toString())),
        )

        assertEquals(diskText, Files.readString(filePath), "the document must remain unsaved")
        assertNotEquals(diskText, unsavedPsiText)
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertEquals(FileHashing.sha256(unsavedPsiText), result.fileHashes.single().hash)
        assertEquals(filePath.toString(), result.fileHashes.single().filePath)
    }

    @Test
    fun `diagnostic continuation preserves snapshot hashes across pages`() = runBlocking {
        ensureProjectReady()
        val backend = backend()
        val brokenFile = Path.of(brokenFileFixture.get().virtualFile.path).toString()
        val query = DiagnosticsQuery(
            filePaths = listOf(brokenFile, brokenFile),
            maxResults = 1,
        )

        val firstPage = backend.diagnostics(query)
        val secondPage = backend.diagnostics(
            query.copy(pageToken = requireNotNull(firstPage.page?.nextPageToken)),
        )

        assertEquals(firstPage.fileHashes, secondPage.fileHashes)
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
    fun `indexing blocks compiler diagnostics until the current host is smart`() {
        ensureProjectReady()
        val validFile = Path.of(validFileFixture.get().virtualFile.path)

        val (runtime, failure) = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
            runBlocking {
                val backend = backend()
                backend.runtimeStatus() to runCatching {
                    backend.diagnostics(
                        DiagnosticsQuery(filePaths = listOf(validFile.toString())),
                    )
                }.exceptionOrNull()
            }
        }

        assertEquals(RuntimeState.INDEXING, runtime.state)
        assertFalse(runtime.readiness.runtime is RuntimeReadinessLane.Blocked)
        assertTrue(failure is ConflictException)
        assertTrue(failure?.message.orEmpty().contains("compiler"))
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
    fun `indexing blocks compiler diagnostics before source classification`() {
        ensureProjectReady()
        val outsideSourceFile = workspaceRoot.resolve("OutsideSourceDuringIndexing.kt")
        Files.writeString(outsideSourceFile, validSource)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outsideSourceFile)

        try {
            val failure = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
                runBlocking {
                    runCatching {
                        backend().diagnostics(
                            DiagnosticsQuery(filePaths = listOf(outsideSourceFile.toString())),
                        )
                    }.exceptionOrNull()
                }
            }

            assertTrue(failure is ConflictException)
            assertTrue(failure?.message.orEmpty().contains("compiler"))
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
