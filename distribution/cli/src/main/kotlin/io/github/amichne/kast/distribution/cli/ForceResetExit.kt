package io.github.amichne.kast.distribution.cli

internal enum class ForceResetExit {
    COMPLETE,
    INCOMPLETE,
}

internal fun presentForceReset(
    outcome: ForceResetOutcome,
    json: Boolean,
    output: ManagementOutput = ManagementOutput(),
): ForceResetExit {
    if (output.structured(json)) output.publish(outcome.asJson()) else presentHumanReset(outcome)
    return when (outcome) {
        is ForceResetOutcome.Removed -> ForceResetExit.COMPLETE
        is ForceResetOutcome.Rejected,
        is ForceResetOutcome.Retained -> ForceResetExit.INCOMPLETE
    }
}

private fun presentHumanReset(outcome: ForceResetOutcome) {
    when (outcome) {
        is ForceResetOutcome.Removed -> println("Removed the entire Kast directory: ${outcome.root}")
        is ForceResetOutcome.Retained ->
            System.err.println("kast: ${outcome.stage}: ${outcome.failure}; retained data: ${outcome.recoveryPath}")
        is ForceResetOutcome.Rejected -> System.err.println("kast: ${outcome.stage}: ${outcome.failure}")
    }
}
