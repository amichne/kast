package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.wm.WindowManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage
import io.github.amichne.kast.runtime.hosted.HostedEndpointService
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.gradle.settings.GradleSettings

/** Ordinary graphical 262 APIs only. No force closure, global preferences, or implicit trust. */
internal class IdeLifecycleNative(private val state: IdeLifecycleState) {
    private val projects = java.util.concurrent.ConcurrentHashMap<UUID, Project>()

    fun canonicalRoot(raw: String): Refinement<CanonicalWorkspaceRoot, IdeLifecycleFailure> =
        try {
            val path = Path.of(raw)
            if (!path.isAbsolute || !Files.isDirectory(path)) Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
            else
                when (val root = CanonicalWorkspaceRoot.fromCanonicalPath(path.toRealPath())) {
                    is Refinement.Refined -> root
                    is Refinement.Rejected -> Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
                }
        } catch (_: java.io.IOException) {
            Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
        } catch (_: IllegalArgumentException) {
            Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
        }

    fun observeProjects() {
        ProjectManager.getInstance()
            .openProjects
            .filterNot { it.isDisposed }
            .forEach { project ->
                val root =
                    when (val admitted = canonicalRoot(project.basePath ?: return@forEach)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return@forEach
                    }
                observe(project, root, IdeProjectOwnership.BORROWED)
            }
    }

    private fun observe(
        project: Project,
        root: CanonicalWorkspaceRoot,
        ownership: IdeProjectOwnership,
    ): IdeProjectTarget {
        val id = project.getService(HostedQueryService::class.java).hostLifetime.value
        projects[id] = project
        return state.observe(id, root, ownership)
    }

    fun retire(project: Project) {
        projects.entries
            .filter { it.value === project }
            .forEach {
                state.retire(it.key)
                projects.remove(it.key, project)
            }
    }

