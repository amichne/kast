package io.github.amichne.kast.distribution.cli

internal enum class ResetObservedProcessState {
    EXITED,
    ACTIVE,
    UNAVAILABLE,
}

internal fun observeResetProcessState(handle: ProcessHandle): ResetObservedProcessState =
    admitResetProcessState(runBounded(listOf("/bin/ps", "-p", handle.pid().toString(), "-o", "stat=")))

/** A zombie still has a ProcessHandle but cannot execute or own a running Kast operation. */
internal fun admitResetProcessState(observation: ProcessObservation): ResetObservedProcessState =
    when (observation) {
        is ProcessObservation.Exited -> {
            val state = observation.output.trim()
            if (observation.code != 0 || !Regex("[IRSTUZ][+<>AELNSsVWX]*").matches(state))
                ResetObservedProcessState.UNAVAILABLE
            else
                when (state.first()) {
                    'Z' -> ResetObservedProcessState.EXITED
                    'R',
                    'S',
                    'I',
                    'T',
                    'U' -> ResetObservedProcessState.ACTIVE
                    else -> ResetObservedProcessState.UNAVAILABLE
                }
        }
        ProcessObservation.Unavailable -> ResetObservedProcessState.UNAVAILABLE
    }
