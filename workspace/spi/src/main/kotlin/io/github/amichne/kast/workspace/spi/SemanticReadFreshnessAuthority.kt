package io.github.amichne.kast.workspace.spi

/**
 * Semantic-source freshness observed independently from runtime liveness and downstream evidence
 * lanes.
 */
sealed interface SemanticReadFreshness {
    data object Ready : SemanticReadFreshness

    data object DumbMode : SemanticReadFreshness

    data object TransitionInProgress : SemanticReadFreshness

    data object WorkspaceBlocked : SemanticReadFreshness
}

enum class SemanticReadFreshnessRequirement {
    SMART_INDEXES,
    QUALIFIED_DUMB_MODE,
}

fun interface SemanticReadFreshnessAuthority {
    /**
     * Proof transition: <code>SemanticReadFreshnessAuthority -> SemanticReadFreshness</code>.
     *
     * Refines physical IntelliJ indexing and workspace-publication observations into one closed
     * semantic-source state without treating runtime, relation, or graph readiness as equivalent.
     * Raw IDE and transition state may be observed only inside the owning adapter.
     */
    fun observe(): SemanticReadFreshness
}
