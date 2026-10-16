package io.github.amichne.kast.distribution.cli

internal enum class ForceResetExit {
    COMPLETE,
    INCOMPLETE,
}

internal fun presentForceReset(outcome: ForceResetOutcome, json: Boolean): ForceResetExit {
    if (json) println(outcome.asJson()) else presentHumanReset(outcome)
    return when (outcome) {
        is ForceResetOutcome.Removed,
        is ForceResetOutcome.Installed -> ForceResetExit.COMPLETE
        is ForceResetOutcome.Rejected,
        is ForceResetOutcome.Pending,
        is ForceResetOutcome.Retained,
        is ForceResetOutcome.RecoveryRequired -> ForceResetExit.INCOMPLETE
    }
}

private fun presentHumanReset(outcome: ForceResetOutcome) {
    when (outcome) {
        is ForceResetOutcome.Removed -> println("Removed the entire Kast directory: ${outcome.root}")
        is ForceResetOutcome.Installed -> {
            println("Installed Kast ${outcome.version}: ${outcome.command}")
            println("Restart IDEA and affected harnesses. Use kast plugin codex for Codex.")
        }
        is ForceResetOutcome.Retained ->
            System.err.println("kast: ${outcome.stage}: ${outcome.failure}; retained data: ${outcome.recoveryPath}")
        is ForceResetOutcome.RecoveryRequired ->
            System.err.println(
                "kast: ${outcome.stage}: ${outcome.failure}; " +
                    "recovery: ${outcome.recoveryFailure}; root: ${outcome.root}"
            )
        is ForceResetOutcome.Pending ->
            System.err.println(
                "kast: installed ${outcome.version}; activation pending; " +
                    "inspect kast status --json, then retry kast reinstall --force"
            )
        is ForceResetOutcome.Rejected -> System.err.println("kast: ${outcome.stage}: ${outcome.failure}")
    }
}
