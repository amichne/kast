package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.edit.IdeaEditApplier
import io.github.amichne.kast.idea.mutation.*

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsShowConfirmationOption
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
import io.github.amichne.kast.api.validation.EditPlanValidator
import io.github.amichne.kast.idea.edit.withVcsFileOperationConfirmationsSuppressed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
internal class IdeaEditApplicationTest : IdeaEditApplicationTestFixture() {
    @Test
    fun `VCS confirmation suppression is serialized per project`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val operations = EditPlanValidator.validateFileOperations(
            listOf(FileOperation.CreateFile(workspaceRoot.resolve("Serialized.kt").toString(), "class Serialized\n")),
        )
        val applier = IdeaEditApplier(project, workspaceRoot)
        val option = ProjectLevelVcsManager.getInstance(project)
            .getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, null)
        val originalValue = option.value
        option.value = VcsShowConfirmationOption.Value.SHOW_CONFIRMATION

        try {
            coroutineScope {
                val firstEntered = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()
                val secondEntered = CompletableDeferred<Unit>()
                val releaseSecond = CompletableDeferred<Unit>()

                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    applier.withVcsFileOperationConfirmationsSuppressed(operations) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
                firstEntered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    applier.withVcsFileOperationConfirmationsSuppressed(operations) {
                        secondEntered.complete(Unit)
                        releaseSecond.await()
                    }
                }

                try {
                    assertFalse(secondEntered.isCompleted, "A concurrent override must wait for the project lock")
                    releaseFirst.complete(Unit)
                    first.await()
                    secondEntered.await()
                    assertEquals(VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY, option.value)
                } finally {
                    releaseFirst.complete(Unit)
                    releaseSecond.complete(Unit)
                    first.await()
                    second.await()
                }
                assertEquals(VcsShowConfirmationOption.Value.SHOW_CONFIRMATION, option.value)
            }
        } finally {
            option.value = originalValue
        }
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
        val hashes = IdeaFileHashComputer.currentHashes(listOf(filePath))

        val unsavedHash = io.github.amichne.kast.api.validation.FileHashing.sha256(unsavedText)
        val diskHash = io.github.amichne.kast.api.validation.FileHashing.sha256(originalSourceText)

        assertEquals(1, hashes.size)
        assertEquals(filePath, hashes[0].filePath)
        assertEquals(unsavedHash, hashes[0].hash, "Hash should reflect unsaved Document text")
        assertNotEquals(diskHash, hashes[0].hash, "Hash should NOT match stale disk text")
    }

    @Test
    fun `applyEdits updates IDEA Document and secure disk state`() = runBlocking {
        ensureProjectReady()

        val filePath = readAction { testFile.virtualFile.path }
        val originalText = readAction { testFile.text }

        // Compute hash of original
        val originalHash = io.github.amichne.kast.api.validation.FileHashing.sha256(originalText)

        // Apply edit through backend
        val backend = backend()
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

        val documentText = readAction {
            FileDocumentManager.getInstance().getDocument(testFile.virtualFile)!!.text
        }

        assert(documentText.contains("newName")) { "Document should contain 'newName' after applyEdits" }
        assert(!documentText.contains("oldName")) { "Document should NOT contain 'oldName' after applyEdits" }
    }

    @Test
    fun `applyEdits creates files inside active workspace and verifies disk state`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val newFile = workspaceRoot.resolve("CreatedInside.kt")
        val content = "package demo\n\nfun createdInside(): Int = 1\n"

        val result = backend(workspaceRoot).applyEdits(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(FileOperation.CreateFile(newFile.toString(), content)),
            ),
        )

        assertEquals(listOf(newFile.toString()), result.createdFiles)
        assertEquals(content, Files.readString(newFile))
    }

    @Test
    fun `applyEdits creates missing parent directories for new files inside active workspace`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val newFile = workspaceRoot.resolve("nested/source/CreatedInside.kt")
        val content = "package demo.nested\n\nfun createdInsideNested(): Int = 1\n"

        val result = backend(workspaceRoot).applyEdits(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(FileOperation.CreateFile(newFile.toString(), content)),
            ),
        )

        assertEquals(listOf(newFile.toString()), result.createdFiles)
        assertTrue(Files.isDirectory(newFile.parent), "Create file should materialize missing parent directories")
        assertEquals(content, Files.readString(newFile))
    }

    @Test
    fun `add file create fails closed when validated ancestor becomes escaping symlink at write boundary`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val guardedParent = Files.createDirectory(workspaceRoot.resolve("guarded-create"))
        val displacedParent = workspaceRoot.resolve("guarded-create-displaced")
        val outsideParent = Files.createTempDirectory("kast-escaping-create")
        val target = guardedParent.resolve("Created.kt")

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                beforeSecureMutation = { filePath, mutation ->
                    if (filePath == target && mutation == IdeaWorkspaceMutation.CREATE_FILE) {
                        Files.move(guardedParent, displacedParent)
                        Files.createSymbolicLink(guardedParent, outsideParent)
                    }
                },
            ).apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(FileOperation.CreateFile(target.toString(), "class Created\n")),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            failure is UnsafeWorkspaceMutationException,
            "Expected UnsafeWorkspaceMutationException, got ${failure?.let { it::class.qualifiedName } ?: "success"}",
        )
        val unsafeFailure = failure as UnsafeWorkspaceMutationException
        assertEquals("UNSAFE_WORKSPACE_MUTATION", unsafeFailure.errorCode)
        assertEquals("openat-directory", unsafeFailure.details["nativeOperation"])
        assertFalse(Files.exists(outsideParent.resolve(target.fileName)), "Escaping target must remain untouched")
        assertFalse(Files.exists(displacedParent.resolve(target.fileName)), "Displaced in-workspace directory must remain untouched")
    }

    @Test
    fun `file scoped mutation fails closed when validated ancestor becomes escaping symlink at write boundary`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val guardedParent = Files.createDirectory(workspaceRoot.resolve("guarded-edit"))
        val displacedParent = workspaceRoot.resolve("guarded-edit-displaced")
        val outsideParent = Files.createTempDirectory("kast-escaping-edit")
        val target = guardedParent.resolve("Scoped.kt")
        val original = "package demo\n\nfun value(): Int = 1\n"
        val outsideOriginal = "package outside\n\nfun value(): Int = 9\n"
        Files.writeString(target, original)
        Files.writeString(outsideParent.resolve(target.fileName), outsideOriginal)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                beforeSecureMutation = { filePath, mutation ->
                    if (filePath == target && mutation == IdeaWorkspaceMutation.TEXT_EDIT) {
                        Files.move(guardedParent, displacedParent)
                        Files.createSymbolicLink(guardedParent, outsideParent)
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
                    fileHashes = listOf(FileHash(target.toString(), io.github.amichne.kast.api.validation.FileHashing.sha256(original))),
                    fileOperations = emptyList(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            failure is UnsafeWorkspaceMutationException,
            "Expected UnsafeWorkspaceMutationException, got ${failure?.let { it::class.qualifiedName } ?: "success"}",
        )
        val unsafeFailure = failure as UnsafeWorkspaceMutationException
        assertEquals("UNSAFE_WORKSPACE_MUTATION", unsafeFailure.errorCode)
        assertEquals("openat-directory", unsafeFailure.details["nativeOperation"])
        assertEquals(outsideOriginal, Files.readString(outsideParent.resolve(target.fileName)))
        assertEquals(original, Files.readString(displacedParent.resolve(target.fileName)))
    }

    @Test
    fun `file scoped mutation reports a typed conflict when a concurrent final entry blocks restoration`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("ConcurrentEdit.kt")
        val original = "package demo\n\nfun value(): Int = 1\n"
        val concurrent = "package demo\n\nfun concurrent(): Int = 9\n"
        Files.writeString(target, original)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                secureWorkspaceMutation = SecureWorkspaceMutation(
                    workspaceRoot = workspaceRoot,
                    afterTargetDetached = { detachedTarget, mutation ->
                        if (detachedTarget == target && mutation == IdeaWorkspaceMutation.TEXT_EDIT) {
                            Files.writeString(target, concurrent)
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
            failure is ConflictException,
            "Expected ConflictException, got ${failure?.let { it::class.qualifiedName } ?: "success"}",
        )
        val conflict = failure as ConflictException
        assertEquals("quarantined", conflict.details["restoration"])
        assertEquals(concurrent, Files.readString(target))
        assertEquals(original, Files.readString(Path.of(conflict.details.getValue("recoveryFilePath"))))
    }

}
