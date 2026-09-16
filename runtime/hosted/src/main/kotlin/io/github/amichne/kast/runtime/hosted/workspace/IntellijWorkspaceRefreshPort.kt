package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure as PublicFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument

/** Effect boundary for exactly the existing project's linked root; never opens or links a project. */
internal class IntellijWorkspaceRefreshPort(
    private val project: Project,
    private val root: CanonicalWorkspaceRoot,
    private val query: HostedQueryService,
) : WorkspaceRefreshPort {
    fun admission(): Refinement<Unit, PublicFailure> {
        if (project.isDisposed) return Refinement.Rejected(PublicFailure.DISPOSED)
        if (project.basePath != root.value || !TrustedProjects.isProjectTrusted(project))
            return Refinement.Rejected(PublicFailure.UNLINKED_BUILD)
        val settings = ExternalSystemApiUtil.getSettings(project, GRADLE)
        if (settings.linkedProjectsSettings.none { it.externalProjectPath == root.value })
            return Refinement.Rejected(PublicFailure.UNLINKED_BUILD)
        // Native lifecycle must never silently save or discard an editor buffer.
        if (FileDocumentManager.getInstance().unsavedDocuments.isNotEmpty())
            return Refinement.Rejected(PublicFailure.UNSAVED_DOCUMENTS)
        return Refinement.Refined(Unit)
    }

    override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            startOnEdt(effect, complete)
        }
    }

    private fun startOnEdt(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
        try {
            if (admission() is Refinement.Rejected) {
                complete(
                    if (project.isDisposed) WorkspaceRefreshEffectResult.DISPOSED
                    else WorkspaceRefreshEffectResult.FAILED
                )
                return
            }
            when (effect) {
                WorkspaceRefreshEffect.FILE_REFRESH -> refreshFiles(complete)
                WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD -> reloadModel(complete)
            }
        } catch (_: ProcessCanceledException) {
            complete(WorkspaceRefreshEffectResult.CANCELLED)
        } catch (_: RuntimeException) {
            complete(WorkspaceRefreshEffectResult.FAILED)
        }
    }

    override fun readiness(): WorkspaceRefreshReadiness =
        if (project.isDisposed) WorkspaceRefreshReadiness.DISPOSED
        else
            when (query.readiness(root)) {
                HostedReadinessDocument.AdmissionReady -> WorkspaceRefreshReadiness.READY
                is HostedReadinessDocument.Unavailable -> WorkspaceRefreshReadiness.NOT_READY
            }

    private fun refreshFiles(complete: (WorkspaceRefreshEffectResult) -> Unit) {
        val directory = LocalFileSystem.getInstance().findFileByPath(root.value)
        if (directory == null) {
            complete(WorkspaceRefreshEffectResult.FAILED)
            return
        }
        // Explicit disk refresh cannot depend on the native watcher having reported the change yet.
        VfsUtil.markDirty(true, false, directory)
        RefreshQueue.getInstance()
            .refresh(
                true,
                true,
                {
                    complete(
                        if (project.isDisposed) WorkspaceRefreshEffectResult.DISPOSED
                        else WorkspaceRefreshEffectResult.SUCCEEDED
                    )
                },
                directory,
            )
    }

    private fun reloadModel(complete: (WorkspaceRefreshEffectResult) -> Unit) {
        val callback = WorkspaceRefreshImportCallback(project, root, complete)
        try {
            ExternalSystemUtil.refreshProject(
                root.value,
                ImportSpecBuilder(project, GRADLE)
                    .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                    .withImportProjectData(true)
                    .withCallback(callback),
            )
        } catch (_: ProcessCanceledException) {
            callback.failedToStart(WorkspaceRefreshEffectResult.CANCELLED)
        } catch (_: RuntimeException) {
            callback.failedToStart(WorkspaceRefreshEffectResult.FAILED)
        }
    }

    companion object {
        val GRADLE = ProjectSystemId("GRADLE")
    }
}
