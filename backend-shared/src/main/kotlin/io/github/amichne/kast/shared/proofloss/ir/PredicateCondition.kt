package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.shared.proofloss.model.PredicateId

data class PredicateCondition(
    val predicate: PredicateId,
    val subject: TrackedValueId,
    val polarity: PredicatePolarity,
    val location: SourceSpan,
)
