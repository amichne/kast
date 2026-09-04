package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState

/** Persisted evidence is distinct from proof that its process is still alive. */
sealed interface RuntimeBootstrapObservation {
    data object Unavailable : RuntimeBootstrapObservation
    data object Invalid : RuntimeBootstrapObservation
    data class Observed(val state: SemanticRuntimeBootstrapState) : RuntimeBootstrapObservation
}
