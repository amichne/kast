package io.github.amichne.kast.fixtureprobe

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.platform.backend.workspace.WorkspaceModelChangeListener
import com.intellij.platform.backend.workspace.WorkspaceModelTopics
import com.intellij.platform.workspace.storage.VersionedStorageChange
import com.intellij.psi.util.PsiModificationTracker
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class ProbeSetupReadiness(private val project: Project, private val sandbox: ProbeSandbox) : Disposable {
    private val import = AtomicReference(ProbeImportState.NOT_OBSERVED)
    private val workspaceChanges = AtomicLong()
    private val connection = project.messageBus.connect()
    private val disposed = CountDownLatch(1)

    init {
        connection.subscribe(
            WorkspaceModelTopics.CHANGED,
            object : WorkspaceModelChangeListener {
                override fun changed(event: VersionedStorageChange) {
                    workspaceChanges.incrementAndGet()
                }
            },
        )
        connection.subscribe(
            ProjectDataImportListener.TOPIC,
            object : ProjectDataImportListener {
                override fun onImportStarted(path: String?) = update(path, ProbeImportState.IMPORTING)

                override fun onImportFinished(path: String?) = update(path, ProbeImportState.IMPORT_FINISHED)

                @Deprecated("Platform compatibility callback; the two-argument callback is preferred")
                override fun onImportFailed(path: String?) = update(path, ProbeImportState.FAILED)

                override fun onImportFailed(path: String?, error: Throwable) = update(path, ProbeImportState.FAILED)

                override fun onFinalTasksStarted(path: String?) {
                    if (matches(path))
                        import.compareAndSet(ProbeImportState.IMPORT_FINISHED, ProbeImportState.FINALIZING)
                }

                override fun onFinalTasksFinished(path: String?) {
                    if (matches(path))
                        import.compareAndSet(ProbeImportState.FINALIZING, ProbeImportState.FINAL_TASKS_FINISHED)
                }
            },
        )
    }

    fun await(request: ProbeRequest, observeSource: () -> ProbeExecution): ProbeExecution {
        val deadline = System.nanoTime() + SETUP_TIMEOUT_NANOS
        return try {
            when (val refresh = refresh(deadline)) {
                is ProbeResult.Accepted -> awaitQuiet(request, deadline, observeSource)
                is ProbeResult.Rejected -> ProbeExecution.Rejected(refresh.failure)
            }
        } catch (_: TimeoutException) {
            ProbeExecution.Rejected(timeoutFailure())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ProbeExecution.Rejected(ProbeFailure.SETUP_CANCELLED)
        } catch (_: Exception) {
            ProbeExecution.Rejected(ProbeFailure.NATIVE_UNAVAILABLE)
        }
    }

    private fun awaitQuiet(request: ProbeRequest, deadline: Long, observeSource: () -> ProbeExecution): ProbeExecution {
        var candidate = onEdt(deadline) { sample(request.command) }
        var candidateSince = System.nanoTime()
        while (System.nanoTime() < deadline) {
            if (disposed.await(SETUP_POLL_MILLIS, TimeUnit.MILLISECONDS))
                return ProbeExecution.Rejected(ProbeFailure.SETUP_CANCELLED)
            val current = onEdt(deadline) { sample(request.command) }
            val now = System.nanoTime()
            if (current != candidate || current.status != ProbeSetupStatus.CANDIDATE) {
                candidate = current
                candidateSince = now
            }
            when (val proof = ProbeSetupObservation.admit(candidate, current, now - candidateSince)) {
                is ProbeResult.Rejected -> Unit
                is ProbeResult.Accepted ->
                    return onEdt(deadline) {
                        completeObservation(
                            request = request,
                            expected = current,
                            proof = proof.value,
                            observeSource = observeSource,
                        )
                    }
            }
        }
        return ProbeExecution.Rejected(timeoutFailure())
    }

    private fun completeObservation(
        request: ProbeRequest,
        expected: ProbeSetupSample,
        proof: ProbeSetupObservation,
        observeSource: () -> ProbeExecution,
    ): ProbeExecution {
        if (sample(request.command) != expected) return ProbeExecution.Rejected(ProbeFailure.SETUP_MOVING)
        return when (val source = observeSource()) {
            is ProbeExecution.Completed ->
                if (source.evidence.documentState == ProbeDocumentState.SAVED_COMMITTED) {
                    ProbeExecution.SetupReady(source.evidence, proof)
                } else ProbeExecution.Rejected(ProbeFailure.DOCUMENT_STATE_REJECTED)
            else -> source
        }
    }

    private fun refresh(deadline: Long): ProbeResult<Unit> {
        if (!sandbox.valid(project)) return ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
        val root =
            LocalFileSystem.getInstance().findFileByNioFile(sandbox.project)
                ?: return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        val finished = CountDownLatch(1)
        RefreshQueue.getInstance().refresh(true, true, Runnable { finished.countDown() }, root)
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 || !finished.await(remaining, TimeUnit.NANOSECONDS))
            return ProbeResult.Rejected(ProbeFailure.SETUP_REFRESH_TIMEOUT)
        // The observation is posted after refresh completion, so earlier queued EDT work runs before it.
        onEdt(deadline) { Unit }
        return ProbeResult.Accepted(Unit)
    }

    private fun sample(command: ProbeCommand): ProbeSetupSample {
        val dumb = DumbService.getInstance(project)
        val state = import.get()
        val status =
            when {
                !sandbox.valid(project) -> ProbeSetupStatus.GRADLE_MODULE_UNAVAILABLE
                state == ProbeImportState.FAILED -> ProbeSetupStatus.IMPORT_FAILED
                dumb.isDumb -> ProbeSetupStatus.INDEXING
                ExternalSystemTaskType.entries.any {
                    ExternalSystemProcessingManager.getInstance().hasTaskOfTypeInProgress(it, project)
                } -> ProbeSetupStatus.EXTERNAL_TASKS_ACTIVE
                !importReady(state, command) -> ProbeSetupStatus.IMPORT_PENDING
                !hasGradleSourceModule() -> ProbeSetupStatus.GRADLE_MODULE_UNAVAILABLE
                else -> ProbeSetupStatus.CANDIDATE
            }
        return ProbeSetupSample(
            status,
            ProbeSetupGeneration(
                roots = ProjectRootModificationTracker.getInstance(project).modificationCount,
                workspace = workspaceChanges.get(),
                vfs = VirtualFileManager.getInstance().modificationCount,
                psi = PsiModificationTracker.getInstance(project).modificationCount,
                dumb = dumb.modificationTracker.modificationCount,
            ),
            state,
        )
    }

    private fun hasGradleSourceModule(): Boolean {
        val file =
            LocalFileSystem.getInstance().findFileByNioFile(sandbox.project.resolve("src/main/kotlin/Fixture.kt"))
                ?: return false
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInSource(file) || index.isInGeneratedSources(file)) return false
        val modules = index.getModulesForFile(file, false)
        if (modules.size != 1) return false
        val module = modules.single()
        val properties = ExternalSystemModulePropertyManager.getInstance(module)
        return !module.isDisposed &&
            properties.getExternalSystemId() == "GRADLE" &&
            matches(properties.getRootProjectPath().orEmpty())
    }

    private fun importReady(state: ProbeImportState, command: ProbeCommand): Boolean =
        state == ProbeImportState.FINAL_TASKS_FINISHED ||
            (command == ProbeCommand.AWAIT_REOPEN_READY && state == ProbeImportState.NOT_OBSERVED)

    private fun <Value> onEdt(deadline: Long, observe: () -> Value): Value {
        val future =
            CompletableFuture.supplyAsync(
                {
                    if (System.nanoTime() >= deadline || disposed.count == 0L) throw TimeoutException()
                    observe()
                },
                { action -> ApplicationManager.getApplication().invokeLater(action, ModalityState.nonModal()) },
            )
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw TimeoutException()
        return future.get(remaining, TimeUnit.NANOSECONDS)
    }

    private fun update(path: String?, state: ProbeImportState) {
        if (matches(path)) import.set(state)
    }

    private fun matches(path: String?): Boolean =
        try {
            Path.of(path.orEmpty()).toRealPath() == sandbox.project
        } catch (_: Exception) {
            false
        }

    private fun timeoutFailure(): ProbeFailure =
        when (import.get()) {
            ProbeImportState.NOT_OBSERVED -> ProbeFailure.SETUP_IMPORT_NOT_OBSERVED
            ProbeImportState.FAILED -> ProbeFailure.SETUP_IMPORT_FAILED
            ProbeImportState.IMPORTING,
            ProbeImportState.IMPORT_FINISHED,
            ProbeImportState.FINALIZING -> ProbeFailure.SETUP_IMPORT_PENDING
            ProbeImportState.FINAL_TASKS_FINISHED -> ProbeFailure.SETUP_TIMEOUT
        }

    override fun dispose() {
        disposed.countDown()
        connection.disconnect()
    }
}

private const val SETUP_TIMEOUT_NANOS = 60000000000L
private const val SETUP_POLL_MILLIS = 100L
