package io.github.amichne.kast.shared.proofloss.analysis

import io.github.amichne.kast.shared.proofloss.ir.SourceSpan
import io.github.amichne.kast.shared.proofloss.ir.TrackedValueId

sealed interface ProofEstablishment {
    val location: SourceSpan

    data class PredicateGuard(override val location: SourceSpan) : ProofEstablishment
    data class MaterializationSuccess(
        override val location: SourceSpan,
        val producedValue: TrackedValueId,
    ) : ProofEstablishment
}
