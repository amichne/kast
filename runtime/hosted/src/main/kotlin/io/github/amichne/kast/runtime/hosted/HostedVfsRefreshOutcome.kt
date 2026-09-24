package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class HostedVfsRefreshOutcome {
    READY,
    ROOT_UNAVAILABLE,
    PROJECT_DISPOSED,
    UNSAVED_DOCUMENTS,
    DEADLINE_EXCEEDED,
    FAILED,
}

internal fun HostedVfsRefreshOutcome.failure(): HostedEndpointFailure? =
    when (this) {
        HostedVfsRefreshOutcome.READY -> null
        HostedVfsRefreshOutcome.ROOT_UNAVAILABLE -> HostedEndpointFailure.IO_UNAVAILABLE
        HostedVfsRefreshOutcome.PROJECT_DISPOSED,
        HostedVfsRefreshOutcome.FAILED -> HostedEndpointFailure.PLATFORM_UNAVAILABLE
        HostedVfsRefreshOutcome.UNSAVED_DOCUMENTS -> HostedEndpointFailure.UNSAVED_DOCUMENTS
        HostedVfsRefreshOutcome.DEADLINE_EXCEEDED -> HostedEndpointFailure.DEADLINE_EXCEEDED
    }

/** Mirrors foreground project synchronization before a semantic operation enters its read epoch. */
internal suspend fun awaitHostedVfsRefresh(project: Project, root: CanonicalWorkspaceRoot): HostedVfsRefreshOutcome {
    val outcome =
        try {
            refreshProjectVfs(project, root)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: RuntimeException) {
            HostedVfsRefreshOutcome.FAILED
        }
    Logger.getInstance(HostedEndpointService::class.java)
        .info("kast_readiness stage=VFS_REFRESH outcome=${outcome.name}")
    return outcome
}

private suspend fun refreshProjectVfs(project: Project, root: CanonicalWorkspaceRoot): HostedVfsRefreshOutcome {
    if (project.isDisposed) return HostedVfsRefreshOutcome.PROJECT_DISPOSED
    val directory =
        LocalFileSystem.getInstance().findFileByPath(root.value) ?: return HostedVfsRefreshOutcome.ROOT_UNAVAILABLE
    if (!withContext(Dispatchers.EDT) { saveProjectDocuments(root) }) return HostedVfsRefreshOutcome.UNSAVED_DOCUMENTS
    // A backgrounded IDE may not have observed a watcher event. Compare disk before the callback.
    VfsUtil.markDirty(true, true, directory)
    return awaitVfsRefresh(
        disposed = { project.isDisposed },
        start = { completed -> RefreshQueue.getInstance().refresh(true, true, completed, directory) },
    )
}

/** The callback is the native completion boundary; timeout never authorizes a stale read. */
internal suspend fun awaitVfsRefresh(
    disposed: () -> Boolean,
    start: (completed: () -> Unit) -> Unit,
): HostedVfsRefreshOutcome {
    if (disposed()) return HostedVfsRefreshOutcome.PROJECT_DISPOSED
    try {
        withTimeoutOrNull(VFS_REFRESH_WAIT_MILLIS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                start { if (continuation.isActive) continuation.resume(Unit) }
            }
        } ?: return HostedVfsRefreshOutcome.DEADLINE_EXCEEDED
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: RuntimeException) {
        return HostedVfsRefreshOutcome.FAILED
    }
    return if (disposed()) HostedVfsRefreshOutcome.PROJECT_DISPOSED else HostedVfsRefreshOutcome.READY
}

private const val VFS_REFRESH_WAIT_MILLIS = 10_000L
