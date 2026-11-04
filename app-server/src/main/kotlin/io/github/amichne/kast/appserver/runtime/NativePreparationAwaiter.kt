package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import io.github.amichne.kast.protocol.contract.WorkspacePreparationTiming
import io.github.amichne.kast.protocol.contract.WorkspacePreparationWaitMode
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.delay

/** A coalesced native waiter's deadline cannot shorten this owner's admitted lifetime. */
internal class NativePreparationAwaiter(
    private val entry: WorkspacePreparation,
    private val exchange: suspend (WorkspaceLifecycleRequest) -> IdeLifecycleResult,
    private val nanoTime: () -> Long,
    private val interval: Duration,
    private val newId: () -> WorkspacePreparationId,
    private val observer: WorkspacePreparationObserver,
    private val publish: (WorkspacePreparationOutcome) -> Unit,
) {
    suspend fun await(expectedHost: UUID?): WorkspacePreparationOutcome {
        var request = entry.id
        var host = expectedHost
        var project: NativeContinuationProject? = null
        val issued = mutableSetOf(request)
        while (true) {
            val observation =
                when (val result = poll(request, host)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return WorkspacePreparationOutcome.Rejected(result.failure)
                }
            host = observation.progress?.host ?: host
            when (val continuation = continuation(observation)) {
                NativeDeadlineContinuation.Terminal -> return completion(observation.response, host, project)
                NativeDeadlineContinuation.Expired -> return deadlineExceeded()
                is NativeDeadlineContinuation.Admitted -> {
                    when (val admitted = inspectContinuation(continuation.progress.host, project)) {
                        is Refinement.Refined -> project = admitted.value
                        is Refinement.Rejected -> return admitted.failure
                    }
                    val next = newId()
                    if (!issued.add(next))
                        return WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.IDENTITY_REJECTED)
                    recordContinuation(request, next, continuation.timing)
                    request = next
                }
            }
        }
    }

    private suspend fun poll(
        request: WorkspacePreparationId,
        expectedHost: UUID?,
    ): Refinement<NativePreparationObservation, WorkspacePreparationFailure> {
        val allowance =
            when (val remaining = entry.deadline.remaining(nanoTime())) {
                is Refinement.Refined -> remaining.value
                is Refinement.Rejected -> return Refinement.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED)
            }
        val requestId = request.value.toString()
        var response =
            exchange(WorkspaceLifecycleRequest.Open(entry.root.path.toString(), requestId, allowance.protocolMillis()))
        var progress: NativePreparationProgress? = null
        while (response is IdeLifecycleResult.Pending) {
            progress =
                when (val admitted = admitPreparationProgress(response, request, progress)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            if (expectedHost != null && progress.host != expectedHost)
                return Refinement.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
            publish(WorkspacePreparationOutcome.Pending(maxOf(entry.lastStage, progress.stage)))
            delay(interval)
            response = exchange(WorkspaceLifecycleRequest.Status(progress.host.toString(), requestId))
        }
        return Refinement.Refined(NativePreparationObservation(response, progress))
    }

    private fun continuation(observation: NativePreparationObservation): NativeDeadlineContinuation =
        when (val response = observation.response) {
            is IdeLifecycleResult.Blocked -> sharedDeadline(response, observation.progress)
            else -> NativeDeadlineContinuation.Terminal
        }

    private fun sharedDeadline(
        response: IdeLifecycleResult.Blocked,
        progress: NativePreparationProgress?,
    ): NativeDeadlineContinuation {
        val timing = response.preparation
        val limit = timing?.limitMillis
        if (response.reason != IdeLifecycleFailure.DEADLINE_EXCEEDED || progress == null || timing == null)
            return NativeDeadlineContinuation.Terminal
        if (limit == null) return NativeDeadlineContinuation.Terminal
        if (
            timing.waitMode != WorkspacePreparationWaitMode.BOUNDED ||
                timing.remainingMillis != 0L ||
                timing.elapsedMillis < limit
        )
            return NativeDeadlineContinuation.Terminal
        return when (entry.deadline.remaining(nanoTime())) {
            is Refinement.Refined -> NativeDeadlineContinuation.Admitted(progress, timing)
            is Refinement.Rejected -> NativeDeadlineContinuation.Expired
        }
    }

    private suspend fun inspectContinuation(
        expectedHost: UUID,
        project: NativeContinuationProject?,
    ): Refinement<NativeContinuationProject, WorkspacePreparationOutcome.Terminal> =
        when (val inspected = exchange(WorkspaceLifecycleRequest.Inspect)) {
            is IdeLifecycleResult.Inspected -> admitContinuationProject(inspected, expectedHost, project)
            is IdeLifecycleResult.Blocked ->
                Refinement.Rejected(
                    WorkspacePreparationOutcome.Blocked(inspected.reason, inspected.preparation, inspected.exchange)
                )
            else ->
                Refinement.Rejected(WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED))
        }

    private fun admitContinuationProject(
        inspected: IdeLifecycleResult.Inspected,
        expectedHost: UUID,
        previous: NativeContinuationProject?,
    ): Refinement<NativeContinuationProject, WorkspacePreparationOutcome.Terminal> {
        if (inspected.host != expectedHost.toString())
            return Refinement.Rejected(WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.WRONG_HOST))
        val target =
            when (val found = inspected.projects.singleOrNull { it.target.root == entry.root.path.toString() }) {
                null ->
                    return Refinement.Rejected(
                        WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.ROOT_REJECTED)
                    )
                else -> found.target
            }
        if (target.host != inspected.host)
            return Refinement.Rejected(
                WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
            )
        val project =
            when (val admitted = canonicalPreparationUuid(target.project)) {
                null ->
                    return Refinement.Rejected(
                        WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
                    )
                else -> NativeContinuationProject(admitted)
            }
        return if (previous != null && previous != project)
            Refinement.Rejected(WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.STALE_PROJECT))
        else Refinement.Refined(project)
    }

    private fun recordContinuation(
        previous: WorkspacePreparationId,
        next: WorkspacePreparationId,
        timing: WorkspacePreparationTiming,
    ) =
        observer.observe(
            WorkspacePreparationActivity(
                entry.id.value.toString(),
                WorkspacePreparationActivityOutcome.WaitingAfterSharedDeadline(
                    previous.value.toString(),
                    next.value.toString(),
                    timing,
                ),
                preparation =
                    entry.deadline.timing(
                        entry.lastStage,
                        WorkspacePreparationDeadlineOwner.PREPARATION_OWNER,
                        nanoTime(),
                    ),
            )
        )

    private fun completion(
        response: IdeLifecycleResult,
        host: UUID?,
        project: NativeContinuationProject?,
    ): WorkspacePreparationOutcome =
        when (response) {
            is IdeLifecycleResult.Opened ->
                when (val admitted = PreparedWorkspace.admit(entry.root, response.target)) {
                    is Refinement.Rejected -> WorkspacePreparationOutcome.Rejected(admitted.failure)
                    is Refinement.Refined ->
                        when {
                            host != null && host != admitted.value.host ->
                                WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
                            project != null && project.id != admitted.value.project ->
                                WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.STALE_PROJECT)
                            else -> WorkspacePreparationOutcome.Complete(admitted.value)
                        }
                }
            is IdeLifecycleResult.Blocked ->
                WorkspacePreparationOutcome.Blocked(response.reason, response.preparation, response.exchange)
            else -> WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
        }

    private fun deadlineExceeded() = WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED)
}

private data class NativePreparationObservation(
    val response: IdeLifecycleResult,
    val progress: NativePreparationProgress?,
)

private data class NativeContinuationProject(val id: UUID)

private sealed interface NativeDeadlineContinuation {
    data object Terminal : NativeDeadlineContinuation

    data object Expired : NativeDeadlineContinuation

    data class Admitted(val progress: NativePreparationProgress, val timing: WorkspacePreparationTiming) :
        NativeDeadlineContinuation
}
