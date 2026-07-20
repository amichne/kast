package io.github.amichne.kast.idea.edit

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsShowConfirmationOption
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.ValidatedFileEdits
import io.github.amichne.kast.api.validation.ValidatedFileOperation
import java.nio.file.Path
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.mutation.*

internal suspend fun IdeaEditApplier.applyFileOperations(
        operations: List<ValidatedFileOperation>,
        invocationId: String,
        workspaceRoot: Path,
    ): Triple<MutableList<String>, MutableList<String>, MutableList<String>> {
        val affectedFiles = mutableListOf<String>()
        val createdFiles = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()

        operations.forEach { operation ->
            var committedMutation: SecureWorkspaceMutationResult? = null
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
                        runFileOperationWriteAction {
                            val filePath = Path.of(operation.filePath).toAbsolutePath().normalize()
                            beforeSecureMutation(filePath, IdeaWorkspaceMutation.CREATE_FILE)
                            val mutationResult = secureWorkspaceMutation.createFile(filePath, operation.content)
                            committedMutation = mutationResult
                            createdFiles += operation.filePath
                            affectedFiles += operation.filePath
                            afterFilesystemCommit(filePath, IdeaWorkspaceMutation.CREATE_FILE)
                        }
                        val mutationResult = checkNotNull(committedMutation)
                        secureWorkspaceMutation.verifyCommittedFile(
                            target = Path.of(operation.filePath).toAbsolutePath().normalize(),
                            expectedContent = operation.content,
                            mutation = IdeaWorkspaceMutation.CREATE_FILE,
                        )
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(operation.filePath))
                            ?: throw ValidationException(
                                message = "Kast IDEA could not refresh the securely created file",
                                details = mapOf("filePath" to operation.filePath),
                            )
                        verifyPostWrite(
                            filePath = operation.filePath,
                            mutation = IdeaWorkspaceMutation.CREATE_FILE,
                            expectedExists = true,
                            expectedContent = operation.content,
                            invocationId = invocationId,
                            workspaceRoot = workspaceRoot,
                        )
                        mutationResult.requireNoRecovery(
                            committedFile = operation.filePath,
                            appliedFiles = affectedFiles,
                            createdFiles = createdFiles,
                            deletedFiles = deletedFiles,
                        )
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
                        runFileOperationWriteAction {
                            val filePath = Path.of(operation.filePath).toAbsolutePath().normalize()
                            beforeSecureMutation(filePath, IdeaWorkspaceMutation.DELETE_FILE)
                            val mutationResult = secureWorkspaceMutation.deleteFile(filePath, operation.expectedHash)
                            committedMutation = mutationResult
                            deletedFiles += operation.filePath
                            affectedFiles += operation.filePath
                            afterFilesystemCommit(filePath, IdeaWorkspaceMutation.DELETE_FILE)
                        }
                        val mutationResult = checkNotNull(committedMutation)
                        secureWorkspaceMutation.verifyCommittedDeletion(
                            Path.of(operation.filePath).toAbsolutePath().normalize(),
                        )
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(operation.filePath))
                        verifyPostWrite(
                            filePath = operation.filePath,
                            mutation = IdeaWorkspaceMutation.DELETE_FILE,
                            expectedExists = false,
                            expectedContent = null,
                            invocationId = invocationId,
                            workspaceRoot = workspaceRoot,
                        )
                        mutationResult.requireNoRecovery(
                            committedFile = operation.filePath,
                            appliedFiles = affectedFiles,
                            createdFiles = createdFiles,
                            deletedFiles = deletedFiles,
                        )
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
            } catch (exception: Exception) {
                if (exception is PartialApplyException) throw exception
                if (exception.isTypedSecureMutationFailure() && affectedFiles.isEmpty()) {
                    throw exception
                }
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
                    details = partialApplyDetails(
                        failedFile = operation.filePath,
                        appliedFiles = affectedFiles,
                        createdFiles = createdFiles,
                        deletedFiles = deletedFiles,
                        exception = exception,
                        committedMutation = committedMutation,
                    ),
                )
            }
        }

        return Triple(affectedFiles, createdFiles, deletedFiles)
    }

