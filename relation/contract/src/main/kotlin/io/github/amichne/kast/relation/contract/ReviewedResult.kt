package io.github.amichne.kast.relation.contract

/** Admitted preparation shares only immutable facts; ReviewedValueTransfer owns final target assembly. */
internal sealed interface ReviewedResult {
    val relation: ReviewedValueRelation
    val path: ValueAccessPath
    val conditions: ValueConditions
}
