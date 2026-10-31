package io.github.amichne.kast.distribution.contract

enum class ObservedProcessState {
    EXITED,
    ACTIVE,
    UNAVAILABLE,
}

/** A zombie cannot execute an operation even while its ProcessHandle remains alive. */
fun admitObservedProcessState(code: Int, output: String): ObservedProcessState {
    val state = output.trim()
    if (code != 0 || !Regex("[IRSTUZ][+<>AELNSslVWX]*").matches(state)) return ObservedProcessState.UNAVAILABLE
    return when (state.first()) {
        'Z' -> ObservedProcessState.EXITED
        'R',
        'S',
        'I',
        'T',
        'U' -> ObservedProcessState.ACTIVE
        else -> ObservedProcessState.UNAVAILABLE
    }
}
