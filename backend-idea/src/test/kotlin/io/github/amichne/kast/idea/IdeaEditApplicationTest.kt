package io.github.amichne.kast.idea

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
class IdeaEditApplicationTest {
    companion object {
        private val projectPathFixture: TestFixture<Path> = testFixture {
            val path = tempPathFixture().init()
            val configDirectory = workspaceDataDirectory(path)
            Files.createDirectories(configDirectory)
            Files.writeString(
                configDirectory.resolve("config.toml"),
                """
                    [backends.idea]
                    enabled = false
                """.trimIndent(),
            )
            initialized(path) {}
        }

        private val projectFixture: TestFixture<Project> = projectFixture(
            pathFixture = projectPathFixture,
            openAfterCreation = true,
        )

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

    private fun backend(
        workspaceRoot: Path = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize(),
    ): KastPluginBackend = KastPluginBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
    )

    private fun ensureProjectReady() {
        moduleFixture.get()
        testFileFixture.get()
        waitUntilIndexesAreReady(project)
    }

    private suspend fun expectValidationFailure(query: ApplyEditsQuery): ValidationException {
        val failure = runCatching {
            backend().applyEdits(query)
        }.exceptionOrNull()
        assertTrue(
            failure is ValidationException,
            "Expected ValidationException, got ${failure?.let { it::class.qualifiedName } ?: "success"}",
        )
        return failure as ValidationException
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
        val diskHash = io.github.amichne.kast.api.validation.FileHashing.sha256(originalSource)

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
