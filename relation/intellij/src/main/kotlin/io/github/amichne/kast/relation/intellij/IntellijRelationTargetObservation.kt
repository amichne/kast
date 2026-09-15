package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Preserves the finite target decision while recording bounded, payload-free stage evidence. */
internal fun IntellijK2TargetConfirmation.observedBy(
    observation: IntellijReadObservation
): IntellijK2TargetConfirmation = also {
    observation.count(
        when (this) {
            IntellijK2TargetConfirmation.EXACT_SUBJECT -> IntellijReadCounter.RELATION_K2_CONFIRMED_TARGETS
            IntellijK2TargetConfirmation.DIFFERENT_SYMBOL -> IntellijReadCounter.RELATION_K2_DIFFERENT_TARGETS
            IntellijK2TargetConfirmation.UNRESOLVED -> IntellijReadCounter.RELATION_K2_UNAVAILABLE_TARGETS
        }
    )
}
