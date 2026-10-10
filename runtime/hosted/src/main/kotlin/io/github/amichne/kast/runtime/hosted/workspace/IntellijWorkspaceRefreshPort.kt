package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
import io.github.amichne.kast.runtime.hosted.saveProjectDocuments
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Effect boundary for exactly the existing project's linked root; never opens or links a project. */
internal class IntellijWorkspaceRefreshPort(
    private val project: Project,
    private val root: CanonicalWorkspaceRoot,
    private val query: HostedQueryService,
    private val modelInputs: WorkspaceRefreshModelBoundary,
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
        val observedComplete: (WorkspaceRefreshEffectResult) -> Unit =
            if (effect == WorkspaceRefreshEffect.FILE_REFRESH) {
                { outcome ->
                    observeForcedRefresh(outcome)
                    complete(outcome)
                }
            } else complete
        val boundary = WorkspaceRefreshStartBoundary()
        boundary.run(::startFailure, observedComplete, ::observeUnsettledStart) {
            if (observeExisting(effect, observedComplete, boundary)) return@run
            val admitted = admission()
            if (admitted is Refinement.Rejected) {
                observedComplete(admitted.failure.startResult())
                return@run
            }
            when (effect) {
                WorkspaceRefreshEffect.FILE_REFRESH -> refreshFiles(observedComplete, boundary)
                WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD -> reloadModel(observedComplete, boundary)
            }
        }
    }

    private fun observeExisting(
        effect: WorkspaceRefreshEffect,
        complete: (WorkspaceRefreshEffectResult) -> Unit,
        boundary: WorkspaceRefreshStartBoundary,
    ): Boolean {
        if (effect != WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD || linkState != LinkState.Existing) return false
        if (project.isDisposed || project.basePath != root.value || !TrustedProjects.isProjectTrusted(project))
            return false
        val linked =
            ExternalSystemApiUtil.getSettings(project, GRADLE).linkedProjectsSettings.any {
                it.externalProjectPath == root.value
            }
        if (!linked) return false
        return observeExistingWorkspaceImport(project, root, complete, boundary::mayHaveStarted)
    }

    private fun observeUnsettledStart(failure: WorkspaceRefreshEffectResult) {
        // A thrown scheduling call cannot establish that partially started native work terminated.
        Logger.getInstance(IntellijWorkspaceRefreshPort::class.java)
            .info("kast_workspace_refresh_start_unsettled outcome=" + Json.encodeToString(failure))
    }

    override fun readiness(): WorkspaceCapabilityReadiness = modelInputs.readiness(query.workspaceReadiness(root))

    fun needsModelReload(): Boolean = modelInputs.needsModelReload()

    private fun refreshFiles(
        complete: (WorkspaceRefreshEffectResult) -> Unit,
        boundary: WorkspaceRefreshStartBoundary,
    ) {
        val directory = LocalFileSystem.getInstance().findFileByPath(root.value)
        if (directory == null) {
            complete(WorkspaceRefreshEffectResult.FAILED)
            return
        }
        // Explicit disk refresh cannot depend on the native watcher having reported the change yet.
        VfsUtil.markDirty(true, false, directory)
        val queue = RefreshQueue.getInstance()
        boundary.mayHaveStarted()
        queue.refresh(
            true,
            true,
            {
                val outcome =
                    if (project.isDisposed) WorkspaceRefreshEffectResult.DISPOSED
                    else WorkspaceRefreshEffectResult.SUCCEEDED
                complete(outcome)
            },
            directory,
        )
    }

    /** Automatic read admission waits for native recursive refresh without forcing every descendant dirty. */
    override fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            val boundary = WorkspaceRefreshStartBoundary()
            boundary.run(::startFailure, complete, ::observeUnsettledStart) {
                startIncrementalOnEdt(complete, boundary)
            }
        }
    }

    private fun startIncrementalOnEdt(
        complete: (WorkspaceRefreshEffectResult) -> Unit,
        boundary: WorkspaceRefreshStartBoundary,
    ) {
        if (project.isDisposed) {
            complete(WorkspaceRefreshEffectResult.DISPOSED)
            return
        }
        if (!saveProjectDocuments(root)) {
            complete(WorkspaceRefreshEffectResult.UNSAVED_DOCUMENTS)
            return
        }
        val directory = LocalFileSystem.getInstance().findFileByPath(root.value)
        if (directory == null) {
            complete(WorkspaceRefreshEffectResult.ROOT_UNAVAILABLE)
            return
        }
        // Native incremental refresh retains recursive coverage and reports completion through its callback.
        val queue = RefreshQueue.getInstance()
        boundary.mayHaveStarted()
        queue.refresh(
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

    private fun observeForcedRefresh(outcome: WorkspaceRefreshEffectResult) {
        Logger.getInstance(IntellijWorkspaceRefreshPort::class.java).info(outcome.refreshObservation())
    }

    private fun reloadModel(complete: (WorkspaceRefreshEffectResult) -> Unit, boundary: WorkspaceRefreshStartBoundary) {
        when (val link = linkState) {
            LinkState.Existing -> importModel(link, complete)
            is LinkState.Initial -> {
                val manager =
                    com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManager.getInstance(
                        project
                    )
                boundary.mayHaveStarted()
                manager.runWhenInitialized {
                    ApplicationManager.getApplication().invokeLater {
                        val initialBoundary = WorkspaceRefreshStartBoundary()
                        initialBoundary.run(::startFailure, complete, ::observeUnsettledStart) {
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
        }
    }

    private fun importModel(link: LinkState, complete: (WorkspaceRefreshEffectResult) -> Unit) {
        val boundary = WorkspaceRefreshStartBoundary()
        var callback: WorkspaceRefreshImportCallback? = null
        val importTicket =
            java.util.concurrent.atomic.AtomicReference<
                io.github.amichne.kast.runtime.hosted.HostedGradleRevision.ImportTicket?
            >(
                null
            )
        boundary.run(
            ::startFailure,
            { outcome ->
                callback?.abortBeforeStart()
                complete(outcome)
            },
            { outcome -> callback?.failedStartCall(outcome) ?: observeUnsettledStart(outcome) },
        ) {
            val preparedCallback =
                WorkspaceRefreshImportCallback(project, root) { outcome ->
                    importTicket.get()?.settled(outcome)
                    complete(outcome)
                }
            callback = preparedCallback
            val spec = quietWorkspaceImport(project, preparedCallback)
            importTicket.set(modelInputs.beginOwnedImport())
            boundary.mayHaveStarted()
            when (link) {
                LinkState.Existing -> ExternalSystemUtil.refreshProject(root.value, spec)
                is LinkState.Initial -> {
                    linkState = LinkState.Existing
                    ExternalSystemUtil.linkExternalProject(link.settings, spec)
                }
            }
        }
    }

    private fun startFailure(exception: RuntimeException): WorkspaceRefreshEffectResult =
        if (exception is ProcessCanceledException) WorkspaceRefreshEffectResult.CANCELLED
        else WorkspaceRefreshEffectResult.FAILED

    companion object {
        val GRADLE = ProjectSystemId("GRADLE")
    }
}

internal fun WorkspaceRefreshEffectResult.refreshObservation(): String =
    "kast_vfs_refresh policy=FORCED_RECONCILIATION outcome=$name"

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

private fun PublicFailure.startResult(): WorkspaceRefreshEffectResult =
    when (this) {
        PublicFailure.UNSAVED_DOCUMENTS -> WorkspaceRefreshEffectResult.UNSAVED_DOCUMENTS
        PublicFailure.UNLINKED_BUILD -> WorkspaceRefreshEffectResult.UNLINKED_BUILD
        PublicFailure.NEWER_CHANGE -> WorkspaceRefreshEffectResult.BUSY
        PublicFailure.DISPOSED -> WorkspaceRefreshEffectResult.DISPOSED
        else -> WorkspaceRefreshEffectResult.FAILED
    }
