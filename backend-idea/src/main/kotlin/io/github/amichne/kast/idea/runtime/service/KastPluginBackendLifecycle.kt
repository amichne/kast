package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface KastGradleIndexAdmission {
    data object Pending : KastGradleIndexAdmission

    data object Ready : KastGradleIndexAdmission

    data class Failed(val error: Throwable) : KastGradleIndexAdmission

    companion object {
        fun fromStartIndexing(startIndexing: Boolean): KastGradleIndexAdmission =
            if (startIndexing) Ready else Pending
    }
}

internal data class KastPluginBackendStart(
    val workspaceRoot: Path,
    val config: KastConfig,
    val admission: KastGradleIndexAdmission,
)

internal interface KastIdeaBackendHandle {
    fun startIndexing()

    fun failIndexing(error: Throwable)

    fun closeAsync(): CompletableFuture<Unit>
}

internal enum class KastPluginBackendLifecycleStatus {
    STOPPED,
    RUNNING,
    STOPPING,
    STOP_FAILED,
}

internal class KastPluginBackendLifecycle(
    private val startBackend: (KastPluginBackendStart) -> KastIdeaBackendHandle,
    private val onStopping: (Path) -> Unit = {},
    private val onStopCompleted: (Path, Throwable?) -> Unit = { _, _ -> },
    private val onAsyncStartFailed: (Path, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = ReentrantLock()
    private var state: State = State.Stopped
    private var admission: KastGradleIndexAdmission = KastGradleIndexAdmission.Ready
    private var disposed: Boolean = false

    fun start(
        workspaceRoot: Path,
        config: KastConfig,
        initialAdmission: KastGradleIndexAdmission? = null,
    ) = lock.withLock {
        if (disposed || !config.backends.idea.enabled.value) return@withLock
        when (val current = state) {
            State.Stopped -> {
                initialAdmission?.let { admission = it }
                startLocked(BackendRequest(workspaceRoot, config))
            }
            is State.Stopping -> {
                initialAdmission?.let { admission = it }
                state = current.copy(restart = BackendRequest(workspaceRoot, config))
            }
            is State.Running,
            is State.StopFailed -> Unit
        }
    }

    fun restart(workspaceRoot: Path, config: KastConfig) = lock.withLock {
        if (disposed || !config.backends.idea.enabled.value) return@withLock
        restartLocked(BackendRequest(workspaceRoot, config))
    }

    fun stop() = lock.withLock {
        stopLocked()
    }

    fun dispose() = lock.withLock {
        disposed = true
        stopLocked()
    }

    fun reload(workspaceRoot: Path, nextConfig: KastConfig): KastConfigReloadDecision = lock.withLock {
        val decision = configReloadDecision(currentConfigLocked(), nextConfig)
        when (decision) {
            KastConfigReloadDecision.UNCHANGED -> updateCurrentConfigLocked(nextConfig)
            KastConfigReloadDecision.START_BACKEND -> {
                if (!disposed) startLocked(BackendRequest(workspaceRoot, nextConfig))
            }
            KastConfigReloadDecision.STOP_BACKEND -> stopLocked()
            KastConfigReloadDecision.RESTART_BACKEND -> {
                if (!disposed) restartLocked(BackendRequest(workspaceRoot, nextConfig))
            }
        }
        decision
    }

    fun markIndexReady() = lock.withLock {
        admission = KastGradleIndexAdmission.Ready
        (state as? State.Running)?.backend?.startIndexing()
    }

    fun markIndexFailed(error: Throwable) = lock.withLock {
        admission = KastGradleIndexAdmission.Failed(error)
        (state as? State.Running)?.backend?.failIndexing(error)
    }

    internal fun status(): KastPluginBackendLifecycleStatus = lock.withLock {
        when (state) {
            State.Stopped -> KastPluginBackendLifecycleStatus.STOPPED
            is State.Running -> KastPluginBackendLifecycleStatus.RUNNING
            is State.Stopping -> KastPluginBackendLifecycleStatus.STOPPING
            is State.StopFailed -> KastPluginBackendLifecycleStatus.STOP_FAILED
        }
    }

    private fun currentConfigLocked(): KastConfig? = when (val current = state) {
        State.Stopped -> null
        is State.Running -> current.request.config
        is State.Stopping -> current.restart?.config ?: current.request.config
        is State.StopFailed -> current.request.config
    }

    private fun restartLocked(request: BackendRequest) {
        when (val current = state) {
            State.Stopped -> startLocked(request)
            is State.Running -> beginStopLocked(current, request)
            is State.Stopping -> state = current.copy(restart = request)
            is State.StopFailed -> Unit
        }
    }

    private fun stopLocked() {
        when (val current = state) {
            State.Stopped,
            is State.StopFailed -> Unit
            is State.Running -> beginStopLocked(current, restart = null)
            is State.Stopping -> state = current.copy(restart = null)
        }
    }

    private fun startLocked(request: BackendRequest) {
        check(state == State.Stopped) { "Kast IDEA backend can only start from STOPPED" }
        val backend = startBackend(
            KastPluginBackendStart(
                workspaceRoot = request.workspaceRoot,
                config = request.config,
                admission = admission,
            ),
        )
        state = State.Running(request, backend)
    }

    private fun beginStopLocked(
        running: State.Running,
        restart: BackendRequest?,
    ) {
        onStopping(running.request.workspaceRoot)
        val completion = runCatching(running.backend::closeAsync)
            .getOrElse { failure -> CompletableFuture.failedFuture(failure) }
        state = State.Stopping(
            request = running.request,
            completion = completion,
            restart = restart,
        )
        completion.whenComplete { _, failure ->
            completeStop(completion, unwrapCompletionFailure(failure))
        }
    }

    private fun completeStop(
        completion: CompletableFuture<Unit>,
        failure: Throwable?,
    ) = lock.withLock {
        val stopping = state as? State.Stopping ?: return@withLock
        if (stopping.completion !== completion) return@withLock

        if (failure != null) {
            state = State.StopFailed(stopping.request, failure)
            runCatching { onStopCompleted(stopping.request.workspaceRoot, failure) }
            return@withLock
        }

        state = State.Stopped
        runCatching { onStopCompleted(stopping.request.workspaceRoot, null) }
        val restart = stopping.restart
        if (restart != null && !disposed) {
            runCatching { startLocked(restart) }
                .onFailure { startFailure ->
                    onAsyncStartFailed(restart.workspaceRoot, startFailure)
                }
        }
    }

    private fun updateCurrentConfigLocked(nextConfig: KastConfig) {
        state = when (val current = state) {
            State.Stopped -> current
            is State.Running -> current.copy(request = current.request.copy(config = nextConfig))
            is State.Stopping -> if (current.restart == null) {
                current.copy(request = current.request.copy(config = nextConfig))
            } else {
                current.copy(restart = current.restart.copy(config = nextConfig))
            }
            is State.StopFailed -> current.copy(request = current.request.copy(config = nextConfig))
        }
    }

    private sealed interface State {
        data object Stopped : State

        data class Running(
            val request: BackendRequest,
            val backend: KastIdeaBackendHandle,
        ) : State

        data class Stopping(
            val request: BackendRequest,
            val completion: CompletableFuture<Unit>,
            val restart: BackendRequest?,
        ) : State

        data class StopFailed(
            val request: BackendRequest,
            val failure: Throwable,
        ) : State
    }

    private data class BackendRequest(
        val workspaceRoot: Path,
        val config: KastConfig,
    )
}

private fun unwrapCompletionFailure(failure: Throwable?): Throwable? =
    if (failure is CompletionException && failure.cause != null) failure.cause else failure
