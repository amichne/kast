package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure as PublicFailure
import io.github.amichne.kast.runtime.hosted.HostedVfsRefreshOutcome
import io.github.amichne.kast.runtime.hosted.saveProjectDocuments
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument

/** Effect boundary for exactly the existing project's linked root; never opens or links a project. */
internal class IntellijWorkspaceRefreshPort(
    private val project: Project,
    private val root: CanonicalWorkspaceRoot,
    private val query: HostedQueryService,
) : WorkspaceRefreshPort {
    private sealed interface LinkState {
        data object Existing : LinkState

        data class Initial(val settings: org.jetbrains.plugins.gradle.settings.GradleProjectSettings) : LinkState
    }

    private var linkState: LinkState = LinkState.Existing

    /** Granted only for a newly created managed Project, never from semantic request admission. */
    fun prepareInitialLink(): Refinement<Unit, PublicFailure> {
        com.intellij.openapi.application.ApplicationManager.getApplication().assertIsDispatchThread()
        if (project.isDisposed) return Refinement.Rejected(PublicFailure.DISPOSED)
        if (!TrustedProjects.isProjectTrusted(project)) return Refinement.Rejected(PublicFailure.UNLINKED_BUILD)
        if (!saveProjectDocuments(root)) return Refinement.Rejected(PublicFailure.UNSAVED_DOCUMENTS)
        if (ExternalSystemApiUtil.getSettings(project, GRADLE).linkedProjectsSettings.isNotEmpty())
            return Refinement.Rejected(PublicFailure.UNLINKED_BUILD)
        linkState =
            LinkState.Initial(
                org.jetbrains.plugins.gradle.service.project.open.createLinkSettings(
                    java.nio.file.Path.of(root.value),
                    project,
                )
            )
        return Refinement.Refined(Unit)
    }

    fun admission(): Refinement<Unit, PublicFailure> {
        if (project.isDisposed) return Refinement.Rejected(PublicFailure.DISPOSED)
        if (project.basePath != root.value || !TrustedProjects.isProjectTrusted(project))
            return Refinement.Rejected(PublicFailure.UNLINKED_BUILD)
        if (
            com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager.getInstance()
                .hasTaskOfTypeInProgress(
                    com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType.RESOLVE_PROJECT,
                    project,
                )
        )
            return Refinement.Rejected(PublicFailure.NEWER_CHANGE)
        val settings = ExternalSystemApiUtil.getSettings(project, GRADLE)
        if (
            linkState == LinkState.Existing &&
                settings.linkedProjectsSettings.none { it.externalProjectPath == root.value }
        )
            return Refinement.Rejected(PublicFailure.UNLINKED_BUILD)
        if (!saveProjectDocuments(root)) return Refinement.Rejected(PublicFailure.UNSAVED_DOCUMENTS)
        return Refinement.Refined(Unit)
    }

    override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            startOnEdt(effect, complete)
        }
    }

    private fun startOnEdt(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
        try {
            when (val admitted = admission()) {
                is Refinement.Rejected -> {
                    complete(
                        when (admitted.failure) {
                            PublicFailure.UNSAVED_DOCUMENTS -> WorkspaceRefreshEffectResult.UNSAVED_DOCUMENTS
                            PublicFailure.UNLINKED_BUILD -> WorkspaceRefreshEffectResult.UNLINKED_BUILD
                            PublicFailure.NEWER_CHANGE -> WorkspaceRefreshEffectResult.BUSY
                            PublicFailure.DISPOSED -> WorkspaceRefreshEffectResult.DISPOSED
                            else -> WorkspaceRefreshEffectResult.FAILED
                        }
                    )
                    return
                }
                is Refinement.Refined -> Unit
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

    /** Automatic read admission uses this same explicit effect owner. */
    fun refreshForRead(complete: (HostedVfsRefreshOutcome) -> Unit) {
        if (project.isDisposed) {
            complete(HostedVfsRefreshOutcome.PROJECT_DISPOSED)
            return
        }
        val directory = LocalFileSystem.getInstance().findFileByPath(root.value)
        if (directory == null) {
            complete(HostedVfsRefreshOutcome.ROOT_UNAVAILABLE)
            return
        }
        // Include nested source and Gradle inputs even when the IDE window is backgrounded.
        VfsUtil.markDirty(true, true, directory)
        RefreshQueue.getInstance()
            .refresh(
                true,
                true,
                {
                    complete(
                        if (project.isDisposed) HostedVfsRefreshOutcome.PROJECT_DISPOSED
                        else HostedVfsRefreshOutcome.READY
                    )
                },
                directory,
            )
    }

    private fun reloadModel(complete: (WorkspaceRefreshEffectResult) -> Unit) {
        when (val link = linkState) {
            LinkState.Existing -> importModel(link, complete)
            is LinkState.Initial ->
                com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManager.getInstance(project)
                    .runWhenInitialized {
                        ApplicationManager.getApplication().invokeLater {
                            when (val admitted = admission()) {
                                is Refinement.Rejected ->
                                    complete(
                                        when (admitted.failure) {
                                            PublicFailure.UNSAVED_DOCUMENTS ->
                                                WorkspaceRefreshEffectResult.UNSAVED_DOCUMENTS
                                            PublicFailure.DISPOSED -> WorkspaceRefreshEffectResult.DISPOSED
                                            PublicFailure.NEWER_CHANGE -> WorkspaceRefreshEffectResult.BUSY
                                            else -> WorkspaceRefreshEffectResult.UNLINKED_BUILD
                                        }
                                    )
                                is Refinement.Refined -> importModel(link, complete)
                            }
                        }
                    }
        }
    }

    private fun importModel(link: LinkState, complete: (WorkspaceRefreshEffectResult) -> Unit) {
        val callback = WorkspaceRefreshImportCallback(project, root, complete)
        try {
            val spec = quietWorkspaceImport(project, callback)
            when (link) {
                LinkState.Existing -> ExternalSystemUtil.refreshProject(root.value, spec)
                is LinkState.Initial -> {
                    linkState = LinkState.Existing
                    ExternalSystemUtil.linkExternalProject(link.settings, spec)
                }
            }
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

/** One policy for initial linking and subsequent reload; never changes IDE preferences. */
internal fun quietWorkspaceImport(
    project: Project,
    callback: com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback,
): ImportSpecBuilder =
    ImportSpecBuilder(project, IntellijWorkspaceRefreshPort.GRADLE)
        .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
        .withImportProjectData(true)
        .withActivateToolWindowOnStart(false)
        .withActivateToolWindowOnFailure(false)
        .dontNavigateToError()
        .withCallback(callback)
