package io.github.amichne.kast.shared.hierarchy

/**
 * Request-confined candidate budget shared by one hierarchy provider expansion.
 *
 * Providers call [tryAdmitCandidate] before retaining or resolving each raw
 * candidate. The first candidate beyond [maxCandidates] proves truncation
 * without materializing the remainder of the provider result.
 */
class EdgeDiscoveryBudget(
    val maxCandidates: Int,
    private val timeoutCheck: () -> Boolean = { false },
) {
    private var admittedCandidates: Int = 0
    private var currentCompletion: EdgeDiscoveryCompletion = EdgeDiscoveryCompletion.EXHAUSTIVE

    init {
        require(maxCandidates >= 0) { "maxCandidates must be non-negative" }
    }

    val completion: EdgeDiscoveryCompletion
        get() = currentCompletion

    fun tryAdmitCandidate(): Boolean {
        if (timeoutReached()) return false
        if (currentCompletion == EdgeDiscoveryCompletion.CANDIDATE_LIMIT_REACHED) return false
        if (admittedCandidates >= maxCandidates) {
            currentCompletion = EdgeDiscoveryCompletion.CANDIDATE_LIMIT_REACHED
            return false
        }
        admittedCandidates += 1
        return true
    }

    fun timeoutReached(): Boolean {
        if (currentCompletion == EdgeDiscoveryCompletion.TIMED_OUT) return true
        if (timeoutCheck()) {
            currentCompletion = EdgeDiscoveryCompletion.TIMED_OUT
            return true
        }
        return false
    }
}
