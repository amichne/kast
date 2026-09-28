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
    REFERENCE_RESTORATION,
    PLANNING,
    APPROVAL,
    MUTATION_PERMIT_WAIT,
    APPLICATION,
    VERIFICATION,
    RECEIPT_PERSISTENCE,
}

internal enum class HostedChangePhaseOutcome { STARTED, RETURNED, CANCELLED, DEADLINE_EXCEEDED, FAILED }

internal data class HostedChangePhaseObservation(
    val stage: HostedChangeStage,
    val outcome: HostedChangePhaseOutcome,
    val elapsed: Duration,
    val plan: ChangePlanId?,
)

internal inline fun <T> observeHostedChange(
    stage: HostedChangeStage,
    plan: ChangePlanId? = null,
    clock: () -> Long = System::nanoTime,
    publish: (HostedChangePhaseObservation) -> Unit = ::logHostedChangePhase,
    block: () -> T,
): T {
    val started = clock()
    publish(HostedChangePhaseObservation(stage, HostedChangePhaseOutcome.STARTED, Duration.ZERO, plan))
    var outcome = HostedChangePhaseOutcome.FAILED
    try {
        return block().also { outcome = HostedChangePhaseOutcome.RETURNED }
    } catch (cancelled: TimeoutCancellationException) {
        outcome = HostedChangePhaseOutcome.DEADLINE_EXCEEDED
        throw cancelled
    } catch (cancelled: CancellationException) {
        outcome = HostedChangePhaseOutcome.CANCELLED
        throw cancelled
    } catch (cancelled: ProcessCanceledException) {
        outcome = HostedChangePhaseOutcome.CANCELLED
        throw cancelled
    } finally {
        publish(HostedChangePhaseObservation(stage, outcome, (clock() - started).coerceAtLeast(0).nanoseconds, plan))
    }
}

internal fun logHostedChangePhase(value: HostedChangePhaseObservation) {
    Logger.getInstance(HostedChangeCoordinator::class.java).info(
        "kast_change stage=${value.stage.name} outcome=${value.outcome.name}" +
            (value.plan?.let { " plan=plan:${it.value}" } ?: "") +
            " elapsedNanos=${value.elapsed.inWholeNanoseconds}"
    )
}