    suspend fun execute(command: IdeLifecycleCommand): IdeLifecycleResult =
        when (command) {
            is IdeLifecycleCommand.Open -> open(command)
            is IdeLifecycleCommand.Present ->
                withTarget(command.target) { project ->
                    withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                        val frame = WindowManager.getInstance().getFrame(project)
                        if (frame == null) blocked(IdeLifecycleFailure.PLATFORM_UNAVAILABLE)
                        else {
                            frame.isVisible = true
                            frame.toFront()
                            IdeLifecycleResult.Presented(command.target)
                        }
                    }
                }
            is IdeLifecycleCommand.Sync ->
                withTarget(command.target) { sync(it, command.target, command.requestId, command.effect) }
            is IdeLifecycleCommand.ConfigureSync ->
                withTarget(command.target) { configureSync(it, command.target, command.rule) }
            is IdeLifecycleCommand.Close -> withTarget(command.target) { close(it, command.target) }
            else -> blocked(IdeLifecycleFailure.INVALID_REQUEST)
        }

    private suspend fun open(command: IdeLifecycleCommand.Open): IdeLifecycleResult {
        val root =
            when (val admitted = canonicalRoot(command.root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return blocked(admitted.failure)
            }
        val existing = existingProjects(root)
        if (existing.size > 1) return blocked(IdeLifecycleFailure.PROJECT_BUSY)
        if (existing.size == 1) {
            val target = observe(existing.single(), root, IdeProjectOwnership.BORROWED)
            return awaitReady(existing.single(), target)
        }
        if (!TrustedProjects.isProjectTrusted(Path.of(root.value))) return blocked(IdeLifecycleFailure.TRUST_REQUIRED)
        val guard = withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().unsavedDocuments.isEmpty() }
        if (!guard) return blocked(IdeLifecycleFailure.UNSAVED_DOCUMENTS)
        val importScopes = mutableListOf<InitialImportScope>()
        try {
            val project = openNative(root, importScopes) ?: return blocked(IdeLifecycleFailure.OPEN_FAILED)
            val target = observe(project, root, IdeProjectOwnership.MANAGED)
            val endpoint = project.getService(HostedEndpointService::class.java)
            if (!awaitCondition { project.isDisposed || (project.isInitialized && endpoint.lifecycleRefreshReady()) })
                return blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
            if (project.isDisposed) return blocked(IdeLifecycleFailure.DISPOSED)
            state.progress(LifecycleRequest(command.requestId), IdeLifecycleStage.IMPORTING)
            val initial = initialImport(project, endpoint, root, command.requestId)
            return when (val imported = awaitRefresh(endpoint, target, command.requestId, initial)) {
                is IdeLifecycleResult.Synced -> IdeLifecycleResult.Opened(target)
                else -> imported
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) { importScopes.forEach(InitialImportScope::close) }
        }
    }

    private fun existingProjects(root: CanonicalWorkspaceRoot): List<Project> =
        ProjectManager.getInstance().openProjects.filter {
            !it.isDisposed && it.basePath?.let(::canonicalRoot) == Refinement.Refined(root)
        }

    private suspend fun openNative(
        root: CanonicalWorkspaceRoot,
        importScopes: MutableList<InitialImportScope>,
    ): Project? =
        ProjectManagerEx.getInstanceEx()
            .openProjectAsync(
                Path.of(root.value),
                OpenProjectTask {
                    forceOpenInNewFrame = true
                    forceReuseFrame = false
                    projectToClose = null
                    showWelcomeScreen = false
                    runConfigurators = false
                    runConversionBeforeOpen = false
                    isNewProject = !Files.exists(Path.of(root.value).resolve(".idea"))
                    beforeOpen = { opening ->
                        importScopes.add(InitialImportScope(opening))
                        true
                    }
                    useDefaultProjectAsTemplate = false
                },
            )

    private suspend fun initialImport(
        project: Project,
        endpoint: HostedEndpointService,
        root: CanonicalWorkspaceRoot,
        requestId: String,
    ): WorkspaceRefreshResult =
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            val settings = GradleSettings.getInstance(project).linkedProjectsSettings
            if (settings.isEmpty()) endpoint.lifecycleInitialImport(requestId)
            else if (settings.any { it.externalProjectPath == root.value })
                endpoint.lifecycleRefresh(
                    WorkspaceRefreshCommand.Request(
                        requestId,
                        WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD,
                    )
                )
            else WorkspaceRefreshResult.Rejected(WorkspaceRefreshFailure.UNLINKED_BUILD)
        }

    private suspend fun awaitReady(project: Project, target: IdeProjectTarget): IdeLifecycleResult {
        if (!TrustedProjects.isProjectTrusted(project)) return blocked(IdeLifecycleFailure.TRUST_REQUIRED)
        val root =
            when (val admitted = canonicalRoot(target.root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return blocked(admitted.failure)
            }
        val query = project.getService(HostedQueryService::class.java)
        val ready = awaitCondition {
            project.isDisposed || query.readiness(root) == HostedReadinessDocument.AdmissionReady
        }
        return when {
            project.isDisposed -> blocked(IdeLifecycleFailure.DISPOSED)
            !ready -> blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
            else -> IdeLifecycleResult.Opened(target)
        }
    }

    private suspend fun sync(
        project: Project,
        target: IdeProjectTarget,
        requestId: String,
        effect: WorkspaceRefreshEffect,
    ): IdeLifecycleResult {
        if (!TrustedProjects.isProjectTrusted(project)) return blocked(IdeLifecycleFailure.TRUST_REQUIRED)
        val endpoint = project.getService(HostedEndpointService::class.java)
        return awaitRefresh(
            endpoint,
            target,
            requestId,
            endpoint.lifecycleRefresh(WorkspaceRefreshCommand.Request(requestId, effect)),
        )
    }

    private fun configureSync(
        project: Project,
        target: IdeProjectTarget,
        rule: io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule,
    ): IdeLifecycleResult {
        if (!TrustedProjects.isProjectTrusted(project)) return blocked(IdeLifecycleFailure.TRUST_REQUIRED)
        return when (
            val configured =
                project
                    .getService(HostedEndpointService::class.java)
                    .lifecycleRefresh(WorkspaceRefreshCommand.Configure(rule))
        ) {
            is WorkspaceRefreshResult.Configured -> IdeLifecycleResult.Configured(target, configured.rule)
            is WorkspaceRefreshResult.Rejected -> blocked(configured.reason.lifecycleFailure())
            else -> blocked(IdeLifecycleFailure.INVALID_REQUEST)
        }
    }

    private suspend fun awaitRefresh(
        endpoint: HostedEndpointService,
        target: IdeProjectTarget,
        requestId: String,
        initial: WorkspaceRefreshResult,
    ): IdeLifecycleResult {
        var result = initial
        val finished = awaitCondition {
            if (result is WorkspaceRefreshResult.Pending) {
                result = endpoint.lifecycleRefresh(WorkspaceRefreshCommand.Status(requestId))
                val pending = result as? WorkspaceRefreshResult.Pending
                state.progress(
                    LifecycleRequest(requestId),
                    if (pending?.stage == WorkspaceRefreshStage.ADMISSION) IdeLifecycleStage.ADMISSION
                    else IdeLifecycleStage.IMPORTING,
                )
            }
            result !is WorkspaceRefreshResult.Pending
        }
        if (!finished) return blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
        return when (val terminal = result) {
            is WorkspaceRefreshResult.Complete -> IdeLifecycleResult.Synced(target)
            is WorkspaceRefreshResult.Failed -> blocked(terminal.reason.lifecycleFailure())
            is WorkspaceRefreshResult.Rejected -> blocked(terminal.reason.lifecycleFailure())
            else -> blocked(IdeLifecycleFailure.IMPORT_FAILED)
        }
    }

    private suspend fun close(project: Project, target: IdeProjectTarget): IdeLifecycleResult {
        val endpoint = project.getService(HostedEndpointService::class.java)
        if (endpoint.lifecycleHasWork()) return blocked(IdeLifecycleFailure.PROJECT_BUSY)
        if (!endpoint.lifecycleAdmission.beginClose()) return blocked(IdeLifecycleFailure.PROJECT_BUSY)
        try {
            val result =
                withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                    when {
                        endpoint.lifecycleHasWork() -> blocked(IdeLifecycleFailure.PROJECT_BUSY)
                        project.isDisposed -> blocked(IdeLifecycleFailure.DISPOSED)
                        com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType.entries.any {
                            com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
                                .getInstance()
                                .hasTaskOfTypeInProgress(it, project)
                        } -> blocked(IdeLifecycleFailure.PROJECT_BUSY)
                        FileDocumentManager.getInstance().unsavedDocuments.isNotEmpty() ->
                            blocked(IdeLifecycleFailure.UNSAVED_DOCUMENTS)
                        !ProjectManager.getInstance().closeAndDispose(project) ->
                            blocked(IdeLifecycleFailure.CLOSE_VETOED)
                        else -> IdeLifecycleResult.Closed(target)
                    }
                }
            if (result !is IdeLifecycleResult.Closed) return result
            if (!awaitCondition { project.isDisposed && endpoint.lifecycleEndpointRetired() })
                return blocked(IdeLifecycleFailure.CLOSE_RETIREMENT_PENDING)
            return result
        } finally {
            if (!project.isDisposed) endpoint.lifecycleAdmission.restore()
        }
    }

    private suspend fun withTarget(
        target: IdeProjectTarget,
        action: suspend (Project) -> IdeLifecycleResult,
    ): IdeLifecycleResult {
        val id =
            try {
                UUID.fromString(target.project)
            } catch (_: IllegalArgumentException) {
                return blocked(IdeLifecycleFailure.INVALID_REQUEST)
            }
        val project = projects[id] ?: return blocked(IdeLifecycleFailure.STALE_PROJECT)
        val root =
            when (val admitted = canonicalRoot(project.basePath ?: return blocked(IdeLifecycleFailure.STALE_PROJECT))) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return blocked(IdeLifecycleFailure.STALE_PROJECT)
            }
        if (project.isDisposed || root.value != target.root) return blocked(IdeLifecycleFailure.STALE_PROJECT)
        return action(project)
    }

    private suspend fun awaitCondition(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(OPERATION_WAIT_MILLIS) {
            while (!condition()) delay(POLL_MILLIS)
            true
        } ?: false

    private companion object {
        const val OPERATION_WAIT_MILLIS = 120_000L
        const val POLL_MILLIS = 100L
    }

    private fun blocked(reason: IdeLifecycleFailure) = IdeLifecycleResult.Blocked(reason)
}

