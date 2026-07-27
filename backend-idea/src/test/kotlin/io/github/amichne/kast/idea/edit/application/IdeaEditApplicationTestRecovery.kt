package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.edit.IdeaEditApplier
import io.github.amichne.kast.idea.mutation.*

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import io.github.amichne.kast.api.client.workspaceDataDirectory
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

@TestApplication
internal class IdeaEditApplicationTestRecovery : IdeaEditApplicationTestFixture() {
    @Test
    fun `committed text edit reports applied file and retained recovery evidence`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("RetainedCleanupEdit.kt")
        val original = "package demo\n\nfun retainedCleanup(): Int = 1\n"
        val replacement = "package demo\n\nfun retainedCleanup(): Int = 2\n"
        Files.writeString(target, original)
        val virtualFile = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target))
        var failNextCleanup = true

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                secureWorkspaceMutation = SecureWorkspaceMutation(
                    workspaceRoot = workspaceRoot,
                    beforeCleanupUnlink = {
                        if (failNextCleanup) {
                            failNextCleanup = false
                            error("forced retained cleanup evidence")
                        }
                    },
                ),
            ).apply(
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = target.toString(),
                            startOffset = original.indexOf('1'),
                            endOffset = original.indexOf('1') + 1,
                            newText = "2",
                        ),
                    ),
                    fileHashes = listOf(
                        FileHash(
                            target.toString(),
                            io.github.amichne.kast.api.validation.FileHashing.sha256(original),
                        ),
                    ),
                    fileOperations = emptyList(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            failure is PartialApplyException,
            "Expected PartialApplyException, got ${failure?.let { it::class.qualifiedName } ?: "success"}",
        )
        val partial = failure as PartialApplyException
        assertEquals(target.toString(), partial.details["appliedFiles"])
        val recoveryFile = Path.of(partial.details.getValue("recoveryFilePaths"))
        assertEquals(original, Files.readString(recoveryFile))
        assertEquals(replacement, Files.readString(target))
        val documentText = readAction {
            FileDocumentManager.getInstance().getDocument(virtualFile)!!.text
        }
        assertEquals(replacement, documentText)
    }

    @Test
    fun `post commit create verification failure reports created file as applied`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("PostCommitCreate.kt")
        val committed = "class PostCommitCreate\n"
        val raced = "class RacedPostCommitCreate\n"

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                afterFilesystemCommit = { committedTarget, mutation ->
                    if (committedTarget == target && mutation == IdeaWorkspaceMutation.CREATE_FILE) {
                        Files.writeString(target, raced)
                    }
                },
            ).apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(FileOperation.CreateFile(target.toString(), committed)),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is PartialApplyException)
        val partial = failure as PartialApplyException
        assertEquals(target.toString(), partial.details["appliedFiles"])
        assertEquals(target.toString(), partial.details["createdFiles"])
        assertEquals(raced, Files.readString(target))
    }

    @Test
    fun `post commit deletion failure reports deleted file as applied`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("PostCommitDelete.kt")
        val original = "class PostCommitDelete\n"
        Files.writeString(target, original)

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                afterFilesystemCommit = { committedTarget, mutation ->
                    if (committedTarget == target && mutation == IdeaWorkspaceMutation.DELETE_FILE) {
                        error("forced post-commit delete failure")
                    }
                },
            ).apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.DeleteFile(
                            target.toString(),
                            io.github.amichne.kast.api.validation.FileHashing.sha256(original),
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is PartialApplyException)
        val partial = failure as PartialApplyException
        assertEquals(target.toString(), partial.details["appliedFiles"])
        assertEquals(target.toString(), partial.details["deletedFiles"])
        assertFalse(Files.exists(target))
    }

    @Test
    fun `write action completion failure retains the committed create ledger`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("WriteActionCompletion.kt")
        val content = "class WriteActionCompletion\n"

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                runFileOperationWriteAction = { operation ->
                    operation()
                    error("forced write action completion failure")
                },
            ).apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(FileOperation.CreateFile(target.toString(), content)),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is PartialApplyException)
        val partial = failure as PartialApplyException
        assertEquals(target.toString(), partial.details["appliedFiles"])
        assertEquals(target.toString(), partial.details["createdFiles"])
        assertEquals(content, Files.readString(target))
    }

    @Test
    fun `post commit create verification refuses an escaping final symlink`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("PostCommitEscapingCreate.kt")
        val content = "class PostCommitEscapingCreate\n"
        val outsideTarget = Files.createTempFile("kast-post-commit-escaping-create", ".kt")
        Files.writeString(outsideTarget, content)

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                afterFilesystemCommit = { committedTarget, mutation ->
                    if (committedTarget == target && mutation == IdeaWorkspaceMutation.CREATE_FILE) {
                        Files.delete(target)
                        Files.createSymbolicLink(target, outsideTarget)
                    }
                },
            ).apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(FileOperation.CreateFile(target.toString(), content)),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is PartialApplyException)
        val partial = failure as PartialApplyException
        assertEquals(target.toString(), partial.details["appliedFiles"])
        assertEquals(target.toString(), partial.details["createdFiles"])
        assertEquals(content, Files.readString(outsideTarget))
    }

    @Test
    fun `post commit text failure reports replacement as applied`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("PostCommitText.kt")
        val original = "package demo\n\nfun postCommitText(): Int = 1\n"
        val replacement = "package demo\n\nfun postCommitText(): Int = 2\n"
        Files.writeString(target, original)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                afterFilesystemCommit = { committedTarget, mutation ->
                    if (committedTarget == target && mutation == IdeaWorkspaceMutation.TEXT_EDIT) {
                        error("forced post-commit document failure")
                    }
                },
            ).apply(
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = target.toString(),
                            startOffset = original.indexOf('1'),
                            endOffset = original.indexOf('1') + 1,
                            newText = "2",
                        ),
                    ),
                    fileHashes = listOf(
                        FileHash(
                            target.toString(),
                            io.github.amichne.kast.api.validation.FileHashing.sha256(original),
                        ),
                    ),
                    fileOperations = emptyList(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is PartialApplyException)
        val partial = failure as PartialApplyException
        assertEquals(target.toString(), partial.details["appliedFiles"])
        assertEquals(replacement, Files.readString(target))
    }

    @Test
    fun `hash conflict after file operation preserves committed operation evidence`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val created = workspaceRoot.resolve("CreatedBeforeHashConflict.kt")
        val createdContent = "class CreatedBeforeHashConflict\n"
        val editTarget = readAction { testFile.virtualFile.path }
        val editText = readAction { testFile.text }

        val failure = runCatching {
            backend(workspaceRoot).applyEdits(
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = editTarget,
                            startOffset = editText.indexOf("oldName"),
                            endOffset = editText.indexOf("oldName") + "oldName".length,
                            newText = "newName",
                        ),
                    ),
                    fileHashes = listOf(FileHash(editTarget, io.github.amichne.kast.api.validation.FileHashing.sha256("stale"))),
                    fileOperations = listOf(FileOperation.CreateFile(created.toString(), createdContent)),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is PartialApplyException)
        val partial = failure as PartialApplyException
        assertEquals(created.toString(), partial.details["appliedFiles"])
        assertEquals(created.toString(), partial.details["createdFiles"])
        assertEquals(createdContent, Files.readString(created))
    }

}
