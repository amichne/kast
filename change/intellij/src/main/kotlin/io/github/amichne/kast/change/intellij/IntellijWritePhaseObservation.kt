package io.github.amichne.kast.change.intellij

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException

internal enum class IntellijWritePhase {
    DOCUMENT_COMMIT,
    EXPLICIT_SAVE,
}

internal enum class IntellijWritePhaseOutcome {
    STARTED,
    RETURNED,
    CANCELLED,
    FAILED,
}

internal data class IntellijWritePhaseObservation(
    val phase: IntellijWritePhase,
    val outcome: IntellijWritePhaseOutcome,
    val elapsedNanos: Long,
)

/** Effect timing only: RETURNED does not imply that the typed write outcome succeeded. */
internal inline fun <T> observeIntellijWritePhase(
    phase: IntellijWritePhase,
    clock: () -> Long = System::nanoTime,
    publish: (IntellijWritePhaseObservation) -> Unit = ::logIntellijWritePhase,
    block: () -> T,
): T {
    val started = clock()
    publish(IntellijWritePhaseObservation(phase, IntellijWritePhaseOutcome.STARTED, 0))
    var outcome = IntellijWritePhaseOutcome.FAILED
    try {
        return block().also { outcome = IntellijWritePhaseOutcome.RETURNED }
    } catch (cancelled: ProcessCanceledException) {
        outcome = IntellijWritePhaseOutcome.CANCELLED
        throw cancelled
    } finally {
        publish(IntellijWritePhaseObservation(phase, outcome, (clock() - started).coerceAtLeast(0)))
    }
}

internal fun logIntellijWritePhase(value: IntellijWritePhaseObservation) {
    Logger.getInstance(LiveIntellijDocumentSession::class.java)
        .info("kast_change stage=${value.phase.name} outcome=${value.outcome.name} elapsedNanos=${value.elapsedNanos}")
}
