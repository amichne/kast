package io.github.amichne.kast.appserver

/** Passive observation does not confer authorization to start or attach a host. */
internal enum class PassiveServiceState {
    READY,
    UNAVAILABLE,
    REJECTED,
}
