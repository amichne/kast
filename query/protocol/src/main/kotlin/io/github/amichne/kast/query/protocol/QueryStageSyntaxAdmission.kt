package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryPrimitiveField
import io.github.amichne.kast.query.contract.QueryPrimitiveOperator
import io.github.amichne.kast.query.contract.QueryPrimitiveValue
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryVisibilitySelection
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalStrategy

internal fun QueryStepDocument.syntax(): QueryStepSyntax? =
    when (this) {
        is QueryStepDocument.Related -> QueryStepSyntax.Related(relation.meaning())
        is QueryStepDocument.Walk ->
            QueryStepSyntax.Walk(
                relation.meaning(),
                TraversalDepthLimit.parse(maximumDepth.value).refinedOrNull() ?: return null,
                when (val selected = strategy) {
                    TraversalStrategyDocument.BreadthFirst -> TraversalStrategy.BreadthFirst
                    is TraversalStrategyDocument.BoundedFanOut ->
                        TraversalStrategy.BoundedFanOut(
                            io.github.amichne.kast.kernel.ResultLimit.parse(selected.maximumEdgesPerNode.value)
                                .refinedOrNull() ?: return null
                        )
                },
            )
        QueryStepDocument.Distinct -> QueryStepSyntax.Distinct
        is QueryStepDocument.Concat,
        is QueryStepDocument.Bind,
        is QueryStepDocument.Join,
        is QueryStepDocument.Intersect,
        is QueryStepDocument.Union,
        is QueryStepDocument.Difference -> null // Inputs are admitted with the complete plan.
        is QueryStepDocument.Where ->
            when (val value = predicate) {
                is QueryPredicateDocument.Visibility -> {
                    val visibilities =
                        value.values.values.uniqueValues()?.mapTo(linkedSetOf()) {
                            DeclarationVisibility.valueOf(it.name)
                        } ?: return null
                    QueryStepSyntax.Where(
                        QueryPredicate.Visibility(
                            QueryVisibilitySelection.from(visibilities).refinedOrNull() ?: return null
                        )
                    )
                }
                is QueryPredicateDocument.Primitive ->
                    QueryStepSyntax.Where(
                        QueryPredicate.Primitive(
                            QueryPrimitiveField.valueOf(value.field.name),
                            QueryPrimitiveOperator.valueOf(value.operator.name),
                            QueryPrimitiveValue.parse(value.value.value).refinedOrNull() ?: return null,
                        )
                    )
            }
    }

internal fun QueryOutputDocument.syntax(): QueryOutputSyntax? =
    when (this) {
        is QueryOutputDocument.Symbols -> {
            val selected =
                fields.values.uniqueValues()?.mapTo(linkedSetOf()) { QuerySymbolField.valueOf(it.name) } ?: return null
            QueryOutputSyntax.Symbols(QuerySymbolFields.from(selected).refinedOrNull() ?: return null)
        }
        QueryOutputDocument.Occurrences -> QueryOutputSyntax.Occurrences
        QueryOutputDocument.TraversalRecords -> QueryOutputSyntax.TraversalRecords
        QueryOutputDocument.BindingRows -> QueryOutputSyntax.BindingRows
    }

private fun RelationKindDocument.meaning(): RelationMeaning =
    when (this) {
        RelationKindDocument.REFERENCES -> RelationMeaning.References
        RelationKindDocument.CALLERS -> RelationMeaning.Callers
        RelationKindDocument.CALLEES -> RelationMeaning.Callees
        RelationKindDocument.IMPLEMENTATIONS -> RelationMeaning.Implementations
        RelationKindDocument.INHERITORS -> RelationMeaning.Inheritors
        RelationKindDocument.OVERRIDES -> RelationMeaning.Overrides
        RelationKindDocument.TYPE_USES -> RelationMeaning.TypeUses
    }

private fun <Value> List<Value>.uniqueValues(): List<Value>? = takeIf { it.distinct().size == it.size }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
