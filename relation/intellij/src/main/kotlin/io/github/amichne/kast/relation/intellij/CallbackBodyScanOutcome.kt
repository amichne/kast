package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Native inventory drainage only; neither outcome establishes static callback activation. */
internal enum class CallbackBodyScanOutcome {
    EXHAUSTED,
    STOPPED,
}

/** The native traversal reports a Boolean at this boundary; observations never replace its outcome. */
internal fun observeCallbackBodyScan(
    observation: IntellijReadObservation,
    processBody: () -> Boolean,
): CallbackBodyScanOutcome {
    observation.count(IntellijReadCounter.CALLBACK_BODY_SCANS)
    var outcome = CallbackBodyScanOutcome.STOPPED
    try {
        outcome = if (processBody()) CallbackBodyScanOutcome.EXHAUSTED else CallbackBodyScanOutcome.STOPPED
        return outcome
    } finally {
        // Cancellation and native failure retain entered-but-undrained evidence and propagate unchanged.
        observation.count(
            when (outcome) {
                CallbackBodyScanOutcome.EXHAUSTED -> IntellijReadCounter.CALLBACK_BODY_SCANS_COMPLETED
                CallbackBodyScanOutcome.STOPPED -> IntellijReadCounter.CALLBACK_BODY_SCANS_INCOMPLETE
            }
        )
    }
}
