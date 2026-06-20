package io.github.amichne.kast.idea

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
import java.nio.file.Path

/**
 * Applies edits using IDEA's VFS, Document, and WriteCommandAction APIs.
 *
 * Preserves IDEA's undo/redo, PSI synchronization, and VFS notification semantics.
 * All mutations happen through proper IDEA APIs with write actions.
 */
internal class IdeaEditApplier(
    private val project: Project,
    private val workspaceRoot: Path,
) {
    /**
     * Applies text edits and file operations through IDEA APIs.
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
        val invocationId = KastStructuredTrace.newInvocationId()
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val normalizedWorkspaceRoot = workspaceIdentity.workspaceRootPath
        KastStructuredTrace.event(
            eventName = "idea.apply_edits.started",
            project = project,
            workspaceRoot = normalizedWorkspaceRoot,
            fields = KastStructuredTraceFields(
                invocationId = invocationId,
                agentRole = "idea-edit-applier",
            ),
            detail = mapOf(
                "textEditCount" to query.edits.size,
                "fileOperationCount" to query.fileOperations.size,
            ),
        )

        val fileDocumentManager = FileDocumentManager.getInstance()
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val vfsManager = VirtualFileManager.getInstance()

        val validatedFileOperations = EditPlanValidator.validateFileOperations(query.fileOperations)
        val validatedEdits = if (query.edits.isEmpty()) {
            emptyList()
        } else {
            EditPlanValidator.validate(query.edits, query.fileHashes)
        }
        validateWorkspaceTargets(
            workspaceIdentity = workspaceIdentity,
            fileOperations = validatedFileOperations,
            edits = validatedEdits,
            invocationId = invocationId,
        )

        // Validate and apply file operations first.
        val (affectedFiles, createdFiles, deletedFiles) = applyFileOperations(
            validatedFileOperations,
            vfsManager,
            invocationId,
            normalizedWorkspaceRoot,
        )

        // Check hashes against current IDEA state
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
                KastStructuredTrace.event(
                    eventName = "idea.apply_edits.hash_conflict",
                    project = project,
                    workspaceRoot = normalizedWorkspaceRoot,
                    fields = KastStructuredTraceFields(
                        invocationId = invocationId,
                        agentRole = "idea-edit-applier",
                        targetFilePath = plan.filePath,
                    ),
                    outcome = "failed",
                    detail = mapOf(
                        "expectedHash" to plan.expectedHash,
                        "actualHash" to currentHash,
                    ),
                )
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
                applyTextEdits(
                    plan,
                    vfsManager,
                    fileDocumentManager,
                    psiDocumentManager,
                    invocationId,
                    normalizedWorkspaceRoot,
                )
                editAffectedFiles += plan.filePath
                appliedEdits += plan.edits.sortedBy { it.startOffset }
            } catch (exception: Exception) {
                KastStructuredTrace.event(
                    eventName = "idea.apply_edits.text_edit_failed",
                    project = project,
                    workspaceRoot = normalizedWorkspaceRoot,
                    fields = KastStructuredTraceFields(
                        invocationId = invocationId,
                        agentRole = "idea-edit-applier",
                        targetFilePath = plan.filePath,
                    ),
                    outcome = "failed",
                    detail = mapOf(
                        "errorClass" to exception::class.qualifiedName,
                        "message" to exception.message,
                    ),
                )
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

        val result = ApplyEditsResult(
            applied = appliedEdits,
            affectedFiles = (affectedFiles + editAffectedFiles).distinct().sorted(),
            createdFiles = createdFiles.sorted(),
            deletedFiles = deletedFiles.sorted(),
        )
        KastStructuredTrace.event(
            eventName = "idea.apply_edits.completed",
            project = project,
            workspaceRoot = normalizedWorkspaceRoot,
            fields = KastStructuredTraceFields(
                invocationId = invocationId,
                agentRole = "idea-edit-applier",
            ),
            outcome = "completed",
            detail = mapOf(
                "affectedFiles" to result.affectedFiles,
                "createdFiles" to result.createdFiles,
                "deletedFiles" to result.deletedFiles,
            ),
        )
        return result
    }

    private suspend fun applyFileOperations(
        operations: List<ValidatedFileOperation>,
        vfsManager: VirtualFileManager,
        invocationId: String,
        workspaceRoot: Path,
    ): Triple<MutableList<String>, MutableList<String>, MutableList<String>> {
        val affectedFiles = mutableListOf<String>()
        val createdFiles = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()

        operations.forEach { operation ->
            try {
                when (operation) {
                    is ValidatedFileOperation.CreateFile -> {
                        KastStructuredTrace.event(
                            eventName = "idea.apply_edits.file_create_started",
                            project = project,
                            workspaceRoot = workspaceRoot,
                            fields = KastStructuredTraceFields(
                                invocationId = invocationId,
                                agentRole = "idea-edit-applier",
                                targetFilePath = operation.filePath,
                            ),
                        )
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
                        KastStructuredTrace.event(
                            eventName = "idea.apply_edits.file_create_completed",
                            project = project,
                            workspaceRoot = workspaceRoot,
                            fields = KastStructuredTraceFields(
                                invocationId = invocationId,
                                agentRole = "idea-edit-applier",
                                targetFilePath = operation.filePath,
                            ),
                            outcome = "completed",
                        )
                    }

                    is ValidatedFileOperation.DeleteFile -> {
                        KastStructuredTrace.event(
                            eventName = "idea.apply_edits.file_delete_started",
                            project = project,
                            workspaceRoot = workspaceRoot,
                            fields = KastStructuredTraceFields(
                                invocationId = invocationId,
                                agentRole = "idea-edit-applier",
                                targetFilePath = operation.filePath,
                            ),
                        )
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
                            KastStructuredTrace.event(
                                eventName = "idea.apply_edits.file_delete_hash_conflict",
                                project = project,
                                workspaceRoot = workspaceRoot,
                                fields = KastStructuredTraceFields(
                                    invocationId = invocationId,
                                    agentRole = "idea-edit-applier",
                                    targetFilePath = operation.filePath,
                                ),
                                outcome = "failed",
                                detail = mapOf(
                                    "expectedHash" to operation.expectedHash,
                                    "actualHash" to currentHash,
                                ),
                            )
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
                        KastStructuredTrace.event(
                            eventName = "idea.apply_edits.file_delete_completed",
                            project = project,
                            workspaceRoot = workspaceRoot,
                            fields = KastStructuredTraceFields(
                                invocationId = invocationId,
                                agentRole = "idea-edit-applier",
                                targetFilePath = operation.filePath,
                            ),
                            outcome = "completed",
                        )
                    }
                }
                affectedFiles += operation.filePath
            } catch (exception: Exception) {
                KastStructuredTrace.event(
                    eventName = "idea.apply_edits.file_operation_failed",
                    project = project,
                    workspaceRoot = workspaceRoot,
                    fields = KastStructuredTraceFields(
                        invocationId = invocationId,
                        agentRole = "idea-edit-applier",
                        targetFilePath = operation.filePath,
                    ),
                    outcome = "failed",
                    detail = mapOf(
                        "errorClass" to exception::class.qualifiedName,
                        "message" to exception.message,
                    ),
                )
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

    private fun validateWorkspaceTargets(
        workspaceIdentity: IdeaWorkspaceIdentity,
        fileOperations: List<ValidatedFileOperation>,
        edits: List<ValidatedFileEdits>,
        invocationId: String,
    ) {
        fileOperations.forEach { operation ->
            val mutation = when (operation) {
                is ValidatedFileOperation.CreateFile -> IdeaWorkspaceMutation.CREATE_FILE
                is ValidatedFileOperation.DeleteFile -> IdeaWorkspaceMutation.DELETE_FILE
            }
            requireWorkspaceTarget(workspaceIdentity, operation.filePath, mutation, invocationId)
        }
        edits.forEach { plan ->
            requireWorkspaceTarget(workspaceIdentity, plan.filePath, IdeaWorkspaceMutation.TEXT_EDIT, invocationId)
        }
    }

    private fun requireWorkspaceTarget(
        workspaceIdentity: IdeaWorkspaceIdentity,
        filePath: String,
        mutation: IdeaWorkspaceMutation,
        invocationId: String,
    ): IdeaWorkspaceFilePath = try {
        workspaceIdentity.requireEditablePath(filePath, mutation)
    } catch (exception: ValidationException) {
        KastStructuredTrace.event(
            eventName = "idea.workspace_identity.mismatch",
            project = project,
            workspaceRoot = workspaceIdentity.workspaceRootPath,
            fields = KastStructuredTraceFields(
                invocationId = invocationId,
                agentRole = "idea-edit-applier",
                targetFilePath = filePath,
            ),
            outcome = "failed",
            detail = exception.details,
        )
        throw exception
    }

    private suspend fun applyTextEdits(
        plan: ValidatedFileEdits,
        vfsManager: VirtualFileManager,
        fileDocumentManager: FileDocumentManager,
        psiDocumentManager: PsiDocumentManager,
        invocationId: String,
        workspaceRoot: Path,
    ) {
        KastStructuredTrace.event(
            eventName = "idea.apply_edits.text_edit_started",
            project = project,
            workspaceRoot = workspaceRoot,
            fields = KastStructuredTraceFields(
                invocationId = invocationId,
                agentRole = "idea-edit-applier",
                targetFilePath = plan.filePath,
            ),
            detail = mapOf("editCount" to plan.edits.size),
        )
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
        KastStructuredTrace.event(
            eventName = "idea.apply_edits.text_edit_completed",
            project = project,
            workspaceRoot = workspaceRoot,
            fields = KastStructuredTraceFields(
                invocationId = invocationId,
                agentRole = "idea-edit-applier",
                targetFilePath = plan.filePath,
            ),
            outcome = "completed",
        )
    }
}
