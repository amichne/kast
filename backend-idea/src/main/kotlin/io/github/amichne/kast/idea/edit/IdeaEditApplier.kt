package io.github.amichne.kast.idea.edit

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
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.mutation.*

/**
 * Applies edits using descriptor-relative filesystem writes plus IDEA's
 * Document and WriteCommandAction APIs.
 *
 * Preserves IDEA's undo/redo, PSI synchronization, and VFS notification
 * semantics while preventing pathname replacement from escaping the workspace.
 */
internal class IdeaEditApplier(
    internal val project: Project,
    internal val workspaceRoot: Path,
    internal val workspaceIdentity: IdeaWorkspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot),
    internal val secureWorkspaceMutation: SecureWorkspaceMutation =
        SecureWorkspaceMutation(workspaceIdentity.canonicalWorkspaceRootPath),
    internal val beforeSecureMutation: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    internal val afterFilesystemCommit: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    internal val runFileOperationWriteAction: suspend (() -> Unit) -> Unit = { operation ->
        writeAction { operation() }
    },
) {
    /**
     * Applies text edits and file operations through IDEA APIs.
     *
     * Workflow:
     * 1. Validate operations against current VFS state
     * 2. Apply file operations relative to held workspace directory descriptors
     * 3. Apply text edits through the same secure boundary and an IDEA command
     * 4. Refresh VFS state and commit documents
     *
     * @param query The edit query with edits, hashes, and file operations
     * @return Result with applied edits and affected files
     */
    suspend fun apply(query: ApplyEditsQuery): ApplyEditsResult {
        if (query.edits.isEmpty() && query.fileOperations.isEmpty()) {
            throw ValidationException("At least one text edit or file operation is required")
        }
        val invocationId = KastStructuredTrace.newInvocationId()
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
            ) + workspaceIdentity.traceDetails(),
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
        val (affectedFiles, createdFiles, deletedFiles) = withVcsFileOperationConfirmationsSuppressed(
            validatedFileOperations,
        ) {
            applyFileOperations(
                validatedFileOperations,
                invocationId,
                normalizedWorkspaceRoot,
            )
        }

        // Check hashes against current IDEA state
        validatedEdits.forEach { plan ->
            try {
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
            } catch (exception: Exception) {
                if (affectedFiles.isEmpty()) throw exception
                throw partialApplyFailure(
                    failedFile = plan.filePath,
                    appliedFiles = affectedFiles,
                    createdFiles = createdFiles,
                    deletedFiles = deletedFiles,
                    exception = exception,
                )
            }
        }

        // Apply text edits through Document API
        val appliedEdits = mutableListOf<TextEdit>()
        val editAffectedFiles = mutableListOf<String>()

        validatedEdits.forEach { plan ->
            var committedMutation: SecureWorkspaceMutationResult? = null
            try {
                applyTextEdits(
                    plan,
                    vfsManager,
                    fileDocumentManager,
                    psiDocumentManager,
                    invocationId,
                    normalizedWorkspaceRoot,
                    onFilesystemCommitted = { mutationResult ->
                        committedMutation = mutationResult
                        editAffectedFiles += plan.filePath
                        appliedEdits += plan.edits.sortedBy { it.startOffset }
                        afterFilesystemCommit(
                            Path.of(plan.filePath).toAbsolutePath().normalize(),
                            IdeaWorkspaceMutation.TEXT_EDIT,
                        )
                    },
                )
                checkNotNull(committedMutation).requireNoRecovery(
                    committedFile = plan.filePath,
                    appliedFiles = affectedFiles + editAffectedFiles,
                    createdFiles = createdFiles,
                    deletedFiles = deletedFiles,
                )
            } catch (exception: Exception) {
                if (exception is PartialApplyException) throw exception
                if (
                    exception.isTypedSecureMutationFailure() &&
                    affectedFiles.isEmpty() &&
                    editAffectedFiles.isEmpty()
                ) {
                    throw exception
                }
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
                    details = partialApplyDetails(
                        failedFile = plan.filePath,
                        appliedFiles = affectedFiles + editAffectedFiles,
                        createdFiles = createdFiles,
                        deletedFiles = deletedFiles,
                        exception = exception,
                        committedMutation = committedMutation,
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
            ) + workspaceIdentity.traceDetails(),
        )
        return result
    }

}
