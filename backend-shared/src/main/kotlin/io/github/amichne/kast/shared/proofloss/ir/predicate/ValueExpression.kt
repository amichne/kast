package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.shared.proofloss.model.CallableKey
import io.github.amichne.kast.shared.proofloss.model.PredicateId

sealed interface ValueExpression {
    data class Alias(val source: TrackedValueId) : ValueExpression

    data class Materialize(
        val predicate: PredicateId,
        val source: TrackedValueId,
        val callable: CallableKey,
    ) : ValueExpression
}
