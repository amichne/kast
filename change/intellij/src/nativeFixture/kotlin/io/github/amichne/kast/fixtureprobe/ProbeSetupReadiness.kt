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
import com.intellij.openapi.vfs.PlatformVirtualFileManager
import com.intellij.openapi.vfs.VirtualFileManager
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
    private val import = AtomicReference(ProbeImportProgress(ProbeImportState.NOT_OBSERVED, 0))
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
                    if (matches(path)) transition(ProbeImportState.IMPORT_FINISHED, ProbeImportState.FINALIZING)
                }

                override fun onFinalTasksFinished(path: String?) {
                    if (matches(path)) transition(ProbeImportState.FINALIZING, ProbeImportState.FINAL_TASKS_FINISHED)
                }
            },
        )
    }

    fun await(request: ProbeRequest, observeSource: () -> ProbeExecution): ProbeExecution =
        awaitObserved(
            request = request,
            deadline = System.nanoTime() + SETUP_TIMEOUT_NANOS,
            requirement = ProbeImportRequirement.Current,
            observeSource = observeSource,
        )

    fun reimport(request: ProbeRequest, observeSource: () -> ProbeExecution): ProbeExecution {
        val deadline = System.nanoTime() + SETUP_TIMEOUT_NANOS
        return try {
            val previous = import.get()
            val admitted =
                onEdt(deadline) {
                    when (val source = observeSource()) {
                        is ProbeExecution.Completed ->
                            if (source.evidence.documentState == ProbeDocumentState.SAVED_COMMITTED) {
                                NativeFixtureGradleReimport.begin(project, sandbox, request)
                            } else ProbeResult.Rejected(ProbeFailure.DOCUMENT_STATE_REJECTED)
                        else -> ProbeResult.Rejected(ProbeFailure.REIMPORT_UNAVAILABLE)
                    }
                }
            when (admitted) {
                is ProbeResult.Rejected -> ProbeExecution.Rejected(admitted.failure)
                is ProbeResult.Accepted ->
                    awaitObserved(
                            request = request,
                            deadline = deadline,
                            requirement = ProbeImportRequirement.After(previous.sequence),
                        ) {
                            when (val image = NativeFixtureGradleReimport.validateBuild(sandbox, request)) {
                                is ProbeResult.Accepted -> observeSource()
                                is ProbeResult.Rejected -> ProbeExecution.Rejected(image.failure)
                            }
                        }
                        .afterReimportStarted()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ProbeExecution.EffectUncertain(ProbeFailure.SETUP_CANCELLED)
        } catch (_: Exception) {
            ProbeExecution.EffectUncertain(ProbeFailure.REIMPORT_UNAVAILABLE)
        }
    }

    private fun awaitObserved(
        request: ProbeRequest,
        deadline: Long,
        requirement: ProbeImportRequirement,
        observeSource: () -> ProbeExecution,
    ): ProbeExecution {
        return try {
            // Opening the fixture editor is preparation and must precede the quiet observation.
            when (val prepared = onEdt(deadline) { observeSource() }) {
                is ProbeExecution.Completed ->
                    if (prepared.evidence.documentState != ProbeDocumentState.SAVED_COMMITTED)
                        return ProbeExecution.Rejected(ProbeFailure.DOCUMENT_STATE_REJECTED)
                else -> return prepared
            }
            when (val refresh = refresh(deadline)) {
                is ProbeResult.Accepted ->
                    awaitQuiet(
                        request = request,
                        deadline = deadline,
                        requirement = requirement,
                        drain = refresh.value,
                        observeSource = observeSource,
                    )
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

    private fun awaitQuiet(
        request: ProbeRequest,
        deadline: Long,
        requirement: ProbeImportRequirement,
        drain: ProbeSetupDrainState,
        observeSource: () -> ProbeExecution,
    ): ProbeExecution {
        var candidate = onEdt(deadline) { sample(request.command, requirement) }
        var candidateSince = System.nanoTime()
        while (System.nanoTime() < deadline) {
            if (disposed.await(SETUP_POLL_MILLIS, TimeUnit.MILLISECONDS))
                return ProbeExecution.Rejected(ProbeFailure.SETUP_CANCELLED)
            val current = onEdt(deadline) { sample(request.command, requirement) }
            val now = System.nanoTime()
            if (current != candidate || current.status != ProbeSetupStatus.CANDIDATE) {
                candidate = current
                candidateSince = now
            }
            when (
                val proof =
                    ProbeSetupObservation.admit(
                        before = candidate,
                        after = current,
                        elapsedNanos = now - candidateSince,
                        drain = drain,
                    )
            ) {
                is ProbeResult.Rejected -> Unit
                is ProbeResult.Accepted ->
                    return onEdt(deadline) {
                        completeObservation(
                            request = request,
                            expected = current,
                            proof = proof.value,
                            requirement = requirement,
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
        requirement: ProbeImportRequirement,
        observeSource: () -> ProbeExecution,
    ): ProbeExecution {
        if (sample(request.command, requirement) != expected) return ProbeExecution.Rejected(ProbeFailure.SETUP_MOVING)
        return when (val source = observeSource()) {
            is ProbeExecution.Completed ->
                if (sample(request.command, requirement) != expected) {
                    ProbeExecution.Rejected(ProbeFailure.SETUP_MOVING)
                } else if (source.evidence.documentState == ProbeDocumentState.SAVED_COMMITTED) {
                    ProbeExecution.SetupReady(source.evidence, proof)
                } else ProbeExecution.Rejected(ProbeFailure.DOCUMENT_STATE_REJECTED)
            else -> source
        }
    }

    private fun refresh(deadline: Long): ProbeResult<ProbeSetupDrainState> {
        if (!sandbox.valid(project)) return ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
        if (LocalFileSystem.getInstance().findFileByNioFile(sandbox.project) == null)
            return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        val manager =
            VirtualFileManager.getInstance() as? PlatformVirtualFileManager
                ?: return ProbeResult.Rejected(ProbeFailure.SETUP_NATIVE_TASKS_UNAVAILABLE)
        val finished = CountDownLatch(1)
        // The platform manager scans every cached root after consuming global watcher changes.
        manager.asyncRefresh { finished.countDown() }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 || !finished.await(remaining, TimeUnit.NANOSECONDS))
            return ProbeResult.Rejected(ProbeFailure.SETUP_REFRESH_TIMEOUT)
        // The observation is posted after refresh completion, so earlier queued EDT work runs before it.
        onEdt(deadline) { Unit }
        return when (val drained = ProbeSetupNativeTasks.drain(project, deadline)) {
            is ProbeResult.Accepted -> {
                onEdt(deadline) { Unit }
                drained
            }
            is ProbeResult.Rejected -> drained
        }
    }

    private fun sample(command: ProbeCommand, requirement: ProbeImportRequirement): ProbeSetupSample {
        val dumb = DumbService.getInstance(project)
        val indexing = ProbeSetupNativeTasks.indexingState(project)
        val refresh = ProbeSetupNativeTasks.refreshState()
        val nativeStatus = indexing.setupStatus(refresh)
        val progress = import.get()
        val state = progress.state
        val provenance = observeGradleSourceModule()
        val status =
            when {
                !sandbox.valid(project) -> ProbeSetupStatus.GRADLE_MODULE_UNAVAILABLE
                state == ProbeImportState.FAILED -> ProbeSetupStatus.IMPORT_FAILED
                nativeStatus != ProbeSetupStatus.CANDIDATE -> nativeStatus
                ExternalSystemTaskType.entries.any {
                    ExternalSystemProcessingManager.getInstance().hasTaskOfTypeInProgress(it, project)
                } -> ProbeSetupStatus.EXTERNAL_TASKS_ACTIVE
                !requirement.ready(progress, command) -> ProbeSetupStatus.IMPORT_PENDING
                (provenance == ProbeSourceProvenance.UNAVAILABLE ||
                    (provenance == ProbeSourceProvenance.GENERATED && command != ProbeCommand.REIMPORT_GRADLE)) ->
                    ProbeSetupStatus.GRADLE_MODULE_UNAVAILABLE
                else -> ProbeSetupStatus.CANDIDATE
            }
        return ProbeSetupSample(
            status = status,
            generation =
                ProbeSetupGeneration(
                    imports = progress.sequence,
                    roots = ProjectRootModificationTracker.getInstance(project).modificationCount,
                    workspace = workspaceChanges.get(),
                    vfs = VirtualFileManager.getInstance().modificationCount,
                    psi = PsiModificationTracker.getInstance(project).modificationCount,
                    dumb = dumb.modificationTracker.modificationCount,
                ),
            import = state,
            provenance = provenance,
            indexing = indexing,
            refresh = refresh,
        )
    }

    private fun observeGradleSourceModule(): ProbeSourceProvenance {
        val file =
            LocalFileSystem.getInstance().findFileByNioFile(sandbox.project.resolve("src/main/kotlin/Fixture.kt"))
                ?: return ProbeSourceProvenance.UNAVAILABLE
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInSource(file)) return ProbeSourceProvenance.UNAVAILABLE
        val modules = index.getModulesForFile(file, false)
        if (modules.size != 1) return ProbeSourceProvenance.UNAVAILABLE
        val module = modules.single()
        val properties = ExternalSystemModulePropertyManager.getInstance(module)
        if (
            module.isDisposed ||
                properties.getExternalSystemId() != "GRADLE" ||
                !matches(properties.getRootProjectPath().orEmpty())
        ) {
            return ProbeSourceProvenance.UNAVAILABLE
        }
        return if (index.isInGeneratedSources(file)) ProbeSourceProvenance.GENERATED else ProbeSourceProvenance.AUTHORED
    }

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
        if (matches(path))
            import.updateAndGet { previous ->
                ProbeImportProgress(
                    state,
                    if (state == ProbeImportState.IMPORTING) Math.incrementExact(previous.sequence)
                    else previous.sequence,
                )
            }
    }

    private fun transition(expected: ProbeImportState, next: ProbeImportState) {
        import.updateAndGet { previous -> if (previous.state == expected) previous.copy(state = next) else previous }
    }

    private fun matches(path: String?): Boolean =
        try {
            Path.of(path.orEmpty()).toRealPath() == sandbox.project
        } catch (_: Exception) {
            false
        }

    private fun timeoutFailure(): ProbeFailure =
        when (import.get().state) {
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

private fun ProbeExecution.afterReimportStarted(): ProbeExecution =
    if (this is ProbeExecution.Rejected) ProbeExecution.EffectUncertain(failure) else this
