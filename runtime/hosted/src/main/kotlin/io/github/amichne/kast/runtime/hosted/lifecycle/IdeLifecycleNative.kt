package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadline
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage
import io.github.amichne.kast.runtime.hosted.HostedEndpointService
import io.github.amichne.kast.runtime.hosted.saveProjectDocuments
import io.github.amichne.kast.runtime.hosted.workspace.observeWorkspaceRefresh
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Ordinary graphical 262 APIs only. No force closure, global preferences, or implicit trust. */
@Suppress("LargeClass") // One owner retains the native project and lifecycle effect order.
internal class IdeLifecycleNative(
    private val state: IdeLifecycleState,
    private val observePreparation: (NativePreparationObservation) -> Unit = {},
) {
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

    /** Registration selects an observation only; the associated project adapter supplies all readiness evidence. */
    fun inspectProjects(): List<IdeProjectDescription> =
        state.inspectionSelections().map { selection ->
            val project = projects[selection.project] ?: return@map selection.description
            inspectLifecycleProject(project, selection)
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

    /** Only already registered peers are eligible; the helper has no lifecycle mutation capability. */
    suspend fun <Value> withRegisteredPeer(
        expected: ImpactSemanticBasisDocument.Live,
        action: suspend (Project, CanonicalWorkspaceRoot) -> HostedSemanticReadResult<Value>,
    ): HostedSemanticReadResult<Value> = readRegisteredPeer(projects, expected, ::canonicalRoot, action)

    suspend fun execute(command: IdeLifecycleCommand): IdeLifecycleResult =
        when (command) {
            is IdeLifecycleCommand.Open -> prepare(command.requestId, command.preparationMillis) { open(command, it) }
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
                prepare(
                    command.requestId,
                    io.github.amichne.kast.protocol.contract.WorkspacePreparationBudget.limit.value,
                ) { operation ->
                    withTarget(command.target) { sync(it, command.target, operation, command.effect) }
                }
            is IdeLifecycleCommand.ConfigureSync ->
                withTarget(command.target) { project ->
                    withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                        configureSync(project, command.target, command.rule)
                    }
                }
            is IdeLifecycleCommand.Close -> withTarget(command.target) { close(it, command.target) }
            else -> blocked(IdeLifecycleFailure.INVALID_REQUEST)
        }

    private suspend fun prepare(
        requestId: String,
        allowanceMillis: Long,
        action: suspend (NativeWorkspacePreparation) -> IdeLifecycleResult,
    ): IdeLifecycleResult =
        when (
            val admitted = NativeWorkspacePreparation.admit(requestId, allowanceMillis, observe = observePreparation)
        ) {
            is Refinement.Rejected -> blocked(admitted.failure)
            is Refinement.Refined -> admitted.value.finish(action(admitted.value))
        }

    private suspend fun open(
        command: IdeLifecycleCommand.Open,
        operation: NativeWorkspacePreparation,
    ): IdeLifecycleResult {
        val root =
            when (val admitted = canonicalRoot(command.root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return blocked(admitted.failure)
            }
        val existing = existingProjects(root)
        if (existing.size > 1) return blocked(IdeLifecycleFailure.PROJECT_BUSY)
        if (existing.size == 1) return openExisting(existing.single(), root, operation)
        return openNew(root, operation)
    }

    private suspend fun openExisting(
        project: Project,
        root: CanonicalWorkspaceRoot,
        operation: NativeWorkspacePreparation,
    ): IdeLifecycleResult {
        val target = observe(project, root, IdeProjectOwnership.BORROWED)
        val endpoint = project.getService(HostedEndpointService::class.java)
        if (
            !awaitLifecycleCondition(operation.deadline, operation.nanoTime) {
                project.isDisposed || endpoint.lifecycleRefreshReady()
            }
        )
            return blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
        if (project.isDisposed) return blocked(IdeLifecycleFailure.DISPOSED)
        lifecycleVfsFailure(endpoint)?.let {
            return blocked(it)
        }
        return awaitReady(project, target, operation)
    }

    private suspend fun openNew(
        root: CanonicalWorkspaceRoot,
        operation: NativeWorkspacePreparation,
    ): IdeLifecycleResult {
        val requestId = operation.requestId
        if (!TrustedProjects.isProjectTrusted(Path.of(root.value))) return blocked(IdeLifecycleFailure.TRUST_REQUIRED)
        if (!withContext(Dispatchers.EDT) { saveProjectDocuments(root) })
            return blocked(IdeLifecycleFailure.UNSAVED_DOCUMENTS)
        val importScopes = mutableListOf<InitialImportScope>()
        try {
            if (operation.remaining() is Refinement.Rejected) return blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
            val project = openNativeWorkspace(root, importScopes) ?: return blocked(IdeLifecycleFailure.OPEN_FAILED)
            val target = observe(project, root, IdeProjectOwnership.MANAGED)
            val endpoint = project.getService(HostedEndpointService::class.java)
            if (
                !awaitLifecycleCondition(operation.deadline, operation.nanoTime) {
                    project.isDisposed || (project.isInitialized && endpoint.lifecycleRefreshReady())
                }
            )
                return blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
            if (project.isDisposed) return blocked(IdeLifecycleFailure.DISPOSED)
            lifecycleVfsFailure(endpoint)?.let {
                return blocked(it)
            }
            state.progress(LifecycleRequest(requestId), IdeLifecycleStage.IMPORTING)
            operation.enter(IdeLifecycleStage.IMPORTING)
            val initial = requestInitialWorkspaceImport(project, endpoint, root, operation)
            return when (val imported = awaitRefresh(endpoint, target, operation, initial)) {
                is IdeLifecycleResult.Synced -> {
                    IdeLifecycleResult.Opened(target)
                }
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

    private suspend fun awaitReady(
        project: Project,
        target: IdeProjectTarget,
        operation: NativeWorkspacePreparation,
    ): IdeLifecycleResult {
        if (!TrustedProjects.isProjectTrusted(project)) return blocked(IdeLifecycleFailure.TRUST_REQUIRED)
        val root =
            when (val admitted = canonicalRoot(target.root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return blocked(admitted.failure)
            }
        state.progress(LifecycleRequest(operation.requestId), IdeLifecycleStage.IMPORTING)
        operation.enter(IdeLifecycleStage.IMPORTING)
        return IdeLifecycleReadiness(
                project,
                target,
                observe = {
                    project.getService(HostedEndpointService::class.java).lifecycleReadiness(root)
                        ?: project.getService(HostedQueryService::class.java).workspaceReadiness(root)
                },
                reloadModel = { sync(project, target, operation, WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD) },
                operation = operation,
            )
            .await()
    }

    private suspend fun sync(
        project: Project,
        target: IdeProjectTarget,
        operation: NativeWorkspacePreparation,
        effect: WorkspaceRefreshEffect,
    ): IdeLifecycleResult {
        if (!TrustedProjects.isProjectTrusted(project)) return blocked(IdeLifecycleFailure.TRUST_REQUIRED)
        val requestId = operation.requestId
        operation.enter(IdeLifecycleStage.IMPORTING)
        val endpoint = project.getService(HostedEndpointService::class.java)
        return awaitRefresh(
            endpoint,
            target,
            operation,
            observeWorkspaceRefresh {
                when (val remaining = operation.remaining()) {
                    is Refinement.Rejected ->
                        WorkspaceRefreshResult.Failed(requestId, WorkspaceRefreshFailure.DEADLINE_EXCEEDED)
                    is Refinement.Refined ->
                        endpoint.lifecycleRefresh(
                            WorkspaceRefreshCommand.Request(requestId, effect, remaining.value.value)
                        )
                }
            },
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
        operation: NativeWorkspacePreparation,
        initial: WorkspaceRefreshResult,
    ): IdeLifecycleResult {
        var result = initial
        val requestId = operation.requestId
        val finished =
            awaitLifecycleCondition(operation.deadline, operation.nanoTime) {
                if (result is WorkspaceRefreshResult.Pending) {
                    result = endpoint.lifecycleRefresh(WorkspaceRefreshCommand.Status(requestId))
                    val pending = result as? WorkspaceRefreshResult.Pending
                    if (pending != null) {
                        val stage =
                            if (pending.stage == WorkspaceRefreshStage.ADMISSION) IdeLifecycleStage.ADMISSION
                            else IdeLifecycleStage.IMPORTING
                        state.progress(LifecycleRequest(requestId), stage)
                        operation.enter(stage)
                    }
                }
                result !is WorkspaceRefreshResult.Pending
            }
        if (!finished) return blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
        return when (val terminal = result) {
            is WorkspaceRefreshResult.Complete -> {
                operation.enter(IdeLifecycleStage.ADMISSION)
                IdeLifecycleResult.Synced(target)
            }
            is WorkspaceRefreshResult.Failed ->
                IdeLifecycleResult.Blocked(terminal.reason.lifecycleFailure(), terminal.preparation)
            is WorkspaceRefreshResult.Rejected -> blocked(terminal.reason.lifecycleFailure())
            else -> blocked(IdeLifecycleFailure.IMPORT_FAILED)
        }
    }

    private suspend fun close(project: Project, target: IdeProjectTarget): IdeLifecycleResult {
        val root =
            when (val admitted = canonicalRoot(target.root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return blocked(IdeLifecycleFailure.STALE_PROJECT)
            }
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
                        !saveProjectDocuments(root) -> blocked(IdeLifecycleFailure.UNSAVED_DOCUMENTS)
                        !ProjectManager.getInstance().closeAndDispose(project) ->
                            blocked(IdeLifecycleFailure.CLOSE_VETOED)
                        else -> IdeLifecycleResult.Closed(target)
                    }
                }
            if (result !is IdeLifecycleResult.Closed) return result
            val closeLimit =
                when (val admitted = io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(120_000)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> error("Invalid close retirement bound")
                }
            val closeDeadline = WorkspacePreparationDeadline.start(closeLimit, System.nanoTime())
            if (!awaitLifecycleCondition(closeDeadline) { project.isDisposed && endpoint.lifecycleEndpointRetired() })
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

    private fun blocked(reason: IdeLifecycleFailure) = IdeLifecycleResult.Blocked(reason)
}
