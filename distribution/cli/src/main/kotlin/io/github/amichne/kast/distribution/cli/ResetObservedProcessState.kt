package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ObservedProcessState
import io.github.amichne.kast.distribution.contract.admitObservedProcessState

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
            when (admitObservedProcessState(observation.code, observation.output)) {
                ObservedProcessState.EXITED -> ResetObservedProcessState.EXITED
                ObservedProcessState.ACTIVE -> ResetObservedProcessState.ACTIVE
                ObservedProcessState.UNAVAILABLE -> ResetObservedProcessState.UNAVAILABLE
            }
        }
        ProcessObservation.Unavailable -> ResetObservedProcessState.UNAVAILABLE
    }
