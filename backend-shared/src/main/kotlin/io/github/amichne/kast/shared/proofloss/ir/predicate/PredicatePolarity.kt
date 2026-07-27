package io.github.amichne.kast.shared.proofloss.ir

enum class PredicatePolarity {
    POSITIVE,
    NEGATED;

    fun predicateHoldsWhen(conditionResult: Boolean): Boolean = when (this) {
        POSITIVE -> conditionResult
        NEGATED -> !conditionResult
    }
}
