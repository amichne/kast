package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend
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
internal class IdeaEditApplicationTestBoundaries : IdeaEditApplicationTestFixture() {
    @Test
    fun `secure text edit preserves existing source permissions`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("PermissionPreserved.kt")
        val original = "package demo\n\nfun permissionPreserved(): Int = 1\n"
        val permissions = PosixFilePermissions.fromString("rw-------")
        Files.writeString(target, original)
        Files.setPosixFilePermissions(target, permissions)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)

        IdeaEditApplier(project, workspaceRoot).apply(
            ApplyEditsQuery(
                edits = listOf(
                    TextEdit(
                        filePath = target.toString(),
                        startOffset = original.indexOf('1'),
                        endOffset = original.indexOf('1') + 1,
                        newText = "2",
                    ),
                ),
                fileHashes = listOf(FileHash(target.toString(), io.github.amichne.kast.api.validation.FileHashing.sha256(original))),
                fileOperations = emptyList(),
            ),
        )

        assertEquals(permissions, Files.getPosixFilePermissions(target))
    }

    @Test
    fun `applyEdits deletes files inside active workspace and verifies disk state`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val deleteFile = workspaceRoot.resolve("DeleteInside.kt")
        val content = "package demo\n\nfun deleteInside(): Int = 1\n"
        Files.writeString(deleteFile, content)

        val result = backend(workspaceRoot).applyEdits(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.DeleteFile(
                        filePath = deleteFile.toString(),
                        expectedHash = io.github.amichne.kast.api.validation.FileHashing.sha256(content),
                    ),
                ),
            ),
        )

        assertEquals(listOf(deleteFile.toString()), result.deletedFiles)
        assertTrue(Files.notExists(deleteFile), "Inside workspace delete target should be absent after apply")
    }

    @Test
    fun `committed deletion reports deleted file and retained recovery evidence`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("RetainedCleanupDelete.kt")
        val original = "package demo\n\nfun retainedCleanupDelete(): Int = 1\n"
        Files.writeString(target, original)
        var cleanupCalls = 0

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                secureWorkspaceMutation = SecureWorkspaceMutation(
                    workspaceRoot = workspaceRoot,
                    beforeCleanupUnlink = {
                        cleanupCalls += 1
                        if (cleanupCalls == 2) {
                            error("forced retained delete cleanup evidence")
                        }
                    },
                ),
            ).apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.DeleteFile(
                            filePath = target.toString(),
                            expectedHash = io.github.amichne.kast.api.validation.FileHashing.sha256(original),
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            failure is PartialApplyException,
            "Expected PartialApplyException, got ${failure?.let { it::class.qualifiedName } ?: "success"}",
        )
        val partial = failure as PartialApplyException
        assertEquals(target.toString(), partial.details["appliedFiles"], partial.details.toString())
        assertEquals(target.toString(), partial.details["deletedFiles"], partial.details.toString())
        assertEquals(original, Files.readString(Path.of(partial.details.getValue("recoveryFilePaths"))))
        assertFalse(Files.exists(target), "The deletion must remain committed")
    }

    @Test
    fun `applyEdits rejects text edits outside active IDEA workspace`() = runBlocking {
        ensureProjectReady()

        val outsideFile = Files.createTempDirectory("kast-outside-text-edit").resolve("Outside.kt")
        val originalText = "package outside\n\nfun value(): Int = 1\n"
        Files.writeString(outsideFile, originalText)

        val exception = expectValidationFailure(
            ApplyEditsQuery(
                edits = listOf(
                    TextEdit(
                        filePath = outsideFile.toString(),
                        startOffset = originalText.indexOf("1"),
                        endOffset = originalText.indexOf("1") + 1,
                        newText = "2",
                    ),
                ),
                fileHashes = listOf(
                    FileHash(
                        filePath = outsideFile.toString(),
                        hash = io.github.amichne.kast.api.validation.FileHashing.sha256(originalText),
                    ),
                ),
                fileOperations = emptyList(),
            ),
        )

        assertEquals("text_edit", exception.details["mutation"])
        assertEquals(originalText, Files.readString(outsideFile))
    }

    @Test
    fun `applyEdits rejects create file operations outside active IDEA workspace`() = runBlocking {
        ensureProjectReady()

        val outsideFile = Files.createTempDirectory("kast-outside-create").resolve("Created.kt")

        val exception = expectValidationFailure(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = outsideFile.toString(),
                        content = "class Created\n",
                    ),
                ),
            ),
        )

        assertEquals("create_file", exception.details["mutation"])
        assertTrue(Files.notExists(outsideFile), "Outside workspace create target should remain absent")
    }

    @Test
    fun `applyEdits rejects delete file operations outside active IDEA workspace`() = runBlocking {
        ensureProjectReady()

        val outsideFile = Files.createTempDirectory("kast-outside-delete").resolve("DeleteMe.kt")
        val originalText = "class DeleteMe\n"
        Files.writeString(outsideFile, originalText)

        val exception = expectValidationFailure(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.DeleteFile(
                        filePath = outsideFile.toString(),
                        expectedHash = io.github.amichne.kast.api.validation.FileHashing.sha256(originalText),
                    ),
                ),
            ),
        )

        assertEquals("delete_file", exception.details["mutation"])
        assertEquals(originalText, Files.readString(outsideFile))
    }
}
