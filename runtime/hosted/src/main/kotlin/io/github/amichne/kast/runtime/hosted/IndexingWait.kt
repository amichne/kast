package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal enum class IndexingWait {
    Ready,
    Unavailable,
}

/** A failed presemantic admission has published no semantic result, so one retry cannot duplicate semantic effects. */
internal suspend fun <Value> retryPresemanticIndexing(
    read: suspend () -> HostedSemanticReadResult<Value>,
    wait: suspend () -> IndexingWait,
): HostedSemanticReadResult<Value> {
    val first = read()
    if (first !is HostedSemanticReadResult.Rejected || !first.isPresemanticIndexing()) return first
    return if (wait() == IndexingWait.Ready) read() else first
}

private fun HostedSemanticReadResult.Rejected.isPresemanticIndexing(): Boolean =
    stage.ordinal <= HostedQueryStage.MODEL_CAPTURE.ordinal &&
        when (val reason = failure) {
            is HostedQueryFailure.ProjectAdmission -> reason.cause == ExistingProjectAdmissionFailure.DumbMode
            is HostedQueryFailure.ReadEpoch -> reason.cause == ProjectReadEpochObservationFailure.DumbMode
            is HostedQueryFailure.Freshness -> reason.cause == VfsPassiveReadAdmissionFailure.DumbMode
            is HostedQueryFailure.NamedSourceScope -> reason.cause == NamedGradleSourceScopeFailure.INDEXING
            HostedQueryFailure.INDEXING -> true
            else -> false
        }

internal suspend fun awaitHostedSmartMode(project: Project): IndexingWait =
    withTimeoutOrNull(INDEXING_WAIT_MILLIS) {
        while (!project.isDisposed && DumbService.isDumb(project)) delay(INDEXING_POLL_MILLIS)
        if (project.isDisposed) IndexingWait.Unavailable else IndexingWait.Ready
    } ?: IndexingWait.Unavailable

private const val INDEXING_WAIT_MILLIS = 15_000L
private const val INDEXING_POLL_MILLIS = 100L
