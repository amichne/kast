package io.github.amichne.kast.idea.edit

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsShowConfirmationOption
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.contract.CreateFileParentPolicy
import io.github.amichne.kast.api.validation.ValidatedFileEdits
import io.github.amichne.kast.api.validation.ValidatedFileOperation
import io.github.amichne.kast.api.validation.ParsedMutationScratchSet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.modification.publishGlobalSourceOutOfBlockModificationEvent
import java.nio.file.Path
import java.util.WeakHashMap
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.mutation.*

private val vcsConfirmationLocks = WeakHashMap<Project, Mutex>()

private fun Project.vcsConfirmationLock(): Mutex =
    synchronized(vcsConfirmationLocks) {
        vcsConfirmationLocks.getOrPut(this) { Mutex() }
    }

internal suspend fun IdeaEditApplier.applyFileOperations(
        operations: List<ValidatedFileOperation>,
        invocationId: String,
        workspaceRoot: Path,
        mutationScratchSets: List<ParsedMutationScratchSet> = emptyList(),
    ): Triple<MutableList<String>, MutableList<String>, MutableList<String>> {
        if (mutationScratchSets.isNotEmpty()) {
            secureWorkspaceMutation.requireScratchBatchAbsent(mutationScratchSets)
        }
        val affectedFiles = mutableListOf<String>()
        val createdFiles = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()

        operations.forEachIndexed { operationIndex, operation ->
            val scratch = mutationScratchSets.getOrNull(operationIndex)
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
                            val mutationResult = when (operation.parentPolicy) {
                                CreateFileParentPolicy.CREATE_MISSING_PARENTS ->
                                    check(scratch == null) {
                                        "Parsed verified file creation cannot create parent directories"
                                    }.let { secureWorkspaceMutation.createFile(filePath, operation.content) }

                                CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS ->
                                    if (scratch == null) {
                                        secureWorkspaceMutation.createFileRequiringExistingParents(
                                            filePath,
                                            operation.content,
                                        )
                                    } else {
                                        secureWorkspaceMutation.createFileRequiringExistingParents(
                                            filePath,
                                            operation.content,
                                            scratch,
                                        )
                                    }
                            }
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
                        admitCreatedFile(operation.filePath)
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
                            val mutationResult = secureWorkspaceMutation.deleteFile(
                                filePath,
                                operation.expectedHash,
                                scratch,
                            )
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
                exception.rethrowIfMutationCancellation(
                    partialApplyFailure(
                        failedFile = operation.filePath,
                        appliedFiles = affectedFiles,
                        createdFiles = createdFiles,
                        deletedFiles = deletedFiles,
                        exception = exception,
                        committedMutation = committedMutation,
                    ),
                )
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
                "reason" to "Committed filesystem mutation retained recovery entries",
            ) + indexedRecoveryFilePaths(recoveryFilePaths),
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
            indexedRecoveryFilePaths(recoveryFilePaths)
        } else {
            emptyMap()
        }

private fun indexedRecoveryFilePaths(paths: List<Path>): Map<String, String> = buildMap {
    put("recoveryFilePathCount", paths.size.toString())
    paths.forEachIndexed { index, path ->
        put("recoveryFilePath.$index", path.toString())
    }
}

@OptIn(KaPlatformInterface::class)
private fun IdeaEditApplier.admitCreatedFile(filePath: String) {
    val target = Path.of(filePath).toAbsolutePath().normalize()
    if (LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target) == null) {
        throw ValidationException(
            message = "Kast IDEA could not refresh the securely created file",
            details = mapOf("filePath" to filePath),
        )
    }
    WriteAction.runAndWait<RuntimeException> {
        project.publishGlobalSourceOutOfBlockModificationEvent()
    }
}

internal suspend fun <T> IdeaEditApplier.withVcsFileOperationConfirmationsSuppressed(
        fileOperations: List<ValidatedFileOperation>,
        action: suspend () -> T,
    ): T {
        val suppressAdd = fileOperations.any { operation -> operation is ValidatedFileOperation.CreateFile }
        val suppressRemove = fileOperations.any { operation -> operation is ValidatedFileOperation.DeleteFile }
        if (!suppressAdd && !suppressRemove) return action()

        return project.vcsConfirmationLock().withLock {
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            val overrides = buildList {
                if (suppressAdd) {
                    add(
                        VcsConfirmationOverride(
                            option = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, null),
                            suppressedValue = VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY,
                        ),
                    )
                }
                if (suppressRemove) {
                    add(
                        VcsConfirmationOverride(
                            option = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, null),
                            suppressedValue = VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY,
                        ),
                    )
                }
            }
            overrides.forEach { override -> override.apply() }
            try {
                action()
            } finally {
                overrides.asReversed().forEach { override -> override.restore() }
            }
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
