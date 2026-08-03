package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.ExactFileImageBase64
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.query.ExactFileImageQuery
import io.github.amichne.kast.api.contract.result.ExactFileImageStatus
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.mutation.ExactFileImageCasObserver
import io.github.amichne.kast.idea.mutation.SecureWorkspaceMutation
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class ExactFileImageCasTest : KastIndexerBackendContractTestFixture() {
    @Test
    fun `exact image CAS commits and refreshes exact bytes`() = runBlocking {
        ensureProjectReady()
        val (filePath, workspaceRoot) = readAction {
            Path.of(sampleFile.virtualFile.path) to commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
        }
        val before = Files.readAllBytes(filePath)
        val after = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "package demo\r\n\r\nfun exact(): String = \"😀\"\r\n".toByteArray()
        val result = backend(workspaceRoot).exactFileImageCas(query(filePath, before, after))

        assertEquals(ExactFileImageStatus.COMMITTED, result.status)
        assertEquals(FileHashing.sha256(before), result.previousSha256.value)
        assertEquals(FileHashing.sha256(after), result.resultSha256.value)
        assertArrayEquals(after, Files.readAllBytes(filePath))
        assertArrayEquals(after, readAction { sampleFile.virtualFile.contentsToByteArray() })
    }

    @Test
    fun `exact image CAS rejects a stale byte hash without changing the file`() = runBlocking {
        ensureProjectReady()
        val (filePath, workspaceRoot) = readAction {
            Path.of(sampleFile.virtualFile.path) to commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
        }
        val before = Files.readAllBytes(filePath)
        val query = query(filePath, before, "changed".toByteArray()).copy(
            expectedCurrentSha256 = ExactFileImageSha256("0".repeat(64)),
        )

        assertThrows(ConflictException::class.java) {
            runBlocking { backend(workspaceRoot).exactFileImageCas(query) }
        }

        assertArrayEquals(before, Files.readAllBytes(filePath))
    }

    @Test
    fun `exact image CAS rechecks an unsaved document inside its write critical section`() = runBlocking {
        ensureProjectReady()
        val (filePath, workspaceRoot) = readAction {
            Path.of(sampleFile.virtualFile.path) to commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
        }
        val before = Files.readAllBytes(filePath)
        val documentManager = FileDocumentManager.getInstance()
        val document = readAction { requireNotNull(documentManager.getDocument(sampleFile.virtualFile)) }
        val marker = "\n// unsaved race"
        val observer = object : ExactFileImageCasObserver {
            override fun beforeWriteCriticalSection(target: Path) {
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.insertString(document.textLength, marker)
                    }
                }
            }
        }

        assertThrows(ConflictException::class.java) {
            runBlocking {
                backendWithObserver(workspaceRoot, observer).exactFileImageCas(
                    query(filePath, before, "changed".toByteArray()),
                )
            }
        }

        assertArrayEquals(before, Files.readAllBytes(filePath))
        assertTrue(documentManager.isDocumentUnsaved(document))
        assertTrue(document.text.endsWith(marker))
    }

    @Test
    fun `exact image CAS preserves IDEA cancellation after commit without reloading the document`() = runBlocking {
        val cancellation = ProcessCanceledException()

        assertPostCommitCancellationIsPreserved(cancellation)
    }

    @Test
    fun `exact image CAS preserves task cancellation after commit without reloading the document`() = runBlocking {
        val cancellation = CancellationException("cancelled after commit")

        assertPostCommitCancellationIsPreserved(cancellation)
    }

    @Test
    fun `exact image CAS preserves IDEA cancellation raised during secure cleanup`() = runBlocking {
        assertSecureCleanupCancellationIsPreserved(ProcessCanceledException())
    }

    @Test
    fun `exact image CAS preserves task cancellation raised during secure cleanup`() = runBlocking {
        assertSecureCleanupCancellationIsPreserved(CancellationException("cancelled during secure cleanup"))
    }

    @Test
    fun `exact image CAS refuses a symlink target without writing outside the workspace`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, hierarchyFile.virtualFile.path)
        }
        val outside = Files.createTempFile("kast-exact-image-outside", ".kt")
        val outsideBytes = "outside".toByteArray()
        Files.write(outside, outsideBytes)
        val target = workspaceRoot.resolve("ExactImageLink.kt")
        Files.createSymbolicLink(target, outside)

        assertThrows(UnsafeWorkspaceMutationException::class.java) {
            runBlocking {
                backend(workspaceRoot).exactFileImageCas(
                    query(target, outsideBytes, "inside".toByteArray()),
                )
            }
        }

        assertArrayEquals(outsideBytes, Files.readAllBytes(outside))
        org.junit.jupiter.api.Assertions.assertTrue(Files.isSymbolicLink(target))
    }

    @Test
    fun `exact image CAS refuses a path outside the exact workspace`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, hierarchyFile.virtualFile.path)
        }
        val outside = Files.createTempFile("kast-exact-image-outside-path", ".kt")
        val before = "outside".toByteArray()
        Files.write(outside, before)

        assertThrows(UnsafeWorkspaceMutationException::class.java) {
            runBlocking {
                backend(workspaceRoot).exactFileImageCas(
                    query(outside, before, "changed".toByteArray()),
                )
            }
        }

        assertArrayEquals(before, Files.readAllBytes(outside))
    }

    @Test
    fun `exact image CAS fails unsafe with retained recovery evidence after an exact commit`() = runBlocking {
        ensureProjectReady()
        val (filePath, workspaceRoot) = readAction {
            Path.of(sampleFile.virtualFile.path) to commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
        }
        val before = Files.readAllBytes(filePath)
        val after = "package demo\r\nfun recovered(): Unit = Unit\r\n".toByteArray()
        var failCleanup = true
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = workspaceRoot,
            beforeCleanupUnlink = {
                if (failCleanup) {
                    failCleanup = false
                    error("forced retained recovery evidence")
                }
            },
        )
        val backend = KastIndexerBackend(
            project = project,
            workspaceRoot = workspaceRoot,
            limits = ServerLimits(
                maxResults = 100,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 2,
            ),
            exactFileImageMutation = mutation,
        )

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            runBlocking { backend.exactFileImageCas(query(filePath, before, after)) }
        }

        assertEquals("true", failure.details["committed"])
        assertEquals("1", failure.details["recoveryFilePathCount"])
        assertFalse(failure.details.containsKey("recoveryFilePaths"))
        val recoveryPath = Path.of(failure.details.getValue("recoveryFilePath.0"))
        assertArrayEquals(before, Files.readAllBytes(recoveryPath))
        assertArrayEquals(after, Files.readAllBytes(filePath))
    }

    private fun query(filePath: Path, before: ByteArray, after: ByteArray): ExactFileImageQuery =
        ExactFileImageQuery(
            filePath = ExactFileImagePath(filePath.toString()),
            expectedCurrentSha256 = ExactFileImageSha256(FileHashing.sha256(before)),
            contentBase64 = ExactFileImageBase64(Base64.getEncoder().encodeToString(after)),
            expectedResultSha256 = ExactFileImageSha256(FileHashing.sha256(after)),
        )

    private suspend fun assertPostCommitCancellationIsPreserved(cancellation: RuntimeException) {
        ensureProjectReady()
        val (filePath, workspaceRoot) = readAction {
            Path.of(sampleFile.virtualFile.path) to commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
        }
        val before = Files.readAllBytes(filePath)
        val after = "package demo\nfun committedBeforeCancellation(): Unit = Unit\n".toByteArray()
        val document = readAction {
            requireNotNull(FileDocumentManager.getInstance().getDocument(sampleFile.virtualFile))
        }
        val documentBefore = document.text
        val observer = object : ExactFileImageCasObserver {
            override fun afterSecureCommit(target: Path) {
                throw cancellation
            }
        }

        val thrown = assertThrows(cancellation::class.java) {
            runBlocking {
                backendWithObserver(workspaceRoot, observer).exactFileImageCas(
                    query(filePath, before, after),
                )
            }
        }

        if (cancellation is ProcessCanceledException) {
            assertSame(cancellation, thrown)
        } else {
            assertEquals(cancellation::class, thrown::class)
            assertEquals(cancellation.message, thrown.message)
        }
        assertArrayEquals(after, Files.readAllBytes(filePath))
        assertEquals(documentBefore, document.text)
    }

    private suspend fun assertSecureCleanupCancellationIsPreserved(cancellation: RuntimeException) {
        ensureProjectReady()
        val (filePath, workspaceRoot) = readAction {
            Path.of(sampleFile.virtualFile.path) to commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
        }
        val before = Files.readAllBytes(filePath)
        val after = "package demo\nfun committedBeforeCleanupCancellation(): Unit = Unit\n".toByteArray()
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = workspaceRoot,
            beforeCleanupUnlink = { throw cancellation },
        )
        val backend = KastIndexerBackend(
            project = project,
            workspaceRoot = workspaceRoot,
            limits = ServerLimits(
                maxResults = 100,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 2,
            ),
            exactFileImageMutation = mutation,
        )

        val thrown = try {
            backend.exactFileImageCas(query(filePath, before, after))
            throw AssertionError("Expected secure cleanup cancellation")
        } catch (failure: RuntimeException) {
            failure
        }

        assertSame(cancellation, thrown)
        assertArrayEquals(after, Files.readAllBytes(filePath))
        assertEquals(1, thrown.suppressed.size)
        assertTrue(thrown.suppressed.all { it is UnsafeWorkspaceMutationException })
        val recovery = thrown.suppressed.single() as UnsafeWorkspaceMutationException
        assertEquals("true", recovery.details["committed"])
        assertEquals("1", recovery.details["recoveryFilePathCount"])
        val recoveryPath = Path.of(recovery.details.getValue("recoveryFilePath.0"))
        assertArrayEquals(before, Files.readAllBytes(recoveryPath))
    }

    private fun backendWithObserver(
        workspaceRoot: Path,
        observer: ExactFileImageCasObserver,
    ): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = ServerLimits(
            maxResults = 100,
            requestTimeoutMillis = 30_000,
            maxConcurrentRequests = 2,
        ),
        exactFileImageCasObserver = observer,
    )
}
