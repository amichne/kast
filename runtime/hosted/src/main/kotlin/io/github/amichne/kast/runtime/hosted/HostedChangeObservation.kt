package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.change.contract.ChangePlanId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Local phase evidence: returning from an effect is distinct from semantic success. */
internal enum class HostedChangeStage {
    ADMISSION,
    READINESS_WAIT,
    REFERENCE_RESTORATION,
    PLANNING,
    APPROVAL,
    MUTATION_PERMIT_WAIT,
    APPLICATION,
    VERIFICATION,
    RECEIPT_PERSISTENCE,
}

internal enum class HostedChangePhaseOutcome {
    STARTED,
    RETURNED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    FAILED,
}

internal data class HostedChangePhaseObservation(
    val stage: HostedChangeStage,
    val outcome: HostedChangePhaseOutcome,
    val elapsed: Duration,
    val plan: ChangePlanId?,
    val deadline: Duration? = null,
)

@Suppress("TooGenericExceptionCaught") // Observe and rethrow the same failure; never map it to success.
internal inline fun <T> observeHostedChange(
    stage: HostedChangeStage,
    plan: ChangePlanId? = null,
    deadline: Duration? = null,
    clock: () -> Long = System::nanoTime,
    publish: (HostedChangePhaseObservation) -> Unit = ::logHostedChangePhase,
    block: () -> T,
): T {
    val started = clock()
    publish(HostedChangePhaseObservation(stage, HostedChangePhaseOutcome.STARTED, Duration.ZERO, plan, deadline))
    var outcome = HostedChangePhaseOutcome.FAILED
    try {
        return block().also { outcome = HostedChangePhaseOutcome.RETURNED }
    } catch (failure: RuntimeException) {
        outcome =
            when (failure) {
                is TimeoutCancellationException -> HostedChangePhaseOutcome.DEADLINE_EXCEEDED
                is CancellationException,
                is ProcessCanceledException -> HostedChangePhaseOutcome.CANCELLED
                else -> HostedChangePhaseOutcome.FAILED
            }
        throw failure
    } finally {
        publish(
            HostedChangePhaseObservation(
                stage,
                outcome,
                (clock() - started).coerceAtLeast(0).nanoseconds,
                plan,
                deadline,
            )
        )
    }
}

internal fun logHostedChangePhase(value: HostedChangePhaseObservation) {
    Logger.getInstance(HostedChangeCoordinator::class.java)
        .info(
            "kast_change stage=${value.stage.name} outcome=${value.outcome.name}" +
                (value.plan?.let { " plan=plan:${it.value}" } ?: "") +
                " elapsedNanos=${value.elapsed.inWholeNanoseconds}" +
                (value.deadline?.let { " deadlineMs=${it.inWholeMilliseconds}" } ?: "")
        )
}

private class HostedMutationPermitOwner

/** The owner witness also releases an acquired permit if publishing phase evidence fails. */
internal suspend fun <T> withHostedMutationPermit(
    mutex: kotlinx.coroutines.sync.Mutex,
    publish: (HostedChangePhaseObservation) -> Unit = ::logHostedChangePhase,
    block: suspend () -> T,
): T {
    val owner = HostedMutationPermitOwner()
    try {
        observeHostedChange(HostedChangeStage.MUTATION_PERMIT_WAIT, publish = publish) { mutex.lock(owner) }
        return block()
    } finally {
        if (mutex.holdsLock(owner)) mutex.unlock(owner)
    }
}
