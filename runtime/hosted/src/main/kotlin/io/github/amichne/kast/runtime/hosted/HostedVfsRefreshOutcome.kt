package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal enum class HostedVfsRefreshOutcome {
    READY,
    ROOT_UNAVAILABLE,
    PROJECT_DISPOSED,
    UNSAVED_DOCUMENTS,
    DEADLINE_EXCEEDED,
    FAILED,
}

internal fun HostedVfsRefreshOutcome.refreshObservation(): String =
    "kast_readiness stage=VFS_REFRESH policy=NATIVE_INCREMENTAL outcome=$name"

internal fun HostedVfsRefreshOutcome.failure(): HostedEndpointFailure? =
    when (this) {
        HostedVfsRefreshOutcome.READY -> null
        HostedVfsRefreshOutcome.ROOT_UNAVAILABLE -> HostedEndpointFailure.IO_UNAVAILABLE
        HostedVfsRefreshOutcome.PROJECT_DISPOSED,
        HostedVfsRefreshOutcome.FAILED -> HostedEndpointFailure.PLATFORM_UNAVAILABLE
        HostedVfsRefreshOutcome.UNSAVED_DOCUMENTS -> HostedEndpointFailure.UNSAVED_DOCUMENTS
        HostedVfsRefreshOutcome.DEADLINE_EXCEEDED -> HostedEndpointFailure.DEADLINE_EXCEEDED
    }

/** Awaits native incremental VFS completion before a semantic operation enters its read epoch. */
internal suspend fun awaitHostedVfsRefresh(
    project: Project,
    start: (completed: (HostedVfsRefreshOutcome) -> Unit) -> (() -> Unit),
): HostedVfsRefreshOutcome {
    val outcome =
        try {
            refreshProjectVfs(project, start)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: RuntimeException) {
            HostedVfsRefreshOutcome.FAILED
        }
    Logger.getInstance(HostedEndpointService::class.java).info(outcome.refreshObservation())
    return outcome
}

private suspend fun refreshProjectVfs(
    project: Project,
    start: (completed: (HostedVfsRefreshOutcome) -> Unit) -> (() -> Unit),
): HostedVfsRefreshOutcome {
    if (project.isDisposed) return HostedVfsRefreshOutcome.PROJECT_DISPOSED
    return awaitVfsRefresh(disposed = { project.isDisposed }, start = start)
}

/** The callback is the native completion boundary; timeout never authorizes a stale read. */
internal suspend fun awaitVfsRefresh(
    disposed: () -> Boolean,
    start: (completed: (HostedVfsRefreshOutcome) -> Unit) -> (() -> Unit),
): HostedVfsRefreshOutcome {
    if (disposed()) return HostedVfsRefreshOutcome.PROJECT_DISPOSED
    val result =
        try {
            withTimeoutOrNull(VFS_REFRESH_WAIT_MILLIS) {
                suspendCancellableCoroutine<HostedVfsRefreshOutcome> { continuation ->
                    val cancelWaiter = start { outcome -> if (continuation.isActive) continuation.resume(outcome) }
                    continuation.invokeOnCancellation { cancelWaiter() }
                }
            } ?: return HostedVfsRefreshOutcome.DEADLINE_EXCEEDED
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return HostedVfsRefreshOutcome.FAILED
        }
    return if (disposed()) HostedVfsRefreshOutcome.PROJECT_DISPOSED else result
}

private const val VFS_REFRESH_WAIT_MILLIS = 10_000L
