package io.github.amichne.kast.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.EditPlanValidator
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ValidatedFileEdits
import io.github.amichne.kast.api.validation.ValidatedFileOperation
import java.nio.charset.StandardCharsets

/**
 * Applies edits using IntelliJ's VFS, Document, and WriteCommandAction APIs.
 *
 * Preserves IntelliJ's undo/redo, PSI synchronization, and VFS notification semantics.
 * All mutations happen through proper IntelliJ APIs with write actions.
 */
internal class IntelliJEditApplier(private val project: Project) {
    /**
     * Applies text edits and file operations through IntelliJ APIs.
     *
     * Workflow:
     * 1. Validate operations against current VFS state
     * 2. Apply file operations (create/delete) through VFS
     * 3. Apply text edits through Document API with WriteCommandAction
     * 4. Commit and save documents
     *
     * @param query The edit query with edits, hashes, and file operations
     * @return Result with applied edits and affected files
     */
    suspend fun apply(query: ApplyEditsQuery): ApplyEditsResult {
        if (query.edits.isEmpty() && query.fileOperations.isEmpty()) {
            throw ValidationException("At least one text edit or file operation is required")
        }

        val fileDocumentManager = FileDocumentManager.getInstance()
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val vfsManager = VirtualFileManager.getInstance()

        // Validate and apply file operations first
        val validatedFileOperations = EditPlanValidator.validateFileOperations(query.fileOperations)
        val (affectedFiles, createdFiles, deletedFiles) = applyFileOperations(
            validatedFileOperations,
            vfsManager,
        )

        // Validate text edits
        val validatedEdits = if (query.edits.isEmpty()) {
            emptyList()
        } else {
            EditPlanValidator.validate(query.edits, query.fileHashes)
        }

        // Check hashes against current IntelliJ state
        validatedEdits.forEach { plan ->
            val virtualFile = vfsManager.findFileByUrl("file://${plan.filePath}")
                ?: throw NotFoundException(
                    message = "The requested file does not exist",
                    details = mapOf("filePath" to plan.filePath),
                )

            val currentContent = readAction {
                val document = fileDocumentManager.getCachedDocument(virtualFile)
                if (document != null) {
                    document.text
                } else {
                    String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                }
            }

            val currentHash = FileHashing.sha256(currentContent)
            if (currentHash != plan.expectedHash) {
                throw ConflictException(
                    message = "The file changed after the edit plan was created",
                    details = mapOf(
                        "filePath" to plan.filePath,
                        "expectedHash" to plan.expectedHash,
                        "actualHash" to currentHash,
                    ),
                )
            }
        }

        // Apply text edits through Document API
        val appliedEdits = mutableListOf<TextEdit>()
        val editAffectedFiles = mutableListOf<String>()

        validatedEdits.forEach { plan ->
            try {
                applyTextEdits(plan, vfsManager, fileDocumentManager, psiDocumentManager)
                editAffectedFiles += plan.filePath
                appliedEdits += plan.edits.sortedBy { it.startOffset }
            } catch (exception: Exception) {
                throw PartialApplyException(
                    details = mapOf(
                        "failedFile" to plan.filePath,
                        "appliedFiles" to (affectedFiles + editAffectedFiles).joinToString(","),
                        "createdFiles" to createdFiles.joinToString(","),
                        "deletedFiles" to deletedFiles.joinToString(","),
                        "reason" to (exception.message ?: exception::class.java.simpleName),
                        "exceptionClass" to (exception::class.qualifiedName ?: "Unknown"),
                        "stackTrace" to exception.stackTraceToString().take(500),
                    ),
                )
            }
        }

        return ApplyEditsResult(
            applied = appliedEdits,
            affectedFiles = (affectedFiles + editAffectedFiles).distinct().sorted(),
            createdFiles = createdFiles.sorted(),
            deletedFiles = deletedFiles.sorted(),
        )
    }

