package io.github.amichne.kast.idea.edit

import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ValidatedFileEdits
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.mutation.*

internal suspend fun IdeaEditApplier.applyTextEdits(
        plan: ValidatedFileEdits,
        vfsManager: VirtualFileManager,
        fileDocumentManager: FileDocumentManager,
        psiDocumentManager: PsiDocumentManager,
        invocationId: String,
        workspaceRoot: Path,
        onFilesystemCommitted: (SecureWorkspaceMutationResult) -> Unit,
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

        val diskHash = readAction {
            FileHashing.sha256(String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8))
        }
        val updatedContent = readAction {
            StringBuilder(document.text).apply {
                plan.edits.forEach { edit ->
                    replace(edit.startOffset, edit.endOffset, edit.newText)
                }
            }.toString()
        }

        // Keep the IDEA command boundary, but make the disk write relative to a
        // held directory descriptor so a replaced ancestor cannot redirect it.
        WriteCommandAction.runWriteCommandAction(project) {
            val filePath = Path.of(plan.filePath).toAbsolutePath().normalize()
            beforeSecureMutation(filePath, IdeaWorkspaceMutation.TEXT_EDIT)
            val mutationResult = secureWorkspaceMutation.replaceFile(filePath, diskHash, updatedContent)
            onFilesystemCommitted(mutationResult)
            secureWorkspaceMutation.verifyCommittedFile(
                target = filePath,
                expectedContent = updatedContent,
                mutation = IdeaWorkspaceMutation.TEXT_EDIT,
            )
            plan.edits.forEach { edit ->
                document.replaceString(edit.startOffset, edit.endOffset, edit.newText)
            }
            psiDocumentManager.commitDocument(document)
            virtualFile.refresh(false, false)
            fileDocumentManager.reloadFromDisk(document)
        }
        verifyPostWrite(
            filePath = plan.filePath,
            mutation = IdeaWorkspaceMutation.TEXT_EDIT,
            expectedExists = true,
            expectedContent = document.text,
            invocationId = invocationId,
            workspaceRoot = workspaceRoot,
        )
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

internal fun IdeaEditApplier.verifyPostWrite(
        filePath: String,
        mutation: IdeaWorkspaceMutation,
        expectedExists: Boolean,
        expectedContent: String?,
        invocationId: String,
        workspaceRoot: Path,
    ) {
        val path = Path.of(filePath).toAbsolutePath().normalize()
        val workspaceFile = workspaceIdentity.workspaceIdentity.contains(path)
        if (!workspaceFile) {
            throw ValidationException(
                message = "Kast IDEA post-write verification failed",
                details = mapOf(
                    "filePath" to filePath,
                    "mutation" to mutation.wireName,
                    "workspaceContained" to workspaceFile.toString(),
                    "expectedExists" to expectedExists.toString(),
                ) + workspaceIdentity.stringTraceDetails(),
            )
        }
        val diskExists = expectedExists

        val refreshedFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        val vfsExists = refreshedFile?.isValid == true
        if (vfsExists != expectedExists) {
            throw ValidationException(
                message = "Kast IDEA VFS post-write verification failed",
                details = mapOf(
                    "filePath" to filePath,
                    "mutation" to mutation.wireName,
                    "expectedExists" to expectedExists.toString(),
                    "vfsExists" to vfsExists.toString(),
                ),
            )
        }
        val psiFileResolved = if (expectedExists && refreshedFile != null && !refreshedFile.isDirectory) {
            runIdeaReadAction { PsiManager.getInstance(project).findFile(refreshedFile) != null }
        } else {
            false
        }
        KastStructuredTrace.event(
            eventName = "idea.apply_edits.post_write_verified",
            project = project,
            workspaceRoot = workspaceRoot,
            fields = KastStructuredTraceFields(
                invocationId = invocationId,
                agentRole = "idea-edit-applier",
                targetFilePath = filePath,
            ),
            outcome = "completed",
            detail = mapOf(
                "mutation" to mutation.wireName,
                "workspaceContained" to workspaceFile,
                "diskExists" to diskExists,
                "vfsExists" to vfsExists,
                "psiFileResolved" to psiFileResolved,
            ) + workspaceIdentity.traceDetails(),
        )
    }

internal fun IdeaWorkspaceIdentity.stringTraceDetails(): Map<String, String> =
        traceDetails().mapValues { (_, value) -> value?.toString().orEmpty() }