internal fun IdeaEditApplier.validateWorkspaceTargets(
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

internal fun Exception.isTypedSecureMutationFailure(): Boolean =
        this is UnsafeWorkspaceMutationException || this is ConflictException || this is NotFoundException

internal fun SecureWorkspaceMutationResult.requireNoRecovery(
        committedFile: String,
        appliedFiles: List<String>,
        createdFiles: List<String>,
        deletedFiles: List<String>,
    ) {
        if (this !is SecureWorkspaceMutationResult.CommittedWithRecovery) return
        throw PartialApplyException(
            message = "The workspace mutation committed but retained recovery evidence",
            details = mapOf(
                "failedFile" to committedFile,
                "appliedFiles" to appliedFiles.joinToString(","),
                "createdFiles" to createdFiles.joinToString(","),
                "deletedFiles" to deletedFiles.joinToString(","),
                "recoveryFilePaths" to recoveryFilePaths.joinToString(","),
                "reason" to "Committed filesystem mutation retained recovery entries",
            ),
        )
    }

internal fun IdeaEditApplier.partialApplyFailure(
        failedFile: String,
        appliedFiles: List<String>,
        createdFiles: List<String>,
        deletedFiles: List<String>,
        exception: Exception,
        committedMutation: SecureWorkspaceMutationResult? = null,
    ): PartialApplyException = PartialApplyException(
        details = partialApplyDetails(
            failedFile = failedFile,
            appliedFiles = appliedFiles,
            createdFiles = createdFiles,
            deletedFiles = deletedFiles,
            exception = exception,
            committedMutation = committedMutation,
        ),
    )

internal fun IdeaEditApplier.partialApplyDetails(
        failedFile: String,
        appliedFiles: List<String>,
        createdFiles: List<String>,
        deletedFiles: List<String>,
        exception: Exception,
        committedMutation: SecureWorkspaceMutationResult? = null,
    ): Map<String, String> = mapOf(
        "failedFile" to failedFile,
        "appliedFiles" to appliedFiles.joinToString(","),
        "createdFiles" to createdFiles.joinToString(","),
        "deletedFiles" to deletedFiles.joinToString(","),
        "reason" to (exception.message ?: exception::class.java.simpleName),
        "exceptionClass" to (exception::class.qualifiedName ?: "Unknown"),
    ) + committedMutation.recoveryDetails()

internal fun SecureWorkspaceMutationResult?.recoveryDetails(): Map<String, String> =
        if (this is SecureWorkspaceMutationResult.CommittedWithRecovery) {
            mapOf("recoveryFilePaths" to recoveryFilePaths.joinToString(","))
        } else {
            emptyMap()
        }

internal suspend fun <T> IdeaEditApplier.withVcsFileOperationConfirmationsSuppressed(
        fileOperations: List<ValidatedFileOperation>,
        action: suspend () -> T,
    ): T {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val overrides = buildList {
            if (fileOperations.any { operation -> operation is ValidatedFileOperation.CreateFile }) {
                add(
                    VcsConfirmationOverride(
                        option = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, null),
                        suppressedValue = VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY,
                    ),
                )
            }
            if (fileOperations.any { operation -> operation is ValidatedFileOperation.DeleteFile }) {
                add(
                    VcsConfirmationOverride(
                        option = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, null),
                        suppressedValue = VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY,
                    ),
                )
            }
        }
        overrides.forEach { override -> override.apply() }
        return try {
            action()
        } finally {
            overrides.asReversed().forEach { override -> override.restore() }
        }
    }

internal class VcsConfirmationOverride(
        private val option: VcsShowConfirmationOption,
        private val suppressedValue: VcsShowConfirmationOption.Value,
    ) {
        private val previousValue: VcsShowConfirmationOption.Value = option.value

        fun apply() {
            option.value = suppressedValue
        }

        fun restore() {
            option.value = previousValue
        }
    }

internal fun IdeaEditApplier.requireWorkspaceTarget(
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
            detail = exception.details + workspaceIdentity.traceDetails(),
        )
        throw exception
    }
