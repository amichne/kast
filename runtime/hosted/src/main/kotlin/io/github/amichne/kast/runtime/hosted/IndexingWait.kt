package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult

internal enum class IndexingWait {
    Ready,
    TimedOut,
    Disposed,
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

internal suspend fun awaitHostedSmartMode(
    project: Project,
    observe: (HostedSmartModeWaitObservation) -> Unit = {},
): IndexingWait = waitForHostedSmartMode(state = { readHostedIndexingState(project) }, observe = observe)

/** One cancellable native status snapshot; no read access survives into the polling delay. */
internal suspend fun readHostedIndexingState(project: Project): HostedIndexingState = readAction {
    if (project.isDisposed) HostedIndexingState.DISPOSED
    else if (DumbService.isDumb(project)) HostedIndexingState.INDEXING
    else if (project.isDisposed) HostedIndexingState.DISPOSED else HostedIndexingState.SMART
}