private fun WorkspaceRefreshFailure.lifecycleFailure(): IdeLifecycleFailure =
    when (this) {
        WorkspaceRefreshFailure.UNSAVED_DOCUMENTS -> IdeLifecycleFailure.UNSAVED_DOCUMENTS
        WorkspaceRefreshFailure.UNLINKED_BUILD -> IdeLifecycleFailure.UNLINKED_BUILD
        WorkspaceRefreshFailure.DISPOSED -> IdeLifecycleFailure.DISPOSED
        WorkspaceRefreshFailure.CANCELLED -> IdeLifecycleFailure.CANCELLED
        WorkspaceRefreshFailure.DEADLINE_EXCEEDED -> IdeLifecycleFailure.DEADLINE_EXCEEDED
        WorkspaceRefreshFailure.CAPACITY -> IdeLifecycleFailure.CAPACITY
        WorkspaceRefreshFailure.REQUEST_CONFLICT -> IdeLifecycleFailure.REQUEST_CONFLICT
        WorkspaceRefreshFailure.UNKNOWN_REQUEST -> IdeLifecycleFailure.UNKNOWN_OPERATION
        WorkspaceRefreshFailure.INVALID_REQUEST -> IdeLifecycleFailure.INVALID_REQUEST
        WorkspaceRefreshFailure.ADMISSION_REJECTED,
        WorkspaceRefreshFailure.NEWER_CHANGE -> IdeLifecycleFailure.PROJECT_BUSY
        WorkspaceRefreshFailure.EFFECT_FAILED -> IdeLifecycleFailure.IMPORT_FAILED
    }
