package io.github.amichne.kast.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.TextEdit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

@TestApplication
class IntelliJEditApplicationTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private val originalSource = """
            package demo

            fun oldName(x: Int): Int = x * 2
        """.trimIndent()
    }

    private val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val sourceRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture()
    private val testFileFixture: TestFixture<PsiFile> = sourceRootFixture.psiFileFixture("Test.kt", originalSource)

    private val project: Project
        get() = projectFixture.get()

    private val testFile: PsiFile
        get() = testFileFixture.get()

    private fun backend(): KastPluginBackend = KastPluginBackend(
        project = project,
        workspaceRoot = Path.of(project.basePath!!),
        limits = defaultLimits,
    )

    private fun ensureProjectReady() {
        moduleFixture.get()
        testFileFixture.get()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `currentHashes uses unsaved Document text instead of disk`() = runBlocking {
        ensureProjectReady()

        val filePath = readAction { testFile.virtualFile.path }
        val unsavedText = "package demo\n\nfun newName(x: Int): Int = x * 3"

        // Modify Document without saving to disk
        writeAction {
            val document = FileDocumentManager.getInstance().getDocument(testFile.virtualFile)!!
            document.setText(unsavedText)
            // Do NOT save - leave it unsaved
        }

        // Hash should reflect unsaved Document text, not disk text
        val hashes = IntelliJFileHashComputer.currentHashes(listOf(filePath))

        val unsavedHash = io.github.amichne.kast.api.validation.FileHashing.sha256(unsavedText)
        val diskHash = io.github.amichne.kast.api.validation.FileHashing.sha256(originalSource)

        // RED: This should FAIL if currentHashes reads from disk
        assertEquals(1, hashes.size)
        assertEquals(filePath, hashes[0].filePath)
        assertEquals(unsavedHash, hashes[0].hash, "Hash should reflect unsaved Document text")
        assertNotEquals(diskHash, hashes[0].hash, "Hash should NOT match stale disk text")
    }

    @Test
    fun `applyEdits updates IntelliJ Document immediately without disk write`() = runBlocking {
        ensureProjectReady()

        val filePath = readAction { testFile.virtualFile.path }
        val originalText = readAction { testFile.text }

        // Compute hash of original
        val originalHash = io.github.amichne.kast.api.validation.FileHashing.sha256(originalText)

        // Apply edit through backend
        val backend = backend()
        try {
            val result = backend.applyEdits(
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = filePath,
                            startOffset = originalText.indexOf("oldName"),
                            endOffset = originalText.indexOf("oldName") + "oldName".length,
                            newText = "newName",
                        ),
                    ),
                    fileHashes = listOf(FileHash(filePath, originalHash)),
                    fileOperations = emptyList(),
                ),
            )

            assertEquals(1, result.applied.size)
            assertEquals(listOf(filePath), result.affectedFiles)
        } catch (e: io.github.amichne.kast.api.protocol.PartialApplyException) {
            println("=== PartialApplyException DEBUG ===")
            println("Message: ${e.message}")
            println("Details: ${e.details}")
            e.details.forEach { (key, value) ->
                println("  $key = $value")
            }
            println("Stack trace:")
            e.printStackTrace()
            throw e
        } catch (e: Exception) {
            println("=== Unexpected exception ===")
            println("Type: ${e::class.qualifiedName}")
            println("Message: ${e.message}")
            e.printStackTrace()
            throw e
        }

        // RED: This should FAIL if applyEdits bypasses IntelliJ Document API
        // After applyEdits, Document should have new text immediately
        val documentText = readAction {
            FileDocumentManager.getInstance().getDocument(testFile.virtualFile)!!.text
        }

        assert(documentText.contains("newName")) { "Document should contain 'newName' after applyEdits" }
        assert(!documentText.contains("oldName")) { "Document should NOT contain 'oldName' after applyEdits" }
    }
}
