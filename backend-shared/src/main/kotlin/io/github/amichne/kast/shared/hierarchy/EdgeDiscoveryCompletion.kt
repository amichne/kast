package io.github.amichne.kast.shared.hierarchy

/** How a bounded hierarchy provider stopped enumerating candidates. */
enum class EdgeDiscoveryCompletion {
    EXHAUSTIVE,
    CANDIDATE_LIMIT_REACHED,
    TIMED_OUT,
}