    private suspend fun applyFileOperations(
        operations: List<ValidatedFileOperation>,
        vfsManager: VirtualFileManager,
    ): Triple<MutableList<String>, MutableList<String>, MutableList<String>> {
        val affectedFiles = mutableListOf<String>()
        val createdFiles = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()

        operations.forEach { operation ->
            try {
                when (operation) {
                    is ValidatedFileOperation.CreateFile -> {
                        writeAction {
                            val parentPath = operation.filePath.substringBeforeLast('/')
                            val fileName = operation.filePath.substringAfterLast('/')
                            val parentFile = vfsManager.findFileByUrl("file://$parentPath")
                                ?: throw IllegalStateException("Parent directory not found: $parentPath")

                            if (parentFile.findChild(fileName) != null) {
                                throw ConflictException(
                                    message = "The requested file already exists",
                                    details = mapOf("filePath" to operation.filePath),
                                )
                            }

                            val newFile = parentFile.createChildData(this, fileName)
                            newFile.setBinaryContent(operation.content.toByteArray(StandardCharsets.UTF_8))
                        }
                        createdFiles += operation.filePath
                    }

                    is ValidatedFileOperation.DeleteFile -> {
                        val virtualFile = vfsManager.findFileByUrl("file://${operation.filePath}")
                            ?: throw NotFoundException(
                                message = "The requested file does not exist",
                                details = mapOf("filePath" to operation.filePath),
                            )

                        val currentContent = readAction {
                            String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                        }
                        val currentHash = FileHashing.sha256(currentContent)

                        if (currentHash != operation.expectedHash) {
                            throw ConflictException(
                                message = "The file changed after the delete plan was created",
                                details = mapOf(
                                    "filePath" to operation.filePath,
                                    "expectedHash" to operation.expectedHash,
                                    "actualHash" to currentHash,
                                ),
                            )
                        }

                        writeAction {
                            virtualFile.delete(this)
                        }
                        deletedFiles += operation.filePath
                    }
                }
                affectedFiles += operation.filePath
            } catch (exception: Exception) {
                throw PartialApplyException(
                    details = mapOf(
                        "failedFile" to operation.filePath,
                        "appliedFiles" to affectedFiles.joinToString(","),
                        "createdFiles" to createdFiles.joinToString(","),
                        "deletedFiles" to deletedFiles.joinToString(","),
                        "reason" to (exception.message ?: exception::class.java.simpleName),
                    ),
                )
            }
        }

        return Triple(affectedFiles, createdFiles, deletedFiles)
    }

    private suspend fun applyTextEdits(
        plan: ValidatedFileEdits,
        vfsManager: VirtualFileManager,
        fileDocumentManager: FileDocumentManager,
        psiDocumentManager: PsiDocumentManager,
    ) {
        val virtualFile = readAction {
            vfsManager.findFileByUrl("file://${plan.filePath}")
        } ?: throw NotFoundException(
            message = "The requested file does not exist",
            details = mapOf("filePath" to plan.filePath),
        )

        // Get Document in read action
        val document = readAction {
            fileDocumentManager.getDocument(virtualFile)
        } ?: throw IllegalStateException("Cannot get Document for file: ${plan.filePath}")

        // Apply edits in WriteCommandAction (required for Document modifications)
        WriteCommandAction.runWriteCommandAction(project) {
            // Validated edits are already sorted descending by start offset, so offsets remain stable as replacements are applied.
            plan.edits.forEach { edit ->
                document.replaceString(edit.startOffset, edit.endOffset, edit.newText)
            }

            // Commit to PSI - catch exceptions for test compatibility
            // In production, project is always open; in tests, fixture may not be fully initialized
            try {
                psiDocumentManager.commitDocument(document)
            } catch (e: IllegalArgumentException) {
                // Ignore "unopened project" errors in test scenarios
                if (e.message?.contains("unopened project") != true) {
                    throw e
                }
            }

            // Save to VFS
            fileDocumentManager.saveDocument(document)
        }
    }
}
